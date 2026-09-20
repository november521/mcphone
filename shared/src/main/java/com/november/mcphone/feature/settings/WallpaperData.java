package com.november.mcphone.feature.settings;

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
    public static final int MAX_FILE_NAME = 255;

    // ---- Codec: 序列化到 NBT ----
    public static final Codec<WallpaperData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Codec.STRING.fieldOf("wallpaper").forGetter(WallpaperData::wallpaperFileName)
            ).apply(instance, WallpaperData::new)
    );

    /** 服务端边界使用：壁纸只能是本地壁纸目录中的单个 PNG 文件名。 */
    public static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        if (raw.length() > MAX_FILE_NAME || raw.equals(".") || raw.equals("..")) return "";
        if (!raw.toLowerCase(java.util.Locale.ROOT).endsWith(".png")) return "";
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '/' || c == '\\' || Character.isISOControl(c)
                    || Character.getType(c) == Character.FORMAT
                    || Character.getType(c) == Character.LINE_SEPARATOR
                    || Character.getType(c) == Character.PARAGRAPH_SEPARATOR) return "";
        }
        return raw;
    }

}
