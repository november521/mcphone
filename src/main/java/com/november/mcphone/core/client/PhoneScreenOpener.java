package com.november.mcphone.core.client;

import com.november.mcphone.core.PhoneLocation;
import com.november.mcphone.core.client.PhoneScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import com.november.mcphone.core.PhoneItem;

/**
 * 打开手机主界面。客户端专用。
 *
 * ============================================================
 * 为什么这两个方法不能待在 PhoneItem 里
 * ============================================================
 *
 * 它们原本就在 PhoneItem 上，结果把专用服务器打崩了：
 *
 *     java.lang.RuntimeException: Attempted to load class
 *         net/minecraft/client/gui/screens/Screen for invalid dist DEDICATED_SERVER
 *       at com.november.mcphone.MCphone.lambda$static$0(MCphone.java:44)
 *       at DeferredRegister$Items.lambda$registerItem$2
 *       ← RegisterEvent / GameData.postRegisterEvents
 *
 * 注册物品时要 new 一个 PhoneItem，JVM 于是加载并【校验】PhoneItem。校验
 * 一个方法时，它得确认压进栈的类型确实能赋给方法签名要求的类型——碰上
 * Minecraft.setScreen(Screen) 这句，就要查 PhoneScreen 是不是 Screen 的
 * 子类，而这一查就必须把 Screen 加载进来。专用服务器上 NeoForge 的
 * RuntimeDistCleaner 拦下了它，直接抛异常。
 *
 * 注意崩的不是"执行到了这句"——那句被 level.isClientSide() 挡得好好的，
 * 服务端永远跑不到。光是【类里写着这句】就够了。所以"用 if 挡住"这个
 * 直觉是错的，客户端类型必须从服务端会加载的类里彻底消失。
 *
 * ============================================================
 * 那 PhoneItem 现在怎么调它
 * ============================================================
 *
 * PhoneItem.use() 里写的是 invokestatic PhoneScreenOpener.open(PhoneLocation)。
 * invokestatic 的【属主类】是第一次执行到时才解析的，校验期不碰；校验期
 * 只查参数类型 PhoneLocation，那是个服务端安全的类。于是本类在专用服务器
 * 上从头到尾不会被加载。
 *
 * 一句话：跨端调用可以写，但签名里不许出现客户端类型。
 */
public final class PhoneScreenOpener {

    private PhoneScreenOpener() {}

    /**
     * 打开手机主屏幕。
     *
     * @param location 手机在身上的哪个位置。设备名要写回【这一部】手机，
     *                 玩家身上不止一部时不能改错。
     */
    public static void open(PhoneLocation location) {
        Minecraft.getInstance().setScreen(new PhoneScreen(location));
    }

    /**
     * 从玩家身上找一部手机打开，找不到就什么都不做。
     *
     * 快捷键走这条路：手上、背包、饰品槽都找，所以手机收在哪儿都能开机。
     *
     * @return 真的开了机才返回 true
     */
    public static boolean open(Player player) {
        return PhoneLocation.find(player)
                .map(location -> {
                    open(location);
                    return true;
                })
                .orElse(false);
    }
}
