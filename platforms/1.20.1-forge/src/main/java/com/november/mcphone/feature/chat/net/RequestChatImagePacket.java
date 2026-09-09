package com.november.mcphone.feature.chat.net;

import com.november.mcphone.core.net.Wire;
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

    /**
     * 张数上限两侧都封死，见 {@link Wire}。
     *
     * 1.21.1 那边一句 ByteBufCodecs.list(MAX_IDS) 就带着两侧的上限；1.20.1 的
     * readCollection / writeCollection 都没有上限参数，交给 Wire 补上。
     *
     * 发的那一侧尤其要拦：这是个 C2S 包，超量的话服务端会在解码时抛，而 netty 的
     * 解码异常等于断开连接——症状是"点开一屏图就被踢下线"，而错在客户端。
     */
    public static void encode(RequestChatImagePacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.peer());
        Wire.writeList(buf, msg.images(), MAX_IDS, (v, b) -> b.writeUUID(v));
    }

    public static RequestChatImagePacket decode(FriendlyByteBuf buf) {
        UUID peer = buf.readUUID();
        List<UUID> images = Wire.readList(buf, MAX_IDS, FriendlyByteBuf::readUUID);
        return new RequestChatImagePacket(peer, images);
    }
}
