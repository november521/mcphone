package com.november.mcphone.network.chat;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 网络包：客户端 → 服务端，把某人加为联系人。
 *
 * 只带 UUID，不带名字：名字由服务端自己解析。让客户端指定名字的话，
 * 伪造客户端就能把联系人存成任意字符串，那是往别人存档里写垃圾。
 */
public record AddContactPacket(UUID target) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AddContactPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "add_contact"));

    public static final StreamCodec<ByteBuf, AddContactPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, AddContactPacket::target,
                    AddContactPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
