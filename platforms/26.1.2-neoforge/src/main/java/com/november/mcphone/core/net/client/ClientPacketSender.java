package com.november.mcphone.core.net.client;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
/** 新版客户端发包入口已移入 client 包；服务端网络入口不直接引用它的加载器实现。 */
public final class ClientPacketSender {
    private ClientPacketSender() {}
    public static void send(CustomPacketPayload packet) { ClientPacketDistributor.sendToServer(packet); }
}
