package com.november.mcphone.feature.reader.client.source;

import com.november.mcphone.MCphone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.lang.reflect.Method;

/**
 * 沉浸工程的《工程师手册》 —— 它不是 Patchouli 书，是它自己写的一整套手册系统。
 *
 * 它的手册长在哪儿
 *
 * 沉浸工程有自己的 {@code blusunrize.lib.manual} 那一包：ManualInstance 管条目树、
 * ManualScreen 是界面、ManualEntry 是一页页内容，与 Patchouli 没有任何关系。所以
 * {@link PatchouliSource} 扫不到它——不是漏了，是它压根不在那张注册表里。而玩家
 * 照样要看这本书，于是走白名单。
 *
 * 开书的那一句，是从它自己的物品里抄来的
 *
 * ManualItem.use() 在客户端走的是 ClientProxy.openManual()，而那个方法整个就是：
 *
 *     Minecraft.getInstance().setScreen(ManualHelper.getManual().getGui());
 *
 * 我们照抄这一句——玩家在手机里点开，和他在背包里右键那本手册，走的是同一条路，
 * 连"停在上次看的那一页"都跟着一致（getGui() 不重置进度）。
 *
 * 别被 openManual() 的名字骗了
 *
 * {@code ManualInstance} 上确实有一个叫 openManual() 的公开方法，但它【不开界面】：
 * 它的全部内容是清空已解锁成就的缓存、把自己挂成 ClientAdvancements 的监听器。
 * 照名字猜着调它，得到的是一个点了毫无反应、也不报错的按钮。这条注释就是为了
 * 让下一个人不必再反编译一次才发现。
 *
 * 反射，不加编译依赖
 *
 * 我们要的只是两个方法。而它的 jar 有十几 MB，还带着自己的 API 包与一堆前置——
 * 为这两句话加一条编译依赖不值。断了的代价也可控：{@link #open()} 返回 false，
 * 界面把它当成"这本书打不开"，别的书照常。
 *
 * 接触面只有两处，而且都在它的公开 API 包里
 *
 *   blusunrize.immersiveengineering.api.ManualHelper.getManual()
 *   blusunrize.lib.manual.ManualInstance.getGui()
 *
 * 前者在 api 包下，是它明确开给别人用的；后者是手册库的公开方法。取 getGui 的
 * 声明类而不是实例的实际类：实例是 IEManualInstance，从子类上取方法在某些
 * 类可见性组合下会 IllegalAccessException，从声明它的公开抽象类上取不会。
 */
public final class ImmersiveEngineeringManual implements ExternalBook {

    public static final String MODID = "immersiveengineering";

    /** 手册物品：拿它画图标，也拿它的名字当书名（它自带中文「工程师手册」） */
    private static final ResourceLocation MANUAL_ITEM =
            ResourceLocation.fromNamespaceAndPath(MODID, "manual");

    /**
     * 书 id。刻意与物品 id 同名——它本来就是"那本手册"，而且玩家的书架按这个记，
     * 发布之后改不得。
     */
    private static final ResourceLocation BOOK_ID = MANUAL_ITEM;

    private static final String MANUAL_HELPER = "blusunrize.immersiveengineering.api.ManualHelper";
    private static final String MANUAL_INSTANCE = "blusunrize.lib.manual.ManualInstance";

    /** 解析过了吗。无论成没成都只做一次 */
    private boolean resolved;

    private Method getManual;
    private Method getGui;

    @Override
    public String modId() {
        return MODID;
    }

    @Override
    public ResourceLocation bookId() {
        return BOOK_ID;
    }

    @Override
    public ResourceLocation item() {
        return MANUAL_ITEM;
    }

    @Override
    public Component title() {
        return ExternalBook.itemTitle(MANUAL_ITEM,
                Component.translatable("item.immersiveengineering.manual"));
    }

    @Override
    public boolean open() {
        Screen screen = manualScreen();
        if (screen == null) return false;

        Minecraft.getInstance().setScreen(screen);
        return true;
    }

    /** 取它的手册界面。任何一步不成都返回 null，由调用方当成"打不开" */
    private Screen manualScreen() {
        if (!resolve()) return null;

        try {
            Object manual = getManual.invoke(null);
            if (manual == null) {
                MCphone.LOGGER.error("[MCphone] 沉浸工程的 ManualHelper.getManual() 返回了 null，"
                        + "手册这次打不开");
                return null;
            }

            Object screen = getGui.invoke(manual);
            if (screen instanceof Screen s) return s;

            MCphone.LOGGER.error("[MCphone] 沉浸工程的 getGui() 给回来的不是一个 Screen（{}），"
                    + "手册打不开", screen == null ? "null" : screen.getClass().getName());
            return null;
        } catch (Throwable t) {
            // 记一次就把方法丢掉：这条路已经证明走不通，之后每次点都记一行没有意义
            MCphone.LOGGER.error("[MCphone] 打开沉浸工程的工程师手册失败", t);
            getManual = null;
            getGui = null;
            return null;
        }
    }

    /** 找不到不是错误，只是这个版本对不上——记一行，之后这本书就是打不开 */
    private boolean resolve() {
        if (resolved) return getManual != null && getGui != null;
        resolved = true;

        try {
            getManual = Class.forName(MANUAL_HELPER).getMethod("getManual");
            getGui = Class.forName(MANUAL_INSTANCE).getMethod("getGui");
            return true;
        } catch (Throwable t) {
            MCphone.LOGGER.warn("[MCphone] 没找到沉浸工程的 {}.getManual() 或 {}.getGui()（{}），"
                    + "「阅读」里那本工程师手册将打不开。这多半是它改了 API，白名单需要跟进",
                    MANUAL_HELPER, MANUAL_INSTANCE, t.toString());
            getManual = null;
            getGui = null;
            return false;
        }
    }
}
