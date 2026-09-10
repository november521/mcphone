package com.november.mcphone.core.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;

/** 客户端用两位掩码同步主手和副手的设备亮屏状态。 */
public record PhoneScreenOnPacket(int litHands) implements CustomPacketPayload {

    public static final int MAIN_HAND = 1;
    public static final int OFF_HAND = 2;
    public static final int ALL_HANDS = MAIN_HAND | OFF_HAND;

    public static final CustomPacketPayload.Type<PhoneScreenOnPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "phone_screen_on"));

    public static final StreamCodec<ByteBuf, PhoneScreenOnPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    PhoneScreenOnPacket::litHands,
                    PhoneScreenOnPacket::new
            );

    public PhoneScreenOnPacket {
        litHands &= ALL_HANDS;
    }

    public boolean isLit(InteractionHand hand) {
        int bit = hand == InteractionHand.MAIN_HAND ? MAIN_HAND : OFF_HAND;
        return (litHands & bit) != 0;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
