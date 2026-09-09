package com.november.mcphone.core.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.november.mcphone.MCphone;
import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.core.PhoneLocation;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

/**
 * 按下某个 App 的快捷键 —— 直接进那个 App。键盘、鼠标键都行，支持
 * Ctrl / Shift / Alt 的组合。
 *
 * 界面在手机【里】的 App（记事本、聊天、设置……）是开机再进那一页；界面在手机
 * 【外面】的（终端、末影箱、相机……）根本不开机，直接交给它，见 {@link #launchOutside}。
 *
 * 绑定表在 {@link AppHotkeys}，界面在「设置 → App 管理器 → 某个 App」。
 *
 * 为什么听 InputEvent.Key，而开机键 {@link PhoneKeyHandler} 是在 tick 里读
 *
 * 那个键是 KeyMapping，{@code consumeClick()} 会把积压的按下逐个取走，同一
 * tick 内连按几下不丢也不重；而这里的键不是 KeyMapping（理由见 AppHotkeys），
 * 没有那口队列。改在 tick 里轮询 {@code isKeyDown} 的话，比一个 tick（50ms）
 * 更短的一下就会整个丢掉——按得快正是快捷键的常态。所以听按下事件本身。
 *
 * 只认 GLFW_PRESS：REPEAT 是按住不放时系统补发的，那会变成一直重开手机。
 *
 * 键盘与鼠标为什么不是同一套收尾
 *
 * 鼠标那条事件（{@code MouseButton.Pre}）是【可取消】的，取消掉原版就完全不处理这一下，
 * 于是绑在侧键上的 App 打开时不会顺带挥一下手。键盘那条（{@code InputEvent.Key}）不可
 * 取消，只能等原版把点击排进队之后再把队倒空，见 {@link #drainConflicting}。
 *
 * 组合键要【完全一致】才算数：绑了 Ctrl+K 的人按 K 不会开，按 Ctrl+Shift+K 也不会。
 * 宽松匹配（"按住的修饰键含着绑定的那几个就算"）会让 Ctrl+K 顺带响应 Ctrl+Shift+K，
 * 于是两个 App 分别绑这两个组合时，后者永远会连着前者一起触发。
 *
 * 界面开着时不响应
 *
 * {@code mc.screen != null} 就直接回来。玩家正在背包、聊天框、甚至手机自己
 * 里面时，他按的键属于那个界面——尤其聊天框，那时候每一个字母键都是在打字。
 * （手机已经开着时按某个 App 的键不会切过去，这是刻意的：那一下多半是在
 * 手机里输入，而不是想换 App。）
 */
public final class AppHotkeyHandler {

    private AppHotkeyHandler() {}

    /** 由 MCphoneClient 构造函数挂到游戏总线 */
    public static void onKeyInput(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null || mc.level == null) return;

        // 与原版记按键同一套：有键位符号的用 KEYSYM，没有的退回扫描码
        InputConstants.Key key = InputConstants.getKey(event.getKey(), event.getScanCode());

        // Ctrl / Shift / Alt 自己按下去不算数：组合键要等主键那一下才成立。
        // 不挡的话，绑了 Ctrl+K 的人光按 Ctrl 就会被当成"主键是 Ctrl"来查一遍
        if (KeyModifier.isKeyCodeModifier(key)) return;

        // 修饰键取此刻真的按住的那几个，不用事件里的 mods 位：Mac 上 Command 要
        // 算成 Ctrl，那层换算在 KeyModifier 里，位掩码自己看不出来
        AppHotkeys.Binding pressed = AppHotkeys.Binding.of(key, AppHotkeys.activeModifiers());

        if (!launch(mc, pressed)) return;

