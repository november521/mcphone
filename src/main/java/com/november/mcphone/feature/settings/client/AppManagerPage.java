package com.november.mcphone.feature.settings.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.core.client.AppHotkeys;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.core.client.PhoneTheme;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * App 管理器 —— 列出已安装的 App，点一行进它自己的管理页。
 *
 * 从 PhoneScreen 里搬出来的（1.7.1）。那个类当时 1626 行、装着 20 种界面
 * 模式，这一块是其中最自足的一簇：它不需要导航、不与别的页共享状态，
 * 只用得到 phoneLeft / phoneTop / font 三样。
 *
 * 点一行不再是卸载
 *
 * 原来是的：点中哪一行就把哪个 App 卸掉，没有第二步。而同一部手机里，删一张照片
 * 要点两次（见 Gallery 的删除键）——两套规矩，而危险的那件事反倒更容易做成。
 * 现在点一行是【进去看看】，卸载在 {@link AppManagerDetail} 里，并且要点两次。
 *
 * 系统 App 也点得进去
 *
 * 原来它们是灰的、点不动。但"看看它是什么"跟"能不能卸"是两件事，玩家点进来
 * 多半是为了前者。所以照样能进，只是那一页的卸载键是灰的，并写明为什么。
 *
 * 每帧重建列表：卸载发生在这一页之外（详情页里），回来时列表要立刻少一行。
 */
public final class AppManagerPage {

    /** 左右各留多少 */
    private static final int PAD_X = 6;

    /** 行内那个小图标的边长 */
    private static final int ICON = 12;

    private final List<IPhoneApp> apps = new ArrayList<>();

    /** 鼠标停在第几行，-1 表示没有 */
    private int hovered = -1;

    /** 玩家点中的那个 App，等 PhoneScreen 来取 */
    private IPhoneApp selected;

    /** 从第几个 App 开始画。屏幕只放得下八行，装了几个联动模组就不止八个 App */
    private int scrollOffset;

    /** 上一帧算出来的滚动上限，给 mouseScrolled 夹用 —— 可见行数只有渲染时才知道 */
    private int maxScrollOffset;

    /** 进入这一页 */
    public void open() {
        refresh();
        hovered = -1;
        selected = null;
        scrollOffset = 0;
    }

    /** 点中的那一个，取走就清空。没点就是 null */
    public IPhoneApp consumeSelection() {
        IPhoneApp out = selected;
        selected = null;
        return out;
    }

    private void refresh() {
        apps.clear();
        apps.addAll(PhoneScreenRegistry.getApps());
    }

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, float partialTick, Font font) {
        refresh();

        final int x = phoneLeft + PAD_X;
        final int w = screenW - PAD_X * 2;
        final int bottom = phoneTop + screenH - navH;
        int y = phoneTop + statusH + 4;

        g.drawString(font, Component.translatable("mcphone.app.app_manager").getString(),
                x, y, FontPalette.title(), true);

        // 右边写个总数，与相册那一页同一个位置、同一个意思
        String count = String.valueOf(apps.size());
        g.drawString(font, count, x + w - font.width(count), y, FontPalette.subtle(), false);
        y += font.lineHeight + 4;

        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 4;

        if (apps.isEmpty()) {
            g.drawString(font, Component.translatable("mcphone.gui.app_manager_empty").getString(),
                    x, y, FontPalette.subtle(), false);
            hovered = -1;
            return;
        }

        final String systemTag = Component.translatable("mcphone.gui.system_app").getString();
        final int rowH = Math.max(ICON, font.lineHeight) + 4;

        // 最后一行底下不需要那 2 像素行距，所以按"第一行占 rowH、其余每行占 rowH+2"算，
        // 直接除会少算一行，滚到底时底下会露出一条空白
        final int availH = bottom - y;
        final int visible = availH < rowH ? 1 : (availH - rowH) / (rowH + 2) + 1;
        // 卸载会让列表变短，不夹一下就会停在空白处
        maxScrollOffset = Math.max(0, apps.size() - visible);
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScrollOffset);

        hovered = -1;
        for (int i = scrollOffset; i < apps.size(); i++) {
            if (y + rowH > bottom) break;

            final IPhoneApp app = apps.get(i);

            if (GuiUtil.hit(mouseX, mouseY, x, y, w, rowH)) {
                hovered = i;
                g.fill(x, y, x + w, y + rowH, PhoneTheme.COLOR_ROW_HOVER);
            }

            final int iconY = y + (rowH - ICON) / 2;
            safeRun(() -> app.renderIcon(g, x + 2, iconY, ICON, partialTick));

            // 右边那一段先量出来，名字按剩下的宽度截——不截的话长名字会盖在标签上。
            // 绑了快捷键的把键名也写在这儿：这一页是唯一能一眼扫完"谁绑了什么"的地方，
            // 而绑定分散在各自的详情页里，不摆出来就得一个个点进去看
            AppHotkeys.Binding bound = AppHotkeys.get(app.getId());
            String tag = app.isSystemApp() ? systemTag : ">";
            if (bound != null) tag = bound.displayName().getString() + "  " + tag;
            int tagW = font.width(tag);
            int textY = y + (rowH - font.lineHeight) / 2;
            g.drawString(font, tag, x + w - tagW - 2, textY, FontPalette.dim(), false);

            int nameX = x + ICON + 6;
            g.drawString(font, GuiUtil.truncate(font, safeName(app), x + w - tagW - 6 - nameX),
                    nameX, textY, FontPalette.body(), false);

            y += rowH + 2;
        }
    }

    /** 滚轮翻列表。到头了返回 false，让上层去做它的事 */
    public boolean mouseScrolled(double scrollY) {
        if (scrollY > 0 && scrollOffset > 0) {
            scrollOffset--;
            return true;
        }
        if (scrollY < 0 && scrollOffset < maxScrollOffset) {
            scrollOffset++;
            return true;
        }
        return false;
    }

    /** 点一行＝选中它，交给 PhoneScreen 去开详情页 */
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return true;
        if (hovered >= 0 && hovered < apps.size()) selected = apps.get(hovered);
        return true;
    }

    /** 第三方 App 的显示名可能抛，理由见 AppManagerDetail 里的 safe */
    private static String safeName(IPhoneApp app) {
        try {
            return app.getDisplayName().getString();
        } catch (Throwable t) {
            return app.getId().toString();
        }
    }

    private static void safeRun(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            MCphone.LOGGER.warn("[MCphone] App 管理器里画图标失败: {}", t.toString());
        }
    }
}
