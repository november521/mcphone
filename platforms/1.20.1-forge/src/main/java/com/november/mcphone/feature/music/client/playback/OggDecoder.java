package com.november.mcphone.feature.music.client.playback;

import net.minecraft.client.sounds.AudioStream;
import com.mojang.blaze3d.audio.OggAudioStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * OGG Vorbis 解码器 —— 直接用 Minecraft 自己的那一个。
 *
 * 为什么不引第三方库
 *
 * 游戏本体就带着一个 Vorbis 解码器（原版所有音效都是 .ogg，全靠它），
 * 而且它已经输出 OpenAL 要的格式。再塞一个进来是白白给 jar 增重，
 * 还多一处许可证要交代。
 *
 * OggAudioStream 直接实现 AudioStream，能交给 Channel.attachBufferStream，
 * 中间不需要任何适配。
 *
 * 【类名在两个版本上不一样】：1.20.2 起它被挪进 net.minecraft.client.sounds
 * 并改名 JOrbisAudioStream，1.20.1 上还叫 com.mojang.blaze3d.audio.OggAudioStream。
 * 构造函数同样是收一个 InputStream，除了名字之外没有差别。
 *
 * 这是这个播放器的首选格式
 *
 * 零依赖、原生支持、压缩率与 MP3 相当。界面上提示玩家转成 OGG，
 * 指的就是这一条路。
 */
public final class OggDecoder implements AudioDecoder {

    @Override
    public boolean supports(Path file) {
        return AudioDecoder.hasExtension(file, ".ogg", ".oga");
    }

    @Override
    public AudioStream open(Path file) throws IOException {
        // 包 Buffered：解码器逐小块地读，直接怼文件流的话每次都是一次系统调用
        return new OggAudioStream(new BufferedInputStream(Files.newInputStream(file)));
    }

    @Override
    public String formatName() {
        return "OGG";
    }
}
