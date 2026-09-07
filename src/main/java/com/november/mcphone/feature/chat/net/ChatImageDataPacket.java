package com.november.mcphone.feature.chat.net;

import com.november.mcphone.core.net.Wire;
import com.november.mcphone.feature.chat.ChatImage;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/**
 * S2C：一张图的像素。不分片——服务端发给客户端那条路的上限是 1 MB，一张图只有几十 KB。
 *
 * data 是空数组表示【这张图没了】：被上限挤掉了像素、或者服主手动清过图片仓。
 * 空数组而不是干脆不回，是因为客户端必须能分辨"还没到"与"不会来了"：
 * 前者要接着等，后者要把气泡改成「图片已过期」并且不再问第二次。
 */
public record ChatImageDataPacket(UUID image, byte[] data) {

    /** 两侧都拦上限，理由见 {@link Wire}——漏了发的那一侧，挨罚的是收件人 */
    public static void encode(ChatImageDataPacket msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.image());
        Wire.writeBytes(buf, msg.data(), ChatImage.MAX_BYTES_CEILING);
    }

    public static ChatImageDataPacket decode(FriendlyByteBuf buf) {
        return new ChatImageDataPacket(
                buf.readUUID(),
                Wire.readBytes(buf, ChatImage.MAX_BYTES_CEILING));
    }

    /** 没有这张图 */
    public static ChatImageDataPacket gone(UUID image) {
        return new ChatImageDataPacket(image, new byte[0]);
    }
}
