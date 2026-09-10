package com.november.mcphone.core.net;

import com.mojang.logging.LogUtils;
import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

/**
 * 网络包：服务端 → 客户端，把服主的那份服务端配置推给玩家。
 *
 * 原 NeoForge 版由加载器自动把服务端配置同步过来；Fabric 没有这个机制，
 * 所以补这一个包，玩家连上服务器时发一次。客户端把它存进
 * {@link com.november.mcphone.core.ServerConfig} 的同步态，界面据此提前藏按钮。
 * 真正的拦截仍在服务端，界面只是不给入口。
 */
public record SyncServerConfigPacket(boolean allowFriendTeleport,
                                     boolean allowChatImages,
                                     int chatImageMaxKb) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    public static final CustomPacketPayload.Type<SyncServerConfigPacket> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "sync_server_config"));

    public static final StreamCodec<ByteBuf, SyncServerConfigPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, SyncServerConfigPacket::allowFriendTeleport,
                    ByteBufCodecs.BOOL, SyncServerConfigPacket::allowChatImages,
                    ByteBufCodecs.VAR_INT, SyncServerConfigPacket::chatImageMaxKb,
                    SyncServerConfigPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
