package com.november.mcphone.feature.chat.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 网络包：客户端 → 服务端，把某个会话标为已读。
 *
 * ============================================================
 * 为什么不复用请求历史消息那个包
 * ============================================================
 *
 * 拉历史时服务端本来就会顺带标已读，问题出在【会话开着的时候】：玩家
 * 正盯着界面，对方发来的消息明明看见了，退出去却还顶着未读红点。
 *
 * 用 RequestMessagesPacket 也能顺带标已读，但那要把整段历史（最多 100
 * 条）再传一遍，只为改一个时间戳。这个包只带一个 UUID。
 *
 * 已读时刻仍由服务端盖章，本包不带时间：客户端报一个未来的时间戳就能
 * 把之后收到的消息全标成已读，红点再也不出现。
 */
public record MarkReadPacket(UUID peer) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<MarkReadPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "mark_read"));

    public static final StreamCodec<ByteBuf, MarkReadPacket> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, MarkReadPacket::peer,
                    MarkReadPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
