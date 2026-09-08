package com.november.mcphone.feature.terminal.client;

import com.november.mcphone.api.client.app.RequiredMod;
import com.november.mcphone.core.client.PhoneApp;
import com.november.mcphone.core.net.MCphoneNetwork;
import com.november.mcphone.feature.terminal.TerminalSlot;
import com.november.mcphone.feature.terminal.integration.TerminalIntegration;
import com.november.mcphone.feature.terminal.integration.Terminals;
import com.november.mcphone.feature.terminal.integration.ae2.Ae2Integration;
import com.november.mcphone.feature.terminal.integration.refinedstorage.RefinedStorageIntegration;
import com.november.mcphone.feature.terminal.integration.toms.TomsStorageIntegration;
import com.november.mcphone.feature.terminal.net.TerminalActionPacket;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;

import java.util.List;

/**
 * 终端 App：在手机上点一下，打开你自己的存储终端。
 * 贴图: assets/mcphone/textures/app/terminal.png (20×20)
 *
 * 哪一家的终端都行 —— Applied Energistics 2、Refined Storage、Tom's Simple Storage，装了
 * 哪家就支持哪家。挑哪一台、怎么开，全在 {@link Terminals} 那一层，这个类只管身份与点击。
 *
 * 为什么不画在手机屏幕里
 *
 * 覆盖 {@code openPage()} 就能把界面画在那块 120×176 的屏幕里，内建 App 多数是这么做的。
 * 这一格刻意<b>不</b>：存储终端是一整面物品网格加搜索、排序、视图卡、合成状态，放不下；
 * 真画了就得把对方那套界面重新实现一遍，而且注定跟不上它的更新——何况要跟的是三家。
 * 手机在这里的角色是<b>入口</b>，不是第二个终端。
 */
public final class TerminalApp extends PhoneApp {

    public TerminalApp() {
        super("terminal");
    }

    /**
     * 三家存储模组都是<b>联动</b>，不是前置。
     *
     * 写成 {@code requiredMods()} 的话，默认的可用性判断会要求<b>三家全装</b>——那是错的，
     * 装了任意一家这一格就有内容。所以声明成联动，并自己覆盖 {@link #isAvailable()}，
     * 和传送石那一格同一个写法。
     *
     * modid 与显示名都从那三个联动类的常量取，全模组只有一份，不会两处对不上。
     * <b>这不会把那三个类加载进来</b>：它们是编译期常量，javac 直接内联成 ldc
     * ——理由与验法见 {@code Ae2Integration.NAME} 的注释。
     */
    private static final List<RequiredMod> COMPANIONS = List.of(
            new RequiredMod(Ae2Integration.MODID, Ae2Integration.NAME),
            new RequiredMod(RefinedStorageIntegration.MODID, RefinedStorageIntegration.NAME),
            new RequiredMod(TomsStorageIntegration.MODID, TomsStorageIntegration.NAME));

    @Override
    public List<RequiredMod> companionMods() {
        return COMPANIONS;
    }

    /**
     * 一家都没装就不出现在主屏和商店里。
     *
     * 必须自己判：联动声明不参与默认的可用性判断（默认实现只看 requiredMods），照默认走就是
     * "永远可用"，于是没装任何存储模组的玩家主屏上会多一个点了没反应的图标。
     */
    @Override
    public boolean isAvailable() {
        return Terminals.anyPresent();
    }

    /**
     * 不预装：去应用商店拿，<b>免费</b>（所以它不在 {@code BuiltinAppPrices} 里）。
     *
     * 免费是刻意的。末影箱那一格卖一个末影箱、传送石那一格卖一个传送石，因为 App 本身就是
     * 那件实物的替代品；而这一格<b>替代不了任何东西</b>——它开的是你自己那台终端，没有终端
     * 它一格内容都没有。真正的门槛是那台终端，再收一次等于收两遍。
     */
    @Override
    public boolean isPreinstalled() {
        return false;
    }

    /**
     * 卡槽里装了终端就开它，卡槽空着就开卡槽界面；按住 Shift 一律开卡槽界面。
     *
     * 为什么不直接开背包里那台：那样就没人装得进去。卡槽空着时点一下永远是终端界面的话，
     * 卡槽界面只剩 Shift 这一个入口，没人会去猜它。现在卡槽空着就把卡槽摆到面前，那一页上
     * 格子和背包挨着，拖进去就装好了。背包里那台没被扔掉——卡槽界面上「打开终端」照样用它。
     *
     * 只发包不自己开界面：容器菜单必须由服务端 openMenu 建立，界面才由原版流程自动弹出。
     * 客户端自己 setScreen 一个终端界面，那界面背后没有菜单，点什么都没反应。和末影箱那一格
     * 同一个写法。
     *
     * 两个判断都在客户端做。修饰键：{@code onPress()} 没有参数拿不到，但这一刻我们就在客户端；
     * 卡槽内容：它是 {@code sync} 的附件，这里读到的和服务端是同一份，万一读错服务端那两级
     * 也会兜住。
     */
    @Override
    public void onPress() {
        MCphoneNetwork.sendToServer(new TerminalActionPacket(
                Screen.hasShiftDown() || !hasInstalledTerminal()
                        ? TerminalActionPacket.Action.OPEN_SLOT_MENU
                        : TerminalActionPacket.Action.OPEN_TERMINAL));
    }

    /**
     * 界面不在手机里 —— 开的是那三家自己的终端界面（{@link #onPress()} 只发包，
     * 界面由服务端 openMenu 之后原版流程自己弹）。
     *
     * 所以快捷键不必先开机：开了的话玩家会看见手机弹出来、等服务端把容器开回来之后
     * 才被终端顶掉，中间白闪一下。
     */
    @Override
    public boolean opensInsidePhone() { return false; }

    /** 手机卡槽里装着终端吗。还没进世界（player 为 null）时当没装 */
    private static boolean hasInstalledTerminal() {
        LocalPlayer player = Minecraft.getInstance().player;
        return player != null && !TerminalSlot.get(player).isEmpty();
    }

    /**
     * 详情页的简介，末尾缀上这一局真正接上了哪几家。
     *
     * 那一串是现问 {@link Terminals} 的：装了 AE2 的人看到 AE2，装了三家的看到三家。比一句
     * 写死的"支持 AE2 / RS / Tom's"有用——玩家想知道的是"我这一局能用哪家"。
     */
    @Override
    public String getDescription() {
        String text = super.getDescription();

        List<TerminalIntegration> active = Terminals.active();
        if (active.isEmpty()) return text;

        // 分隔符走语言文件：中文用「、」，英文用「, 」。写死一个的话另一边必然难看
        String names = String.join(I18n.get("mcphone.terminal.list_sep"),
                active.stream().map(TerminalIntegration::displayName).toList());
        return text + I18n.get("mcphone.app.terminal.desc.connected", names);
    }
}
