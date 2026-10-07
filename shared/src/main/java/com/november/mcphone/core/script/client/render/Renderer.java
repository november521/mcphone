package com.november.mcphone.core.script.client.render;

import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphone.api.client.ui.PhoneStyle;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.script.ItemRefs;
import com.november.mcphone.core.script.client.tex.AppTextures;
import com.november.mcphone.core.script.layout.LayoutEngine;
import com.november.mcphone.core.script.layout.LayoutNode;
import com.november.mcphone.core.script.layout.Node;
import com.november.mcphone.core.script.layout.NodeType;
import com.november.mcphone.core.script.layout.Style;
import com.november.mcphone.core.script.layout.UiState;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Map;

/**
 * 把布局结果画到手机上（施工方案 §8.2–§8.4）。布局坐标相对内容区，这里加上画布的绝对偏移。
 *
 * <p>只有 scroll 与 list 建立裁剪区，而且只走 {@link PhoneCanvas#clipped}：原版 enableScissor 收窗口坐标、不看 PoseStack，
 * 界面缩放不是 100% 时裁剪框会错位。别的容器溢出就让它溢出，排不下一眼看得出来。
 */
public final class Renderer {

    private static final int TOGGLE_W = 20;
    private static final int TOGGLE_H = 10;
    private static final int TAB_ICON = 8;

    /** 与 LayoutEngine 给定高 list 排项时用的上限一致，否则超大 item-height 时可见区与项的位置对不上。 */
    private static final int MAX_ITEM_HEIGHT = Integer.MAX_VALUE / 4;

    private Renderer() {
    }

    /** 画一页。Frame 要在这一帧开头新建，悬停才是这一帧的。 */
    public static void draw(LayoutNode root, Frame f) {
        draw(root, f, f.canvas.x(), f.canvas.y());
    }

    private static void draw(LayoutNode n, Frame f, int ox, int oy) {
        int x = n.x + ox;
        int y = n.y + oy;
        boolean sized = n.w > 0 && n.h > 0;
        if (sized) paintSelf(n, f, x, y);
        if (isScroller(n)) {
            if (sized) f.canvas.clipped(x, y, n.w, n.h, () -> drawScrolled(n, f, ox, oy));
        } else {
            // 0 尺寸的容器也画子节点：写死 height: 0 的 column 里溢出去的子节点看得见，HitTest 也点得到
            for (LayoutNode c : n.children) draw(c, f, ox, oy);
        }
    }

    private static void drawScrolled(LayoutNode n, Frame f, int ox, int oy) {
        int sy = clampScroll(n);
        int[] range = childRange(n, sy);
        for (int i = range[0]; i <= range[1]; i++) {
            LayoutEngine.layoutItem(n, i, f.tm);
            draw(n.children.get(i), f, ox, oy - sy);
        }
    }

    /** button 看 enabledIf（写了就覆盖 enabled）与 enabled，toggle 看 enabled，别的类型恒为 true。 */
    public static boolean isEnabled(LayoutNode n, UiState state) {
        return switch (n.node.type()) {
            case BUTTON -> n.node.props().get("enabledIf") instanceof Node.ShowIf condition
                    ? state.test(condition) : n.node.flag("enabled", true);
            case TOGGLE, TEXT_INPUT -> n.node.flag("enabled", true);
            default -> true;
        };
    }

    /** 把 scrollY 夹进 [0, scrollMax]。内容变短后旧的 scrollY 会越界，画与判之前都先夹一次。 */
    public static int clampScroll(LayoutNode n) {
        n.scrollY = Math.max(0, Math.min(n.scrollY, n.scrollMax()));
        return n.scrollY;
    }

