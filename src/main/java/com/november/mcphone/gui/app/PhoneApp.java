package com.november.mcphone.gui.app;

import com.november.mcphone.api.IPhoneApp;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import com.november.mcphone.MCphone;

/**
 * 手机内建 App 基类 —— 实现 IPhoneApp 接口。
 *
 * 仅用于 MCphone 内建 App。附属模组开发者请直接实现
 * {@link com.november.mcphone.api.IPhoneApp} 接口。
 */
public abstract class PhoneApp implements IPhoneApp {

    private final String id;
    private final String displayName;

    protected PhoneApp(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    @Override
    public String getId() { return id; }

    @Override
    public String getDisplayName() { return displayName; }

    /**
     * 内建 App 图标路径: mcphone:textures/gui/app_icon_{id}.png
     */
    @Override
    public ResourceLocation getIconTexture() {
        return ResourceLocation.fromNamespaceAndPath(
                MCphone.MODID, "textures/gui/app_icon_" + id + ".png");
    }

    @Override
    public boolean isSystemApp() { return true; }

    @Override
    public void renderIcon(GuiGraphics g, int x, int y, int size, float partialTick) {
        ResourceLocation tex = getIconTexture();
        if (tex != null) {
            g.blit(tex, x, y, 0, 0, size, size, size, size);
        }
    }

    @Override
    public abstract void onPress();
}
