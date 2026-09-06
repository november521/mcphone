package com.november.mcphone.core.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.november.mcphone.api.client.app.IPhoneApp;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

/**
 * 按下某个 App 的快捷键 —— 开机，并且直接进那个 App。支持 Ctrl / Shift / Alt 的组合。
 *
 * 绑定表在 {@link AppHotkeys}，界面在「设置 → App 管理器 → 某个 App」。
 *
 * 为什么听键盘事件而不是在 tick 里读
 *
 * 这个键不是 KeyMapping（理由见 AppHotkeys），没有 consumeClick 那口队列。
 * 改在 tick 里轮询 isKeyDown 的话，比一个 tick（50ms）更短的一下就会整个丢掉——
 * 按得快正是快捷键的常态。所以由 {@link KeyboardHandlerMixin} 截原版的
 * {@code KeyboardHandler.keyPress}，把原始键码、扫描码、动作与修饰位送到这里。
 *
 * 只认 GLFW_PRESS：REPEAT 是按住不放时系统补发的，那会变成一直重开手机。
 *
 * 组合键要【完全一致】才算数：绑了 Ctrl+K 的人按 K 不会开，按 Ctrl+Shift+K 也不会。
 *
 * 界面开着时不响应：mc.screen != null 就直接回来。
 */
public final class AppHotkeyHandler {

    private AppHotkeyHandler() {}

    /** 由 KeyboardHandlerMixin 在每次键盘按下事件时调用 */
    public static void onKeyInput(int key, int scancode, int action, int modifiers) {
        if (action != GLFW.GLFW_PRESS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null || mc.level == null) return;

        // 与原版记按键同一套：有键位符号的用 KEYSYM，没有的退回扫描码
        InputConstants.Key k = InputConstants.getKey(key, scancode);

        // Ctrl / Shift / Alt 自己按下去不算数：组合键要等主键那一下才成立。
        if (KeyModifier.isKeyCodeModifier(k)) return;

        // 修饰键取这次事件携带的修饰位（Mac 上 Command 算成 Ctrl，见 KeyModifier.fromModifiers）
        AppHotkeys.Binding pressed = AppHotkeys.Binding.of(k, KeyModifier.fromModifiers(modifiers));

        ResourceLocation appId = AppHotkeys.appFor(pressed);
        if (appId == null) return;

        // 卸载了就当没绑：绑定按机器存、安装状态按存档存，同一台电脑换个存档
        // 完全可能没装这个 App。此时什么都不做，也不提示。
        if (!PhoneScreenRegistry.isInstalled(appId)) return;

        IPhoneApp app = PhoneScreenRegistry.getApp(appId);
        if (app == null) return;    // 目录里没有＝前置模组这局没装，不可用

        // 身上没有手机就开不了机，那就更谈不上进 App
        if (!PhoneScreenOpener.open(mc.player)) return;

        if (mc.screen instanceof PhoneScreen phone) phone.launchApp(app);

        drainConflicting(pressed);
    }

    /**
     * 这个键要是同时还挂着一条 KeyMapping，把它这一下攒的点击倒掉。
     *
     * 冲突现在是【可以强制绑上】的（绑定界面会先说被谁占了，玩家再按一次就照绑），
     * 所以这条路是常态而不是意外。
     *
     * 不倒的话会变成这样：按 E，我们开了手机，原版那一下 click 排在队里没人取（原版
     * 取它的地方要求当前没有界面），等玩家关掉手机，背包【补开一次】。
     */
    private static void drainConflicting(AppHotkeys.Binding binding) {
        for (KeyMapping clash : AppHotkeys.conflictingMappings(binding)) {
            while (clash.consumeClick()) { /* 倒空 */ }
        }
    }
}
