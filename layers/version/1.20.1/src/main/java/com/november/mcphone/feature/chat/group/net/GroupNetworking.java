package com.november.mcphone.feature.chat.group.net;
import com.november.mcphone.core.net.MCphoneNetwork;

/** 平台网络入口；权限、持久化、缓存和推送协调均在共享实现。 */
public final class GroupNetworking {
    private GroupNetworking() {}
    public static void register() {
        GroupServer.install((player, reply) -> MCphoneNetwork.sendToPlayer(player, new GroupResponsePacket(reply)),
                MCphoneNetwork::sendToPlayer);
        MCphoneNetwork.registerToServer(GroupRequestPacket.class, GroupRequestPacket::encode, GroupRequestPacket::decode,
                (packet, player) -> GroupServer.handle(packet.value(), player));
        MCphoneNetwork.registerToClient(GroupResponsePacket.class, GroupResponsePacket::encode, GroupResponsePacket::decode,
                packet -> GroupClientCache.accept(packet.value()));

    }
}
