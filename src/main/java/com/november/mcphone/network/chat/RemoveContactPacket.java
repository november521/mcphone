package com.november.mcphone.network.chat;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 网络包：客户端 → 服务端，删除某个联系人。
 *
 * 只删本人这一侧：对方的联系人列表是他自己的数据，我方无权改动。
 * 聊天记录也不删——那是双方共有的，单方面抹掉等于替对方做决定。
 */
public record RemoveContactPacket(UUID target) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RemoveContactPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "remove_contact"));

    public static final StreamCodec<ByteBuf, RemoveContactPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, RemoveContactPacket::target,
                    RemoveContactPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
