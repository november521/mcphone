package com.november.mcphone.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.util.ExtraCodecs;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.neoforged.neoforge.registries.DeferredRegister;
import com.november.mcphone.MCphone;

import java.util.function.Supplier;

/**
 * 玩家附着数据 —— 存储当前选择的壁纸文件名。
 *
 * 空字符串 "" 表示不使用壁纸（纯色背景）。
 */
public record WallpaperData(String wallpaperFileName) {

    public static final WallpaperData DEFAULT = new WallpaperData("");

    // ---- Codec: 序列化到 NBT ----
    public static final Codec<WallpaperData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("wallpaper").forGetter(WallpaperData::wallpaperFileName)
            ).apply(instance, WallpaperData::new)
    );

    // ---- Attachment 注册入口 ----
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MCphone.MODID);

    public static final Supplier<AttachmentType<WallpaperData>> TYPE = ATTACHMENT_TYPES.register(
            "wallpaper_data",
            () -> AttachmentType.builder(() -> WallpaperData.DEFAULT)
                    .serialize(CODEC)
                    .build()
    );
}