        drainConflicting(pressed);
    }

    /**
     * 鼠标键那条路。由 MCphoneClient 构造函数挂到游戏总线。
     *
     * 用 Pre 而不是 Post：它可以取消，取消掉原版就不再处理这一下——绑在侧键上的 App
     * 打开时不会顺带挥一次手、放一次方块。键盘那条没有这个待遇。
     */
    public static void onMouseInput(InputEvent.MouseButton.Pre event) {
        if (event.getAction() != GLFW.GLFW_PRESS) return;

        Minecraft mc = Minecraft.getInstance();

        // 【绑键界面正在等的话，这一下先归它。】
        //
        // 原版的按键设置是在 Screen.mouseClicked 里收鼠标键的，第一版照抄了那个做法。
        // 但原版那个界面里只有它自己，而手机这一页的点击要穿过 PhoneScreen 的一整条
        // 分发链（机身外判定、导航栏命中、各页分发……），中间任何一层把它吃掉，
        // 表现出来都是"侧键按了没反应"，而且完全不报错。
        //
        // 这条路没有那些层：MouseHandler.onPress 一进门就发这个事件，比屏幕分发早
        //     if (ClientHooks.onMouseButtonPre(...)) return;   ← 这里
        //     ... 之后才轮到 screen.mouseClicked(...)
        // 收下之后把事件取消掉，原版就不会再把这一下发给屏幕，两条路不会都响。
        if (mc.screen instanceof PhoneScreen phone && phone.captureHotkeyMouse(event.getButton())) {
            event.setCanceled(true);
            return;
        }

        if (mc.screen != null || mc.player == null || mc.level == null) return;

        InputConstants.Key key = InputConstants.Type.MOUSE.getOrCreate(event.getButton());
        AppHotkeys.Binding pressed = AppHotkeys.Binding.of(key, AppHotkeys.activeModifiers());

        if (!launch(mc, pressed)) return;

        // 原版这一下整个不处理了，自然也没有点击排进队，不必再倒
        event.setCanceled(true);
    }

    /** 真的把 App 开起来了才 true */
    private static boolean launch(Minecraft mc, AppHotkeys.Binding pressed) {
        ResourceLocation appId = AppHotkeys.appFor(pressed);
        if (appId == null) return false;

        // 卸载了就当没绑：绑定按机器存、安装状态按存档存，同一台电脑换个存档
        // 完全可能没装这个 App。此时什么都不做，也不提示——按错键是很常见的事，
        // 为此弹一句话反而聒噪（开机键那边同一条规矩）
        if (!PhoneScreenRegistry.isInstalled(appId)) return false;

        IPhoneApp app = PhoneScreenRegistry.getApp(appId);
        if (app == null) return false;    // 目录里没有＝前置模组这局没装，不可用

        // 界面不在手机里的那几个（终端、末影箱、传送石、任务书、浏览器、相机）不开机，
        // 见 launchOutside
        if (!opensInsidePhone(app)) return launchOutside(mc, app);

        // 身上没有手机就开不了机，那就更谈不上进 App
        if (!PhoneScreenOpener.open(mc.player)) return false;

        if (mc.screen instanceof PhoneScreen phone) phone.launchApp(app);
        return true;
    }

    /**
     * 界面在手机外面的 App：<b>不开机</b>，直接把这一下交给它。
     *
     * 为什么要专门分一条路
     *
     * 这几个 App 的 onPress() 要么自己 setScreen（浏览器、相机、任务书），要么发个包等
     * 服务端开容器（终端、末影箱、传送石）。照旧先开机的话，手机会先弹出来——自己
     * setScreen 的那几个是闪一帧，发包的那几个更久：界面得等服务端把容器开回来才换，
     * 单机也要一整个 tick，联机再加一个来回。玩家看到的是"手机开了一下，然后终端才出来"，
     * 而快捷键的全部意义就是省掉中间那一步。
     *
     * 手机仍然必须在身上：手机是那台设备，锁在箱子里的话按一下键不该开出终端来。这一句
     * 同时顶替了 {@link PhoneScreenOpener#open(net.minecraft.world.entity.player.Player)}
     * 顺手做的那次查找——那条路这里不走了。
     *
     * onPress() 抛了照样算"这一下我们收了"：与手机里点图标同一个规矩（见
     * {@link PhoneScreen#launchApp}），记一条日志，但不让这一下漏回原版去挥手、开背包。
     */
    private static boolean launchOutside(Minecraft mc, IPhoneApp app) {
        if (mc.player == null || PhoneLocation.find(mc.player).isEmpty()) return false;

        try {
            app.onPress();
        } catch (Throwable t) {
            MCphone.LOGGER.error("[MCphone] App {} 的 onPress() 抛异常", app.getId(), t);
        }
        return true;
    }

    /** 附属答的话，问一句也得兜住。抛了当它在手机里——那是老行为 */
    private static boolean opensInsidePhone(IPhoneApp app) {
        try {
            return app.opensInsidePhone();
        } catch (Throwable t) {
            MCphone.LOGGER.error("[MCphone] App {} 的 opensInsidePhone() 抛异常，当它在手机里",
                    app.getId(), t);
            return true;
        }
    }

    /**
     * 这个键要是同时还挂着一条 KeyMapping，把它这一下攒的点击倒掉。
     *
     * 冲突现在是【可以强制绑上】的（绑定界面会先说被谁占了，玩家再按一次就照绑），
     * 所以这条路是常态而不是意外；手改配置那条路也在这儿汇合。
     *
     * 不倒的话会变成这样：按 E，我们开了手机，原版那一下 click 排在队里没人取（原版
     * 取它的地方要求当前没有界面），等玩家关掉手机，背包【补开一次】。玩家会觉得是
     * 手机把背包键弄坏了，而且第二次按才对——这种时序错位最难查。
     */
    private static void drainConflicting(AppHotkeys.Binding binding) {
        for (KeyMapping clash : AppHotkeys.conflictingMappings(binding)) {
            while (clash.consumeClick()) { /* 倒空 */ }
        }
    }
}
