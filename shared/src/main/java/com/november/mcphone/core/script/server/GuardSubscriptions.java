package com.november.mcphone.core.script.server;

import com.november.mcphone.core.script.net.ScriptProtocol;
import com.november.mcphone.core.script.net.ScriptPush;
import java.util.*;
import java.util.function.*;

/** 剩余数推送只给打开页面的在线玩家，变化按秒聚合（§20.6）。 */
public final class GuardSubscriptions {
    public static final String WATCH = "__mcphone_guard_watch";
    public static final String UNWATCH = "__mcphone_guard_unwatch";
    public static final String SNAPSHOT = "__mcphone_guard_snapshot";
    public static final String TOPIC = ScriptProtocol.HOST_TOPIC_PREFIX + "guards";
    public static final int MAX_PER_PLAYER = 4;
    private final Map<UUID, Map<String, Long>> watching = new LinkedHashMap<>();
    private final LongSupplier nanos;
    private long lastFlush;
    private boolean flushed;
    public GuardSubscriptions(LongSupplier nanos) { this.nanos = nanos; }
    public boolean watch(UUID player, String app, long revision) {
        Map<String, Long> apps = watching.computeIfAbsent(player, p -> new LinkedHashMap<>());
        if (apps.size() >= MAX_PER_PLAYER && !apps.containsKey(app)) return false;
        apps.put(app, revision); return true;
    }
    public void unwatch(UUID player, String app) {
        Map<String, Long> apps = watching.get(player);
        if (apps != null) { apps.remove(app); if (apps.isEmpty()) watching.remove(player); }
    }
    public void forget(UUID player) { watching.remove(player); }
    public void flush(long revision, Predicate<UUID> online, BiFunction<UUID, String, byte[]> snapshot,
                      BiConsumer<UUID, ScriptPush> send) {
        long now = nanos.getAsLong();
        if (flushed && now - lastFlush < 1_000_000_000L) return;
        lastFlush = now; flushed = true;
        for (var player : List.copyOf(watching.entrySet())) {
            if (!online.test(player.getKey())) { forget(player.getKey()); continue; }
            for (var app : List.copyOf(player.getValue().entrySet())) {
                if (app.getValue() == revision) continue;
                byte[] data = snapshot.apply(player.getKey(), app.getKey());
                if (data == null || data.length > ScriptProtocol.DATA_MAX) { unwatch(player.getKey(), app.getKey()); continue; }
                try {
                    send.accept(player.getKey(), new ScriptPush(ScriptProtocol.HOST_APP_ID, TOPIC, data, revision));
                    player.getValue().put(app.getKey(), revision);
                } catch (RuntimeException failure) {
                    com.november.mcphone.MCphone.LOGGER.warn("[MCphone] 守卫显示推送失败 player={} app={}，下次聚合重发",
                            player.getKey(), app.getKey(), failure);
                }
            }
        }
    }
}
