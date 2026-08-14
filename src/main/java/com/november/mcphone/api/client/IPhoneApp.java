package com.november.mcphone.api.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

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
 *                   "mymod", "textures/gui/app_icon_calculator.png");
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
 *   贴图: assets/mymod/textures/gui/app_icon_calculator.png
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
