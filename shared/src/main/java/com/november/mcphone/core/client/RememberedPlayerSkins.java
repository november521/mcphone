package com.november.mcphone.core.client;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 客户端见过的玩家皮肤。只保存原版皮肤引用与轻量玩家资料，不复制图片，也不另发查询请求。
 * 供应器保留当前连接的 PlayerInfo：即使好友下线，原版尚未完成的皮肤加载仍可继续。
 * 默认占位不能覆盖已加载的真皮肤；自己断线后释放玩家资料，已知贴图引用保留至客户端退出。
 * 仅在客户端线程调用，数量由 LRU 上限约束，避免长期开游戏时无限积累。
 */
public final class RememberedPlayerSkins<T> {
    private static final class SkinEntry<T> {
        private Supplier<T> supplier;
        private T known;
    }

    private final Map<UUID, SkinEntry<T>> entries;
    private Object connection;

    public RememberedPlayerSkins(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("缓存容量必须大于零");
        entries = new LinkedHashMap<>(capacity, .75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<UUID, SkinEntry<T>> eldest) {
                return size() > capacity;
            }
        };
    }

    /** 连接身份变化时释放旧的资料供应器，不把旧 PlayerInfo 带进下一个服务器。 */
    public boolean bindConnection(Object current) {
        if (current == connection) return false;
        connection = current;
        entries.values().removeIf(entry -> {
            entry.supplier = null;
            return entry.known == null;
        });
        return true;
    }

    public void remember(UUID player, Supplier<T> skin) {
        Objects.requireNonNull(player);
        Objects.requireNonNull(skin);
        entries.computeIfAbsent(player, id -> new SkinEntry<>()).supplier = skin;
    }

    public T resolve(UUID player, T fallback) {
        SkinEntry<T> entry = entries.get(player);
        if (entry == null) return fallback;
        if (entry.supplier != null) {
            T candidate = entry.supplier.get();
            if (candidate != null && !Objects.equals(candidate, fallback)) entry.known = candidate;
        }
        return entry.known != null ? entry.known : fallback;
    }
}
