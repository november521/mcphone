package com.november.mcphone.core.client;

import com.november.mcphone.core.net.MCphoneNetwork;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class ClientNetworking {
    private ClientNetworking() {}

    public static void register() {
        MCphoneNetwork.installClient((channel, packet) -> {
            if (ClientPlayNetworking.canSend(channel)) ClientPlayNetworking.send(channel, packet);
            else packet.release();
        }, ClientNetworking::receive);
    }

    private static <T> void receive(MCphoneNetwork.Incoming<T> incoming) {
        ClientPlayNetworking.registerGlobalReceiver(incoming.channel(), (client, connection, buffer, response) -> {
            T packet = incoming.decoder().apply(buffer);
            client.execute(() -> {
                if (client.getConnection() == connection) incoming.handler().accept(packet);
            });
        });
    }
}
