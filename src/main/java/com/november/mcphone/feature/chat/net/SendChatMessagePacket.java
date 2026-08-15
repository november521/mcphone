package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.chat.ChatMessage;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 网络包：客户端 → 服务端，发一条消息给某人。
 *
 * 只带收件人与正文。发件人【不】带——那是发包这名玩家自己，服务端从
 * 连接上下文就拿得到；让客户端指定发件人等于允许冒充别人发消息。
 *
 * 时间戳也不带：客户端的钟不可信，早一点晚一点会打乱会话排序，
 * 极端情况下能把自己的消息永远顶在列表最前。一律以服务端收到的时刻为准。
 */
public record SendChatMessagePacket(UUID target, String text) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SendChatMessagePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "send_chat_message"));

    public static final StreamCodec<ByteBuf, SendChatMessagePacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, SendChatMessagePacket::target,
                    ByteBufCodecs.stringUtf8(ChatMessage.MAX_TEXT_LENGTH), SendChatMessagePacket::text,
                    SendChatMessagePacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
