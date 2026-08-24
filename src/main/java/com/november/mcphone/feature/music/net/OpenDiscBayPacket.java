package com.november.mcphone.feature.music.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 网络包：客户端 → 服务端，玩家要开唱片仓那个带背包的界面。
 *
 * 为什么非要走服务端：容器菜单必须由服务端 openMenu 建立，客户端自己 new
 * 一个菜单是没有权威性的 —— 所有的取放最终都由服务端裁决，客户端凭空造的
 * 菜单在服务端没有对应实例，物品搬运会被直接丢弃。与末影箱那个包同一条。
 *
 * 包体没有任何字段：要开的是"发包这名玩家自己的唱片仓"，服务端从连接
 * 上下文就能拿到玩家。带上玩家 ID 反而是给伪造客户端开后门。
 */
public record OpenDiscBayPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OpenDiscBayPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "open_disc_bay"));

    /** 无字段包用 unit 编解码器：不写任何字节，收端直接产出单例 */
    public static final StreamCodec<ByteBuf, OpenDiscBayPacket> STREAM_CODEC =
            StreamCodec.unit(new OpenDiscBayPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
