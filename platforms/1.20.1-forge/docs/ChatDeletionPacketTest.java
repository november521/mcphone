package com.november.mcphone.feature.chat.net;

import com.november.mcphone.feature.chat.ChatDeletionResult;
import com.november.mcphone.feature.chat.ChatMessage;
import com.november.mcphone.feature.chat.ImageBody;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import java.util.List;
import java.util.UUID;

/** 稳定消息 ID、删除请求及结果的实际线格式往返；未知结果不得误当成功。 */
public final class ChatDeletionPacketTest {
    private static int checks;
    private static void check(boolean ok,String message) { checks++; if(!ok)throw new AssertionError(message); }
    private static <T> T roundTrip(T value,java.util.function.BiConsumer<T,FriendlyByteBuf> encode,
                                  java.util.function.Function<FriendlyByteBuf,T> decode) {
        var buf=new FriendlyByteBuf(Unpooled.buffer());
        try { encode.accept(value,buf); T back=decode.apply(buf);
            check(buf.readableBytes()==0,"线格式读尽，不残留错位字段"); return back;
        } finally { buf.release(); }
    }
    public static void main(String[] args) {
        UUID peer=new UUID(0,1), request=new UUID(0,2), image=new UUID(0,3);
        var text=ChatMessage.text(peer,"中文 abc\n第二行",123);
        var pic=new ChatMessage(peer,124,new ImageBody(image,16,32,1,100));
        check(roundTrip(text,ChatMessage::encode,ChatMessage::decode).equals(text),"消息稳定 ID 与正文保留");
        check(roundTrip(pic,ChatMessage::encode,ChatMessage::decode).equals(pic),"消息稳定 ID 与正文保留");
        var history=new SyncMessagesPacket(peer,List.of(text,pic));
        check(roundTrip(history,SyncMessagesPacket::encode,SyncMessagesPacket::decode).equals(history),"历史保留每条独立消息 ID");
        var push=new NewMessagePacket(peer,text);
        check(roundTrip(push,NewMessagePacket::encode,NewMessagePacket::decode).equals(push),"新消息推送保留 ID");
        var deletion=new DeleteChatMessagePacket(peer,text.id(),request);
        check(roundTrip(deletion,DeleteChatMessagePacket::encode,DeleteChatMessagePacket::decode).equals(deletion),"删除请求字段归属正确");
        for(var result:ChatDeletionResult.values()) {
            var ack=new ChatMessageDeleteResultPacket(peer,text.id(),request,result);
            check(roundTrip(ack,ChatMessageDeleteResultPacket::encode,ChatMessageDeleteResultPacket::decode).equals(ack),"删除确认往返");
        }
        for(int invalid:new int[]{-1,99}) {
            var buf=new FriendlyByteBuf(Unpooled.buffer());
            try {
                buf.writeUUID(peer);buf.writeUUID(text.id());buf.writeUUID(request);buf.writeVarInt(invalid);
                try { ChatMessageDeleteResultPacket.decode(buf);throw new AssertionError("无效结果应拒绝"); }
                catch(IllegalArgumentException expected) { checks++; }
            } finally {buf.release();}
        }
        System.out.println("全部通过："+checks+" 条断言");
    }
}
