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
        check(roundTrip(text,(v,b)->ChatMessage.STREAM_CODEC.encode(b,v),ChatMessage.STREAM_CODEC::decode).equals(text),"消息稳定 ID 与正文保留");
        check(roundTrip(pic,(v,b)->ChatMessage.STREAM_CODEC.encode(b,v),ChatMessage.STREAM_CODEC::decode).equals(pic),"消息稳定 ID 与正文保留");
        var history=new SyncMessagesPacket(peer,List.of(text,pic));
        check(roundTrip(history,(v,b)->SyncMessagesPacket.STREAM_CODEC.encode(b,v),SyncMessagesPacket.STREAM_CODEC::decode).equals(history),"历史保留每条独立消息 ID");
        var push=new NewMessagePacket(peer,text);
        check(roundTrip(push,(v,b)->NewMessagePacket.STREAM_CODEC.encode(b,v),NewMessagePacket.STREAM_CODEC::decode).equals(push),"新消息推送保留 ID");
        var deletion=new DeleteChatMessagePacket(peer,text.id(),request);
        check(roundTrip(deletion,(v,b)->DeleteChatMessagePacket.STREAM_CODEC.encode(b,v),DeleteChatMessagePacket.STREAM_CODEC::decode).equals(deletion),"删除请求字段归属正确");
        for(var result:ChatDeletionResult.values()) {
            var ack=new ChatMessageDeleteResultPacket(peer,text.id(),request,result);
            check(roundTrip(ack,(v,b)->ChatMessageDeleteResultPacket.STREAM_CODEC.encode(b,v),ChatMessageDeleteResultPacket.STREAM_CODEC::decode).equals(ack),"删除确认往返");
        }
        for(int invalid:new int[]{-1,99}) {
            var buf=new FriendlyByteBuf(Unpooled.buffer());
            try {
                buf.writeUUID(peer);buf.writeUUID(text.id());buf.writeUUID(request);buf.writeVarInt(invalid);
                try { ChatMessageDeleteResultPacket.STREAM_CODEC.decode(buf);throw new AssertionError("无效结果应拒绝"); }
                catch(IllegalArgumentException expected) { checks++; }
            } finally {buf.release();}
        }
        check(SyncMessagesPacket.TYPE.id().getPath().equals("sync_messages_v2"),"历史通道明确升级，旧客户端不会错读新增 ID");
        check(NewMessagePacket.TYPE.id().getPath().equals("new_chat_message_v2"),"推送通道同样升级");
        System.out.println("全部通过："+checks+" 条断言");
    }
}
