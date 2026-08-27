package com.november.mcphone.feature.music.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：对唱片仓做一件事。四个动作包体完全一样，合成一个包。
 * 故意不带"放哪张""从第几秒开始"之类的字段：唱片是仓里那一张，时刻由服务端盖章，不采信客户端。
 */
public record DiscActionPacket(Action action) implements CustomPacketPayload {

    public enum Action {
        /** 把主手上的唱片放进去 */
        INSERT,
        EJECT,
        TOGGLE,
        /** 只要一份最新状态，什么都不改。打开音乐 App 时用 */
        QUERY;

        private static final Action[] VALUES = values();

        /** 序号越界一律退回 QUERY：唯一什么都不改的动作，不瞎猜也不抛异常打断连接 */
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
