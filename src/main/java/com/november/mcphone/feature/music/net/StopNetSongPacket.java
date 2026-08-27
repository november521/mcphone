package com.november.mcphone.feature.music.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** S2C：某个人的手机把网络音乐停了。按实体停，比原版按音效 ID 的 ClientboundStopSoundPacket 停得准 */
public record StopNetSongPacket(int entityId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<StopNetSongPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "stop_net_song"));

    public static final StreamCodec<ByteBuf, StopNetSongPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, StopNetSongPacket::entityId,
                    StopNetSongPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
