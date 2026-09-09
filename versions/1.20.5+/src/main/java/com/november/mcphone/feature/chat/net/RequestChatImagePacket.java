package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/**
 * C2S：把这几张图的像素发给我。
 *
 * 为什么一次要几张
 *
 * 服务端对拉取类的包有 500 毫秒的限流（见 RequestThrottle）。一次一张的话，屏幕上同时
 * 出现三张图就要 1.5 秒才凑齐，而玩家往回翻记录时一屏出现好几张是常事。一次几张则一轮
 * 就够，代价是单次回包更大——所以张数卡得很死，见 {@link #MAX_IDS}。
 *
 * 为什么要带上 peer
 *
 * 服务端据此判"这张图是不是出现在你和他的记录里"（见 ChatService.mayReadImage）。
 * 不带的话就只能拿着一个图片 id 去全服的记录里找，那既贵又等于承认"知道 id 就能看"。
 */
public record RequestChatImagePacket(UUID peer, List<UUID> images) implements CustomPacketPayload {

    /** 一次最多要几张。四张已经比一屏能显示的图还多 */
    public static final int MAX_IDS = 4;

    public static final CustomPacketPayload.Type<RequestChatImagePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "request_chat_image"));

    public static final StreamCodec<ByteBuf, RequestChatImagePacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, RequestChatImagePacket::peer,
                    UUIDUtil.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_IDS)),
                    RequestChatImagePacket::images,
                    RequestChatImagePacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
