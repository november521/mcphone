package com.november.mcphone.core.client;

import com.november.mcphone.core.ServerConfig;
import com.november.mcphone.core.net.NetworkHandler;
import com.november.mcphone.core.net.SyncServerConfigPacket;
import com.november.mcphone.feature.settings.net.SyncWallpaperPacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * 网络包处理（客户端一半）—— 注册 S2C 包并处理客户端收到的推送。
 *
 * 必须放在含 {@code /client/} 的包里：{@link ClientPlayNetworking} 是客户端
 * 专用类，专用服务器不会加载本类，也就不会碰到它。C2S 那一半在
 * {@link NetworkHandler#registerServer()}。
 */
public final class ClientNetworking {

    private ClientNetworking() {}

    /** S2C：注册 + 客户端接收，由 MCphoneClient.onInitializeClient 调用 */
    public static void register() {
        PayloadTypeRegistry.playS2C().register(SyncWallpaperPacket.TYPE, SyncWallpaperPacket.STREAM_CODEC);
        ClientPlayNetworking.registerGlobalReceiver(SyncWallpaperPacket.TYPE,
                (packet, ctx) -> NetworkHandler.WakeholderData.setWallpaperFileName(packet.wallpaperFileName()));

        PayloadTypeRegistry.playS2C().register(SyncServerConfigPacket.TYPE, SyncServerConfigPacket.STREAM_CODEC);
        ClientPlayNetworking.registerGlobalReceiver(SyncServerConfigPacket.TYPE,
                (packet, ctx) -> ServerConfig.applySync(
                        packet.allowFriendTeleport(), packet.allowChatImages(), packet.chatImageMaxKb()));

        com.november.mcphone.feature.chat.client.ChatNetworkingClient.register();
        com.november.mcphone.feature.notes.client.NotesNetworkingClient.register();
        com.november.mcphone.feature.store.client.StoreNetworkingClient.register();
        com.november.mcphone.feature.music.client.MusicNetworkingClient.register();
    }
}
