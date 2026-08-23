package com.november.mcphone.feature.music.client.playback;

import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

/**
 * 把任意一条 PCM 字节流接到 Minecraft 的 {@link AudioStream} 上。
 *
 * ================================================================
 * 为什么必须是 direct ByteBuffer
 * ================================================================
 *
 * 这条流最终会被交给 alBufferData。LWJGL 的原生调用只接受**堆外**内存，
 * 传一个普通的 {@code ByteBuffer.allocate} 进去会直接抛异常。所以这里用
 * BufferUtils.createByteBuffer——与 Minecraft 自己的音频流同一个做法。
 *
 * ================================================================
 * 空 buffer 就是"放完了"
 * ================================================================
 *
 * Channel 每次泵流都调一次 read；返回一个长度为 0 的 buffer，它就不再
 * 排队新数据，等已排的放完就自然停下。所以读到文件尾时【不能】抛异常，
 * 也不能返回 null，老老实实返回空 buffer。
 */
public final class PcmAudioStream implements AudioStream {

    private final InputStream in;
    private final AudioFormat format;
    private final int frameSize;

    /**
     * 收裸流而不是 {@link AudioInputStream}：MP3 解码器给出的就是一条普通
     * 的 InputStream 加一个格式，为它凭空包一层 AudioInputStream 只是绕路。
     *
     * @param format 这条流的真实格式。必须是 OpenAL 收得下的 PCM，
     *               见 {@link AudioDecoder} 的规矩
     */
    public PcmAudioStream(InputStream in, AudioFormat format) {
        this.in = in;
        this.format = format;
        // 帧大小可能是 NOT_SPECIFIED(-1)，那时按位深与声道自己算
        int fs = format.getFrameSize();
        this.frameSize = fs > 0 ? fs : Math.max(1,
                format.getChannels() * format.getSampleSizeInBits() / 8);
    }

    @Override
    public AudioFormat getFormat() {
        return format;
    }

    @Override
    public ByteBuffer read(int size) throws IOException {
        byte[] chunk = new byte[size];

        // 一次 read 未必读满，循环补齐。不补的话每次只喂一点点，
        // 排队的 buffer 撑不满一格，声音会断续
        int filled = 0;
        while (filled < size) {
            int n = in.read(chunk, filled, size - filled);
            if (n <= 0) break;      // 文件到尾了
            filled += n;
        }

        // 只交出整帧。裸流在文件末尾可能剩下半帧（ID3v1 那 128 字节的尾巴
        // 就会造成这种情况），半帧喂进去会让声道错位——从那一刻起整首歌
        // 变成噪音，而且不报错。最多丢掉结尾几个字节，听不出来
        filled -= filled % frameSize;

        ByteBuffer out = BufferUtils.createByteBuffer(filled);
        out.put(chunk, 0, filled);
        out.flip();
        return out;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
