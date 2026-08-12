package com.november.mcphone.store;

import com.november.mcphone.api.IPhoneApp;
import com.november.mcphone.api.store.AppInfo;
import com.november.mcphone.api.store.IAppSource;
import com.november.mcphone.gui.PhoneScreenRegistry;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 本地来源 —— 列出目录中已发现但尚未安装的 App。
 *
 * 这些 App 的实现已经随模组加载进来了，"下载"只是把它们
 * 加进已安装集合而已，所以两个方法都同步地立即回调。
 *
 * App 出现在这里通常有两种原因：
 *   1. 声明了 isPreinstalled() == false，从一开始就不在主屏
 *   2. 被玩家卸载过
 */
public final class LocalAppSource implements IAppSource {

    public static final String ID = "local";

    @Override
    public String getId() { return ID; }

    @Override
    public Component getDisplayName() {
        return Component.translatable("mcphone.store.source.local");
    }

    @Override
    public void listAvailable(Consumer<List<AppInfo>> callback) {
        List<AppInfo> out = new ArrayList<>();
        for (IPhoneApp app : PhoneScreenRegistry.getAvailable()) {
            out.add(AppInfo.of(app, ID));
        }
        callback.accept(out);
    }

    @Override
    public void install(AppInfo info, Consumer<IPhoneApp> onSuccess, Consumer<Component> onError) {
        IPhoneApp app = PhoneScreenRegistry.getApp(info.id());
        if (app == null) {
            onError.accept(Component.translatable("mcphone.store.error.not_found", info.id()));
            return;
        }
        if (!PhoneScreenRegistry.install(info.id())) {
            onError.accept(Component.translatable("mcphone.store.error.install_failed", info.id()));
            return;
        }
        onSuccess.accept(app);
    }
}
