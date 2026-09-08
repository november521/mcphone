package com.november.mcphone.core.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.function.Consumer;

/**
 * 网络包处理（客户端一半）—— 给 {@code MCphoneNetwork} 装桥、领走 S2C 接收器。
 *
 * 必须放在含 {@code /client/} 的包里：{@link ClientPlayNetworking} 是客户端
 * 专用类，专用服务器不会加载本类，也就不会碰到它。C2S 那一半在
 * {@code NetworkHandler#registerServer()}。
 *
 * S2C 的处理函数本体都住在共享的 {@code *Networking} 类里（上游 1.10.1 起
 * 就是这个形状），由 {@code MCphoneNetwork.registerToClient} 收进候车室；
 * 本类是领走它们的那只手 —— 除此之外它没有任何自己的包。
 */
@Environment(EnvType.CLIENT)
public final class ClientNetworking {

    private ClientNetworking() {}

    /** S2C：装桥 + 领接收器，由 MCphoneClient.onInitializeClient 调用 */
    public static void register() {
        com.november.mcphone.core.net.MCphoneNetwork.installClient(
                ClientPlayNetworking::send,
                new com.november.mcphone.core.net.MCphoneNetwork.S2CInstaller() {
                    @Override
                    public <T extends CustomPacketPayload> void register(
                            CustomPacketPayload.Type<T> type,
                            Consumer<T> handler) {
                        ClientPlayNetworking.registerGlobalReceiver(
                                type, (packet, ctx) -> handler.accept(packet));
                    }
                });
    }
}
