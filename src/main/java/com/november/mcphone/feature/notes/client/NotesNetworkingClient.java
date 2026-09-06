package com.november.mcphone.feature.notes.client;

import com.november.mcphone.feature.notes.net.NotesClientCache;
import com.november.mcphone.feature.notes.net.SyncNoteListPacket;
import com.november.mcphone.feature.notes.net.SyncNotePacket;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/**
 * 记事本网络包（客户端一半）：S2C 注册 + 客户端接收。
 * 必须放在含 {@code /client/} 的包里。
 */
public final class NotesNetworkingClient {

    private NotesNetworkingClient() {}

    /** 由 core.client.ClientNetworking.register 调用 */
    public static void register() {
        PayloadTypeRegistry.playS2C().register(SyncNoteListPacket.TYPE, SyncNoteListPacket.STREAM_CODEC);
        ClientPlayNetworking.registerGlobalReceiver(SyncNoteListPacket.TYPE, NotesNetworkingClient::handleSyncNoteList);

        PayloadTypeRegistry.playS2C().register(SyncNotePacket.TYPE, SyncNotePacket.STREAM_CODEC);
        ClientPlayNetworking.registerGlobalReceiver(SyncNotePacket.TYPE, NotesNetworkingClient::handleSyncNote);
    }

    private static void handleSyncNoteList(SyncNoteListPacket packet, ClientPlayNetworking.Context ctx) {
        NotesClientCache.setSummaries(packet.notes());
    }

    private static void handleSyncNote(SyncNotePacket packet, ClientPlayNetworking.Context ctx) {
        NotesClientCache.setOpenNote(packet.note());
    }
}
