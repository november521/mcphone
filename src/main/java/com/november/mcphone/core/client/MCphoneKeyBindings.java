package com.november.mcphone.core.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * MCphone 的按键绑定。
 *
 * 这些都是标准的 KeyMapping，玩家可以在原版「选项 → 按键设置」里
 * 自行改键，分类为 "MCphone"。代码中一律通过 KeyMapping 判断按下，
 * 不要写死键码，否则玩家改键后会失效。
 *
 * Fabric 没有 NeoForge 的 KeyConflictContext（按键冲突上下文），KeyMapping
 * 构造签名也就少一个参数；注册走 {@link KeyBindingHelper}。
 */
public final class MCphoneKeyBindings {

    /** 按键设置界面中的分类名 */
    public static final String CATEGORY = "key.categories.mcphone";

    /** 拍照。默认 V —— 原版 1.21.1 未占用。 */
    public static final KeyMapping CAMERA_SHUTTER = new KeyMapping(
            "key.mcphone.camera_shutter",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            CATEGORY);

    /** 退出相机。默认 X —— 原版 1.21.1 未占用。 */
    public static final KeyMapping CAMERA_EXIT = new KeyMapping(
            "key.mcphone.camera_exit",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_X,
            CATEGORY);

    /**
     * 开机。默认 H —— 原版 1.21.1 未占用。
     *
     * 有了它，手机放在背包或饰品槽里也能直接打开，不必先切到手上。
     * 这个键与 Curios 无关：没装任何附属模组时照样从背包里把手机翻出来。
     */
    public static final KeyMapping OPEN_PHONE = new KeyMapping(
            "key.mcphone.open_phone",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            CATEGORY);

    private MCphoneKeyBindings() {}

    /** 由 MCphoneClient.onInitializeClient 调用 */
    public static void register() {
        KeyBindingHelper.registerKeyBinding(CAMERA_SHUTTER);
        KeyBindingHelper.registerKeyBinding(CAMERA_EXIT);
        KeyBindingHelper.registerKeyBinding(OPEN_PHONE);
    }
}
