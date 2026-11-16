package com.november.mcphone.platform.client;

import com.mojang.blaze3d.audio.OggAudioStream;
import net.minecraft.client.sounds.AudioStream;

import java.io.IOException;
import java.io.InputStream;

/**
 * 打开一路 Ogg 音频流。
 *
 * <h2>为什么要有这个门面</h2>
 *
 * 同一个类在两支上叫的名字不一样：1.20.2 起它被挪进 {@code net.minecraft.client.sounds}
 * 并改名 {@code JOrbisAudioStream}，1.20.1 上还叫 {@code com.mojang.blaze3d.audio.OggAudioStream}。
 * <b>构造函数同形</b>（收一个 {@link InputStream}、抛 {@link IOException}），除了名字之外没有差别。
 *
 * <h2>为什么返回 AudioStream 就够</h2>
 *
 * 两支都能上溯到 {@link AudioStream}，只是路径不同：1.20.1 上是直接实现，
 * 1.21 上隔着 {@code FloatSampleSource → FiniteAudioStream} 两层。
 * 调用方（{@code AudioDecoder} / {@code LocalPlayback} / {@code MusicController}）
 * 一路只认 {@code AudioStream}，交给 {@code Channel.attachBufferStream} 也只要这个类型，
 * 中间不需要任何适配。
 */
public final class VanillaAudio {

    private VanillaAudio() {}

    /** 交出去的流由调用方负责关。 */
    public static AudioStream openOgg(InputStream in) throws IOException {
        return new OggAudioStream(in);
    }
}
