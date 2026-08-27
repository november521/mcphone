package com.november.mcphone.feature.music.net;

import net.minecraft.network.FriendlyByteBuf;

/** S2C：某个人的手机把网络音乐停了。按实体停，比原版按音效 ID 的 ClientboundStopSoundPacket 停得准 */
public record StopNetSongPacket(int entityId) {

    public static void encode(StopNetSongPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.entityId());
    }

    public static StopNetSongPacket decode(FriendlyByteBuf buf) {
        return new StopNetSongPacket(
                buf.readVarInt());
    }
}
