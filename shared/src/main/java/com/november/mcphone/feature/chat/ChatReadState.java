package com.november.mcphone.feature.chat;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家的已读进度（对端 → 上次已读时刻），存为玩家附件。
 * 必须不可变：DEFAULT 是附件全局共享的默认实例，可变的话一个玩家读消息会串到所有人身上。未读数由 {@link ChatData#tail} 现算。
 */
public record ChatReadState(Map<UUID, Long> lastRead) {

    public static final ChatReadState DEFAULT = new ChatReadState(Map.of());

    /** Current friends plus a small amount of churn; decoded data is clamped to this bound too. */
    public static final int MAX_PEERS = FriendData.MAX_FRIENDS;

    public ChatReadState {
        if (lastRead == null || lastRead.isEmpty()) {
            lastRead = Map.of();
        } else if (lastRead.size() > MAX_PEERS) {
            Map<UUID, Long> bounded = new java.util.LinkedHashMap<>();
            lastRead.entrySet().stream()
                    .sorted(Map.Entry.<UUID, Long>comparingByValue().reversed())
                    .limit(MAX_PEERS)
                    .forEach(e -> bounded.put(e.getKey(), e.getValue()));
            lastRead = Map.copyOf(bounded);
        } else {
            lastRead = Map.copyOf(lastRead);
        }
    }

    public static final Codec<ChatReadState> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    // 键必须编成字符串：NBT 复合标签只接受字符串键，默认 UUIDUtil.CODEC 写不进去
                    Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.LONG)
                            .fieldOf("last_read").forGetter(ChatReadState::lastRead)
            ).apply(instance, ChatReadState::new)
    );

    /** 从未读过则返回 0，即全部算未读 */
    public long getLastRead(UUID peer) {
        return lastRead.getOrDefault(peer, 0L);
    }

    /** 只前进不后退：网络乱序发来更早的时刻不能把已读的重新变成未读 */
    public ChatReadState withLastRead(UUID peer, long time) {
        if (getLastRead(peer) >= time) return this;

        Map<UUID, Long> next = new HashMap<>(lastRead);
        if (!next.containsKey(peer) && next.size() >= MAX_PEERS) {
            UUID oldest = next.entrySet().stream()
                    .min(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse(null);
            if (oldest != null) next.remove(oldest);
        }
        next.put(peer, time);
        return new ChatReadState(Map.copyOf(next));
    }

    /** 解除好友时清掉，免得日后重新加回来时未读数不对 */
    public ChatReadState without(UUID peer) {
        if (!lastRead.containsKey(peer)) return this;

        Map<UUID, Long> next = new HashMap<>(lastRead);
        next.remove(peer);
        return new ChatReadState(Map.copyOf(next));
    }
}
