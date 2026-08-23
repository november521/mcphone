package com.november.mcphone.feature.music.client.playback;

import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * WAV / AIFF / AU 解码器 —— 全靠 JDK 自带的 javax.sound，不需要任何依赖。
 *
 * ================================================================
 * 为什么要显式转一次格式
 * ================================================================
 *
 * WAV 里装的未必是 OpenAL 收得下的东西：24 位、32 位浮点、A-law、大端的
 * AIFF 都很常见。直接把原始流交给 OpenAL，轻则抛异常，重则**当成噪音放出来**
 * ——后者不报错，只是难听得吓人。
 *
 * 所以这里一律转成 16 位小端 PCM。javax.sound 的转换是惰性的：包一层
 * AudioInputStream，边读边转，不会把整首歌先解到内存里。
 *
 * ================================================================
 * 转不了的情况
 * ================================================================
 *
 * JDK 只带 PCM 系的转换器。压缩编码（MP3 在 WAV 壳里这种邪门写法）没有
 * 对应的 SPI 就转不了，getAudioInputStream 会抛 IllegalArgumentException。
 * 这里把它统一包成 IOException：对调用方来说"这个文件放不了"是一件事，
 * 不该按抛什么异常分两种处理。
 */
public final class WavDecoder implements AudioDecoder {

    /** OpenAL 只吃这一种。位深写死 16：8 位听着就是砂纸，而 24/32 位它根本不收 */
    private static final int TARGET_BITS = 16;

    @Override
    public boolean supports(Path file) {
        return AudioDecoder.hasExtension(file, ".wav", ".wave", ".aiff", ".aif", ".au");
    }

    @Override
    public AudioStream open(Path file) throws IOException {
        // 包一层 Buffered：AudioSystem 要回退读取文件头来认格式，
        // 没有缓冲的流不支持 mark/reset，它会直接失败
        BufferedInputStream raw = new BufferedInputStream(Files.newInputStream(file));

        AudioInputStream src;
        try {
            src = AudioSystem.getAudioInputStream(raw);
        } catch (UnsupportedAudioFileException e) {
            raw.close();
            throw new IOException("不是能识别的 WAV/AIFF/AU：" + file.getFileName(), e);
        }

        AudioFormat in = src.getFormat();
        AudioFormat target = new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                in.getSampleRate(),
                TARGET_BITS,
                in.getChannels(),
                in.getChannels() * (TARGET_BITS / 8),
                in.getSampleRate(),
                false);                       // 小端。OpenAL 不检查字节序，写错了只会变噪音

        // 已经是目标格式就不必再包一层
        if (in.matches(target)) {
            return new PcmAudioStream(src, in);
        }

        try {
            return new PcmAudioStream(AudioSystem.getAudioInputStream(target, src), target);
        } catch (IllegalArgumentException e) {
            src.close();
            throw new IOException("这个编码 JDK 转不了：" + in, e);
        }
    }

    @Override
    public String formatName() {
        return "WAV";
    }
}
