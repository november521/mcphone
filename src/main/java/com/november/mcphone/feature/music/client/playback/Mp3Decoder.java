package com.november.mcphone.feature.music.client.playback;

import com.november.mcphone.MCphone;
import fr.delthas.javamp3.Sound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.network.chat.Component;

import javax.sound.sampled.AudioFormat;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MP3 解码器 —— 靠 JavaMP3（fr.delthas:javamp3，MIT）。
 *
 * 为什么是这个库
 *
 * JDK 不带 MP3 解码器，MC 也没有（它自己全套用 ogg）。GitHub 上纯 Java 的
 * MP3 解码器里，非 LGPL 的实际上只有这一个——JLayer 与 mp3spi 都是 LGPL，
 * 而把 LGPL 的库嵌进一个要分发的 jar 里是个说不清的灰区。
 *
 * 它 2023 年初已归档。对一个 1993 年就冻结的格式来说，这比"API 客户端停止
 * 维护"的风险小得多：MP3 不会再变，解码器也就没什么要跟进的。真出问题，
 * MIT 许可让我们可以自己接手那 5 个类。
 *
 * 打包方式见 build.gradle 里的 jarJar：模组 jar 不会自动带第三方库。
 *
 * 它只认 MPEG-1，所以我们先自己查一遍
 *
 * 这一条 1.5.5 才补上，之前不知道。作者在仓库 issue #8 里写着
 * "The project currently supports MPEG 1 files only"，至今开着 —— 而代码里
 * 的表现是：帧头里的版本位读出来【赋给一个再没被用过的变量】，然后一律按
 * MPEG-1 的表和 144×码率/采样率 的帧长公式算。
 *
 * 喂它一个 MPEG-2 的文件（22050 / 24000 / 16000 Hz，低码率 MP3 都是这一档）
 * 会在构造时抛 ArrayIndexOutOfBoundsException —— 实测 22050Hz 单声道的文件
 * 换 300 个起始偏移都是这个结果，MPEG-2.5 同样。异常我们接得住，但玩家看到
 * 的是"歌在列表里，点了没反应"，日志里只有一行看不懂的数组越界。
 *
 * 所以现在交给它之前先用 {@link Mp3Header} 自己读一遍帧头：不是 MPEG-1 就
 * 当场说清楚是什么、该怎么办；是 MPEG-1 就把规格写进日志。
 *
 * 顺带还核一道它报的格式与我们读到的一致不一致 —— issue #6 报的
 * "16000Hz 被说成 32000Hz"就是查错表的直接后果，而采样率报错的表现是
 * 整首歌以错误的速度播放，不会有任何报错。
 *
 * 输出格式不必转换
 *
 * 核过它 getAudioFormat 的字节码：AudioFormat(采样率, 16, 声道, signed=true,
 * bigEndian=false)——16 位有符号小端，正是 OpenAL 要的那一种。所以这里
 * 不像 WavDecoder 那样再过一道转换。
 *
 * 它变了的话声音会立刻变成噪音而不报错，所以下面仍然查一道，不对就拒绝。
 *
 * ID3 标签得自己跳过
 *
 * 绝大多数 MP3 开头有一段 ID3v2 标签（歌名、专辑封面，封面动辄几百 KB）。
 * 这个库的类里搜不到任何 ID3 字样，也就是说它不认识这段东西，只能指望
 * 帧同步去蒙——蒙对了没事，蒙不对就是开头一阵噪音，或者干脆解不出来。
 *
 * 与其赌，不如自己跳：ID3v2 的长度就写在头 10 个字节里，读出来跳过即可。
 *
 * 文件末尾那 128 字节的 ID3v1（以 "TAG" 开头）不处理：解码器在那儿找不到
 * 合法帧，自然就停了，最多在最后漏出半帧——而半帧已经被 PcmAudioStream
 * 的整帧对齐挡掉了。
 */
public final class Mp3Decoder implements AudioDecoder {

    /** ID3v2 头的固定长度：3 字节标识 + 2 版本 + 1 标志 + 4 长度 */
    private static final int ID3V2_HEADER = 10;

    @Override
    public boolean supports(Path file) {
        // mp1/mp2 也认：这个库本来就支持 MPEG Layer I/II/III，
        // 而玩家手里偶尔真有 mp2（老录音、某些视频抽出来的音轨）
        return AudioDecoder.hasExtension(file, ".mp3", ".mp2", ".mp1");
    }

