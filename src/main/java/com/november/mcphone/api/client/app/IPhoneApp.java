package com.november.mcphone.api.client.app;

import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.core.client.GuiUtil;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * MCphone 的 App 接口——实现它并用 Java SPI 注册，你的 App 就会出现在手机里。上手步骤见 {@code docs/addon-api.md}。
 *
 * <b>本接口是客户端专用的</b>：{@link #renderIcon} 的签名里有 GuiGraphics，实现类只能在客户端加载，
 * 被物品或网络包顺带引用到会让专用服务器启动即崩。实现类请放在 {@code yourmod.client} 包下。
 * 别继承 MCphone 内建的 PhoneApp 基类——它把命名空间写死成 mcphone。
 */
public interface IPhoneApp {

    /** App 唯一标识，形如 {@code mymod:calculator}，命名空间用你自己的 modid。id 撞车时后登记的会被直接丢弃。 */
    ResourceLocation getId();

    /** App 显示名称。推荐 Component.translatable 以支持多语言。 */
    Component getDisplayName();

    /** 图标纹理定位 */
    ResourceLocation getIconTexture();

    /** 用户点击 App 图标时触发。在此打开 GUI、执行业务逻辑。 */
    void onPress();

    /** 绘制图标。默认整张纹理拉伸到 size×size，带抗锯齿的图请照此走——直接调 g.blit 会丢掉半透明。 */
    default void renderIcon(GuiGraphics g, int x, int y, int size, float partialTick) {
        ResourceLocation tex = getIconTexture();
        if (tex != null) {
            GuiUtil.drawTexture(g, tex, x, y, size, size, size, size);
        }
    }

    /**
     * 点开这个 App 时画在手机屏幕【里】的那一页，与内建 App 同等待遇：共用状态栏、导航栏、壁纸与返回键。
     * 返回 null（默认）则走 {@link #onPress()}，由你自己 setScreen 整个跳出手机——
     * 只有界面在 120×200 里放不下时才该这么做。
     *
     * @return 要显示的页面；null 表示走 {@link #onPress()}
     */
    default IPhonePage openPage() { return null; }

    /**
     * 主屏图标右上角的角标数，0（默认）表示不画。像手机上的未读红点：有几条没看的就显几。
     *
     * <b>每帧每个图标问一次</b>，只能读现成的值——别在这里查数据库、发网络包或做任何要等的事。
     * 数据要现拉的话，自己按时间间隔限流（内建的美西螈 App 就是这么做的）。
     * 大于 99 显示成 99+。
     */
    default int getBadgeCount() { return 0; }

    /** App 被卸载时调用。在此清理持久化数据。 */
    default void onUninstall() {}

    /** 系统 App 不可被玩家卸载（如"设置"）。默认 false。 */
    default boolean isSystemApp() { return false; }

    /**
     * 这个 App 的硬前置模组——缺了就整个 App 不可用。声明后 {@link #isAvailable()}、商店的「联动 App」页、
     * 「设置 → 关于」的联动模组列表都自动生效。
     *
     * 内建的 App 现在一律【不】用这个，改用 {@link #companionMods()}：MCphone 自己一个前置
     * 都没有，缺了谁都照常开机，只是少那一个 App。这里留着是给附属模组用的——你的 App 真要
     * 是"没有对方连加载都不该加载"，声明它比自己判省事。
     *
     * displayName 要写死，别在运行时查——要显示它的时候那个模组多半没装。
     */
    default List<RequiredMod> requiredMods() { return List.of(); }

    /**
     * 这个 App 沾了哪个模组的光。
     *
     * 两种都算联动，声明方式一样
     *
     *   装了多一块内容：「阅读」装了 Patchouli 多几十本手册，装了沉浸工程多一本
     *   工程师手册，两者互不依赖，缺一个另一个照样有书可看。
     *
     *   没装就没内容可给：「任务书」缺了 FTB Quests 就没有内容，这一格不该出现。
     *
     * 后者听着像前置，但它不是：缺的是【这一个 App】，不是 MCphone 跑不起来。
     * MCphone 自己一个前置都没有，装不装别的模组只是功能多少的分别。
     *
     * ⚠ 属于后一种时，必须自己覆盖 {@link #isAvailable()}
     *
     * 联动声明【不】参与默认的可用性判断（那个默认实现只看 {@link #requiredMods()}），
     * 照默认走就是"永远可用"，于是对方没装时主屏上会多一个点了没反应的图标。
     * 「任务书」与「阅读」都是自己判的，可以照抄。
     *
     * 声明之后玩家在哪儿看得到
     *
     *   「设置 → 关于」的联动模组列表：一律列出，告诉玩家装没装。
     *   商店的「联动 App」页：只在你的 App 当前【不可用】时收它，写明缺哪个模组。
     *
     * 后面这一条 1.8.12 之前不成立——那一页当时只认 {@link #requiredMods()}，于是一个
     * "靠某个模组撑着、但声明成联动"的 App 在对方没装时会从玩家眼前彻底消失：主屏没有、
     * 商店没有、那一页也没有。而那一页存在的全部理由就是回答"我怎么没有这个"。
     *
     * 反过来，你的 App 当前可用时那一页不收它——它没有被任何模组卡住，列出来只是噪声。
     *
     * displayName 与前置那边同一条规矩：写死，别在运行时查——要显示它的时候
     * 那个模组多半没装。
     */
    default List<RequiredMod> companionMods() { return List.of(); }

    /**
     * 这个 App 在当前环境下存不存在。返回 false 则主屏与商店里都不出现。
     * 默认按 {@link #requiredMods()} 回答，绝大多数情况声明前置就够了。
     *
     * <b>只在目录构建时问一次</b>，之后不复查。别放会随时间变化的条件，那属于 {@link #onPress()}。
     */
    default boolean isAvailable() {
        for (RequiredMod required : requiredMods()) {
            if (!ModList.get().isLoaded(required.modId())) return false;
        }
        return true;
    }

    /**
     * 是否预装：true（默认）首次被发现即上主屏；false 进应用商店等玩家下载。
     * 只在该 App 首次进入目录时生效，此后以玩家的安装/卸载选择为准。
     */
    default boolean isPreinstalled() { return true; }

    /** App 版本号。默认 "1.0.0" */
    default String getVersion() { return "1.0.0"; }

    /** 作者名。默认空 */
    default String getAuthor() { return ""; }

    /** App 描述，在 App 详情页显示。默认空 */
    default String getDescription() { return ""; }
}
