package com.november.mcphone.feature.chat.group.net;
import com.november.mcphone.core.net.MCphoneNetwork;

/** 平台网络入口；权限、持久化、缓存和推送协调均在共享实现。 */
public final class GroupNetworking {
    private GroupNetworking() {}
    public static void register() {
        GroupServer.install((player, reply) -> MCphoneNetwork.sendToPlayer(player, new GroupResponsePacket(reply)),
                MCphoneNetwork::sendToPlayer);
        MCphoneNetwork.registerToServer(GroupRequestPacket.TYPE, GroupRequestPacket.STREAM_CODEC,
                (packet, player) -> GroupServer.handle(packet.value(), player));
        MCphoneNetwork.registerToClient(GroupResponsePacket.TYPE, GroupResponsePacket.STREAM_CODEC,
                packet -> GroupClientCache.accept(packet.value()));
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(handler.player,
                    com.november.mcphone.feature.chat.net.SyncConversationsPacket.TYPE)
                    && !net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.canSend(handler.player, GroupResponsePacket.TYPE)) {
                handler.disconnect(net.minecraft.network.chat.Component.translatable("mcphone.chat.protocol_mismatch"));
            }
        });
    }
}
