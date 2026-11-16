package com.november.mcphone.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 暴露 KeyMapping 当前的键。
 *
 * 原 NeoForge 版给 KeyMapping 打了补丁，有 getKey()；Fabric 的原版类只有
 * getDefaultKey()（永远返回初始键，重绑之后对不上）。AppHotkeys 判快捷键
 * 冲突要的是【当前】键，故用 accessor 把私有字段 key 放出来。
 */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {

    @Accessor("key")
    InputConstants.Key mcphone$getKey();
}
