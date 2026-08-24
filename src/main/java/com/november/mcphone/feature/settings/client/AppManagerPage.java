package com.november.mcphone.feature.settings.client;

import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.core.client.PhoneTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * App 管理器 —— 列出已安装的 App，点一行就卸载。
 *
 * 从 PhoneScreen 里搬出来的（1.7.1）。那个类当时 1626 行、装着 20 种界面
 * 模式，这一块是其中最自足的一簇：它不需要导航、不与别的页共享状态，
 * 只用得到 phoneLeft / phoneTop / font 三样。
 *
 * 形状与音乐、会话列表那些页一致：{@code render(...)} 收几何参数，
 * {@code mouseClicked(...)} 自己判命中。悬停在渲染时算出来，点击时用 ——
 * 与 MusicPage 同一条：列表会变，记下标会点到别人，所以记的是行号加一次
 * 现算，不跨帧缓存位置。
 *
 * 系统 App（设置这类）列出来但不可卸载：标灰、不画悬停高亮、点了不响应。
 * 列出来而不是藏起来，是为了让玩家看见"它在，只是动不了"，否则会以为
 * 自己把设置弄丢了。
 */
public final class AppManagerPage {

    /** 左右各留多少 */
    private static final int PAD_X = 6;

    private final List<IPhoneApp> apps = new ArrayList<>();

    /** 鼠标停在第几行，-1 表示没有。系统 App 那几行永远不会被选中 */
    private int hovered = -1;

    /** 进入这一页 */
    public void open() {
        refresh();
        hovered = -1;
    }

    /**
     * 重建列表：列出全部已安装 App。
     *
     * 每帧都重建一次（render 里调），行为与搬家前一致 —— 卸载之后
     * 列表要立刻少一行，而卸载走的正是这条路。
     */
    private void refresh() {
        apps.clear();
        apps.addAll(PhoneScreenRegistry.getApps());
    }

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font) {
        refresh();

        final int x = phoneLeft + PAD_X;
        final int w = screenW - PAD_X * 2;
        final int bottom = phoneTop + screenH - navH;
        int y = phoneTop + statusH + 4;

        g.drawString(font, Component.translatable("mcphone.app.app_manager").getString(),
                x, y, FontPalette.title(), true);
        y += font.lineHeight + 4;

        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 4;

        if (apps.isEmpty()) {
            g.drawString(font, Component.translatable("mcphone.gui.app_manager_empty").getString(),
                    x, y, FontPalette.subtle(), false);
            hovered = -1;
            return;
        }

        final String uninstall = Component.translatable("mcphone.gui.uninstall").getString();
        final String systemTag = Component.translatable("mcphone.gui.system_app").getString();
        final int rowH = font.lineHeight + 4;

        hovered = -1;
        for (int i = 0; i < apps.size(); i++) {
            if (y + font.lineHeight + 6 > bottom) break;

            IPhoneApp app = apps.get(i);
            boolean system = app.isSystemApp();

            // 系统 App 行不可选中，但 y 照样累加 —— 不加的话后面几行的命中区
            // 会整体错位
            if (!system && mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + rowH) {
                hovered = i;
                g.fill(x, y, x + w, y + rowH, PhoneTheme.COLOR_ROW_HOVER_DANGER);
            }

            g.drawString(font, app.getDisplayName().getString(), x + 2, y + 2,
                    system ? FontPalette.dim() : FontPalette.body(), false);

            String tag = system ? systemTag : uninstall;
            g.drawString(font, tag, x + w - font.width(tag) - 4, y + 2,
                    system ? FontPalette.dim() : FontPalette.uninstall(), false);

            y += rowH + 2;
        }
    }

    /**
     * 点一行就卸载那个 App。
     *
     * 卸载之后把悬停清掉：那个下标现在指向顶上来的另一个 App，不清的话
     * 连点两下会把下一个也卸了。
     */
    public boolean mouseClicked(double mx, double my, int button) {
        if (hovered < 0 || hovered >= apps.size()) return true;

        IPhoneApp toUninstall = apps.get(hovered);
        if (!toUninstall.isSystemApp()) {
            PhoneScreenRegistry.uninstall(toUninstall.getId());
            refresh();
            hovered = -1;
        }
        return true;
    }
}
