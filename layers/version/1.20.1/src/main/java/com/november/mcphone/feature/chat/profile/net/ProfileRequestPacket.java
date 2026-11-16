package com.november.mcphone.feature.chat.profile.net;
import net.minecraft.network.FriendlyByteBuf;
/** 旧版整数通道只负责编解码。 */
public record ProfileRequestPacket(ProfileWire.Query value) {
    public static void encode(ProfileRequestPacket v, FriendlyByteBuf b) { ProfileWire.writeQuery(v.value(), b); }
    public static ProfileRequestPacket decode(FriendlyByteBuf b) { return new ProfileRequestPacket(ProfileWire.readQuery(b)); }
}
