package com.november.mcphone.feature.music.client.playback;

import fr.delthas.javamp3.Sound;
import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MP3 解码器 —— 靠 JavaMP3（fr.delthas:javamp3，MIT）。
 *
 * ================================================================
 * 为什么是这个库
 * ================================================================
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
 * ================================================================
 * 输出格式不必转换
 * ================================================================
 *
 * 核过它 getAudioFormat 的字节码：AudioFormat(采样率, 16, 声道, signed=true,
 * bigEndian=false)——16 位有符号小端，正是 OpenAL 要的那一种。所以这里
 * 不像 WavDecoder 那样再过一道转换。
 *
 * 它变了的话声音会立刻变成噪音而不报错，所以下面仍然查一道，不对就拒绝。
 *
 * ================================================================
 * ID3 标签得自己跳过
 * ================================================================
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
        InputStream raw = new BufferedInputStream(Files.newInputStream(file));
        try {
            skipId3v2(raw);

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

            return new PcmAudioStream(sound, format, file.getFileName().toString());
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e instanceof IOException io ? io : new IOException(e);
        }
    }

    /**
     * 跳过开头的 ID3v2 标签；没有标签则原样退回去。
     *
     * 长度是「同步安全整数」：4 个字节各只用低 7 位，最高位恒为 0——这样
     * 标签长度本身永远不会撞上帧同步字（0xFF 开头）。所以拼的时候要按 7 位
     * 移位，不是 8 位；按 8 位算出来的长度会大得离谱，一跳就跳过了半首歌。
     */
    private static void skipId3v2(InputStream in) throws IOException {
        in.mark(ID3V2_HEADER);

        byte[] head = in.readNBytes(ID3V2_HEADER);
        if (head.length < ID3V2_HEADER
                || head[0] != 'I' || head[1] != 'D' || head[2] != '3') {
            in.reset();     // 没有标签，交还给解码器
            return;
        }

        int size = ((head[6] & 0x7F) << 21)
                 | ((head[7] & 0x7F) << 14)
                 | ((head[8] & 0x7F) << 7)
                 |  (head[9] & 0x7F);
        in.skipNBytes(size);
    }

    @Override
    public String formatName() {
        return "MP3";
    }
}
