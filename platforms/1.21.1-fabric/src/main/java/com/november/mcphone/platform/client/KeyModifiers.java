package com.november.mcphone.platform.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.november.mcphone.core.client.KeyModifier;

/**
 * 「这个键是不是修饰键」的门面。NeoForge 那一支问的是它自带的
 * {@code KeyModifier}；Fabric 没有这号人物，答话的是我们复刻的
 * {@link KeyModifier} 枚举 —— 语义与 NeoForge 逐字对齐，见它的类注释。
 */
public final class KeyModifiers {

    private KeyModifiers() {}

    /** 这个键本身是不是一个修饰键（Ctrl / Shift / Alt）。 */
    public static boolean isModifierKey(InputConstants.Key key) {
        return KeyModifier.isKeyCodeModifier(key);
    }
}
