package com.november.mcphone.core.script.net;

import com.november.mcphone.MCphone;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** 1.20.5+ 的通道包装；字段与编解码仍只有 shared 一份。 */
public record StoreFileRequestPayload(StoreFileRequest msg) implements CustomPacketPayload {
    public static final Type<StoreFileRequestPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(MCphone.MODID,"store_file"));
    public static final StreamCodec<FriendlyByteBuf,StoreFileRequestPayload> STREAM_CODEC=StreamCodec.of((b,p)->StoreFileRequest.encode(p.msg,b),b->new StoreFileRequestPayload(StoreFileRequest.decode(b)));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
