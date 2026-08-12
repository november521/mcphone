package com.november.mcphone.gui;

import com.november.mcphone.gui.app.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 手机主屏幕 App 注册中心。
 *
 * 这里是添加/移除 App 的唯一入口。
 * 所有 App 必须继承 {@link com.november.mcphone.gui.app.PhoneApp}。
 *
 * 给手机添加新 App 的步骤：
 * <pre>
 * 1. 在 gui/app/ 包下新建类，继承 PhoneApp
 * 2. 实现 onPress() 方法
 * 3. 准备贴图: assets/mcphone/textures/gui/app_icon_{id}.png (20×20)
 * 4. 在 registerDefaultApps() 中 register(new YourApp())
 * </pre>
 */
public final class PhoneScreenRegistry {

    private static final List<PhoneApp> APPS = new ArrayList<>();

    private PhoneScreenRegistry() {}

    /** 注册一个 App */
    public static void register(PhoneApp app) {
        if (app == null) throw new IllegalArgumentException("PhoneApp 不能为 null");
        APPS.add(app);
    }

    /** 获取所有已注册 App 的只读列表 */
    public static List<PhoneApp> getApps() {
        return Collections.unmodifiableList(APPS);
    }

    /** 获取当前 App 数量 */
    public static int getAppCount() {
        return APPS.size();
    }

    /** 获取指定位置的 App，越界返回 null */
    public static PhoneApp getApp(int index) {
        return (index >= 0 && index < APPS.size()) ? APPS.get(index) : null;
    }

    // ================================================================
    //  默认 App 注册 —— 一行一个 App，干净清晰
    // ================================================================

    /**
     * 注册所有内建 App。在客户端初始化阶段调用一次。
     *
     * 要开发新 App，就新加一行 register(new XxxApp())。
     */
    public static void registerDefaultApps() {
        if (!APPS.isEmpty()) return;

        register(new SettingsApp());
        register(new MessagesApp());
        register(new ContactsApp());
        register(new CameraApp());
        register(new GalleryApp());
        register(new MusicApp());
    }
}
