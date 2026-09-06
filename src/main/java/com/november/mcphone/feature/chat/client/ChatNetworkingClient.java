package com.november.mcphone.feature.chat.client;

import com.november.mcphone.feature.chat.net.ChatClientCache;
import com.november.mcphone.feature.chat.net.ChatImageDataPacket;
import com.november.mcphone.feature.chat.net.NewMessagePacket;
import com.november.mcphone.feature.chat.net.SyncConversationsPacket;
import com.november.mcphone.feature.chat.net.SyncMessagesPacket;
import com.november.mcphone.feature.chat.net.SyncOnlinePlayersPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * 聊天网络包（客户端一半）：S2C 注册 + 客户端接收。
 *
 * 必须放在含 {@code /client/} 的包里：{@link ClientPlayNetworking} 是客户端
 * 专用类，专用服务器不会加载本类。
 */
public final class ChatNetworkingClient {

    private ChatNetworkingClient() {}

    /** 由 core.client.ClientNetworking.register 调用 */
    public static void register() {
        PayloadTypeRegistry.playS2C().register(SyncConversationsPacket.TYPE, SyncConversationsPacket.STREAM_CODEC);
        ClientPlayNetworking.registerGlobalReceiver(SyncConversationsPacket.TYPE, ChatNetworkingClient::handleSyncConversations);

        PayloadTypeRegistry.playS2C().register(SyncMessagesPacket.TYPE, SyncMessagesPacket.STREAM_CODEC);
        ClientPlayNetworking.registerGlobalReceiver(SyncMessagesPacket.TYPE, ChatNetworkingClient::handleSyncMessages);

        PayloadTypeRegistry.playS2C().register(NewMessagePacket.TYPE, NewMessagePacket.STREAM_CODEC);
        ClientPlayNetworking.registerGlobalReceiver(NewMessagePacket.TYPE, ChatNetworkingClient::handleNewMessage);

        PayloadTypeRegistry.playS2C().register(ChatImageDataPacket.TYPE, ChatImageDataPacket.STREAM_CODEC);
        ClientPlayNetworking.registerGlobalReceiver(ChatImageDataPacket.TYPE, ChatNetworkingClient::handleImageData);

        PayloadTypeRegistry.playS2C().register(SyncOnlinePlayersPacket.TYPE, SyncOnlinePlayersPacket.STREAM_CODEC);
        ClientPlayNetworking.registerGlobalReceiver(SyncOnlinePlayersPacket.TYPE, ChatNetworkingClient::handleSyncOnlinePlayers);
    }

    private static void handleSyncConversations(SyncConversationsPacket packet, ClientPlayNetworking.Context ctx) {
        ChatClientCache.setConversations(packet.conversations());
    }

    private static void handleSyncMessages(SyncMessagesPacket packet, ClientPlayNetworking.Context ctx) {
        ChatClientCache.setMessages(packet.peer(), packet.messages());
    }

    private static void handleNewMessage(NewMessagePacket packet, ClientPlayNetworking.Context ctx) {
        ChatClientCache.onNewMessage(packet.peer(), packet.message());
    }

    private static void handleImageData(ChatImageDataPacket packet, ClientPlayNetworking.Context ctx) {
        ChatClientCache.onImageData(packet.image(), packet.data());
    }

    private static void handleSyncOnlinePlayers(SyncOnlinePlayersPacket packet, ClientPlayNetworking.Context ctx) {
        ChatClientCache.setOnlinePlayers(packet.players(), packet.totalOnline());
    }
}
