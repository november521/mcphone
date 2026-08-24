package com.november.mcphone.feature.music.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.music.NetSong;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 网络包：服务端 → 客户端，某个人的手机开始外放一首网络歌。
 *
 * ================================================================
 * 为什么发地址，而不是音频
 * ================================================================
 *
 * 这正是 {@link com.november.mcphone.feature.music.Track.Kind} 里那条
 * "本地文件不能外放"想不通的地方：服务端没法把你硬盘上那个 mp3 发给别人，
 * 别人电脑上也没有那个文件。
 *
 * 网络音乐没有这个问题 —— 歌在网上，每个客户端自己去拉就行。服务端只需要
 * 说一句"某某在放这个地址"，一句话的事。NetMusic 自己也是这么做的。
 *
 * ================================================================
 * 为什么带实体 ID 而不是坐标
 * ================================================================
 *
 * 因为声音要跟着人走。给坐标的话，声源就钉在按下播放的那一刻他站的地方，
 * 与原版唱片那一支（绑在实体上）行为不一致 —— 同一个唱片仓里两种唱片
 * 表现不同，玩家只会觉得坏了。
 *
 * @param entityId 谁在放。客户端拿它去自己那份世界里找那个实体
 * @param song     放什么
 */
public record PlayNetSongPacket(int entityId, NetSong song) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PlayNetSongPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "play_net_song"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayNetSongPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, PlayNetSongPacket::entityId,
                    NetSong.STREAM_CODEC, PlayNetSongPacket::song,
                    PlayNetSongPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
