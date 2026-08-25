package com.november.mcphone.feature.music.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S：玩家要开唱片仓那个带背包的界面。容器菜单必须由服务端 openMenu 建立。
 * 故意没有字段：开的是发包玩家自己的唱片仓，带玩家 ID 反而给伪造客户端开后门。
 */
public record OpenDiscBayPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OpenDiscBayPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "open_disc_bay"));

    public static final StreamCodec<ByteBuf, OpenDiscBayPacket> STREAM_CODEC =
            StreamCodec.unit(new OpenDiscBayPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
