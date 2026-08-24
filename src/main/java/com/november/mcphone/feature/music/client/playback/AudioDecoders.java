package com.november.mcphone.feature.music.client.playback;

import net.minecraft.client.sounds.AudioStream;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * 解码器名单 —— 拿到一个文件，找出谁认得它。
 *
 * 加一种格式要改的全部东西
 *
 * 写一个 {@link AudioDecoder} 实现，在下面 DECODERS 里加一行。完。
 * 播放核心、界面、音源三层都不认识具体格式，也就都不用改。
 *
 * MP3、FLAC、M4A 都按这条路走。它们与 OGG/WAV 的区别只在于要额外引一个
 * 解码库进来——那是依赖与许可证的问题，不是结构问题，结构这里已经留好了。
 *
 * 为什么是一份写死的名单，不走 SPI
 *
 * 音源（歌从哪儿来）走 SPI，因为那是给附属模组开的口子。解码器不是：
 * 它要求输出严格的 PCM 格式，写错了不报错、只是变噪音，这种东西不该
 * 让外部随便插手。何况格式就那么几种，一份看得见的名单比一份看不见的
 * 注册表好排查。
 */
public final class AudioDecoders {

    private AudioDecoders() {}

    /**
     * 按顺序问。顺序有意义：先问的先认领，所以更专用的放前面。
     *
     * WavDecoder 排在最后是有讲究的：它背后是 JDK 的 javax.sound，而那套
     * 东西认得的格式取决于运行时有哪些 SPI——别的模组要是带了一个 mp3 SPI
     * 进来，它就会声称自己也认 .mp3，把这一档从我们自己的解码器手里抢走。
     * 排在后面就轮不到它抢。
     */
    private static final List<AudioDecoder> DECODERS = List.of(
            new OggDecoder(),
            new Mp3Decoder(),
            new WavDecoder()
    );

    /**
     * 界面上提示"支持哪些格式"用，如 "OGG、MP3、WAV"。
     *
     * 算一次存着：这个值由上面那份写死的名单决定，永远不会变，而调用它的
     * 是空曲库那一页的提示语 —— 那一句每帧都要拼一次。1.5.19 之前每次调用
     * 都要走一遍 stream + toList + join。
     */
    private static final String SUPPORTED_NAMES =
            String.join("、", DECODERS.stream().map(AudioDecoder::formatName).toList());

    public static String supportedNames() {
        return SUPPORTED_NAMES;
    }

    /** 这个文件有没有人认得。扫目录时用来筛掉封面图、歌词之类的杂文件 */
    public static boolean isSupported(Path file) {
        return find(file) != null;
    }

    /**
     * 打开一个文件。
     *
     * 打不开就抛 {@link UnplayableException}，而不是返回 null —— 原因要能
     * 一路带到界面上去，玩家才知道自己那首歌为什么点了没反应。1.5.6 之前
     * 这里是"记一行日志然后返回 null"，日志他不会去翻。
     *
     * @return 一条 PCM 流，调用方负责关闭
     * @throws UnplayableException 没人认得、或者解不开
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
            // 连 RuntimeException 一起兜住：解码库遇到坏文件时抛什么全凭它们
            // 高兴，而一首歌放不出来不该让整个界面崩掉
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
