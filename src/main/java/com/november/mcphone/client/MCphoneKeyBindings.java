package com.november.mcphone.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * MCphone 的按键绑定。
 *
 * 这些都是标准的 KeyMapping，玩家可以在原版「选项 → 按键设置」里
 * 自行改键，分类为 "MCphone"。代码中一律通过 KeyMapping 判断按下，
 * 不要写死键码，否则玩家改键后会失效。
 *
 * ================================================================
 * 总线
 * ================================================================
 *
 * RegisterKeyMappingsEvent 是【模组总线】事件，由 MCphoneClient 的
 * 构造函数用 modEventBus.addListener(MCphoneKeyBindings::register) 显式挂载。
 *
 * 这里刻意不用 @EventBusSubscriber 自动注册：按键注册在模组总线、
 * 而相机的按键监听与渲染在游戏总线，两者混在一个类里靠注解自动路由
 * 容易出错，且出错时不报错、事件直接不触发，排查成本很高。
 */
public final class MCphoneKeyBindings {

    /** 按键设置界面中的分类名 */
    public static final String CATEGORY = "key.categories.mcphone";

    /** 拍照。默认 V —— 原版 1.21.1 未占用。 */
    public static final KeyMapping CAMERA_SHUTTER = new KeyMapping(
            "key.mcphone.camera_shutter",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            CATEGORY);

    /** 退出相机。默认 X —— 原版 1.21.1 未占用。 */
    public static final KeyMapping CAMERA_EXIT = new KeyMapping(
            "key.mcphone.camera_exit",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_X,
            CATEGORY);

    private MCphoneKeyBindings() {}

    /** 由 MCphoneClient 构造函数挂到模组总线 */
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(CAMERA_SHUTTER);
        event.register(CAMERA_EXIT);
    }
}
