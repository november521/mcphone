package com.november.mcphone.api;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * MCphone App API —— 附属模组开发接口。
 *
 * ================================================================
 * 【附属模组开发者指南】
 * ================================================================
 *
 * 要让你的 App 出现在 MCphone 手机主屏幕上，只需：
 *
 * 1. 在你的 mod 的 build.gradle 中添加：
 *    dependencies {
 *        implementation "com.november:mcphone:1.0.0"
 *    }
 *
 * 2. 创建一个类实现 IPhoneApp：
 *
 *    public final class MyApp implements IPhoneApp {
 *        @Override
 *        public String getId() { return "myapp"; }
 *
 *        @Override
 *        public String getDisplayName() { return "我的App"; }
 *
 *        @Override
 *        public ResourceLocation getIconTexture() {
 *            return ResourceLocation.fromNamespaceAndPath("mymod", "textures/gui/app_icon_myapp.png");
 *        }
 *
 *        @Override
 *        public void onPress() {
 *            Minecraft.getInstance().player.displayClientMessage(
 *                Component.literal("Hello!"), false);
 *        }
 *    }
 *
 * 3. 在 src/main/resources/META-INF/services/ 下创建文件：
 *    文件名: com.november.mcphone.api.IPhoneApp
 *    内容: com.yourmod.MyApp
 *
 * 4. 贴图放在: assets/<你的modid>/textures/gui/app_icon_myapp.png (20×20, PNG)
 *
 * 就这么简单！MCphone 会在手机打开时自动发现并加载你的 App。
 *
 * ================================================================
 */
public interface IPhoneApp {

    /** App 唯一标识（英文小写+下划线），如 "my_calculator" */
    String getId();

    /** 显示名称，如 "计算器" */
    String getDisplayName();

    /**
     * 图标纹理路径。
     * 默认实现: mcphone:textures/gui/app_icon_{id}.png
     * 附属模组应覆盖以使用自己的 modid，如:
     * return ResourceLocation.fromNamespaceAndPath("mymod", "textures/gui/app_icon_myapp.png");
     */
    ResourceLocation getIconTexture();

    /** 绘制图标，默认实现会从 getIconTexture blit */
    default void renderIcon(GuiGraphics g, int x, int y, int size, float partialTick) {
        ResourceLocation tex = getIconTexture();
        if (tex != null) {
            g.blit(tex, x, y, 0, 0, size, size, size, size);
        }
    }

    /** 点击 App 时触发 */
    void onPress();
}
