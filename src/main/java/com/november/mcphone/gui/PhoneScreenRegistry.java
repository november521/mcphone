package com.november.mcphone.gui;

import com.november.mcphone.MCphone;
import com.november.mcphone.api.IPhoneApp;

import java.util.*;

/**
 * 手机主屏幕 App 管理器 —— 模拟手机操作系统的 App 安装/卸载。
 *
 * ================================================================
 * 开放环境设计
 * ================================================================
 *
 * App 来源：
 * 1. 内建系统App（不可卸载）  — registerBuiltinApps()
 * 2. SPI 自动发现              — ServiceLoader 扫描所有模组
 * 3. 运行时动态注册            — 任何代码调用 install()
 *
 * 支持的操作：
 * - install(IPhoneApp)        安装一个 App
 * - uninstall(String id)      卸载一个 App（系统App除外）
 * - isInstalled(String id)    查询是否已安装
 * - getApps()                 获取当前已安装 App 列表
 *
 * 这模仿了真实手机操作系统的 App 管理机制——
 * 系统App预装，第三方App通过SPI自动发现，
 * 玩家/管理员可以动态安装卸载。
 */
public final class PhoneScreenRegistry {

    /** 使用 LinkedHashMap 保持插入顺序 + O(1) 查找 */
    private static final Map<String, IPhoneApp> APP_MAP = new LinkedHashMap<>();
    private static boolean loaded = false;

    private PhoneScreenRegistry() {}

    // ================================================================
    //  安装 / 卸载
    // ================================================================

    /**
     * 安装一个 App。如果同 ID 已存在，旧 App 的 onUninstall 会被调用。
     * @return true 表示安装成功
     */
    public static boolean install(IPhoneApp app) {
        if (app == null || app.getId() == null || app.getId().isEmpty()) {
            MCphone.LOGGER.warn("[MCphone] App 安装失败: id 为空");
            return false;
        }

        IPhoneApp old = APP_MAP.get(app.getId());
        if (old != null) {
            old.onUninstall();
            MCphone.LOGGER.info("[MCphone] 覆盖安装 App: {} (旧版本: {}, 新版本: {})",
                    app.getId(), old.getVersion(), app.getVersion());
        }

        APP_MAP.put(app.getId(), app);
        MCphone.LOGGER.info("[MCphone] App 已安装: {} v{} by {}",
                app.getDisplayName(), app.getVersion(), app.getAuthor());
        return true;
    }

    /**
     * 卸载一个 App。系统 App 不可卸载。
     * @return true 表示卸载成功
     */
    public static boolean uninstall(String id) {
        if (id == null || id.isEmpty()) return false;

        IPhoneApp app = APP_MAP.get(id);
        if (app == null) {
            MCphone.LOGGER.warn("[MCphone] 卸载失败: App '{}' 未安装", id);
            return false;
        }
        if (app.isSystemApp()) {
            MCphone.LOGGER.warn("[MCphone] 卸载失败: '{}' 是系统App，不可卸载", id);
            return false;
        }

        app.onUninstall();
        APP_MAP.remove(id);
        MCphone.LOGGER.info("[MCphone] App 已卸载: {}", app.getDisplayName());
        return true;
    }

    // ================================================================
    //  查询
    // ================================================================

    /** 获取当前所有已安装 App（只读列表，保持注册顺序） */
    public static List<IPhoneApp> getApps() {
        ensureLoaded();
        return List.copyOf(APP_MAP.values());
    }

    /** 根据 ID 查找 App */
    public static IPhoneApp getApp(String id) {
        ensureLoaded();
        return APP_MAP.get(id);
    }

    /** 根据索引查找 App */
    public static IPhoneApp getApp(int index) {
        ensureLoaded();
        int i = 0;
        for (IPhoneApp app : APP_MAP.values()) {
            if (i == index) return app;
            i++;
        }
        return null;
    }

    public static int getAppCount() { ensureLoaded(); return APP_MAP.size(); }

    public static boolean isInstalled(String id) {
        ensureLoaded();
        return APP_MAP.containsKey(id);
    }

    // ================================================================
    //  延迟初始化
    // ================================================================

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;

        // 第一层: 内建系统 App
        registerBuiltinApps();

        // 第二层: SPI 自动发现第三方 App
        registerSpiApps();
    }

    private static void registerBuiltinApps() {
        for (IPhoneApp app : new IPhoneApp[]{
                new com.november.mcphone.gui.app.SettingsApp(),
                new com.november.mcphone.gui.app.MessagesApp(),
                new com.november.mcphone.gui.app.ContactsApp(),
                new com.november.mcphone.gui.app.CameraApp(),
                new com.november.mcphone.gui.app.GalleryApp(),
                new com.november.mcphone.gui.app.MusicApp()
        }) {
            install(app);
        }
    }

    private static void registerSpiApps() {
        for (IPhoneApp app : ServiceLoader.load(IPhoneApp.class)) {
            install(app);
        }
    }
}
