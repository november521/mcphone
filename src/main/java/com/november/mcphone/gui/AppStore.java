package com.november.mcphone.gui;

import com.november.mcphone.api.client.store.AppInfo;
import com.november.mcphone.api.client.store.IAppSource;
import com.november.mcphone.store.AppSourceRegistry;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用商店界面 —— 由 PhoneScreen 嵌入渲染。
 *
 * 只与 {@link IAppSource} 打交道，不关心 App 来自本地还是远程：
 * 列表由 AppSourceRegistry 汇总各来源提供，点击安装则交回对应来源处理。
 * 因此新增来源时这个类无需改动。
 */
public final class AppStore {

    private static final int PAD = 6;

    private final List<AppInfo> available = new ArrayList<>();

    /** 是否已发起过一次拉取。避免每帧都请求各来源。 */
    private boolean requested = false;

    private int hoveredIdx = -1;

    /** 安装结果或错误提示，显示在标题下方 */
    private Component message = null;

    /** 重新拉取可安装列表。进入商店界面、以及每次安装成功后调用。 */
    public void refresh() {
        requested = true;
        AppSourceRegistry.listAllAvailable(list -> {
            available.clear();
            if (list != null) available.addAll(list);
        });
    }

    /** 离开商店界面时调用，下次进入重新拉取并清掉上次的提示 */
    public void reset() {
        requested = false;
        message = null;
        hoveredIdx = -1;
    }

    // ============================================================
    //  渲染
    // ============================================================

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font) {

        if (!requested) refresh();

        int x = phoneLeft + PAD;
        int y = phoneTop + statusH + 4;
        int w = screenW - PAD * 2;
        int bottom = phoneTop + screenH - navH;

        g.drawString(font, Component.translatable("mcphone.app.app_store").getString(),
                x, y, PhoneTheme.FONT_COLOR_TITLE, true);
        y += font.lineHeight + 4;

        g.fill(x, y, x + w, y + 1, 0x44FFFFFF);
        y += 4;

        if (message != null) {
            String msg = message.getString();
            if (font.width(msg) > w - 4) msg = font.plainSubstrByWidth(msg, w - 8) + "…";
            g.drawString(font, msg, x, y, 0xFFFFAA44, false);
            y += font.lineHeight + 2;
        }

        if (available.isEmpty()) {
            g.drawString(font, Component.translatable("mcphone.store.empty").getString(),
                    x, y, 0xFF888888, false);
            hoveredIdx = -1;
            return;
        }

        final String install = Component.translatable("mcphone.store.install").getString();
        final int installW = font.width(install);

        hoveredIdx = -1;
        for (int i = 0; i < available.size(); i++) {
            int rowH = font.lineHeight + 4;
            if (y + rowH > bottom) break;

            AppInfo info = available.get(i);
            boolean hovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + rowH;
            if (hovered) {
                hoveredIdx = i;
                g.fill(x, y, x + w, y + rowH, 0x3344FF44);
            }

            // 名称过长时截断，避免与右侧的"安装"重叠
            String name = info.displayName().getString();
            int nameMaxW = w - installW - 10;
            if (font.width(name) > nameMaxW) {
                name = font.plainSubstrByWidth(name, nameMaxW - 4) + "…";
            }
            g.drawString(font, name, x + 2, y + 2, 0xFFCCCCCC, false);
            g.drawString(font, install, x + w - installW - 4, y + 2, 0xFF66FF88, false);

            y += rowH + 2;
        }
    }

    // ============================================================
    //  鼠标
    // ============================================================

    public boolean mouseClicked(double mx, double my, int button) {
        if (hoveredIdx < 0 || hoveredIdx >= available.size()) return false;

        AppInfo info = available.get(hoveredIdx);
        IAppSource source = AppSourceRegistry.getSource(info.sourceId());
        if (source == null) {
            message = Component.translatable("mcphone.store.error.no_source", info.sourceId().toString());
            return true;
        }

        source.install(info,
                app -> {
                    message = Component.translatable("mcphone.store.installed", app.getDisplayName());
                    // 装完从可安装列表中移除
                    refresh();
                },
                err -> message = err);
        return true;
    }
}
