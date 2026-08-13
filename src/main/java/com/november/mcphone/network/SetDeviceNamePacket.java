package com.november.mcphone.network;

import com.november.mcphone.MCphone;
import com.november.mcphone.util.TextSanitizer;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 网络包：客户端 → 服务端，玩家给手上的手机起了个名字。
 *
 * 带上是哪只手：玩家两只手可能各拿一只手机，
 * 服务端若自己去猜就可能改错那一只。
 * 手机界面由 PhoneItem.use 打开，那里本来就知道是哪只手。
 *
 * 空名字表示清除设备名，恢复默认物品名。
 */
public record SetDeviceNamePacket(String name, boolean mainHand) implements CustomPacketPayload {

    /**
     * 设备名长度上限。
     *
     * 在编解码器层面就封死，而不是只在服务端逻辑里检查：
     * 恶意客户端可以绕过界面直接发包，
     * 让读取阶段就拒收超长字符串才不会白白吃下几十 KB。
     * 手机屏幕只有 120 像素宽，24 字已经远超能显示的量。
     */
    public static final int MAX_NAME_LENGTH = 24;

    public static final CustomPacketPayload.Type<SetDeviceNamePacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "set_device_name"));

    public static final StreamCodec<ByteBuf, SetDeviceNamePacket> STREAM_CODEC =
            StreamCodec.composite(
                    // 这里的上限是【字符数】而非字节数，UTF-8 的字节余量
                    // 由 Utf8String 内部按 utf8MaxBytes 自行换算，不必乘 3
                    ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH),
                    SetDeviceNamePacket::name,
                    ByteBufCodecs.BOOL,
                    SetDeviceNamePacket::mainHand,
                    SetDeviceNamePacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
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
