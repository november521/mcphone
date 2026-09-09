package com.november.mcphone.core.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.PhoneLocation;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * 网络包：客户端 → 服务端，"我这会儿开着的是哪一部手机"。空 ＝ 一部都没开。
 *
 * 为什么非要服务端知道
 *
 * 手机界面是纯客户端的（{@code PhoneScreen} 不是容器菜单），所以服务端本来完全不知道谁开着
 * 手机。而物品模型要在<b>别人的</b>屏幕上也显示成亮着的，判断依据就必须放在同步得出去的地方
 * ——也就是物品堆上的 {@code ModDataComponents.SCREEN_ON}。只有服务端能写它。
 *
 * 为什么带位置而不是只带一个布尔
 *
 * 一个玩家可以同时带好几部手机（两手、背包、饰品栏）。服务端自己去猜就会点亮错的那一部。
 * 位置由客户端给——它从一开始就知道开的是哪一部；服务端解析之后仍要再验一次那儿拿到的确实
 * 是手机，所以伪造的位置点不亮任何东西。
 *
 * 发包时机由 {@code PhoneScreenOnSync} 管：每 tick 算一次"现在亮的是哪一部"，<b>只在变了的
 * 时候发</b>。开关手机是很低频的事，这条线上一分钟也走不了几个包。
 *
 * 最坏情况会怎样：伪造客户端可以让自己那部手机一直亮着。那点亮的是它自己的贴图，不改任何
 * 游戏状态——不值得为它加一道校验。
 */
public record PhoneScreenOnPacket(Optional<PhoneLocation> lit) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PhoneScreenOnPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "phone_screen_on"));

    public static final StreamCodec<ByteBuf, PhoneScreenOnPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.optional(PhoneLocation.STREAM_CODEC),
                    PhoneScreenOnPacket::lit,
                    PhoneScreenOnPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
