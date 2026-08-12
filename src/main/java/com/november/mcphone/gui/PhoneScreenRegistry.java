package com.november.mcphone.gui;

import com.november.mcphone.api.IPhoneApp;
import com.november.mcphone.gui.app.*;

import java.util.*;

/**
 * 手机主屏幕 App 注册中心。
 *
 * App 来源有三：
 * 1. 内建 App —— registerDefaultApps() 硬编码注册
 * 2. 附属模组 —— SPI 通过 META-INF/services 自动发现
 * 3. 运行时 —— 其他模组代码调用 register()
 */
public final class PhoneScreenRegistry {

    private static final List<IPhoneApp> APPS = new ArrayList<>();
    private static boolean loaded = false;

    private PhoneScreenRegistry() {}

    // ================================================================
    //  注册
    // ================================================================

    /** 注册一个 App（任何模组可在任意时刻调用） */
    public static void register(IPhoneApp app) {
        if (app == null) throw new IllegalArgumentException("IPhoneApp 不能为 null");
        APPS.add(app);
    }

    // ================================================================
    //  查询
    // ================================================================

    public static List<IPhoneApp> getApps() {
        ensureLoaded();
        return Collections.unmodifiableList(APPS);
    }

    public static int getAppCount() { ensureLoaded(); return APPS.size(); }

    public static IPhoneApp getApp(int index) {
        ensureLoaded();
        return (index >= 0 && index < APPS.size()) ? APPS.get(index) : null;
    }

    // ================================================================
    //  延迟加载（客户端初始化时调用）
    // ================================================================

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;

        // 第一层: 内建 App
        registerBuiltinApps();

        // 第二层: SPI 自动发现附属模组 App
        registerSpiApps();
    }

    private static void registerBuiltinApps() {
        register(new SettingsApp());
        register(new MessagesApp());
        register(new ContactsApp());
        register(new CameraApp());
        register(new GalleryApp());
        register(new MusicApp());
    }

    /** 通过 Java SPI 扫描所有 IPhoneApp 实现（包括附属模组） */
    private static void registerSpiApps() {
        for (IPhoneApp app : ServiceLoader.load(IPhoneApp.class)) {
            register(app);
            MCphone.LOGGER.info("[MCphone] SPI 加载 App: {} ({})",
                    app.getDisplayName(), app.getClass().getName());
        }
    }
}
