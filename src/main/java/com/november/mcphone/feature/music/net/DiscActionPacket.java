package com.november.mcphone.feature.music.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 网络包：客户端 → 服务端，对唱片仓做一件事。
 *
 * ================================================================
 * 为什么四个动作合成一个包，而聊天那边是一动作一个类
 * ================================================================
 *
 * 聊天那边每个包带的字段都不一样（目标 UUID、正文、同意还是拒绝），
 * 拆开是必要的。这里四个动作的包体【完全一样】——全是空包，处理形状也
 * 一样（做那件事，回一份最新状态）。拆成四个类只是把同一段样板抄四遍。
 *
 * 加动作时也只需在枚举里加一项、在处理函数的 switch 里加一支。
 *
 * ================================================================
 * 服务端不采信这个枚举以外的任何东西
 * ================================================================
 *
 * 包里没有"放哪张唱片""从第几秒开始"之类的字段：唱片是仓里那一张，
 * 时刻由服务端盖章。留了字段就等于让伪造客户端指定放什么、放多久。
 */
public record DiscActionPacket(Action action) implements CustomPacketPayload {

    /** 能对唱片仓做的四件事 */
    public enum Action {
        /** 把主手上的唱片放进去 */
        INSERT,
        /** 把唱片还给我 */
        EJECT,
        /** 播放键：没在放就放，在放就停 */
        TOGGLE,
        /** 只要一份最新状态，什么都不改。打开音乐 App 时用 */
        QUERY;

        private static final Action[] VALUES = values();

        /**
         * 按序号传输。
         *
         * 序号越界一律退回 QUERY —— 那是唯一什么都不改的动作。伪造客户端
         * 或版本不一致时收到未知值，宁可当成"只是来问一下"，也不要瞎猜一个
         * 会改东西的动作，更不要抛异常把整条连接打断。
         */
        static Action decode(int ordinal) {
            return (ordinal >= 0 && ordinal < VALUES.length) ? VALUES[ordinal] : QUERY;
        }
    }

    public static final CustomPacketPayload.Type<DiscActionPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "disc_action"));

    public static final StreamCodec<ByteBuf, DiscActionPacket> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public DiscActionPacket decode(ByteBuf buf) {
                    return new DiscActionPacket(
                            Action.decode(ByteBufCodecs.VAR_INT.decode(buf)));
                }

                @Override
                public void encode(ByteBuf buf, DiscActionPacket value) {
                    ByteBufCodecs.VAR_INT.encode(buf, value.action().ordinal());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
