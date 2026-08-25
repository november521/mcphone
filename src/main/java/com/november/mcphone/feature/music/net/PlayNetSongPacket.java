package com.november.mcphone.feature.music.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.music.NetSong;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C：某个人的手机开始外放一首网络歌。发地址不发音频，每个客户端自己去拉。
 * 带实体 ID 而不是坐标：声音要跟着人走，与原版唱片那一支（绑在实体上）一致。
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
