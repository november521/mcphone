package com.november.mcphone.feature.music.client;

import com.november.mcphone.feature.music.net.PlayNetSongPacket;
import com.november.mcphone.feature.music.net.StopNetSongPacket;
import com.november.mcphone.feature.music.net.SyncDiscStatePacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * 音乐网络包（客户端一半）：S2C 注册 + 客户端接收。
 * 必须放在含 {@code /client/} 的包里。
 */
public final class MusicNetworkingClient {

    private MusicNetworkingClient() {}

    /** 由 core.client.ClientNetworking.register 调用 */
    public static void register() {
        PayloadTypeRegistry.playS2C().register(SyncDiscStatePacket.TYPE, SyncDiscStatePacket.STREAM_CODEC);
        ClientPlayNetworking.registerGlobalReceiver(SyncDiscStatePacket.TYPE, MusicNetworkingClient::handleSync);

        // 网络歌的开始 / 停止发给听得见的每一个人，不只是放歌的那个
        PayloadTypeRegistry.playS2C().register(PlayNetSongPacket.TYPE, PlayNetSongPacket.STREAM_CODEC);
        ClientPlayNetworking.registerGlobalReceiver(PlayNetSongPacket.TYPE, MusicNetworkingClient::handlePlayNetSong);

        PayloadTypeRegistry.playS2C().register(StopNetSongPacket.TYPE, StopNetSongPacket.STREAM_CODEC);
        ClientPlayNetworking.registerGlobalReceiver(StopNetSongPacket.TYPE, MusicNetworkingClient::handleStopNetSong);
    }

    private static void handleSync(SyncDiscStatePacket packet, ClientPlayNetworking.Context ctx) {
        DiscClientCache.set(packet.disc(), packet.endsAtTick());
    }

    private static void handlePlayNetSong(PlayNetSongPacket packet, ClientPlayNetworking.Context ctx) {
        NetSongPlayback.start(packet.entityId(), packet.song());
    }

    private static void handleStopNetSong(StopNetSongPacket packet, ClientPlayNetworking.Context ctx) {
        NetSongPlayback.stop(packet.entityId());
    }
}
