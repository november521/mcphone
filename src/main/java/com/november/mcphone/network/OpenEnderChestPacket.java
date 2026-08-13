package com.november.mcphone.network;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 网络包：客户端 → 服务端，玩家在手机里点了「末影箱」。
 *
 * 为什么非要走服务端：容器菜单必须由服务端 openMenu 建立，客户端
 * 自己 new 一个菜单是没有权威性的——所有的取放最终都由服务端裁决，
 * 客户端凭空造的菜单在服务端没有对应实例，物品搬运会被直接丢弃。
 *
 * 包体没有任何字段：要开的是"发包这名玩家自己的末影箱"，服务端从
 * 连接上下文就能拿到玩家。带上玩家 ID 反而是给伪造客户端开后门。
 */
public record OpenEnderChestPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OpenEnderChestPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "open_ender_chest"));

    /** 无字段包用 unit 编解码器：不写任何字节，收端直接产出单例 */
    public static final StreamCodec<ByteBuf, OpenEnderChestPacket> STREAM_CODEC =
            StreamCodec.unit(new OpenEnderChestPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
