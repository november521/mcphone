package com.november.mcphone.feature.music.client.playback;

import net.minecraft.client.sounds.AudioStream;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 一种音频格式的解码器 —— 把一个文件变成一条 PCM 流。
 *
 * ================================================================
 * 这个接口存在的全部理由：加格式不该动别的代码
 * ================================================================
 *
 * 播放核心只认 {@link AudioStream}，不知道 OGG 与 WAV 有什么区别。加一种
 * 格式（MP3、FLAC、M4A）＝写一个实现类 + 在 {@link AudioDecoders} 的名单里
 * 加一行，播放、界面、音源三层一个字都不用改。
 *
 * ================================================================
 * 实现必须守的两条
 * ================================================================
 *
 * 1. 输出必须是 OpenAL 收得下的 PCM：{@code PCM_SIGNED}，8 或 16 位，
 *    单声道或立体声，**小端**。核过 OpenAlUtil.audioFormatToOpenAl，
 *    编码或位深不对它直接抛 IllegalArgumentException。
 *    它不检查字节序，所以大端流必须由解码器自己转好，否则声音会变成噪音
 *    ——而且不报错。
 *
 * 2. {@code supports} 只看文件名，不读内容。它每次刷新曲库都要对每个文件
 *    调一遍，读内容会把一次目录扫描变成一次全盘 IO。真正认不认得，
 *    交给 {@link #open} 抛异常。
 */
public interface AudioDecoder {

    /** 认不认这个文件。只按扩展名判断，理由见接口注释 */
    boolean supports(Path file);

    /**
     * 打开成一条 PCM 流。
     *
     * @return 一条流，**调用方负责关闭**
     * @throws IOException 文件坏了、格式其实不对、或者这一档编码不支持
     */
    AudioStream open(Path file) throws IOException;

    /** 这一档格式的名字，写日志和界面提示用，如 "OGG" */
    String formatName();

    /** 扩展名判断的共用实现，省得每个解码器各写一遍大小写处理 */
    static boolean hasExtension(Path file, String... extensions) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        for (String ext : extensions) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }
}
