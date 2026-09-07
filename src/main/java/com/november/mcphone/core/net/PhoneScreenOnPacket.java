package com.november.mcphone.core.net;

import com.november.mcphone.core.PhoneLocation;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Optional;

/**
 * 网络包：客户端 → 服务端，"我这会儿开着的是哪一部手机"。空 ＝ 一部都没开。
 *
 * <h2>为什么非要服务端知道</h2>
 *
 * 手机界面是纯客户端的（{@code PhoneScreen} 不是容器菜单），所以服务端本来完全不知道谁开着
 * 手机。而物品模型要在<b>别人的</b>屏幕上也显示成亮着的，判断依据就必须放在同步得出去的
 * 地方——也就是手机那件物品自己身上。只有服务端写得了它。
 *
 * <h2>为什么带位置而不是只带一个布尔</h2>
 *
 * 一个玩家可以同时带好几部手机（两手、背包、饰品栏）。服务端自己去猜就会点亮错的那一部。
 * 位置由客户端给——它从一开始就知道开的是哪一部；服务端解析之后仍要再验一次那儿拿到的确实
 * 是手机，所以伪造的位置点不亮任何东西。
 *
 * 发包时机由 {@code PhoneScreenOnSync} 管，<b>只在变了的时候发</b>。开关手机是很低频的事。
 *
 * <h2>与那一支的差别：手写编解码</h2>
 *
 * 那边是 {@code StreamCodec.composite(ByteBufCodecs.optional(...))}，1.20.1 没有这套组合子，
 * 退回一个 present 布尔位加 {@link PhoneLocation} 自己的编解码——和这一支其余的包一个写法。
 *
 * 最坏情况会怎样：伪造客户端可以让自己那部手机一直亮着。那点亮的是它自己的贴图，不改任何
 * 游戏状态——不值得为它加一道校验。
 */
public record PhoneScreenOnPacket(Optional<PhoneLocation> lit) {

    public static void encode(PhoneScreenOnPacket msg, FriendlyByteBuf buf) {
        buf.writeBoolean(msg.lit().isPresent());
        msg.lit().ifPresent(location -> PhoneLocation.encode(location, buf));
    }

    public static PhoneScreenOnPacket decode(FriendlyByteBuf buf) {
        return new PhoneScreenOnPacket(
                buf.readBoolean() ? Optional.of(PhoneLocation.decode(buf)) : Optional.empty());
    }
}
