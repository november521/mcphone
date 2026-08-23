package com.november.mcphone.feature.music.client.playback;

import net.minecraft.client.sounds.AudioStream;
import org.lwjgl.BufferUtils;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 把 JDK 的 {@link AudioInputStream} 接到 Minecraft 的 {@link AudioStream} 上。
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

    private final AudioInputStream in;
    private final AudioFormat format;

    public PcmAudioStream(AudioInputStream in) {
        this.in = in;
        this.format = in.getFormat();
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
