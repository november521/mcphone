package com.november.mcphone.core.script.net;

import com.november.mcphone.MCphone;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 与上传分开的 S2C 字节回包，不能被用作任意脚本结果。 */
public record StoreFileReplyPayload(StoreFileReply msg) implements CustomPacketPayload {
    public static final Type<StoreFileReplyPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(MCphone.MODID,"store_file_reply"));
    public static final StreamCodec<FriendlyByteBuf,StoreFileReplyPayload> STREAM_CODEC=StreamCodec.of((b,p)->StoreFileReply.encode(p.msg,b),b->new StoreFileReplyPayload(StoreFileReply.decode(b)));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
