package com.november.mcphone.gui.app;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import com.november.mcphone.MCphone;

/**
 * 手机 App 基类。
 *
 * 每个 App 是一个独立的类，放在 gui/app/ 包下。
 * 要开发新 App，只需：
 * 1. 继承此类，实现 getName / getIconTexture / onPress
 * 2. 在 {@link com.november.mcphone.gui.PhoneScreenRegistry} 中 register
 *
 * 图标贴图请放在：
 *   assets/mcphone/textures/gui/
 *   文件名：app_icon_<appid>.png
 *   尺寸：20 × 20，PNG-32 透明背景
 */
public abstract class PhoneApp {

    /** App 唯一标识，如 "settings", "messages" */
    private final String id;

    /** 显示名称，如 "设置", "消息" */
    private final String displayName;

    /**
     * @param id          唯一标识（英文小写+下划线）
     * @param displayName 显示名称（支持中文）
     */
    protected PhoneApp(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    public String getId() { return id; }

    public String getDisplayName() { return displayName; }

    // ================================================================
    //  图标 — 子类覆盖
    // ================================================================

    /**
     * App 图标的纹理定位。
     * 默认: mcphone:textures/gui/app_icon_{id}.png
     * 子类可覆盖以使用不同文件名。
     */
    public ResourceLocation getIconTexture() {
        return ResourceLocation.fromNamespaceAndPath(
                MCphone.MODID, "textures/gui/app_icon_" + id + ".png");
    }

    /**
     * 绘制图标。默认用 blit 从 getIconTexture 绘制一个方形纹理。
     * 子类可覆盖以自定义绘制（如代码绘制的纯色图标）。
     */
    public void renderIcon(GuiGraphics g, int x, int y, int size, float partialTick) {
        ResourceLocation tex = getIconTexture();
        if (tex != null) {
            g.blit(tex, x, y, 0, 0, size, size, size, size);
        }
    }

    // ================================================================
    //  交互 — 子类必须实现
    // ================================================================

    /** 点击 App 时触发 */
    public abstract void onPress();
}
