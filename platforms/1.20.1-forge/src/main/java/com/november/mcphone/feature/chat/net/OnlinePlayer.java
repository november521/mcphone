package com.november.mcphone.feature.chat.net;


import net.minecraft.network.FriendlyByteBuf;
import java.util.UUID;

/** "加联系人"界面里的一行（一个在线玩家）；relation 决定这一行显示什么按钮。 */
public record OnlinePlayer(UUID id, String name, Relation relation) {

    public static void encode(OnlinePlayer msg, FriendlyByteBuf buf) {
        buf.writeUUID(msg.id());
        buf.writeUtf(msg.name(), ConversationSummary.MAX_NAME_LENGTH);
        Relation.encode(msg.relation(), buf);
    }

    public static OnlinePlayer decode(FriendlyByteBuf buf) {
        return new OnlinePlayer(
                buf.readUUID(),
                buf.readUtf(ConversationSummary.MAX_NAME_LENGTH),
                Relation.decode(buf));
    }
}
