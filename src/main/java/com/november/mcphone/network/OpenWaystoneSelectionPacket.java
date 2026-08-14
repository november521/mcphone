package com.november.mcphone.network;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 网络包：客户端 → 服务端，玩家在手机里点了「传送石」。
 *
 * 为什么非要走服务端：与末影箱同理，容器菜单必须由服务端建立才有权威性。
 * 更要紧的是传送本身——目标列表、经验扣除、冷却、维度校验全都只有服务端
 * 说了算，客户端凭空造一个菜单，选了也传送不了。
 *
 * 包体没有任何字段：要开的是"发包这名玩家自己的传送点列表"，服务端从连接
 * 上下文就能拿到玩家。带上玩家 ID 反而是给伪造客户端开后门——那等于允许
 * 任何人点开别人的传送点。
 */
public record OpenWaystoneSelectionPacket() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OpenWaystoneSelectionPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "open_waystone_selection"));

    /** 无字段包用 unit 编解码器：不写任何字节，收端直接产出单例 */
    public static final StreamCodec<ByteBuf, OpenWaystoneSelectionPacket> STREAM_CODEC =
            StreamCodec.unit(new OpenWaystoneSelectionPacket());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
