package com.november.mcphone.core;

import com.november.mcphone.MCphone;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 音效事件注册。
 *
 * 只有一个，而且它不对应任何一个音频文件
 *
 * {@link #DISC_STREAM} 是个【壳】：手机外放网络音乐时，音频是我们自己从
 * 网上拉的一条流，不在任何资源包里。
 *
 * 可 Minecraft 的音效系统不接受"凭空一条流"——它只认注册过的音效事件，
 * 再顺着 sounds.json 找到一个文件。NeoForge 为此开了个口子：
 * {@code SoundInstance.getStream(...)} 可以覆写，覆写之后引擎就用你给的流，
 * 不去读那个文件（核过 SoundEngine：走的是 soundinstance.getStream 那一支）。
 *
 * 但那一支只有在 sounds.json 里写了 {@code "stream": true} 时才会被选中，
 * 而且那条定义指向的文件必须真的存在，否则音效事件根本解析不出来。所以
 * sounds.json 里指着 {@code minecraft:random/orb} —— 一个原版一定有的文件。
 * 它一个字节都不会被播放，纯粹是让这个事件"成立"。
 *
 * NetMusic 自己也是这么做的（它那个 netmusic:net_music 同样指着 random/orb），
 * 这不是巧合，是这条路只有这一种走法。
 *
 * 为什么不借用原版某个唱片的音效事件
 *
 * 省下这个注册当然可以，代价是两条：字幕会显示成那张唱片的名字；更糟的是
 * {@code ClientboundStopSoundPacket} 按音效 ID 停 —— 谁停那张原版唱片，
 * 就会把我们的网络音乐一起停掉，反过来也一样。
 */
public final class ModSounds {

    private ModSounds() {}

    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, MCphone.MODID);

    /** 手机外放网络音乐用的壳。音频由 SoundInstance 自己供，见类注释 */
    public static final DeferredHolder<SoundEvent, SoundEvent> DISC_STREAM =
            SOUND_EVENTS.register("disc_stream",
                    () -> SoundEvent.createVariableRangeEvent(
                            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "disc_stream")));
}
