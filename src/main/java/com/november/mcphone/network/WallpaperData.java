package com.november.mcphone.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * 玩家附着数据 —— 存储当前选择的壁纸文件名。
 *
 * 空字符串 "" 表示不使用壁纸（纯色背景）。
 *
 * 注册在 {@link com.november.mcphone.core.ModAttachments#WALLPAPER}：
 * 本类只负责"是什么、怎么序列化"，注册表归属统一放在注册类里。
 */
public record WallpaperData(String wallpaperFileName) {

    public static final WallpaperData DEFAULT = new WallpaperData("");

    // ---- Codec: 序列化到 NBT ----
    public static final Codec<WallpaperData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("wallpaper").forGetter(WallpaperData::wallpaperFileName)
            ).apply(instance, WallpaperData::new)
    );

}
