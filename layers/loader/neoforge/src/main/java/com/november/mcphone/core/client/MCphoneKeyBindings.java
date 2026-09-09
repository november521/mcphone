package com.november.mcphone.core.client;

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
 * 总线
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

    /**
     * 开机。默认 H —— 原版 1.21.1 未占用。
     *
     * 有了它，手机放在背包或饰品槽里也能直接打开，不必先切到手上。
     * 这个键与 Curios 无关：没装任何附属模组时照样从背包里把手机翻出来。
     */
    public static final KeyMapping OPEN_PHONE = new KeyMapping(
            "key.mcphone.open_phone",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            CATEGORY);

    /**
     * 按一下唤出鼠标操作副手 HUD 上那部手机，再按一下收起。默认左 Alt —— 原版 1.21.1 未占用。
     *
     * 【这个键不能用 consumeClick / isDown 判断。】它要答的是"此刻按着没有"，
     * 而按下的那一刻 {@link net.minecraft.client.Minecraft#setScreen} 会调
     * KeyMapping.releaseAll() 把所有 KeyMapping 的按下状态清掉——手机界面一开
     * isDown() 立刻变 false，而 {@link PhoneHud} 认的是"按下去的那一沿"，
     * 一个永远回不到按下状态的键切不动任何东西。
     *
     * 所以 {@link PhoneHud} 直接查物理按键（InputConstants.isKeyDown）。留着
     * KeyMapping 是为了让玩家能在原版按键设置里改键，并让冲突提示照常工作。
     */
    public static final KeyMapping HUD_INTERACT = new KeyMapping(
            "key.mcphone.hud_interact",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_ALT,
            CATEGORY);

    /**
     * 唤出 / 收起副手 HUD 上那部手机。默认 G —— 原版 1.21.1 未占用。
     *
     * 为什么"放进副手就自动亮"之外还要这一个：手机也可以挂在 Curios 的饰品槽里，
     * 那时候副手是空的（挂饰品栏的意思本来就是"腾出两只手"），自动那条规矩够不着它。
     * 这个键让手机收在饰品栏、背包、甚至主手上时同样能把 HUD 叫出来。
     *
     * 它【顶掉】自动那条规矩，而不是与之并列——手机在副手上时按它也要能收起来，
     * 否则会出现"按了没反应"。恢复自动的时机见 {@link PhoneHud} 里 Override 那段。
     */
    public static final KeyMapping HUD_TOGGLE = new KeyMapping(
            "key.mcphone.hud_toggle",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            CATEGORY);

    private MCphoneKeyBindings() {}

    /** 由 MCphoneClient 构造函数挂到模组总线 */
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(CAMERA_SHUTTER);
        event.register(CAMERA_EXIT);
        event.register(OPEN_PHONE);
        event.register(HUD_INTERACT);
        event.register(HUD_TOGGLE);
    }
}