    @Override
    public AudioStream open(Path file) throws IOException {
        String name = file.getFileName().toString();
        InputStream raw = new BufferedInputStream(Files.newInputStream(file));
        try {
            long tagBytes = skipId3v2(raw);

            // 先自己认一遍。放不了的当场说清楚，别让解码库去抛数组越界
            Mp3Header header = Mp3Header.peek(raw);
            // 两句话各说各的：reason 给界面（一行放得下），message 给日志
            if (header == null) {
                throw new UnplayableException(
                        Component.translatable("mcphone.music.problem.not_mp3"),
                        "找不到 MPEG 帧头，这个文件多半不是 MP3：" + name);
            }
            if (!header.isSupported()) {
                throw new UnplayableException(
                        Component.translatable("mcphone.music.problem.not_mpeg1",
                                header.version().toString()),
                        "放不了 " + name + " —— " + header.describe()
                                + "。这个播放器只认 MPEG-1（44100 / 48000 / 32000 Hz），"
                                + "请转成 44.1kHz 再放进来");
            }
            MCphone.LOGGER.info("[MCphone] 打开 MP3 {} —— {}", name, header.describe());

            Sound sound = new Sound(raw);
            AudioFormat format = sound.getAudioFormat();

            // 库要是哪天改了输出格式，声音会直接变噪音且不报错。宁可这一首
            // 放不出来、日志里留一句，也不要让玩家以为自己的文件坏了
            if (format.getSampleSizeInBits() != 16
                    || !AudioFormat.Encoding.PCM_SIGNED.equals(format.getEncoding())
                    || format.isBigEndian()) {
                sound.close();
                throw new IOException("MP3 解码器给出的格式不是 16 位有符号小端 PCM：" + format);
            }

            // 它说的与我们自己读到的对不上，就不能信它。采样率错了不会报错，
            // 只会让整首歌以错误的速度播放；声道数错了则是左右声道错位成噪音
            if ((int) format.getSampleRate() != header.sampleRate()
                    || format.getChannels() != header.channels()) {
                sound.close();
                throw new IOException("MP3 解码器报的格式与帧头对不上：它说 "
                        + (int) format.getSampleRate() + "Hz/" + format.getChannels()
                        + "声道，帧头写的是 " + header.sampleRate() + "Hz/"
                        + header.channels() + "声道：" + name);
            }

            // 时长现在算：帧头已经解出码率，Xing 头里还可能写着总帧数。
            // 只有正在放的这一首需要它，扫目录时一个文件都不必开 ——
            // 理由见 KnownDuration
            long durationMs = header.durationMs(Math.max(0L, Files.size(file) - tagBytes));

            return new PcmAudioStream(sound, format, name, durationMs);
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e instanceof IOException io ? io : new IOException(e);
        }
    }

    /**
     * 跳过开头的 ID3v2 标签；没有标签则原样退回去。
     *
     * 返回跳掉了多少字节：固定码率那一支要拿"文件大小减去标签"当音频字节数，
     * 而专辑封面动辄几百 KB，不减掉的话时长会算多一大截。
     *
     * 长度是「同步安全整数」：4 个字节各只用低 7 位，最高位恒为 0——这样
     * 标签长度本身永远不会撞上帧同步字（0xFF 开头）。所以拼的时候要按 7 位
     * 移位，不是 8 位；按 8 位算出来的长度会大得离谱，一跳就跳过了半首歌。
     */
    private static long skipId3v2(InputStream in) throws IOException {
        in.mark(ID3V2_HEADER);

        byte[] head = in.readNBytes(ID3V2_HEADER);
        if (head.length < ID3V2_HEADER
                || head[0] != 'I' || head[1] != 'D' || head[2] != '3') {
            in.reset();     // 没有标签，交还给解码器
            return 0L;
        }

        int size = ((head[6] & 0x7F) << 21)
                 | ((head[7] & 0x7F) << 14)
                 | ((head[8] & 0x7F) << 7)
                 |  (head[9] & 0x7F);
        in.skipNBytes(size);
        return ID3V2_HEADER + (long) size;
    }

    @Override
    public String formatName() {
        return "MP3";
    }
}
