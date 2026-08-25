package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** S2C：在线玩家列表（已排除本人），截断到 MAX_PLAYERS；totalOnline 是真实总数，界面据此写出"显示前 N 人 / 共 M 人"。 */
public record SyncOnlinePlayersPacket(List<OnlinePlayer> players, int totalOnline)
        implements CustomPacketPayload {

    public static final int MAX_PLAYERS = 200;

    public static final CustomPacketPayload.Type<SyncOnlinePlayersPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "sync_online_players"));

    public static final StreamCodec<ByteBuf, SyncOnlinePlayersPacket> STREAM_CODEC =
            StreamCodec.composite(
                    OnlinePlayer.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_PLAYERS)),
                    SyncOnlinePlayersPacket::players,
                    ByteBufCodecs.VAR_INT,
                    SyncOnlinePlayersPacket::totalOnline,
                    SyncOnlinePlayersPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

}
