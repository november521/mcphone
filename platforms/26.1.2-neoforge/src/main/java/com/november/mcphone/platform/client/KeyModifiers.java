package com.november.mcphone.platform.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.neoforged.neoforge.client.settings.KeyModifier;

/**
 * 修饰键相关的判断。
 *
 * <h2>为什么要有这个门面</h2>
 *
 * {@code KeyModifier} 是<b>加载器的类型</b>（两支的包名不一样），原版没有对应物。
 * 只要某个类里出现这个名字，它就只能留在各平台自己那一份里。
 *
 * <h2>为什么只搬了这一个方法</h2>
 *
 * {@code AppManagerDetail} 用到 {@code KeyModifier} 的地方<b>只有这一处</b>，
 * 收进来它整份（五百多行）就能进共用层。
 *
 * {@code AppHotkeys} 是另一回事：那个类把 {@code Set<KeyModifier>} 摆进了自己的数据
 * 模型（{@code record Binding}）、存盘格式与冲突判定里，要脱钩得先换掉那个类型参数——
 * 那是一次真重构而不是加个门面，而它只有两百多行。<b>没有做，也不该拿这个门面硬套。</b>
 */
public final class KeyModifiers {

    private KeyModifiers() {}

    /** 这个键本身是不是一个修饰键（Ctrl / Shift / Alt）。 */
    public static boolean isModifierKey(InputConstants.Key key) {
        return KeyModifier.isKeyCodeModifier(key);
    }
}
