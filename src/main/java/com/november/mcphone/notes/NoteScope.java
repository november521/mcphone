package com.november.mcphone.notes;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * 笔记存在哪儿 —— 记事本里那两个页签。
 *
 * ============================================================
 * 两种笔记的分别
 * ============================================================
 *
 * 私人笔记跟着【玩家】走，存在玩家附件里。换手机不丢，别人捡到你的手机
 * 也看不见——那本来就是你自己的日记本。
 *
 * 本机笔记跟着【手机】走，存在物品的数据组件里。手机送人、丢在地上被人
 * 捡走，笔记也跟着过去——它更像贴在机身背面的便签。
 *
 * ============================================================
 * 为什么两者的上限差这么多
 * ============================================================
 *
 * 本机笔记挂在 ItemStack 上，而物品组件每 tick 都会被容器拿去比对、
 * 随背包同步。几 KB 的文本挂在物品上就是一直背着它走，所以条数与长度
 * 都收得很紧。
 *
 * 私人笔记走玩家附件，只在打开记事本时按需拉一次，可以宽松得多。
 *
 * 这个差异不是妥协，正好也划出了两者的用途：一个是日记本，一个是便签。
 */
public enum NoteScope {

    /** 私人笔记：跟着玩家走 */
    PERSONAL(50, 2000),

    /** 本机笔记：跟着这部手机走 */
    PHONE(10, 500);

    /** 这一类最多存几条 */
    public final int maxCount;

    /** 这一类单条正文最多多少字 */
    public final int maxLength;

    NoteScope(int maxCount, int maxLength) {
        this.maxCount = maxCount;
        this.maxLength = maxLength;
    }

    private static final NoteScope[] VALUES = values();

    /**
     * 按序号传输。
     *
     * 解码时序号越界一律退回 PHONE：未知值多半来自版本不一致或伪造客户端，
     * 退到限制更紧的那一类最安全，也不会抛异常打断整条连接。
     * Relation 那边是同一个取舍。
     */
    public static final StreamCodec<ByteBuf, NoteScope> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public NoteScope decode(ByteBuf buf) {
                    int ordinal = ByteBufCodecs.VAR_INT.decode(buf);
                    return (ordinal >= 0 && ordinal < VALUES.length) ? VALUES[ordinal] : PHONE;
                }

                @Override
                public void encode(ByteBuf buf, NoteScope value) {
                    ByteBufCodecs.VAR_INT.encode(buf, value.ordinal());
                }
            };
}
