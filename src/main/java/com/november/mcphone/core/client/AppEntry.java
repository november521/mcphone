package com.november.mcphone.core.client;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 手机主屏幕上的一个 App 入口。
 *
 * 每个 App 由以下三要素定义：
 * - 显示名称
 * - 图标（通过 {@link #renderIcon} 自行绘制）
 * - 点击行为（通过 {@link #onPress} 回调定义）
 *
 * 未来要给手机添加新 App 时，只需在
 * {@link PhoneScreenRegistry#registerDefaultApps} 中新增一条即可，
 * 无需修改 PhoneScreen 本身。
 */
public abstract class AppEntry {

    private final String name;

    protected AppEntry(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    /**
     * 在指定位置绘制 App 图标。
     * 子类必须实现此方法来决定图标的绘制方式。
     */
    public abstract void renderIcon(GuiGraphics guiGraphics, int x, int y, int size, float partialTick);

    /** 点击 App 时触发 */
    public abstract void onPress();

    /**
     * 通用 App：通过回调函数自定义图标绘制和点击行为。
     * 适用于不需要特殊逻辑的简单 App。
     */
    public static final class GenericApp extends AppEntry {
        private final IconRenderer iconRenderer;
        private final Runnable onPress;

        public GenericApp(String name, IconRenderer iconRenderer, Runnable onPress) {
            super(name);
            this.iconRenderer = iconRenderer;
            this.onPress = onPress;
        }

        @Override
        public void renderIcon(GuiGraphics guiGraphics, int x, int y, int size, float partialTick) {
            iconRenderer.render(guiGraphics, x, y, size, partialTick);
        }

        @Override
        public void onPress() {
            onPress.run();
        }

        @FunctionalInterface
        public interface IconRenderer {
            void render(GuiGraphics guiGraphics, int x, int y, int size, float partialTick);
        }
    }

    /**
     * 带图标 ResourceLocation 的 App：自动从指定纹理绘图标。
     * 适用于有一个固定贴图的简单 App。
     */
    public static final class IconApp extends AppEntry {
        private final net.minecraft.resources.ResourceLocation iconTexture;
        private final Runnable onPress;

        public IconApp(String name, net.minecraft.resources.ResourceLocation iconTexture, Runnable onPress) {
            super(name);
            this.iconTexture = iconTexture;
            this.onPress = onPress;
        }

        @Override
        public void renderIcon(GuiGraphics guiGraphics, int x, int y, int size, float partialTick) {
            GuiUtil.drawTexture(guiGraphics, iconTexture,
                    x, y,          // 屏幕坐标
                    size, size,    // 绘制宽高
                    size, size     // 纹理总宽高 (1:1 方形纹理)
            );
        }

        @Override
        public void onPress() {
            onPress.run();
        }
    }
}
