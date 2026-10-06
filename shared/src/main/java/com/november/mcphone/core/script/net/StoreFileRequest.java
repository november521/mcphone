package com.november.mcphone.core.script.net;

import com.november.mcphone.core.net.Wire;
import net.minecraft.network.FriendlyByteBuf;

/** 原生商店专用字节通道；不承载脚本参数，不改变普通 RPC 的 4 KiB 上限。 */
public record StoreFileRequest(int protocol,long epoch,String token,int kind,String digest,int point,byte[] bytes) {
    public static final int PROTOCOL=1,CHUNK=16*1024,UPLOAD=1,DOWNLOAD=2;
    public StoreFileRequest {
        if(epoch==0||token==null||!token.matches("[0-9a-f]{32}")||point<0||point>2*1024*1024||bytes==null||bytes.length>CHUNK)throw new IllegalArgumentException("文件片字段无效");
        if(kind==UPLOAD){if(!"".equals(digest)||point>64||bytes.length==0)throw new IllegalArgumentException("上传片无效");}
        else if(kind==DOWNLOAD){if(digest==null||!digest.matches("[0-9a-f]{64}")||bytes.length!=0||point%CHUNK!=0)throw new IllegalArgumentException("下载片无效");}
        else throw new IllegalArgumentException("文件片方向无效");
        bytes=bytes.clone();
    }
    @Override public byte[] bytes(){return bytes.clone();}
    public static void encode(StoreFileRequest m,FriendlyByteBuf b){b.writeVarInt(m.protocol);b.writeVarLong(m.epoch);b.writeUtf(m.token,32);b.writeVarInt(m.kind);b.writeUtf(m.digest,64);b.writeVarInt(m.point);Wire.writeBytes(b,m.bytes,CHUNK);}
    public static StoreFileRequest decode(FriendlyByteBuf b){return new StoreFileRequest(b.readVarInt(),b.readVarLong(),b.readUtf(32),b.readVarInt(),b.readUtf(64),b.readVarInt(),Wire.readBytes(b,CHUNK));}
}