    /**
     * list 滚到 scrollY 时要画的项 [first, last]，上下各多一项：只取正好露出来的项，滚动时边上会闪一条空白。
     * 视口在内容坐标里从 scrollY - padTop 开始，因为项排在 padding 之内。
     */
    public static int[] visibleRange(LayoutNode list, int scrollY) {
        int count = list.children.size();
        if (count == 0) return new int[]{0, -1};
        long top = (long) scrollY - list.style.padTop();
        long bottom = top + list.h - 1;
        int itemHeight = Math.min(list.node.num("item-height", 0), MAX_ITEM_HEIGHT);
        long first;
        long last;
        if (itemHeight > 0) {
            long step = (long) itemHeight + list.style.gap();
            first = Math.floorDiv(top, step);
            last = Math.floorDiv(bottom, step);
        } else {
            first = lastStartingBy(list.cumHeights(), count, top);
            last = lastStartingBy(list.cumHeights(), count, bottom);
        }
        int from = (int) Math.max(0, Math.min(count - 1, first - 1));
        int to = (int) Math.max(0, Math.min(count - 1, last + 1));
        return new int[]{from, Math.max(from, to)};
    }

    /** 顶边不晚于 pos 的最后一项，一项都没有返回 -1。高为 0 的项与下一项顶边相同，取靠后那个。 */
    private static long lastStartingBy(int[] cumHeights, int count, long pos) {
        int lo = 0;
        int hi = count;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (cumHeights[mid] <= pos) lo = mid + 1;
            else hi = mid;
        }
        return lo - 1;
    }

    /** scroll 的全部子节点，或 list 可见区里的项。画与判都从这里取，两边永远是同一批。 */
    static int[] childRange(LayoutNode n, int scrollY) {
        return n.node.type() == NodeType.LIST ? visibleRange(n, scrollY) : new int[]{0, n.children.size() - 1};
    }

    /** 这个节点自己滚不滚。页面按它挑出要保留滚动位置的那些节点（§7.7）。 */
    public static boolean isScroller(LayoutNode n) {
        return n.node.type() == NodeType.SCROLL || n.node.type() == NodeType.LIST;
    }

    /** tab-bar 的分段，每项是恰好一个 text / i18n / icon 的表（NodeParser 校验过）。 */
    static List<?> tabs(LayoutNode tabBar) {
        return tabBar.node.props().get("tabs") instanceof List<?> tabs ? tabs : List.of();
    }

    // ============================================================
    //  paintSelf（§8.4）
    // ============================================================

    private static void paintSelf(LayoutNode n, Frame f, int x, int y) {
        PhoneCanvas c = f.canvas;
        PhoneStyle p = c.style();
        GuiGraphics g = c.graphics();
        Font font = c.font();
        Style s = n.style;
        NodeType type = n.node.type();
        boolean hover = f.hovered(n);
        boolean enabled = isEnabled(n, f.state);

        // 按钮的 $button / $button-hover 由样式第 1 层给，这里不再叠一层按钮色：叠了作者写的 background: none 就不生效
        int bg = !enabled ? (s.background() != null ? p.buttonDisabledColor() : 0)
                : color(hover ? s.hoverBackground() : s.background(), p);
        if (bg != 0 && type != NodeType.BADGE) g.fill(x, y, x + n.w, y + n.h, bg);
        if (type == NodeType.BUTTON && enabled && f.pressed == n) {
            g.fill(x, y, x + n.w, y + n.h, p.pressedOverlay());
        }

        // badge 与 tab-bar 在类型分支里整块铺底色，先画的边会被盖掉，它们的边框放到最后；
        // 其余先画边框：LayoutEngine 不给边框留位置，后画会压住 padding 比边框细的开关、进度条
        int bc = color(s.color(), p);
        boolean borderLast = type == NodeType.BADGE || type == NodeType.TAB_BAR;
        if (!borderLast) drawBorder(g, s.border(), bc, x, y, n.w, n.h);

        int fg = enabled ? color(hover ? s.hoverColor() : s.color(), p) : p.buttonDisabledTextColor();
        int px = x + s.padLeft();
        int py = y + s.padTop();
        int innerW = Math.max(0, n.w - s.padLeft() - s.padRight());
        int innerH = Math.max(0, n.h - s.padTop() - s.padBottom());

        switch (type) {
            case TEXT, BUTTON -> drawLines(g, font, n.lines(), s, px, py, innerW, fg);
            case IMAGE -> {
                ResourceLocation tex = AppTextures.of(f.pkg, n.node.str("src", ""));
                if (tex == null) {
                    drawPlaceholder(g, p, px, py, innerW, innerH);
                } else {
                    // 直接 g.blit 不开混合，半透明像素会画成实心
                    GuiUtil.drawTexture(g, tex, px, py, innerW, innerH, innerW, innerH);
                }
            }
            case ICON -> IconAtlas.draw(g, n.node.str("name", "info"), px, py, n.node.num("size", 8), fg);
            case ITEM -> {
                int size = n.node.num("size", 16);
                ItemStack stack = ItemRefs.resolve(n.node.str("item", ""), n.node.num("count", 1));
                if (!GuiUtil.drawItemIcon(g, stack, px, py, size)) {
                    // 什么都不画会让作者以为布局写错了
                    IconAtlas.draw(g, "cross", px, py, size, fg);
                } else if (stack.getCount() > 1) {
                    drawItemCount(g, font, stack.getCount(), px, py, size);
                }
            }
            case BADGE -> {
                int count = n.node.num("count", 0);
                if (count == 0) break;
                int badgeBg = color(s.background(), p);
                g.fill(x, y, x + n.w, y + n.h, badgeBg != 0 ? badgeBg : p.accentColor());
                g.drawString(font, count > 99 ? "99+" : String.valueOf(count), px + 2, py, fg, false);
            }
            case PROGRESS -> {
                int barH = Math.min(n.node.num("height", 4), innerH);
                int value = Math.max(0, Math.min(100, n.node.num("value", 0)));
                g.fill(px, py, px + innerW, py + barH, p.buttonColor());
                int filled = (int) ((long) innerW * value / 100);
                if (value > 0 && innerW > 0) filled = Math.max(1, filled);   // 开始了就至少 1px（§5.2）
                if (filled > 0) g.fill(px, py, px + filled, py + barH, p.accentColor());
            }
            case DIVIDER -> g.fill(x, y, x + n.w, y + n.h, s.color() != null ? fg : p.subtleColor());
            case TOGGLE -> drawToggle(g, font, p, n, f.state, enabled, px, py, innerW, innerH, fg);
            case TAB_BAR -> drawTabs(g, font, p, n, f.state, x, y);
            case TEXT_INPUT -> {
                if (f.inputPainter != null) f.inputPainter.draw(n, c, x, y, fg);
            }
            default -> {
                // 容器与 spacer 只有底色和边框
            }
        }

        if (borderLast) drawBorder(g, s.border(), bc, x, y, n.w, n.h);
    }

    /**
     * 四条边各画一次，不是大矩形叠小矩形：那样半透明底色会叠两层。
     * 边比节点还粗时逐边夹在节点里：不夹的话 fill 的两个角反过来，原版会把它摆正了画到节点外面。
     */
    private static void drawBorder(GuiGraphics g, int bw, int color, int x, int y, int w, int h) {
        if (bw <= 0 || color == 0) return;
        int top = Math.min(bw, h);
        int bottom = Math.min(bw, h - top);
        int left = Math.min(bw, w);
        int right = Math.min(bw, w - left);
        g.fill(x, y, x + w, y + top, color);
        if (bottom > 0) g.fill(x, y + h - bottom, x + w, y + h, color);
        if (h - top - bottom > 0) {
            g.fill(x, y + top, x + left, y + h - bottom, color);
            if (right > 0) g.fill(x + w - right, y + top, x + w, y + h - bottom, color);
        }
    }

    /** text 与没有 children 的 button 的换行结果。有 children 的按钮 lines 为 null，文字由子节点画。 */
    private static void drawLines(GuiGraphics g, Font font, List<String> lines, Style s, int px, int py, int innerW,
                                  int fg) {
        if (lines == null) return;
        int ty = py;
        for (String line : lines) {
            int lw = font.width(line);
            int tx = switch (s.textAlign()) {
                case LEFT -> px;
                case CENTER -> px + (innerW - lw) / 2;
                case RIGHT -> px + innerW - lw;
            };
            g.drawString(font, line, tx, ty, fg, s.shadow());
            ty += font.lineHeight;
        }
    }

    /** 图片还没有贴图时占住它的位置：一块按钮色加一个叉。 */
    private static void drawPlaceholder(GuiGraphics g, PhoneStyle p, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) return;
        g.fill(x, y, x + w, y + h, p.buttonColor());
        int size = Math.min(8, Math.min(w, h));
        IconAtlas.draw(g, "cross", x + (w - size) / 2, y + (h - size) / 2, size, p.subtleColor());
    }

    /** 数量画在右下角。z 抬到 200：物品模型画在 z≈150，同一深度的字会被它挡住。 */
    private static void drawItemCount(GuiGraphics g, Font font, int count, int x, int y, int size) {
        String text = String.valueOf(count);
        g.pose().pushPose();
        g.pose().translate(x, y, 200);
        if (size != 16) g.pose().scale(size / 16f, size / 16f, 1f);
        g.drawString(font, text, 17 - font.width(text), 9, 0xFFFFFFFF, true);
        g.pose().popPose();
    }

    private static void drawToggle(GuiGraphics g, Font font, PhoneStyle p, LayoutNode n, UiState state,
                                   boolean enabled, int px, int py, int innerW, int innerH, int fg) {
        String label = n.node.str("label", n.node.str("i18n", null));
        if (label != null) {
            g.drawString(font, GuiUtil.truncate(font, label, innerW - TOGGLE_W - 4), px,
                    py + (innerH - font.lineHeight) / 2, fg, n.style.shadow());
        }
        boolean on = state.getBool(n.node.str("bind", ""));
        int tx = px + innerW - TOGGLE_W;
        int ty = py + (innerH - TOGGLE_H) / 2;
        g.fill(tx, ty, tx + TOGGLE_W, ty + TOGGLE_H,
                !enabled ? p.buttonDisabledColor() : on ? p.accentColor() : p.buttonColor());
        int kx = on ? tx + 11 : tx + 1;
        g.fill(kx, ty + 1, kx + 8, ty + 9, enabled ? 0xFFFFFFFF : p.buttonDisabledTextColor());
    }

    private static void drawTabs(GuiGraphics g, Font font, PhoneStyle p, LayoutNode n, UiState state, int x, int y) {
        List<?> tabs = tabs(n);
        if (tabs.isEmpty()) return;
        int selected = state.getInt(n.node.str("bind", ""));
        int segW = n.w / tabs.size();
        for (int i = 0; i < tabs.size(); i++) {
            int sx = x + i * segW;
            int sw = i == tabs.size() - 1 ? n.w - i * segW : segW;   // 余数给最后一段，HitTest.segmentAt 按同样的切法
            boolean active = i == selected;
            g.fill(sx, y, sx + sw, y + n.h, active ? p.accentColor() : p.buttonColor());
            if (i > 0) g.fill(sx, y, sx + 1, y + n.h, p.screenBackground());
            if (!(tabs.get(i) instanceof Map<?, ?> tab)) continue;
            int tc = active ? p.titleColor() : p.subtleColor();
            if (tab.get("icon") instanceof String icon) {
                IconAtlas.draw(g, icon, sx + (sw - TAB_ICON) / 2, y + (n.h - TAB_ICON) / 2, TAB_ICON, tc);
            } else {
                Object label = tab.containsKey("text") ? tab.get("text") : tab.get("i18n");
                String shown = GuiUtil.truncate(font, label instanceof String t ? t : "", sw - 2);
                g.drawString(font, shown, sx + (sw - font.width(shown)) / 2, y + (n.h - font.lineHeight) / 2, tc,
                        false);
            }
        }
    }

    /** 语义色 → 当前配色的 ARGB。null（none，只有底色类属性收）返回 0，调用方据此不画。 */
    static int color(Style.Token token, PhoneStyle p) {
        if (token == null) return 0;
        return switch (token) {
            case TITLE -> p.titleColor();
            case BODY -> p.bodyColor();
            case SUBTLE -> p.subtleColor();
            case ACCENT -> p.accentColor();
            case SCREEN -> p.screenBackground();
            case PRESSED -> p.pressedOverlay();
            case BUTTON -> p.buttonColor();
            case BUTTON_HOVER -> p.buttonHoverColor();
            case BUTTON_DISABLED -> p.buttonDisabledColor();
            case BUTTON_DISABLED_TEXT -> p.buttonDisabledTextColor();
        };
    }
}
