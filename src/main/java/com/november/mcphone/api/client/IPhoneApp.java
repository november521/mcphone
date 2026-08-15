package com.november.mcphone.api.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

import java.util.List;

/**
 * MCphone App API —— 开放的 App 接口。
 *
 * ================================================================
 * 【附属模组开发者指南 —— 像安装手机 App 一样开发】
 * ================================================================
 *
 * 你的模组只需要依赖 MCphone，实现这个接口，然后通过
 * Java SPI（META-INF/services）注册。MCphone 会自动发现并加载。
 *
 * ================================================================
 * 动手之前：这个接口是【客户端专用】的
 * ================================================================
 *
 * 本接口的 renderIcon 签名里有 GuiGraphics，实现类只能在客户端加载。
 * 把它放进你自己的公共包、再被物品或网络包顺带引用到，专用服务器会
 * 启动即崩，而且崩溃信息不会提到你的 App。详见本包的 package-info。
 *
 * 建议：实现类放 yourmod.client 包下，只由客户端代码碰它。
 *
 * ================================================================
 * 第一步：在你的 build.gradle 添加依赖
 * ================================================================
 *
 * MCphone 是 NeoForge 模组，用 ModDevGradle（不是 ForgeGradle，
 * 没有 fg.deobf 这种东西）：
 *
 *   dependencies {
 *       // 坐标 = mod_group_id : mod_id : 版本
 *       compileOnly "com.november.mcphone:mcphone:$版本"
 *       // 想在开发环境里真跑起来，再加一行
 *       localRuntime "com.november.mcphone:mcphone:$版本"
 *   }
 *
 * 版本填你要对接的那一版，别照抄这里的占位符。MCphone 目前没有公开
 * maven，最省事的办法是把 jar 丢进你项目的 libs/ 然后：
 *
 *   dependencies {
 *       compileOnly files("libs/mcphone-$版本.jar")
 *   }
 *
 * 再在你的 neoforge.mods.toml 里声明依赖，让加载顺序正确：
 *
 *   [[dependencies.yourmod]]
 *       modId="mcphone"
 *       type="required"        # 可选依赖写 "optional"
 *       versionRange="[1.0.47,)"
 *       ordering="AFTER"       # 必须 AFTER：你要用的注册表得先就位
 *       side="BOTH"
 *
 * ================================================================
 * 第二步：实现 IPhoneApp
 * ================================================================
 *
 *   public final class CalculatorApp implements IPhoneApp {
 *
 *       @Override
 *       public ResourceLocation getId() {
 *           return ResourceLocation.fromNamespaceAndPath("mymod", "calculator");
 *       }
 *
 *       @Override
 *       public Component getDisplayName() {
 *           return Component.translatable("mymod.app.calculator");
 *       }
 *
 *       @Override
 *       public ResourceLocation getIconTexture() {
 *           return ResourceLocation.fromNamespaceAndPath(
 *                   "mymod", "textures/app/calculator.png");
 *       }
 *
 *       @Override
 *       public void onPress() {
 *           Minecraft.getInstance().setScreen(new CalculatorScreen());
 *       }
 *   }
 *
 * 别继承 MCphone 内建的 PhoneApp 基类——它把命名空间写死成 mcphone，
 * 是给内建 App 用的。直接实现本接口。
 *
 * ================================================================
 * 第三步：注册（SPI 自动发现）
 * ================================================================
 *
 * 在 src/main/resources/META-INF/services/ 创建文件：
 *
 *   文件名: com.november.mcphone.api.client.IPhoneApp
 *   内容:   com.yourmod.CalculatorApp
 *
 * 如果有多个 App，一行一个类名。
 *
 * ================================================================
 * 第四步：语言文件 + 贴图
 * ================================================================
 *
 *   语言: assets/mymod/lang/en_us.json
 *         { "mymod.app.calculator": "Calculator" }
 *         中文另开一份 zh_cn.json，两边键要对齐
 *
 *   贴图: assets/mymod/textures/app/calculator.png
 *         20×20、PNG-32。路径要和 getIconTexture() 返回的一致。
 *         不放贴图也能跑：renderIcon 的默认实现在贴图缺失时由原版画成
 *         紫黑格，不会崩，但玩家会看见紫黑格，所以还是放一张。
 *
 * ================================================================
 * 还有什么可用
 * ================================================================
 *
 * 应用商店来源  {@link com.november.mcphone.api.client.store.IAppSource}
 *               让你的 App 从别处（远程仓库、数据包）进入商店列表
 *
 * 代价          {@link com.november.mcphone.api.cost.ICost}
 *               让某个操作要求玩家先付出点什么。这个是两端安全的，
 *               服务端代码可以放心引用
 *
 * 条件登记      {@link #isAvailable()}
 *               你的 App 依赖另一个可选模组时覆盖它。对方没装，你的 App
 *               就不进目录，主屏与应用商店里都不会出现
 *
 * ================================================================
 */
public interface IPhoneApp {

    // ================================================================
    //  必须实现
    // ================================================================

