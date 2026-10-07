package com.november.mcphone.core.script.net;

import com.november.mcphone.core.net.Wire;
import net.minecraft.network.FriendlyByteBuf;
import java.util.UUID;

/** 回包绑定世界 UUID、连接 epoch、会话随机号和片位置；客户端不能把旧片拼到新下载。 */
public record StoreFileReply(int protocol,UUID serverId,long epoch,String token,int kind,int point,int next,int size,ScriptErrorCode code,byte[] bytes) {
    public StoreFileReply {
        if(serverId==null||epoch==0||token==null||!token.matches("[0-9a-f]{32}")||(kind!=1&&kind!=2)||point<0||next<0||size<0||point>2*1024*1024||next>2*1024*1024||size>2*1024*1024||code==null||bytes==null||bytes.length>StoreFileRequest.CHUNK)throw new IllegalArgumentException("文件回包字段无效");
        if(code!=ScriptErrorCode.OK&&bytes.length!=0)throw new IllegalArgumentException("拒绝回包不能携带文件");
        bytes=bytes.clone();
    }
    @Override public byte[] bytes(){return bytes.clone();}
    public static void encode(StoreFileReply m,FriendlyByteBuf b){b.writeVarInt(m.protocol);b.writeUUID(m.serverId);b.writeVarLong(m.epoch);b.writeUtf(m.token,32);b.writeVarInt(m.kind);b.writeVarInt(m.point);b.writeVarInt(m.next);b.writeVarInt(m.size);b.writeVarInt(m.code.toWire());Wire.writeBytes(b,m.bytes,StoreFileRequest.CHUNK);}
    public static StoreFileReply decode(FriendlyByteBuf b){return new StoreFileReply(b.readVarInt(),b.readUUID(),b.readVarLong(),b.readUtf(32),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readVarInt(),ScriptErrorCode.fromWire(b.readVarInt()),Wire.readBytes(b,StoreFileRequest.CHUNK));}
}
