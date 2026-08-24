package com.november.mcphone.api.client.app;

import com.november.mcphone.api.client.ui.IPhonePage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

import java.util.List;

/**
 * MCphone 的 App 接口 —— 实现它并用 Java SPI 注册，你的 App 就会出现在手机里。
 *
 * 上手步骤（依赖怎么配、示例类、SPI 文件、语言与贴图）见 {@code docs/addon-api.md}。
 * 这里只写每个方法承诺什么。
 *
 * <b>本接口是客户端专用的</b>：{@link #renderIcon} 的签名里有 GuiGraphics，
 * 实现类只能在客户端加载。把它放进你自己的公共包、再被物品或网络包顺带引用到，
 * 专用服务器会启动即崩，而且崩溃信息不会提到你的 App。实现类请放在
 * {@code yourmod.client} 包下。
 *
 * 别继承 MCphone 内建的 PhoneApp 基类 —— 它把命名空间写死成 mcphone。
 */
public interface IPhoneApp {

    //  必须实现

    /**
     * App 唯一标识，形如 {@code mymod:calculator}，命名空间用你自己的 modid。
     *
     * 用 ResourceLocation 而不是字符串，是因为 App 目录是全服共用的一张表：
     * 两个模组各注册一个 "calculator"，后来的那个会被直接丢弃，而两边作者
     * 谁都没写错。带上命名空间，撞车在结构上就不可能。
     */
    ResourceLocation getId();

    /**
     * App 显示名称。
     * 推荐使用 Component.translatable("translation.key") 以支持多语言。
     * 如果不想用翻译系统，可用 Component.literal("计算器")。
     */
    Component getDisplayName();

    /** 图标纹理定位 */
    ResourceLocation getIconTexture();

    /** 用户点击 App 图标时触发。在此打开 GUI、执行业务逻辑。 */
    void onPress();

    //  可选覆盖

    /** 绘制图标。默认 blit 整个纹理。 */
    default void renderIcon(GuiGraphics g, int x, int y, int size, float partialTick) {
        ResourceLocation tex = getIconTexture();
        if (tex != null) {
            g.blit(tex, x, y, 0, 0, size, size, size, size);
        }
    }

    /**
     * 点开这个 App 时画在手机屏幕【里】的那一页。
     *
     * 返回一页 {@link IPhonePage}，就与内建的聊天、记事本、相册同等待遇：
     * 共用状态栏、导航栏、壁纸与返回键。返回 null（默认）则走
     * {@link #onPress()}，由你自己 setScreen 整个跳出手机。
     *
     * 只有界面在 120×200 里放不下时才该继续用 onPress —— 内建浏览器就是这样。
     * 其余情况都建议 openPage：跳出手机对玩家而言是"离开了手机"，而他明明
     * 只是点开了一个 App。
     *
     * @return 要显示的页面；null 表示走 {@link #onPress()}
     */
    default IPhonePage openPage() { return null; }

    /** App 被卸载时调用。在此清理持久化数据。 */
    default void onUninstall() {}

    /** 系统 App 不可被玩家卸载（如"设置"）。默认 false。 */
    default boolean isSystemApp() { return false; }

    /**
     * 这个 App 的硬前置模组 —— 缺了就整个 App 不可用。锦上添花的别写这儿。
     *
     * 声明之后三件事自动发生：{@link #isAvailable()} 按"是不是全装了"回答；
     * App 出现在商店的「联动 App」页并写明缺谁；「设置 → 关于」的联动模组
     * 列表自动带上它们。第三条是这个方法存在的直接原因 —— 那份列表原先手写，
     * 加了浏览器 App 却忘了加 MCEF，玩家看不到自己缺什么，只会当 bug 报。
     *
     * {@snippet :
     * return List.of(new RequiredMod("waystones", "Waystones（传送石碑）"));
     * }
     *
     * 显示名写死，别在运行时查 —— 要显示它的时候那个模组多半没装。
     */
    default List<RequiredMod> requiredMods() { return List.of(); }

    /**
     * 这个 App 在当前环境下存不存在。返回 false 则连目录都不进 —— 主屏与
     * 应用商店里都不出现（商店里躺着一个点了会报错的东西比它不存在更糟）。
     *
     * 默认按 {@link #requiredMods()} 回答，绝大多数情况声明前置就够了；
     * 覆盖本方法反而会让「联动 App」页与真实可用性对不上。判断条件说不清时
     * （比如还要看对方版本）再覆盖。
     *
     * 判断放在这里而不是让你在外面自己挑时机登记：登记顺序、预装判定、
     * 与玩家历史选择的对账都发生在目录构建那一瞬间，绕过 SPI 会打乱主屏
     * 顺序或错过对账窗口。
     *
     * <b>只在目录构建时问一次</b>，之后不复查。别放会随时间变化的条件
     * （比如"玩家有没有某个物品"），那属于 {@link #onPress()}。
     */
    default boolean isAvailable() {
        for (RequiredMod required : requiredMods()) {
            if (!ModList.get().isLoaded(required.modId())) return false;
        }
        return true;
    }

    /**
     * 是否预装。决定该 App 首次被发现时的初始安装状态：
     *
     *   true  —— 直接出现在手机主屏（默认，与旧行为一致）
     *   false —— 不自动安装，出现在应用商店中等待玩家下载
     *
     * 只在该 App 首次进入目录时生效；此后以玩家的安装/卸载选择为准。
     */
    default boolean isPreinstalled() { return true; }

    /** App 版本号。默认 "1.0.0" */
    default String getVersion() { return "1.0.0"; }

    /** 作者名。默认空 */
    default String getAuthor() { return ""; }

    /** App 描述，在 App 详情页显示。默认空 */
    default String getDescription() { return ""; }
}
