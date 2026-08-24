package com.november.mcphone.feature.music.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 网络包：服务端 → 客户端，某个人的手机把网络音乐停了。
 *
 * 与原版唱片那一支用的 {@code ClientboundStopSoundPacket} 不同，这里能
 * 精确停到【这一个人】的那一份：原版那个包是按音效 ID 停的，粒度只到
 * "这一类声音"，旁边有台唱片机在放同一张唱片也会被一起停掉（见
 * DiscService.stopSound 的注释）。
 *
 * 我们自己的声源是按实体记的，所以停得准 —— 两个人同时用手机放歌，
 * 一个人停不会把另一个人的也停掉。
 *
 * @param entityId 谁停了
 */
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
