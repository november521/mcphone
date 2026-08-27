package com.november.mcphone.feature.chat;

import java.util.UUID;

/**
 * 一对玩家之间那条会话的键，运行时形态；存档里仍是 "a|b" 字符串。
 * 运行时按 UUID.compareTo 归一化，存档按字符串序，两者未必一致，所以 toStorageKey 必须转调 FriendGraph.pairKey，不能自己拼。
 */
public record ConversationKey(UUID lo, UUID hi) {

    /** 归一化：A→B 与 B→A 必须落到同一个键 */
    public static ConversationKey of(UUID a, UUID b) {
        return a.compareTo(b) <= 0
                ? new ConversationKey(a, b)
                : new ConversationKey(b, a);
    }

    /** 顺序规则由 FriendGraph 说了算，见类注释 */
    public String toStorageKey() {
        return FriendGraph.pairKey(lo, hi);
    }

    /** 读不懂返回 null 而不是抛：存档可能被手改，不能为一条坏记录让全服起不来 */
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
