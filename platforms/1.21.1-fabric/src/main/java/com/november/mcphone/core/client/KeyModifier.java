package com.november.mcphone.core.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.lwjgl.glfw.GLFW;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 修饰键（Ctrl / Shift / Alt）的语义工具。
 *
 * Fabric 没有 NeoForge 那个 KeyModifier（客户端设置包里），而 AppHotkeys 的
 * 组合键逻辑整套建立在它上面：Mac 上把 Command 算成 Ctrl、修饰键名字的本地化、
 * "一个键是不是修饰键本体"。所以这里按同样的语义复刻一份，只实现本项目用到的
 * 那几个面，别的地方不抄。
 */
public enum KeyModifier {
    NONE, SHIFT, CONTROL, ALT;

    /** 固定顺序：序列化、遍历都按它走，文件才不会无谓地变 */
    public static final KeyModifier[] MODIFIER_VALUES = { SHIFT, CONTROL, ALT };

    /** 从一个名字读。读不出来（含 null/空）一律 NONE，由调用方决定怎么办 */
    public static KeyModifier valueFromString(String value) {
        if (value == null) return NONE;
        for (KeyModifier m : values()) {
            if (m.name().equalsIgnoreCase(value.trim())) return m;
        }
        return NONE;
    }

    /**
     * 此刻按住的所有修饰键。
     *
     * Mac 上 Command 由 {@link Minecraft#ON_OSX} 分岔算成 CONTROL，与 NeoForge
     * 的语义一致。
     */
    public static Set<KeyModifier> getActiveModifiers() {
        EnumSet<KeyModifier> out = EnumSet.noneOf(KeyModifier.class);
        long handle = Minecraft.getInstance().getWindow().getWindow();
        if (handle == 0L) return out;

        if (isDown(handle, GLFW.GLFW_KEY_LEFT_SHIFT) || isDown(handle, GLFW.GLFW_KEY_RIGHT_SHIFT)) {
            out.add(SHIFT);
        }
        boolean control = isDown(handle, GLFW.GLFW_KEY_LEFT_CONTROL) || isDown(handle, GLFW.GLFW_KEY_RIGHT_CONTROL);
        if (Minecraft.ON_OSX) {
            control = control
                    || isDown(handle, GLFW.GLFW_KEY_LEFT_SUPER)
                    || isDown(handle, GLFW.GLFW_KEY_RIGHT_SUPER);
        }
        if (control) out.add(CONTROL);
        if (isDown(handle, GLFW.GLFW_KEY_LEFT_ALT) || isDown(handle, GLFW.GLFW_KEY_RIGHT_ALT)) {
            out.add(ALT);
        }
        return out;
    }

    /** 从一次按键事件的 GLFW 修饰位算出按住的那几个 */
    public static Set<KeyModifier> fromModifiers(int modifiers) {
        EnumSet<KeyModifier> out = EnumSet.noneOf(KeyModifier.class);
        if ((modifiers & GLFW.GLFW_MOD_SHIFT) != 0) out.add(SHIFT);
        boolean control = (modifiers & GLFW.GLFW_MOD_CONTROL) != 0;
        if (Minecraft.ON_OSX) control = control || (modifiers & GLFW.GLFW_MOD_SUPER) != 0;
        if (control) out.add(CONTROL);
        if ((modifiers & GLFW.GLFW_MOD_ALT) != 0) out.add(ALT);
        return out;
    }

    private static boolean isDown(long handle, int key) {
        return InputConstants.isKeyDown(handle, key);
    }

    /** 这个键是不是某个修饰键本体（Ctrl/Shift/Alt，Mac 上还包括 Command） */
    public static boolean isKeyCodeModifier(InputConstants.Key key) {
        int v = key.getValue();
        return v == GLFW.GLFW_KEY_LEFT_SHIFT || v == GLFW.GLFW_KEY_RIGHT_SHIFT
                || v == GLFW.GLFW_KEY_LEFT_CONTROL || v == GLFW.GLFW_KEY_RIGHT_CONTROL
                || v == GLFW.GLFW_KEY_LEFT_ALT || v == GLFW.GLFW_KEY_RIGHT_ALT
                || v == GLFW.GLFW_KEY_LEFT_SUPER || v == GLFW.GLFW_KEY_RIGHT_SUPER;
    }

    /** "Ctrl + K" 那种显示名，修饰键名走语言文件，跟着玩家的语言走 */
    public Component getCombinedName(InputConstants.Key key, Supplier<Component> defaultKeyname) {
        String langKey = "key.mcphone.modifier." + name().toLowerCase(Locale.ROOT);
        MutableComponent name = Component.translatable(langKey, defaultKeyname.get());
        return name;
    }
}
