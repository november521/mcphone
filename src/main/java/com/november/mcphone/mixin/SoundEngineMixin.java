package com.november.mcphone.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.november.mcphone.feature.music.client.NetSongSound;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;

/**
 * 把 SoundEngine 对「自定义网络歌」的取流改道到 NetSongSound 自己的流。
 *
 * 原 NeoForge 版通过给 SoundInstance 加 getStream() 覆写实现；Fabric 没有那个
 * 钩子，而 vanilla 的 SoundEngine.play 对流式声音一律走
 * {@code soundBuffers.getStream(sound.getPath(), looping)}。这里按 path 反查
 * NetSongSound 注册表：命中就返回网络流，没命中（普通声音）走原逻辑。
 *
 * 为什么用 @WrapOperation 而不是 @Redirect
 *
 * MixinExtras 0.5.0（Fabric Loader 0.17.x 自带）的 FactoryRedirectWrapperMixinTransformer
 * 会把 @Redirect 的 at 值强转成单个 AnnotationNode；而 javac 编译 @Redirect 时 at
 * 是单元素数组，于是 ClassCastException 直接崩在游戏初始化。@WrapOperation 走
 * MixinExtras 自己的包装通道，不经过那个转换器，并且多个模组可叠加。
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {

    @WrapOperation(method = "play",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/sounds/SoundBufferLibrary;getStream(Lnet/minecraft/resources/ResourceLocation;Z)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<AudioStream> mcphone$wrapNetStream(SoundBufferLibrary instance,
                                                                 ResourceLocation path,
                                                                 boolean looping,
                                                                 Operation<CompletableFuture<AudioStream>> original) {
        NetSongSound netSong = NetSongSound.byPath(path);
        if (netSong != null) {
            return netSong.openStream();
        }
        return original.call(instance, path, looping);
    }
}
