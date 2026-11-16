package com.november.mcphone.feature.chat.profile.net;
import net.minecraft.network.FriendlyByteBuf;
/** 旧版整数通道只负责编解码。 */
public record ProfileResponsePacket(ProfileWire.Reply value) {
    public static void encode(ProfileResponsePacket v, FriendlyByteBuf b) { ProfileWire.writeReply(v.value(), b); }
    public static ProfileResponsePacket decode(FriendlyByteBuf b) { return new ProfileResponsePacket(ProfileWire.readReply(b)); }
}
