package com.november.mcphone.gui;

import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 手机主屏幕 App 注册中心。
 *
 * 这里是添加/移除 App 的唯一入口。
 * 要给手机添加新 App，调用 {@link #register(AppEntry)} 即可。
 * 所有 App 按注册顺序排列在手机主屏幕的网格中。
 *
 * 使用示例（在任何初始化阶段）：
 * <pre>
 * PhoneScreenRegistry.register(new AppEntry.GenericApp(
 *     "相机",
 *     (g, x, y, size, pt) -> g.fill(x, y, x + size, y + size, 0xFF444444),
 *     () -> player.sendSystemMessage(Component.literal("打开相机"))
 * ));
 * </pre>
 */
public final class PhoneScreenRegistry {

    private static final List<AppEntry> APPS = new ArrayList<>();

    /** 默认 App 数量上限（网格展示上限，后续可翻页） */
    private static final int DEFAULT_APP_CAPACITY = 16;

    private PhoneScreenRegistry() {}

    /**
     * 注册一个 App。
     * 调用时机：在 FMLCommonSetupEvent 或客户端初始化阶段。
     */
    public static void register(AppEntry app) {
        if (app == null) {
            throw new IllegalArgumentException("AppEntry 不能为 null");
        }
        APPS.add(app);
    }

    /** 获取所有已注册 App 的只读列表 */
    public static List<AppEntry> getApps() {
        return Collections.unmodifiableList(APPS);
    }

    /** 获取当前 App 数量 */
    public static int getAppCount() {
        return APPS.size();
    }

    /** 获取指定位置的 App，越界返回 null */
    public static AppEntry getApp(int index) {
        return (index >= 0 && index < APPS.size()) ? APPS.get(index) : null;
    }

    // ================================================================
    //  默认 App 注册 —— 在这里添加内建 App
    //  后续你可以把每个 App 拆成独立类放到 gui/apps/ 包下
    // ================================================================

    /**
     * 注册所有内建 App。
     * 在客户端初始化阶段调用一次。
     * 目前注册几个占位 App 用于展示网格布局。
     */
    public static void registerDefaultApps() {
        // 如果已注册过则跳过（防止重复注册）
        if (!APPS.isEmpty()) return;

        // ---- 设置 ----
        register(new AppEntry.GenericApp(
                "设置",
                (g, x, y, size, pt) -> {
                    int color = 0xFF78909C;
                    int margin = 5;
                    g.fill(x + margin, y + margin, x + size - margin, y + size - margin, color);
                },
                () -> {
                    if (net.minecraft.client.Minecraft.getInstance().screen instanceof PhoneScreen ps) {
                        ps.navigateTo(PhoneScreen.Mode.SETTINGS);
                    }
                }
        ));

        // ---- 消息 ----
        register(new AppEntry.GenericApp(
                "消息",
                (g, x, y, size, pt) -> {
                    int color = 0xFF4CAF50;
                    int margin = 3;
                    g.fill(x + margin, y + margin, x + size - margin, y + size - margin, color);
                    g.fill(x + margin + 2, y + size - margin - 6,
                            x + margin + 10, y + size - margin - 2, 0xFFFFFFFF);
                },
                () -> { /* TODO */ }
        ));

        // ---- 联系人 ----
        register(new AppEntry.GenericApp(
                "联系人",
                (g, x, y, size, pt) -> {
                    int color = 0xFF2196F3;
                    int margin = 5;
                    g.fill(x + margin, y + margin, x + size - margin, y + size - margin, color);
                },
                () -> { /* TODO */ }
        ));

        // ---- 相机 ----
        register(new AppEntry.GenericApp(
                "相机",
                (g, x, y, size, pt) -> {
                    int color = 0xFFFF9800;
                    int margin = 6;
                    g.fill(x + margin, y + margin, x + size - margin, y + size - margin, color);
                    int lensR = 5, cx = x + size / 2, cy = y + size / 2;
                    g.fill(cx - lensR, cy - lensR, cx + lensR, cy + lensR, 0xFF333333);
                },
                () -> { /* TODO */ }
        ));

        // ---- 相册 ----
        register(new AppEntry.GenericApp(
                "相册",
                (g, x, y, size, pt) -> {
                    int color = 0xFF9C27B0;
                    int margin = 4;
                    g.fill(x + margin, y + margin, x + size - margin, y + size - margin, color);
                    int peakX = x + size / 2, peakY1 = y + margin + 4, peakY2 = y + size - margin - 2;
                    int halfW = (size - margin * 2) / 2;
                    g.fill(peakX - halfW, peakY2, peakX, peakY1, 0xFFFFFFFF);
                    g.fill(peakX, peakY1, peakX + halfW, peakY2, 0xFFFFEB3B);
                },
                () -> { /* TODO */ }
        ));

        // ---- 音乐 ----
        register(new AppEntry.GenericApp(
                "音乐",
                (g, x, y, size, pt) -> {
                    int color = 0xFFE91E63;
                    int margin = 4;
                    g.fill(x + margin, y + margin, x + size - margin, y + size - margin, color);
                    int barX = x + size / 2 - 2;
                    g.fill(barX, y + margin + 4, barX + 4, y + size - margin, 0xFFFFFFFF);
                },
                () -> { /* TODO */ }
        ));
    }
}
