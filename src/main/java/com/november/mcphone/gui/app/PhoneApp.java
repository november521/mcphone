package com.november.mcphone.gui.app;

import com.november.mcphone.api.IPhoneApp;
import com.november.mcphone.MCphone;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 手机内建 App 基类 —— 实现 IPhoneApp 接口。
 *
 * 仅用于 MCphone 内建 App。附属模组开发者请直接实现
 * {@link com.november.mcphone.api.IPhoneApp} 接口。
 *
 * 每个内建 App 只需提供 id，名称自动从翻译键获取：
 *   translation key: mcphone.app.<id>
 *
 * 翻译文件位置：
 *   assets/mcphone/lang/en_us.json  — 英文
 *   assets/mcphone/lang/zh_cn.json  — 中文
 */
public abstract class PhoneApp implements IPhoneApp {

    private final String id;

    /**
     * @param id App 唯一标识。名称从 mcphone.app.<id> 翻译键获取。
     */
    protected PhoneApp(String id) {
        this.id = id;
    }

    @Override
    public final String getId() { return id; }

    /**
     * 从语言文件获取显示名称。
     * 翻译键: mcphone.app.<id>
     * 如 id="settings" → 查找 mcphone.app.settings
     */
    @Override
    public Component getDisplayName() {
        return Component.translatable("mcphone.app." + id);
    }

    /**
     * 内建 App 图标路径: mcphone:textures/gui/app_icon_{id}.png
     * 贴图放在: assets/mcphone/textures/gui/app_icon_{id}.png (20×20, PNG)
     */
    @Override
    public ResourceLocation getIconTexture() {
        return ResourceLocation.fromNamespaceAndPath(
                MCphone.MODID, "textures/gui/app_icon_" + id + ".png");
    }

    // isSystemApp() 不在此覆盖，沿用 IPhoneApp 的默认值 false：
    // 内建 App 默认允许玩家卸载，确需常驻的由具体 App 自行覆盖为 true
    // （目前只有 SettingsApp——它是进入 App 管理器的唯一入口）

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
