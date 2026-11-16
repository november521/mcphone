package com.november.mcphone.core.net;

import com.november.mcphone.core.ServerConfig;
import net.minecraft.network.FriendlyByteBuf;

public record SyncServerConfigPacket(boolean allowFriendTeleport, boolean allowChatImages, int chatImageMaxKb) {
    public static SyncServerConfigPacket current() {
        var config = ServerConfig.server();
        return new SyncServerConfigPacket(config.allowFriendTeleport(), config.allowChatImages(), config.chatImageMaxKb());
    }

    static void encode(SyncServerConfigPacket packet, FriendlyByteBuf buffer) {
        buffer.writeBoolean(packet.allowFriendTeleport());
        buffer.writeBoolean(packet.allowChatImages());
        buffer.writeVarInt(packet.chatImageMaxKb());
    }

    static SyncServerConfigPacket decode(FriendlyByteBuf buffer) {
        return new SyncServerConfigPacket(buffer.readBoolean(), buffer.readBoolean(), buffer.readVarInt());
    }

    void apply() {
        ServerConfig.applySync(allowFriendTeleport, allowChatImages, chatImageMaxKb);
    }
}
