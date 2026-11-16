package com.november.mcphone.feature.chat.group.net;
import com.november.mcphone.feature.chat.ChatMessage;
import net.minecraft.network.FriendlyByteBuf;
/** Forge 的整数通道包装；身份追加在全部旧包之后。 */
public record GroupResponsePacket(GroupReply value) {
    public static void encode(GroupResponsePacket v, FriendlyByteBuf b) {
        GroupWire.writeReply(v.value(), b, ChatMessage::encode);
    }
    public static GroupResponsePacket decode(FriendlyByteBuf b) {
        return new GroupResponsePacket(GroupWire.readReply(b, ChatMessage::decode));
    }
}
