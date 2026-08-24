package com.november.mcphone.feature.settings.client;

import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 设置那一页的列表 —— 一行一项，右边显示当前值或一个 &gt; 箭头。
 *
 * 从 PhoneScreen 里搬出来的（1.7.2），拆分的第二刀。
 *
 * 列表内容不归它管
 *
 * 每一项的动作都是"跳到某一页"，而那是 PhoneScreen 的事。所以本类只收一份
 * 已经建好的 {@link Item}，负责画出来、判命中、点中了就把那一项的动作跑掉；
 * 至于跑起来会去哪儿，它不知道也不需要知道。
 *
 * 这条与会话列表、应用商店那边是同一个原则（见 ChatList 的 pendingOpen）：
 * 组件不该知道 PhoneScreen 的导航结构。区别只是那边"提出请求等人来取"，
 * 这边"拿到的本来就是别人给的一个 Runnable"，形式不同，边界一样。
 */
public final class SettingsList {

    /** 左右各留多少 */
    private static final int PAD_X = 6;

    /**
     * 一项设置。
     *
     * @param value 右侧显示的当前值；null 表示画 "&gt;" 箭头。
     *              用 Supplier 而不是定值：列表只建一次，而设备名是会变的，
     *              每帧现取才不会显示过期的名字
     */
    public record Item(String label, Runnable action, Supplier<String> value) {
        public Item(String label, Runnable action) {
            this(label, action, null);
        }
    }

    private List<Item> items = new ArrayList<>();

    /** 鼠标停在第几行，-1 表示没有 */
    private int hovered = -1;

    /** 换一份列表。PhoneScreen 建好之后交进来 */
    public void setItems(List<Item> items) {
        this.items = items == null ? new ArrayList<>() : items;
        this.hovered = -1;
    }

    /** 进入这一页时清掉悬停，免得沿用上一次离开时停在哪一行 */
    public void open() {
        hovered = -1;
    }

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font) {

        final int x = phoneLeft + PAD_X;
        final int w = screenW - PAD_X * 2;
        final int bottom = phoneTop + screenH - navH;
        int y = phoneTop + statusH + 4;

        g.drawString(font, Component.translatable("mcphone.gui.settings").getString(),
                x, y, FontPalette.title(), true);
        y += font.lineHeight + 4;

        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 4;

        if (items.isEmpty()) {
            g.drawString(font, Component.translatable("mcphone.gui.no_settings").getString(),
                    x, y, FontPalette.subtle(), false);
            hovered = -1;
            return;
        }

        final int rowH = font.lineHeight + 4;

        hovered = -1;
        for (int i = 0; i < items.size(); i++) {
            if (y + font.lineHeight + 6 > bottom) break;

            Item item = items.get(i);

            if (mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + rowH) {
                hovered = i;
                g.fill(x, y, x + w, y + rowH, PhoneTheme.COLOR_ROW_HOVER);
            }

            g.drawString(font, item.label(), x + 2, y + 2, FontPalette.body(), false);

            // 有当前值就显示值，否则画右箭头
            String right = item.value() != null ? item.value().get() : ">";

            // 值过长会与左边的标题撞上，按剩余宽度截断
            int maxRightW = w - font.width(item.label()) - 10;
            if (font.width(right) > maxRightW) {
                right = font.plainSubstrByWidth(right, Math.max(6, maxRightW - 4)) + "…";
            }
            g.drawString(font, right, x + w - font.width(right) - 4, y + 2,
                    FontPalette.subtle(), false);

            y += rowH + 2;
        }
    }

    /** 点中哪一行就把那一项的动作跑掉 */
    public boolean mouseClicked(double mx, double my, int button) {
        if (hovered < 0 || hovered >= items.size()) return true;

        items.get(hovered).action().run();
        return true;
    }
}