    /**
     * App 唯一标识，形如 {@code mymod:calculator}。
     *
     * ================================================================
     * 为什么是 ResourceLocation 而不是一个字符串
     * ================================================================
     *
     * 因为 App 目录是全服共用的一张表，而"计算器"这个名字谁都想用。
     * 光靠字符串的话，两个模组各自注册一个 "calculator"，后注册的那个
     * 会被目录直接丢弃——玩家只会看到自己装的某个模组"App 没出现"，
     * 而两边作者谁都没写错。
     *
     * 带上命名空间之后，{@code amod:calculator} 与 {@code bmod:calculator}
     * 天然是两个东西，撞车在结构上就不可能发生。这也是 Minecraft 里所有
     * 注册表的一贯做法。
     *
     * 命名空间请用你自己的 modid。
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

    // ================================================================
    //  可选覆盖
    // ================================================================

    /** 绘制图标。默认 blit 整个纹理。 */
    default void renderIcon(GuiGraphics g, int x, int y, int size, float partialTick) {
        ResourceLocation tex = getIconTexture();
        if (tex != null) {
            g.blit(tex, x, y, 0, 0, size, size, size, size);
        }
    }

    /** App 被卸载时调用。在此清理持久化数据。 */
    default void onUninstall() {}

    /** 系统 App 不可被玩家卸载（如"设置"）。默认 false。 */
    default boolean isSystemApp() { return false; }

    /**
     * 这个 App 需要哪些外部模组才能用。默认不需要任何。
     *
     * ================================================================
     * 声明了它，三件事自动发生
     * ================================================================
     *
     * 一、{@link #isAvailable()} 默认就按"是不是全装了"回答，你不用自己写。
     * 二、你的 App 会出现在应用商店的「联动 App」那一页，缺哪个模组写得
     *     明明白白——没装的时候也在，玩家才知道装了能多个什么。
     * 三、手机里「设置 → 关于」的联动模组列表会自动带上它们。
     *
     * 第三条是这个方法存在的直接原因：那份列表原来是手写的，加了浏览器
     * App 却忘了往里加 MCEF，于是玩家看不到自己缺什么，只会当成 bug 来报。
     * 手写的清单迟早会漏，所以改成从这里汇总。
     *
     * ================================================================
     * 例子
     * ================================================================
     *
     * {@snippet :
     * @Override
     * public List<RequiredMod> requiredMods() {
     *     return List.of(new RequiredMod("waystones", "Waystones（传送石碑）"));
     * }
     * }
     *
     * 显示名自己写死，别在运行时查——要显示它的时候那个模组多半没装，
     * 理由见 {@link RequiredMod}。
     *
     * ================================================================
     * 什么时候【不】该用它
     * ================================================================
     *
     * 你的 App 对某个模组是"锦上添花"而非"没它就不能用"时，别写在这里。
     * 这里列的是硬前置：缺了就整个 App 不可用。可选的增强属于运行时判断。
     */
    default List<RequiredMod> requiredMods() { return List.of(); }

    /**
     * 这个 App 在当前环境下存不存在。返回 false 则连目录都不进。
     *
     * 默认按 {@link #requiredMods()} 回答：声明的模组全装了才算可用。绝大多数
     * 情况下声明前置就够了，不必再覆盖本方法——覆盖了反而会让「联动 App」页
     * 与真实可用性对不上。判断条件确实说不清时（比如还要看对方的版本）再覆盖。
     *
     * ================================================================
     * 什么时候需要它
     * ================================================================
     *
     * 你的 App 依赖【另一个模组】，而那个模组是可选的。比如 MCphone 自带的
     * 「传送石」App 要用传送石碑（Waystones）的选点界面：
     *
     *   {@code @Override public boolean isAvailable() {
     *       return ModList.get().isLoaded("waystones");
     *   }}
     *
     * 没装 Waystones 时，这个 App 不该出现在主屏，**也不该出现在应用商店**
     * ——商店里躺着一个点了会报错的东西，比它压根不存在更糟。
     *
     * ================================================================
     * 为什么不让你自己在外面判断
     * ================================================================
     *
     * 因为你判断不了"该在什么时候登记"。App 走 SPI 自动发现，登记顺序、
     * 预装判定（{@link #isPreinstalled()}）、与玩家历史选择的对账都发生在
     * 目录构建的那一瞬间。绕过 SPI 改成自己挑时机调 register()，要么排在
     * 目录最前面打乱主屏顺序，要么错过对账窗口、预装设置直接失效。
     *
     * 所以判断放在这里：你照常走 SPI 登记，只是多回答一句"我这次算不算数"。
     *
     * ================================================================
     * 注意
     * ================================================================
     *
     * 只在目录构建时问一次，之后不再复查——模组列表在游戏运行期间不会变，
     * 反复查没有意义。别在这里放会随时间变化的条件（比如"玩家有没有某个
     * 物品"），那种判断属于 {@link #onPress()}。
     *
     * 从"可用"变成"不可用"（玩家卸了依赖的模组）时，该 App 会安静地从目录
     * 消失，玩家的安装记录也随之作废；重新装回那个模组，它按首次出现处理。
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
