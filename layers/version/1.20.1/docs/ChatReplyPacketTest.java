package com.november.mcphone.feature.chat.net;
import com.november.mcphone.feature.chat.*;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import java.util.*;

/** 实际消息包必须保留引用；编码、解码都必须完整消费字段。 */
public final class ChatReplyPacketTest {
    private static int checks;
    private static <T> void roundTrip(T value,java.util.function.BiConsumer<T,FriendlyByteBuf> write,java.util.function.Function<FriendlyByteBuf,T> read) {
        var buffer=new FriendlyByteBuf(Unpooled.buffer());
        try { write.accept(value,buffer);checks++;if(!read.apply(buffer).equals(value))throw new AssertionError("引用消息包往返不一致");
            checks++;if(buffer.isReadable())throw new AssertionError("引用字段读取后仍有残余"); }
        finally {buffer.release();}
    }
    public static void main(String[] args) {
        UUID peer=UUID.randomUUID();var original=ChatMessage.text(peer,"原始内容",100);
        var quoted=new ChatMessage(peer,200,new TextBody("回复😀",Optional.of(ChatQuote.from(original,"Player"))));
        roundTrip(quoted,ChatMessage::encode,ChatMessage::decode);
        roundTrip(new SendChatMessagePacket(peer,"回复😀",Optional.of(original.id())),SendChatMessagePacket::encode,SendChatMessagePacket::decode);
        roundTrip(new NewMessagePacket(peer,quoted),NewMessagePacket::encode,NewMessagePacket::decode);
        roundTrip(new SyncMessagesPacket(peer,List.of(original,quoted)),SyncMessagesPacket::encode,SyncMessagesPacket::decode);
        System.out.println("全部通过："+checks+" 条断言");
    }
}
