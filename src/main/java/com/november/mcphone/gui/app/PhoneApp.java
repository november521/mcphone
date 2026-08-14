package com.november.mcphone.gui.app;

import com.november.mcphone.api.client.IPhoneApp;
import com.november.mcphone.MCphone;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 手机内建 App 基类 —— 实现 IPhoneApp 接口。
 *
 * 仅用于 MCphone 内建 App。附属模组开发者请直接实现
 * {@link com.november.mcphone.api.client.IPhoneApp} 接口。
 *
 * 每个内建 App 只需提供 id，名称自动从翻译键获取：
 *   translation key: mcphone.app.<id>
 *
 * 翻译文件位置：
 *   assets/mcphone/lang/en_us.json  — 英文
 *   assets/mcphone/lang/zh_cn.json  — 中文
 */
public abstract class PhoneApp implements IPhoneApp {

    /** 命名空间内的短名，如 "camera"。翻译键与贴图名都由它拼出来 */
    private final String path;

    /** 完整 id，固定是 mcphone:&lt;path&gt; */
    private final ResourceLocation id;

    /**
     * @param path App 短名。内建 App 的命名空间恒为 mcphone，所以这里只给
     *             短名即可；附属模组请直接实现 IPhoneApp 并给出自己的
     *             命名空间，不要继承本类。
     */
    protected PhoneApp(String path) {
        this.path = path;
        this.id = ResourceLocation.fromNamespaceAndPath(MCphone.MODID, path);
    }

    @Override
    public final ResourceLocation getId() { return id; }

    /**
     * 从语言文件获取显示名称。
     * 翻译键: mcphone.app.&lt;path&gt;
     * 如 path="settings" → 查找 mcphone.app.settings
     *
     * 这里拼的是 path 不是 id：id 现在带命名空间，直接拼会得到
     * mcphone.app.mcphone:settings 这种查不到的键，而查不到的翻译键
     * 不会报错，只会在界面上显示成原样，很难发现。
     */
    @Override
    public Component getDisplayName() {
        return Component.translatable("mcphone.app." + path);
    }

    /**
     * 内建 App 图标路径: mcphone:textures/gui/app_icon_{path}.png
     * 贴图放在: assets/mcphone/textures/gui/app_icon_{path}.png (20×20, PNG)
     *
     * 同样只能用 path：ResourceLocation 的路径段不允许出现冒号，
     * 拼 id 会直接抛异常。
     */
    @Override
    public ResourceLocation getIconTexture() {
        return ResourceLocation.fromNamespaceAndPath(
                MCphone.MODID, "textures/gui/app_icon_" + path + ".png");
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

    /**
     * 应用详情页里的简介。翻译键: mcphone.app.&lt;path&gt;.desc
     *
     * 先查键在不在，不在就返回空串——查不到的翻译键不会报错，只会把键本身
     * 原样画出来。详情页上出现一行 "mcphone.app.music.desc" 比什么都不写
     * 难看得多，而且玩家还以为是坏了。
     *
     * 用 I18n 而不是 Component.translatable：本方法返回 String（IPhoneApp
     * 就是这么定的），而且只有它能问"这个键存在吗"。I18n 是客户端类，本类
     * 在 gui 包下，本来就只在客户端加载。
     */
    @Override
    public String getDescription() {
        String key = "mcphone.app." + path + ".desc";
        return I18n.exists(key) ? I18n.get(key) : "";
    }

    @Override
    public abstract void onPress();
}
