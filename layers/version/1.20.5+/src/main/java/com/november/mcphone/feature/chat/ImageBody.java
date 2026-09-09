package com.november.mcphone.feature.chat;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.Mth;

import java.util.UUID;

/**
 * 一张图片消息。消息里只有图片的 id 与尺寸，像素在服务端的图片仓里（见 {@link ChatImageStore}）——
 * 一张图几十 KB，塞进消息就等于塞进存档，还会跟着每一次会话同步重发一遍。
 *
 * 为什么要带上宽高
 *
 * 像素是等玩家看到那条消息才去要的（见客户端的 ChatImageCache），拿到之前界面也得把
 * 气泡摆出来。没有宽高就只能先画一个方块占位，图到了再按真实比例重排——那一下跳动
 * 恰好发生在玩家正在看的地方。
 *
 * 越界的宽高一律夹到合法区间，而不是抛异常
 *
 * 抛的话，伪造客户端发一个宽 20 亿的包就能让【收件人】掉线——挨罚的是无辜的那一方。
 * 而夹住之后最坏情况只是图的比例不对，真实比例在像素到达时自会纠正。
 *
 * 动图
 *
 * frames > 1 表示这是一张动图：像素是所有帧拼成的一张雪碧图（见 {@link ChatImage} 的
 * "动图"一节），而这里的 width/height 说的是【一帧】多大。frameMs 是每帧停多久。
 *
 * 帧数与延迟同样要夹：一个伪造的包说自己有 20 亿帧，收件人取帧时就会算出一个越界的
 * 子矩形；说自己每帧停 0 毫秒，那一格就会被除零。
 */
public record ImageBody(UUID image, int width, int height, int frames, int frameMs)
        implements MessageBody {

    public ImageBody {
        width = Mth.clamp(width, 1, ChatImage.MAX_SIDE);
        height = Mth.clamp(height, 1, ChatImage.MAX_SIDE);
        frames = Mth.clamp(frames, 1, ChatImage.MAX_FRAMES);
        frameMs = frames > 1 ? Mth.clamp(frameMs, 20, 5000) : 0;
    }

    /** 一张普通静态图 */
    public ImageBody(UUID image, int width, int height) {
        this(image, width, height, 1, 0);
    }

    public boolean animated() {
        return frames > 1;
    }

    public static final MapCodec<ImageBody> MAP_CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    UUIDUtil.CODEC.fieldOf("image").forGetter(ImageBody::image),
                    com.mojang.serialization.Codec.INT.fieldOf("width").forGetter(ImageBody::width),
                    com.mojang.serialization.Codec.INT.fieldOf("height").forGetter(ImageBody::height),
                    // 可选：1.9.0 开发期间写下的图片消息没有这两个字段，读成静态图正好
                    com.mojang.serialization.Codec.INT.optionalFieldOf("frames", 1)
                            .forGetter(ImageBody::frames),
                    com.mojang.serialization.Codec.INT.optionalFieldOf("frame_ms", 0)
                            .forGetter(ImageBody::frameMs)
            ).apply(instance, ImageBody::new)
    );

    public static final StreamCodec<ByteBuf, ImageBody> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, ImageBody::image,
                    ByteBufCodecs.VAR_INT, ImageBody::width,
                    ByteBufCodecs.VAR_INT, ImageBody::height,
                    ByteBufCodecs.VAR_INT, ImageBody::frames,
                    ByteBufCodecs.VAR_INT, ImageBody::frameMs,
                    ImageBody::new
            );

    @Override
    public MessageKind kind() {
        return MessageKind.IMAGE;
    }

    @Override
    public Component preview() {
        // 会话列表与通知横幅上那一行。动图与静态图分开说：列表里一眼看得出对方发的是什么
        return Component.translatable(animated()
                ? "mcphone.chat.animation_preview" : "mcphone.chat.image_preview");
    }
}
