package com.november.mcphone.core.script.client;

import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.core.script.client.tex.AppTextures;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 把一个脚本 App 装成手机认的 {@link IPhoneApp}（施工方案 §14.1）—— 与内建 App 同等待遇：
 * 进商店、能安装、上主屏、点开是手机屏幕里的一页。
 *
 * <p><b>不预装</b>：{@link #isPreinstalled()} 返回 false。玩家往目录里放一个文件就直接上主屏，
 * 那是替他做主；§14.1 的语义是"未安装"，先进商店。
 *
 * <p>名字、作者、简介都是包里来的不可信文本（§14.3）。长度与控制字符由 {@code Manifest} 在装包时
 * 把着，这里只管原样显示，不再渲染成别的东西。
 */
public final class ScriptAppAdapter implements IPhoneApp {

    private final ScriptApp app;

    public ScriptAppAdapter(ScriptApp app) {
        this.app = app;
        ClientPackageVersions.observe(app);
        ClientRevocations.register(app);
        ClientUpdates.register(app);
        ClientNetwork.register(app);
        ClientNotifications.register(app);
        ClientBackground.register(app);
    }

    /** 这个适配器背后的那个包，覆盖安装与卸载要用。 */
    public ScriptApp script() {
        return app;
    }

    @Override
    public ResourceLocation getId() {
        return app.id();
    }

    @Override
    public Component getDisplayName() {
        return Component.literal(app.manifest().name());
    }

    /** 图标走 {@code AppTextures} 的图标表；给不出就是 null，商店与主屏画占位方块。 */
    @Override
    public ResourceLocation getIconTexture() {
        return AppTextures.icon(app.id().toString(), app.icon());
    }

    /** 覆盖了 {@link #openPage()} 之后这条走不到，接口要求实现它。 */
    @Override
    public void onPress() {
    }

    @Override
    public IPhonePage openPage() {
        if(ClientServerUpdates.required(app))return new ServerUpdatePage(app);
        return new ScriptPage(app);
    }

    @Override
    public boolean isPreinstalled() {
        return false;
    }

    @Override
    public String getVersion() {
        return app.manifest().version();
    }
    @Override public int getBadgeCount(){return ClientNotifications.badge(app.id().toString());}

    @Override
    public String getAuthor() {
        return app.manifest().author();
    }

    @Override
    public String getDescription() {
        String d = app.manifest().description();
        return d == null ? "" : d;
    }

    /** 卸载：图标与包内贴图都还回去，下次装回来重新读。 */
    @Override
    public void onUninstall() {
        AppTextures.releaseIcon(app.id().toString());
        AppTextures.release(app.pkg());
    }
}
