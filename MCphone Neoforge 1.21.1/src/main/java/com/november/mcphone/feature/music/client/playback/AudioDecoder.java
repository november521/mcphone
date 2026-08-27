package com.november.mcphone.feature.music.client.playback;

import net.minecraft.client.sounds.AudioStream;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 一种音频格式的解码器 —— 把一个文件变成一条 PCM 流。加一档格式 ＝ 写一个实现类 + 在 {@link AudioDecoders} 的名单里加一行。
 * 输出必须是 OpenAlUtil.audioFormatToOpenAl 收得下的 PCM：{@code PCM_SIGNED}、8 或 16 位、单声道或立体声、【小端】。
 * 它不检查字节序，大端流必须由解码器自己转好，否则声音变噪音而且不报错。
 */
public interface AudioDecoder {

    /** 认不认这个文件。只按扩展名判断：每次刷新曲库都要对每个文件调一遍，读内容会变成一次全盘 IO */
    boolean supports(Path file);

    /** 打开成一条 PCM 流，【调用方负责关闭】 */
    AudioStream open(Path file) throws IOException;

    String formatName();

    static boolean hasExtension(Path file, String... extensions) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        for (String ext : extensions) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }
}
