package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 网络包：服务端 → 客户端，当前在线玩家列表，供"加联系人"界面使用。
 *
 * 为什么要截断，以及为什么要带总数
 *
 * 一个 300 人的服务器全量下发既没意义（手机屏幕也翻不完），
 * 又是白送的攻击面——反复请求就能压出可观的流量。
 *
 * 但只截断不告知是在骗人：玩家会以为服务器就这么些人。故把真实总数
 * 一并带下来，界面可以明确写出"显示前 N 人 / 共 M 人"。
 *
 * @param players     截断后的玩家列表，已排除本人
 * @param totalOnline 服务器实际在线人数（不含本人），用于提示被截断了多少
 */
public record SyncOnlinePlayersPacket(List<OnlinePlayer> players, int totalOnline)
        implements CustomPacketPayload {

    /** 单次下发的玩家数上限 */
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
