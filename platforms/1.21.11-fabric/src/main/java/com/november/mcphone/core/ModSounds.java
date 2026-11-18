package com.november.mcphone.core;

import com.november.mcphone.MCphone;
import com.november.mcphone.platform.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;

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
 * 不去读那个文件。这一套在 Fabric 下同样成立（覆写的是原版方法）。
 *
 * 但那一支只有在 sounds.json 里写了 {@code "stream": true} 时才会被选中，
 * 而且那条定义指向的文件必须真的存在，否则音效事件根本解析不出来。所以
 * sounds.json 里指着 {@code minecraft:random/orb} —— 一个原版一定有的文件。
 * 它一个字节都不会被播放，纯粹是让这个事件"成立"。
 */
public final class ModSounds {

    private ModSounds() {}

    /** 手机外放网络音乐用的壳。音频由 SoundInstance 自己供，见类注释 */
    public static final Holder<SoundEvent> DISC_STREAM = Holder.of(SoundEvent.createVariableRangeEvent(
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "disc_stream")));

    /** 由 MCphone.onInitialize 调用 */
    public static void register() {
        Registry.register(BuiltInRegistries.SOUND_EVENT,
                ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "disc_stream"),
                DISC_STREAM.get());
    }
}
