package com.november.mcphone.feature.chat;

import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;

/**
 * 一条消息的正文。种类由 {@link MessageKind} 登记，存档与网络包都按 kind 分派。
 *
 * 为什么正文要单独抽出来
 *
 * 1.8.19 之前一条消息就是一个字符串，加图片时若照着往 {@link ChatMessage} 上再挂几个
 * 字段（图片 id、宽、高），文本消息也会被迫带着三个永远为空的字段走，而下一种消息
 * （分享物品、坐标……）又要再加几个。抽成正文之后，加一种消息＝加一个实现类，
 * 已有的两种一个字节都不必动。
 *
 * 本接口刻意【不写 default 方法】
 *
 * 类初始化的时序：初始化一个类会连带初始化它那些【声明了 default 方法】的父接口。
 * 只要这里出现一个 default 方法，TextBody 的初始化就会拽着 MessageBody 一起初始化，
 * 而 MessageBody 的 CODEC 又要去问 MessageKind、MessageKind 的常量又要回头拿
 * TextBody.MAP_CODEC —— 绕成一个环，谁先被碰到谁就拿到一个还没赋值的 null。
 * 全部写成抽象方法则没有这个环。
 */
public sealed interface MessageBody permits TextBody, ImageBody {

    /**
     * 存档格式：{@code {"kind": "image", ...各自的字段}}。
     * 用 dispatch 而不是自己写 if：加一种消息时这里不必改。
     */
    Codec<MessageBody> CODEC =
            MessageKind.CODEC.dispatch("kind", MessageBody::kind, MessageKind::mapCodec);

    /** 网络格式：先一个种类编号，再是各自的字段 */
    @SuppressWarnings("unchecked")
    StreamCodec<ByteBuf, MessageBody> STREAM_CODEC =
            MessageKind.STREAM_CODEC.dispatch(MessageBody::kind,
                    kind -> (StreamCodec<ByteBuf, MessageBody>) kind.streamCodec());

    MessageKind kind();

    /**
     * 会话列表那一行、以及收到消息时弹的通知里显示的一行字。
     *
     * 返回 Component 而不是 String：图片消息显示成「[图片]」，那是一个翻译键，
     * 服务端不该替客户端决定用哪种语言。
     */
    Component preview();
}
