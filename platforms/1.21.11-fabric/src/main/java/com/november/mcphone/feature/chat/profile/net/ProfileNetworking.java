package com.november.mcphone.feature.chat.profile.net;
import com.november.mcphone.core.net.MCphoneNetwork;
import com.november.mcphone.feature.chat.client.profile.ProfileClientCache;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/** 加载器注册适配，不维护个人资料业务。 */
public final class ProfileNetworking {
    private ProfileNetworking() {}
    public static void register() {
        ProfileServer.install((player, reply) -> MCphoneNetwork.sendToPlayer(player, new ProfileResponsePacket(reply)));
        MCphoneNetwork.registerToServer(ProfileRequestPacket.TYPE, ProfileRequestPacket.STREAM_CODEC,
                (packet, player) -> ProfileServer.handle(packet.value(), player));
        MCphoneNetwork.registerToClient(ProfileResponsePacket.TYPE, ProfileResponsePacket.STREAM_CODEC,
                packet -> ProfileClientCache.accept(packet.value()));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (ServerPlayNetworking.canSend(handler.player, com.november.mcphone.feature.chat.net.SyncConversationsPacket.TYPE)
                    && !ServerPlayNetworking.canSend(handler.player, ProfileResponsePacket.TYPE))
                handler.disconnect(net.minecraft.network.chat.Component.translatable("mcphone.chat.group.protocol_mismatch"));
        });
    }
}
