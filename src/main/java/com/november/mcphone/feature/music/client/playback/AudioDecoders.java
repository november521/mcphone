package com.november.mcphone.feature.music.client.playback;

import net.minecraft.client.sounds.AudioStream;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 解码器名单 —— 拿到一个文件，找出谁认得它。加一档格式 ＝ 写一个 {@link AudioDecoder} 实现，在 DECODERS 里加一行。
 */
public final class AudioDecoders {

    private AudioDecoders() {}

    /** 按顺序问，先问的先认领。WavDecoder 必须排最后：它背后的 javax.sound 会认领别的模组注册进来的 SPI 格式（比如 .mp3），排前面就把这一档抢走了 */
    private static final List<AudioDecoder> DECODERS = List.of(
            new OggDecoder(),
            new Mp3Decoder(),
            new WavDecoder()
    );

    /** 界面上提示"支持哪些格式"用，如 "OGG、MP3、WAV" */
    private static final String SUPPORTED_NAMES =
            String.join("、", DECODERS.stream().map(AudioDecoder::formatName).toList());

    public static String supportedNames() {
        return SUPPORTED_NAMES;
    }

    public static boolean isSupported(Path file) {
        return find(file) != null;
    }

    /**
     * 打开一个文件，得到一条 PCM 流，调用方负责关闭。
     * @throws UnplayableException 没人认得、或者解不开；原因要能一路带到界面上，所以不返回 null
     */
    public static AudioStream open(Path file) throws IOException {
        AudioDecoder decoder = find(file);
        if (decoder == null) {
            throw new UnplayableException(
                    Component.translatable("mcphone.music.problem.unsupported"),
                    "没有解码器认得这个文件：" + file.getFileName());
        }

        try {
            AudioStream stream = decoder.open(file);
            if (stream != null) return stream;

            throw new UnplayableException(
                    Component.translatable("mcphone.music.problem.broken"),
                    decoder.formatName() + " 解码器打不开它，也没说为什么：" + file.getFileName());
        } catch (UnplayableException e) {
            throw e;        // 解码器自己已经说清楚了，别再套一层笼统的
        } catch (IOException | RuntimeException e) {
            // 连 RuntimeException 一起兜住：解码库遇到坏文件时抛什么全凭它们高兴
            throw new UnplayableException(
                    Component.translatable("mcphone.music.problem.broken"),
                    decoder.formatName() + " 解码失败：" + file.getFileName() + " —— " + e, e);
        }
    }

    private static AudioDecoder find(Path file) {
        for (AudioDecoder d : DECODERS) {
            if (d.supports(file)) return d;
        }
        return null;
    }
}
