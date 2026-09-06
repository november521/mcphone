package com.november.mcphone.mixin;

import com.november.mcphone.feature.music.client.NetSongSound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.CompletableFuture;

/**
 * 把 SoundEngine 对「自定义网络歌」的取流重定向到 NetSongSound 自己的流。
 *
 * 原 NeoForge 版通过给 SoundInstance 加 getStream() 覆写实现；Fabric 没有那个
 * 钩子，而 vanilla 的 SoundEngine.play 对流式声音一律走
 * {@code soundBuffers.getStream(sound.getPath(), looping)}。这里按 path 反查
 * NetSongSound 注册表：命中就返回网络流，没命中（普通声音）走原逻辑。
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {

    @Redirect(method = "play",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/sounds/SoundBufferLibrary;getStream(Lnet/minecraft/resources/ResourceLocation;Z)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<AudioStream> mcphone$redirectNetStream(SoundBufferLibrary instance,
                                                                     ResourceLocation path,
                                                                     boolean looping) {
        NetSongSound netSong = NetSongSound.byPath(path);
        if (netSong != null) {
            return netSong.openStream();
        }
        return instance.getStream(path, looping);
    }
}
