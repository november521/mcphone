package com.november.mcphone.feature.store.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.store.AppInfo;
import com.november.mcphone.api.client.store.IAppSource;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.core.script.client.ScriptAppAdapter;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 本地来源：列出已发现但尚未安装的 App。实现已随模组加载进来，
 * 所以两个方法都同步地立即回调。
 */
public final class LocalAppSource implements IAppSource {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "local");

    @Override
    public ResourceLocation getId() { return ID; }

    @Override
    public Component getDisplayName() {
        return Component.translatable("mcphone.store.source.local");
    }

    @Override
    public void listAvailable(Consumer<List<AppInfo>> callback) {
        List<AppInfo> out = new ArrayList<>();
        for (IPhoneApp app : PhoneScreenRegistry.getAvailable()) {
            // 脚本重装必须继续走脚本来源的签名与 SDK 检查，不能转入无校验的 Java App 来源。
            if (app instanceof ScriptAppAdapter) continue;
            out.add(AppInfo.of(app, ID));
        }
        callback.accept(out);
    }

    @Override
    public void install(AppInfo info, Consumer<IPhoneApp> onSuccess, Consumer<Component> onError) {
        IPhoneApp app = PhoneScreenRegistry.getApp(info.id());
        if (app instanceof ScriptAppAdapter) {
            onError.accept(Component.translatable("mcphone.store.error.install_failed", info.id().toString()));
            return;
        }
        if (app == null) {
            onError.accept(Component.translatable("mcphone.store.error.not_found", info.id().toString()));
            return;
        }
        if (!PhoneScreenRegistry.install(info.id())) {
            onError.accept(Component.translatable("mcphone.store.error.install_failed", info.id().toString()));
            return;
        }
        onSuccess.accept(app);
    }
}
