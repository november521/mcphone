package com.november.mcphone.network.notes;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.codec.ByteBufCodecs;

/**
 * 网络包：客户端 → 服务端，删掉一条笔记。
 *
 * 与保存分开而不是"保存空正文即删除"：那样一次误触就可能把整条笔记
 * 抹掉，而删除本该是个明确的动作。服务端两条路都支持，是因为编辑时
 * 把内容清空再保存，语义上确实就是不要它了。
 */
public record DeleteNotePacket(int id) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<DeleteNotePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "delete_note"));

    public static final StreamCodec<ByteBuf, DeleteNotePacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, DeleteNotePacket::id,
                    DeleteNotePacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
