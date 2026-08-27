package com.november.mcphone.feature.chat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 好友关系图：邻接表存双向边，读写存档时转成归一化的 "a|b" 字符串列表。
 * 故意不 import 任何 Minecraft 类型，好用 javac 单独编出来跑断言；别往里加需要 MinecraftServer 或 Codec 的东西。
 */
public final class FriendGraph {

    /** 双向都存，每条边出现两次 */
    private final Map<UUID, Set<UUID>> adjacency = new HashMap<>();

    /** 排序归一化：A→B 与 B→A 必须落进同一个键，{@link ChatData#conversationKey} 转调这里 */
    public static String pairKey(UUID a, UUID b) {
        String sa = a.toString();
        String sb = b.toString();
        return sa.compareTo(sb) <= 0 ? sa + "|" + sb : sb + "|" + sa;
    }

    public boolean areFriends(UUID a, UUID b) {
        Set<UUID> of = adjacency.get(a);
        return of != null && of.contains(b);
    }

    /** 已是好友返回 false。a==b 也要拦：存档可能被手改出 a|a */
    public boolean add(UUID a, UUID b) {
        if (a == null || b == null || a.equals(b)) return false;
        if (areFriends(a, b)) return false;

        adjacency.computeIfAbsent(a, k -> new HashSet<>()).add(b);
        adjacency.computeIfAbsent(b, k -> new HashSet<>()).add(a);
        return true;
    }

    /** 双向解除，不是好友返回 false。删空后摘掉整项，免得空集合只增不减 */
    public boolean remove(UUID a, UUID b) {
        if (!areFriends(a, b)) return false;

        removeOneWay(a, b);
        removeOneWay(b, a);
        return true;
    }

    private void removeOneWay(UUID from, UUID to) {
        Set<UUID> of = adjacency.get(from);
        if (of == null) return;
        of.remove(to);
        if (of.isEmpty()) adjacency.remove(from);
    }

    /** 没有好友时返回空集合，不是 null */
    public Set<UUID> friendsOf(UUID player) {
        Set<UUID> of = adjacency.get(player);
        return of == null ? Set.of() : Collections.unmodifiableSet(of);
    }

    public int countFriends(UUID player) {
        Set<UUID> of = adjacency.get(player);
        return of == null ? 0 : of.size();
    }

    /** 用 TreeSet：双向边归一化后自动去重，且输出顺序稳定，存档不会无故变化 */
    public List<String> toPairKeys() {
        Set<String> keys = new TreeSet<>();
        adjacency.forEach((self, friends) ->
                friends.forEach(other -> keys.add(pairKey(self, other))));
        return new ArrayList<>(keys);
    }

    /** 脏数据一律跳过而不是抛异常：存档可能被手改，不能为一条坏记录让全服起不来 */
    public static FriendGraph fromPairKeys(Collection<String> keys) {
        FriendGraph graph = new FriendGraph();
        if (keys == null) return graph;

        for (String key : keys) {
            if (key == null) continue;
            int sep = key.indexOf('|');
            if (sep < 0) continue;

            try {
                graph.add(UUID.fromString(key.substring(0, sep)),
                          UUID.fromString(key.substring(sep + 1)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return graph;
    }
}
