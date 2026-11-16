package com.november.mcphone.feature.chat.profile.net;
import com.november.mcphone.core.net.MCphoneNetwork;
import com.november.mcphone.feature.chat.client.profile.ProfileClientCache;

/** 加载器注册适配，不维护个人资料业务。 */
public final class ProfileNetworking {
    private ProfileNetworking() {}
    public static void register() {
        ProfileServer.install((player, reply) -> MCphoneNetwork.sendToPlayer(player, new ProfileResponsePacket(reply)));
        MCphoneNetwork.registerToServer(ProfileRequestPacket.class, ProfileRequestPacket::encode, ProfileRequestPacket::decode,
                (packet, player) -> ProfileServer.handle(packet.value(), player));
        MCphoneNetwork.registerToClient(ProfileResponsePacket.class, ProfileResponsePacket::encode, ProfileResponsePacket::decode,
                packet -> ProfileClientCache.accept(packet.value()));
    }
}
