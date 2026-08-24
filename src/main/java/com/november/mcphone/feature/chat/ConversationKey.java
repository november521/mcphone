package com.november.mcphone.feature.chat;

import java.util.UUID;

/**
 * 一对玩家之间那条会话的键 —— 运行时用的形态。
 *
 * 为什么不再用 "uuid|uuid" 那串字符串
 *
 * 这正是 {@link FriendGraph} 在 1.3.22 干过的事，只是聊天记录当时没跟上：
 * 字符串是【存档格式】，不该在热路径上当键用。
 *
 * 会话列表每 3 秒刷一次，每个好友要查一次会话。以前每查一次都要：
 *
 *     a.toString() + "|" + b.toString()     两个 36 字符的临时串 + 一次拼接
 *     map.get(那 73 个字符)                  对 73 个字符算哈希
 *
 * 100 个好友就是每人每 3 秒 200 次 UUID.toString()（getLastMessage 与
 * countAfter 各查一遍）。20 人在线约合每秒 2700 次，全是纯垃圾。
 *
 * 换成记录之后，键的哈希是两个 UUID 的哈希——UUID.hashCode 只是几次异或，
 * 一个字节都不分配。
 *
 * 存档格式一个字节都没变
 *
 * {@link #toStorageKey()} 与 {@link #parse} 负责在两种表示之间转换，
 * 老存档照读，新存档拿回老版本也照读。
 *
 * 有一处细节不能大意：归一化在【运行时】按 UUID.compareTo 排（不分配），
 * 而写出去时仍按【字符串序】排——那是老格式的规矩。两种顺序未必一致，
 * 所以 toStorageKey 必须转调 FriendGraph.pairKey，不能自己拼 lo + "|" + hi。
 * 自己拼的话，一部分会话的键会与老存档里的对不上，表现是"升级之后有些人的
 * 聊天记录不见了"，而且不报错。
 */
public record ConversationKey(UUID lo, UUID hi) {

    /** 归一化：A→B 与 B→A 必须落到同一个键，否则同一对人各看半截记录 */
    public static ConversationKey of(UUID a, UUID b) {
        return a.compareTo(b) <= 0
                ? new ConversationKey(a, b)
                : new ConversationKey(b, a);
    }

    /** 存档里那串 "a|b"。顺序规则由 FriendGraph 说了算，见类注释 */
    public String toStorageKey() {
        return FriendGraph.pairKey(lo, hi);
    }

    /**
     * 从存档里那串 "a|b" 读回来，读不懂返回 null。
     *
     * 不抛异常：这份存档玩家可以手改，也可能被别的工具动过。为一条读不懂
     * 的记录让整个服务端起不来，代价完全不成比例——与 FriendGraph.fromPairKeys
     * 同一个取舍。
     */
    public static ConversationKey parse(String key) {
        if (key == null) return null;
        int sep = key.indexOf('|');
        if (sep < 0) return null;

        try {
            return of(UUID.fromString(key.substring(0, sep)),
                      UUID.fromString(key.substring(sep + 1)));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
