package com.november.mcphone.feature.chat;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.StringRepresentable;

import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 一条消息是什么消息。每一种对应一个 {@link MessageBody} 实现，编解码器挂在这里，
 * 存档与网络包都靠这张表分派（见 {@link MessageBody#CODEC}）。
 *
 * 再加一种要动哪儿
 *
 * 写一个新的 MessageBody 实现（记得加进 permits），在这里登记一行，就完事了：
 * 存档、网络包、会话列表的预览、通知横幅全都自动认得它。要动的只有渲染——
 * 屏幕上画成什么样，只有界面知道。
 *
 * id 是存档里的字符串，改了老存档就读不出那些消息，等同于删记录。
 * 序号（ordinal）是网络包里的编号，只在同一次连接的两端之间有意义，
 * 客户端与服务端版本对不上时通道的 PROTOCOL_VERSION 会拦在握手阶段
 * （见 {@code MCphoneNetwork}），所以这里不必另编版本号。
 * 但顺序仍然别乱调：新的一律往后加。
 */
public enum MessageKind implements StringRepresentable {

    /** 玩家打的字。1.8.19 及更早只有这一种，存档里也就没有 kind 字段，见 {@link ChatMessage#CODEC} */
    TEXT("text", TextBody.MAP_CODEC,
            (body, buf) -> TextBody.encode((TextBody) body, buf), TextBody::decode),

    /** 从相册发出去的一张图。像素不在消息里，消息只带图片 id，见 {@link ImageBody} */
    IMAGE("image", ImageBody.MAP_CODEC,
            (body, buf) -> ImageBody.encode((ImageBody) body, buf), ImageBody::decode);

    public static final Codec<MessageKind> CODEC = StringRepresentable.fromEnum(MessageKind::values);

    private final String id;
    private final MapCodec<? extends MessageBody> mapCodec;
    private final BiConsumer<MessageBody, FriendlyByteBuf> encoder;
    private final Function<FriendlyByteBuf, MessageBody> decoder;

    MessageKind(String id,
                MapCodec<? extends MessageBody> mapCodec,
                BiConsumer<MessageBody, FriendlyByteBuf> encoder,
                Function<FriendlyByteBuf, MessageBody> decoder) {
        this.id = id;
        this.mapCodec = mapCodec;
        this.encoder = encoder;
        this.decoder = decoder;
    }

    public MapCodec<? extends MessageBody> mapCodec() {
        return mapCodec;
    }

    /**
     * 网络编解码。1.21.1 那边这里挂的是一对 {@code StreamCodec}，1.20.1 没有那个类型，
     * 换成一对函数，职责与调用点（{@link MessageBody#encode}/{@link MessageBody#decode}）
     * 都不变。仍然挂在这张表上而不是散在 switch 里，图的是同一件事：
     * 加一种消息不给编解码器就编译不过。
     */
    public BiConsumer<MessageBody, FriendlyByteBuf> encoder() {
        return encoder;
    }

    public Function<FriendlyByteBuf, MessageBody> decoder() {
        return decoder;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    /**
     * 读不懂的编号直接抛。
     *
     * 抛出去的结果是断这一条连接：能发出越界编号的只有伪造客户端，
     * 而 netty 的解码异常本就是这么处理的。返回一个"默认种类"反而更糟——
     * 后面的字段会按错的格式接着读，读出来的东西无从判断真假。
     */
    public static MessageKind byId(int ordinal) {
        MessageKind[] values = values();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalArgumentException("未知的消息种类编号: " + ordinal);
        }
        return values[ordinal];
    }
}
