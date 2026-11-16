package com.november.mcphone.feature.chat.group.net;
import com.november.mcphone.feature.chat.ChatMessage;
import net.minecraft.network.FriendlyByteBuf;
/** Forge 的整数通道包装；身份追加在全部旧包之后。 */
public record GroupRequestPacket(GroupCommand value) {
    public static void encode(GroupRequestPacket v, FriendlyByteBuf b) {
        GroupWire.writeCommand(v.value(), b);
    }
    public static GroupRequestPacket decode(FriendlyByteBuf b) {
        return new GroupRequestPacket(GroupWire.readCommand(b));
    }
}
