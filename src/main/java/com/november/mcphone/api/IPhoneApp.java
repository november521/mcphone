package com.november.mcphone.api;

import net.minecraft.client.gui.GuiGraphics;
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
 * 第一步：在你的 build.gradle 添加依赖
 * ================================================================
 *
 *   dependencies {
 *       implementation fg.deobf("com.november:mcphone:1.0.0")
 *   }
 *
 * ================================================================
 * 第二步：实现 IPhoneApp
 * ================================================================
 *
 *   public final class CalculatorApp implements IPhoneApp {
 *
 *       @Override
 *       public String getId() { return "calculator"; }
 *
 *       @Override
 *       public String getDisplayName() { return "计算器"; }
 *
 *       @Override
 *       public ResourceLocation getIconTexture() {
 *           return ResourceLocation.fromNamespaceAndPath("mymod", "textures/gui/calculator.png");
 *       }
 *
 *       @Override
 *       public void onPress() {
 *           Minecraft.getInstance().setScreen(new CalculatorScreen());
 *       }
 *   }
 *
 * ================================================================
 * 第三步：注册（SPI 自动发现）
 * ================================================================
 *
 * 在 src/main/resources/META-INF/services/ 创建文件：
 *
 *   文件名: com.november.mcphone.api.IPhoneApp
 *   内容:   com.yourmod.CalculatorApp
 *
 * 如果有多个 App，一行一个类名。
 *
 * ================================================================
 * 第四步：贴图
 * ================================================================
 *
 *   位置: assets/<你的modid>/textures/gui/app_icon_xxx.png
 *   尺寸: 20 × 20 px，PNG-32
 *
 * ================================================================
 * 额外能力
 * ================================================================
 *
 * - {@link #onUninstall} — App 被卸载时清理数据
 * - {@link #isSystemApp}  — 标记为系统 App（不可卸载）
 * - {@link #getVersion}   — App 版本号
 * - {@link #getAuthor}    — 作者信息
 */
public interface IPhoneApp {

    // ================================================================
    //  必须实现
    // ================================================================

    /** App 唯一标识符，如 "calculator", "weather"。全小写英文+下划线。 */
    String getId();

    /** App 显示名称，如 "计算器", "天气" */
    String getDisplayName();

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

    /**
     * App 被卸载时调用。在此清理持久化数据。
     * 默认空实现。
     */
    default void onUninstall() {}

    /**
     * 系统 App 不可被玩家卸载（如"设置"）。
     * 默认 false。
     */
    default boolean isSystemApp() { return false; }

    /** App 版本号。默认 "1.0.0" */
    default String getVersion() { return "1.0.0"; }

    /** 作者名。默认空 */
    default String getAuthor() { return ""; }

    /** App 描述，在 App 详情页显示。默认空 */
    default String getDescription() { return ""; }
}
