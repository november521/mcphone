package com.november.mcphone.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.november.mcphone.feature.music.client.NetSongSound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.CompletableFuture;

/**
 * 把网络音乐的播放引到我们自己的音频流上。
 *
 * NeoForge 给 {@code SoundInstance} 补了可覆写的 {@code getStream}，shared 的
 * {@link NetSongSound} 覆写它就完事；原版没有这个钩子，SoundEngine 拿流的唯一
 * 路径是按 Sound 的资源路径去 SoundBufferLibrary 加载文件。所以这里在 {@code play}
 * 里把那一步包一层。
 *
 * <h2>为什么按实例身份认，而不是按路径认</h2>
 *
 * {@code disc_stream} 在 sounds.json 里刻意指向 {@code minecraft:random/orb}
 * （理由见 ModSounds 的类注释：那个壳必须解析得到一个真实存在的文件，事件才成立）。
 * 于是 SoundEngine 交到 getStream 的 path 是 {@code minecraft:random/orb}，
 * <b>不是</b> {@code mcphone:disc_stream} —— 按路径比对那个条件的写法永远不成立，
 * 网音乐会去加载原版那个音效（听上去就是"放不出声"，且不报错）。
 *
 * 所以认【实例】：进 play 的是 NetSongSound 就换流。这个判据本身就够精确 ——
 * 原版播放 random/orb 时，进 play 的是别的 SoundInstance。
 */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {

    /** play 一进门记下这次要播的实例，等下面那个 INVOKE 时对上身份 */
    @Unique
    private static SoundInstance mcphone$pending;

    @Inject(method = "play", at = @At("HEAD"))
    private void mcphone$stashInstance(SoundInstance sound, CallbackInfo ci) {
        mcphone$pending = sound;
    }

    @WrapOperation(method = "play",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/sounds/SoundBufferLibrary;getStream(Lnet/minecraft/resources/ResourceLocation;Z)Ljava/util/concurrent/CompletableFuture;"))
    private CompletableFuture<AudioStream> mcphone$wrapNetStream(SoundBufferLibrary instance,
                                                                 ResourceLocation path,
                                                                 boolean looping,
                                                                 Operation<CompletableFuture<AudioStream>> original) {
        SoundInstance pending = mcphone$pending;
        mcphone$pending = null;                 // 无论走哪条都消费掉，别留给下一次 play
        if (pending instanceof NetSongSound netSong) {
            return netSong.getStream(instance, netSong.getSound(), looping);
        }
        return original.call(instance, path, looping);
    }
}
