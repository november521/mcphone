package com.november.mcphone.feature.settings.net;

import com.november.mcphone.core.PhoneLocation;
import com.november.mcphone.util.TextSanitizer;
import net.minecraft.network.FriendlyByteBuf;

/**
 * 网络包：客户端 → 服务端，玩家给一部手机起了个名字。
 *
 * 带上手机在身上的位置：玩家可能同时有好几部——两手各一部、背包里还
 * 躺着几部、腰上再挂一部。服务端若自己去找就会改错那一部。
 *
 * 位置由界面给出（它从一开始就知道开的是哪一部），服务端解析后还要再验
 * 一次那儿拿到的确实是手机，所以伪造的位置改不出任何东西来。
 *
 * 空名字表示清除设备名，恢复默认物品名。
 */
public record SetDeviceNamePacket(String name, PhoneLocation location) {

    /**
     * 设备名长度上限。
     *
     * 在编解码器层面就封死，而不是只在服务端逻辑里检查：
     * 恶意客户端可以绕过界面直接发包，
     * 让读取阶段就拒收超长字符串才不会白白吃下几十 KB。
     * 手机屏幕只有 120 像素宽，24 字已经远超能显示的量。
     */
    public static final int MAX_NAME_LENGTH = 24;

    public static void encode(SetDeviceNamePacket msg, FriendlyByteBuf buf) {
        // 这里的上限是【字符数】而非字节数。1.21.1 那边由 Utf8String 按
        // utf8MaxBytes 内部换算；FriendlyByteBuf.writeUtf(s, n) 的第二个参数
        // 同样是字符数上限，语义一致，不必乘 3
        buf.writeUtf(msg.name(), MAX_NAME_LENGTH);
        PhoneLocation.encode(buf, msg.location());
    }

    public static SetDeviceNamePacket decode(FriendlyByteBuf buf) {
        return new SetDeviceNamePacket(
                buf.readUtf(MAX_NAME_LENGTH),
                PhoneLocation.decode(buf));
    }

    /**
     * 清洗设备名。收发两端共用：
     * 发送端必须先清洗，否则超长字符串在编码阶段就会抛 EncoderException；
     * 接收端也必须再洗一遍，因为客户端可以是伪造的，不能信。
     *
     * 规则本身与聊天消息完全一致（只是长度上限不同），故收在
     * {@link TextSanitizer} 里共用——各写一份的话，日后补一条规则
     * 必然漏掉另一处。
     */
    public static String sanitize(String raw) {
        return TextSanitizer.sanitize(raw, MAX_NAME_LENGTH);
    }
}
