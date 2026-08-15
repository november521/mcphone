package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.chat.ChatMessage;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 网络包：服务端 → 客户端，来了一条新消息。
 *
 * 只推【这一条】，不重发整个会话列表——那是聊天功能最容易写崩的地方：
 * 一个热闹的服务器里每秒几条消息，每条都全量同步的话，带宽和客户端
 * 卡顿都会失控。
 *
 * 收发双方都会收到这个包：发件人也要收，界面才能立刻显示自己刚发出去
 * 的那条。让客户端自己乐观插入的话，一旦服务端因为校验不过而丢弃了
 * 这条消息，界面上就会留下一条并不存在的消息。
 *
 * @param peer    对端 UUID。站在收包这一方的角度：收件人收到时 peer 是
 *                发件人，发件人收到回声时 peer 是收件人。界面据此归入
 *                哪一个会话。
 * @param message 消息本体，时间戳由服务端盖章
 */
public record NewMessagePacket(UUID peer, ChatMessage message) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<NewMessagePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "new_chat_message"));

    public static final StreamCodec<ByteBuf, NewMessagePacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, NewMessagePacket::peer,
                    ChatMessage.STREAM_CODEC, NewMessagePacket::message,
                    NewMessagePacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
