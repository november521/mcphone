package com.november.mcphone.feature.chat.net;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;

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
public record RequestChatImagePacket(UUID peer, List<UUID> images) {

    /** 一次最多要几张。四张已经比一屏能显示的图还多 */
    public static final int MAX_IDS = 4;

    public static void encode(RequestChatImagePacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.peer());
        buf.writeCollection(msg.images(), (b, v) -> b.writeUUID(v));
    }

    /**
     * 张数上限在解码这一侧封死。
     *
     * 1.21.1 那边写成 ByteBufCodecs.list(MAX_IDS)，上限由组合子带着；1.20.1 的
     * readCollection 【没有上限参数】，得自己在分配前拦一道。
     */
    public static RequestChatImagePacket decode(FriendlyByteBuf buf) {
        UUID peer = buf.readUUID();
        List<UUID> images = buf.readCollection(n -> {
            if (n > MAX_IDS) throw new DecoderException("一次要的张数超过上限 " + MAX_IDS + ": " + n);
            return new java.util.ArrayList<UUID>(n);
        }, FriendlyByteBuf::readUUID);
        return new RequestChatImagePacket(peer, images);
    }
}
