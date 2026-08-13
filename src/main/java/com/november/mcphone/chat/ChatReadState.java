package com.november.mcphone.chat;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家的已读进度 —— 跟着玩家走的数据，存为附件。
 *
 * ============================================================
 * 这里为什么【只】剩已读进度
 * ============================================================
 *
 * 联系人列表曾经也在这里，但好友关系是双向的、同时属于两个人，
 * 存在单个玩家身上根本表达不了，已搬到 {@link FriendData}。
 *
 * 已读进度不同：它纯属本人私事，别人读没读到哪儿与我无关，
 * 所以留在附件里是对的。
 *
 * ============================================================
 * 为什么是不可变的
 * ============================================================
 *
 * 附件的默认值由 {@code AttachmentType.builder(() -> DEFAULT)} 提供，
 * 而 DEFAULT 是全局共享的同一个实例。本类若可变，一个玩家读了消息就会
 * 改到那个共享默认值上，影响所有还没有过已读记录的玩家——这种串数据的
 * bug 极难复现。故所有修改都返回新实例。
 *
 * ============================================================
 * 未读数怎么算
 * ============================================================
 *
 * 不在消息上打"已读"标记（那要改写别人会话里的消息），而是每个对端记一个
 * "上次已读时刻"。未读数 = 该会话中对方发出、且晚于这个时刻的消息条数，
 * 由 {@link ChatData#countAfter} 现算。读消息不会去动共享的会话记录。
 */
public record ChatReadState(Map<UUID, Long> lastRead) {

    public static final ChatReadState DEFAULT = new ChatReadState(Map.of());

    public static final Codec<ChatReadState> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    // 键必须编码成字符串：NBT 的复合标签只接受字符串键，
                    // 用默认的 UUIDUtil.CODEC（编成 int 数组）会写不进去
                    Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.LONG)
                            .fieldOf("last_read").forGetter(ChatReadState::lastRead)
            ).apply(instance, ChatReadState::new)
    );

    /** 与某人的会话读到了哪个时刻。从未读过则返回 0，即全部算未读 */
    public long getLastRead(UUID peer) {
        return lastRead.getOrDefault(peer, 0L);
    }

    /**
     * 把与某人的会话标为读到 time 为止。
     *
     * 只前进不后退：客户端可能因为网络乱序发来一个更早的时刻，
     * 直接覆盖的话已经读过的消息会重新变成未读。
     */
    public ChatReadState withLastRead(UUID peer, long time) {
        if (getLastRead(peer) >= time) return this;

        Map<UUID, Long> next = new HashMap<>(lastRead);
        next.put(peer, time);
        return new ChatReadState(Map.copyOf(next));
    }

    /** 解除好友时清掉已读进度，免得日后重新加回来时未读数不对 */
    public ChatReadState without(UUID peer) {
        if (!lastRead.containsKey(peer)) return this;

        Map<UUID, Long> next = new HashMap<>(lastRead);
        next.remove(peer);
        return new ChatReadState(Map.copyOf(next));
    }
}
