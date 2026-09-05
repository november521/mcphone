package com.november.mcphone.feature.chat;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

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
 * 而夹住之后最坏情况只是气泡比例不对，真实比例在像素到达时自会纠正。
 */
public record ImageBody(UUID image, int width, int height) implements MessageBody {

    public ImageBody {
        width = Math.clamp(width, 1, ChatImage.MAX_SIDE);
        height = Math.clamp(height, 1, ChatImage.MAX_SIDE);
    }

    public static final MapCodec<ImageBody> MAP_CODEC = RecordCodecBuilder.mapCodec(instance ->
            instance.group(
                    UUIDUtil.CODEC.fieldOf("image").forGetter(ImageBody::image),
                    com.mojang.serialization.Codec.INT.fieldOf("width").forGetter(ImageBody::width),
                    com.mojang.serialization.Codec.INT.fieldOf("height").forGetter(ImageBody::height)
            ).apply(instance, ImageBody::new)
    );

    public static final StreamCodec<ByteBuf, ImageBody> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, ImageBody::image,
                    ByteBufCodecs.VAR_INT, ImageBody::width,
                    ByteBufCodecs.VAR_INT, ImageBody::height,
                    ImageBody::new
            );

    @Override
    public MessageKind kind() {
        return MessageKind.IMAGE;
    }

    @Override
    public Component preview() {
        return Component.translatable("mcphone.chat.image_preview");
    }
}
