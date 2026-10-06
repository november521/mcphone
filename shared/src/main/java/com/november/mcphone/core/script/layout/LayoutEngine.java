package com.november.mcphone.core.script.layout;

import com.november.mcphone.core.script.layout.Style.Align;
import com.november.mcphone.core.script.layout.Style.Justify;
import com.november.mcphone.core.script.layout.Style.Layout;
import com.november.mcphone.core.script.layout.Style.SizeSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Node 树 + 样式表 + 状态 + 可用区域 → 每个节点的坐标与尺寸（施工方案 §7）。
 *
 * <p>单遍：一次 measure、一次 arrange，没有迭代、回溯与二次测量。两遍布局会把「子依赖父、父依赖子」的循环
 * 变成跑两次看起来对，然后在某个嵌套组合下震荡；单遍靠 MSS 里没有百分比换来。任何尺寸与坐标都不为负：
 * 负宽度传给 fill 会画出横跨全屏的色块，传给裁剪会让裁剪框反转。
 */
public final class LayoutEngine {

    /** scroll 与不定高 list 给子节点的「无限高」。不小于它的可用尺寸都按无限算，fill 在这里退化成 auto。 */
    static final int UNBOUNDED = Integer.MAX_VALUE / 4;

    /**
     * 一次 layout 最多看这么多个节点，藏起来的也算。实例化之后一页最多 512 个（§9.9），2048 项、每项一个节点的 list 也放得下；
     * 这里防的是绕过实例化造出来的树：同一个 Node 对象挂在多处时建树按层指数展开，或挂上千万个藏起来的子节点。
     * 超出的子节点不排，根上的 {@link LayoutNode#truncated()} 为 true。
     */
    static final int MAX_LAYOUT_NODES = 4096;

    private static final int TOGGLE_W = 20;
    private static final int TOGGLE_H = 10;
    private static final int TAB_ICON = 8;
    private static final int MIN_BUTTON_W = 16;
    private static final int MIN_BUTTON_H = 12;
    /** 没有原始尺寸的 image 占多大。作者写了 w / h 就按作者的，这个数只在两个都没写时用得上。 */
    static final int MISSING_IMAGE = 16;

    private LayoutEngine() {
    }

    /**
     * 排一页。根节点放在 (0, 0)，宽占满可用宽（写死宽度时按写死的），高只有内容那么高（§7.8）：
     * 页面根的 align / justify / 背景按屏宽生效，要占满高就写 height: fill。
     * 根本身被 showIf 或 hidden 藏起来时，返回一个 0×0、没有子节点的根。
     *
     * <p>{@code images} 是图片原始尺寸的来源（§5.2）。建完树之后、开测之前问一遍，整趟 layout 用同一份答案。
     * 有包的时候请给 {@code AppTextures.sizes(pkg)}；给 {@link ImageSizes#NONE} 编得过、跑得起来，
     * 而所有 image 都会按 {@link #MISSING_IMAGE} 排成小方块，且不报错。
     */
    public static LayoutNode layout(Node root, Stylesheet sheet, UiState state, int availW, int availH,
                                    TextMeasure tm, ImageSizes images) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(sheet, "sheet");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(tm, "tm");
        Objects.requireNonNull(images, "images");
        Style style = sheet.resolve(root);
        String key = root.id() != null ? "#" + root.id() : "";
        if (!visible(root, style, state)) {
            LayoutNode empty = new LayoutNode(root, style, key);
            empty.measured = true;
            return empty;
        }
        int w = clampInt(availW);
        int h = clampInt(availH);
        int[] budget = {MAX_LAYOUT_NODES - 1};
        LayoutNode ln = build(root, style, sheet, state, key, 1, budget);
        ln.truncated = budget[0] < 0;
        resolveImages(ln, images);
        measure(ln, w, h, tm);
        arrange(ln, 0, 0, style.width().kind() == SizeSpec.Kind.FIXED ? ln.measuredW : w, ln.measuredH);
        return ln;
    }

    /**
     * 排定高 list 的第 index 项。layout 时定高 list 一个子节点都不测（§5.1），画与命中之前对可见区里的每一项调一次；
     * 排过的直接返回，所以每帧都调也只测一次。list 自己还没排（它是另一个定高 list 里没排的项）、不是定高 list、
     * 下标越界时什么都不做。
     */
    public static void layoutItem(LayoutNode list, int index, TextMeasure tm) {
        int itemHeight = Math.min(list.node.num("item-height", 0), UNBOUNDED);
        if (list.node.type() != NodeType.LIST || !list.measured || itemHeight <= 0
                || index < 0 || index >= list.children.size()) {
            return;
        }
        LayoutNode c = list.children.get(index);
        if (c.measured) return;
        Style s = list.style;
        int cw = Math.max(0, list.w - s.padLeft() - s.padRight());
        measure(c, cw, itemHeight, tm);
        int w = itemWidth(s, c, cw);
        long top = (long) list.y + s.padTop() + (long) index * ((long) itemHeight + s.gap());
        arrange(c, clampInt((long) list.x + s.padLeft() + crossOffset(s.align(), cw, w)), clampInt(top), w, itemHeight);
    }

    // ============================================================
    //  build：建树、解析样式、过滤 showIf
    // ============================================================

    private static LayoutNode build(Node node, Style style, Stylesheet sheet, UiState state, String key, int depth,
                                    int[] budget) {
        LayoutNode ln = new LayoutNode(node, style, key);
        // 叶子类型挂的 children 与超过 32 层的子树不排：IR 校验已经拒了，这里只防绕过校验造出来的树
        if (!node.type().acceptsChildren || depth >= NodeParser.MAX_DEPTH) return ln;
        List<Node> kids = node.children();
        for (int i = 0; i < kids.size(); i++) {
            if (budget[0] <= 0) {
                budget[0] = -1;   // 还有子节点没看就停了
                return ln;
            }
            budget[0]--;
            Node c = kids.get(i);
            Style cs = sheet.resolve(c);
            if (!visible(c, cs, state)) continue;
            // 模板的 :key 进 props.key（§9.4.5）：列表增删时按它找回滚动位置，不按会错位的下标。
            // 带上长度：key 值里可以有 ] 和 .，不带的话 "a].children[0" 会和别的节点的路径拼成同一串
            String slot = c.props().get("key") instanceof String k && !k.isEmpty()
                    ? "key[" + k.length() + ":" + k + "]" : "children[" + i + "]";
            String childKey = c.id() != null ? "#" + c.id() : (key.isEmpty() ? "" : key + ".") + slot;
            ln.children.add(build(c, cs, sheet, state, childKey, depth + 1, budget));
        }
        return ln;
    }

    /**
     * 把整棵树里 image 的原始尺寸一次问完，问到的答案挂到每个节点上。
     *
     * <p>两个理由。一是 {@link #layoutItem} 只有 {@code list} 那个节点，拿不到包，现问也没处问；
     * 二是一趟 layout 里同一个 src 必须给同一个答案 —— 尺寸那头的判定会在画的时候就地变
     * （头合法、像素解不开的图画过一次就没有尺寸了），现问的话同一棵树里先测的和后测的会对不上。
     */
    private static void resolveImages(LayoutNode root, ImageSizes images) {
        Map<String, int[]> found = new LinkedHashMap<>();
        collectImages(root, images, found);
        applyImages(root, found);
    }

    private static void collectImages(LayoutNode n, ImageSizes images, Map<String, int[]> found) {
        if (n.node.type() == NodeType.IMAGE) {
            String src = n.node.str("src", "");
            if (!found.containsKey(src)) found.put(src, images.size(src));
        }
        for (LayoutNode c : n.children) collectImages(c, images, found);
    }

    private static void applyImages(LayoutNode n, Map<String, int[]> found) {
        n.imageSizes = found;
        for (LayoutNode c : n.children) applyImages(c, found);
    }

    /** 不显示的节点完全不参与布局：不占空间、不计 gap、不接收输入（§4.4）。 */
    private static boolean visible(Node node, Style style, UiState state) {
        return !style.hidden() && (node.showIf() == null || state.test(node.showIf()));
    }

    // ============================================================
    //  measure
    // ============================================================

    private static void measure(LayoutNode n, int availW, int availH, TextMeasure tm) {
        n.measured = true;
        Style s = n.style;
        // 写死尺寸的节点，子节点在它自己的尺寸里排：不然 width: 60 的框里文字仍按父的宽度折行。
        // 宽夹在可用宽以内，和节点自己的宽被夹一致；高允许溢出，写死多少就在多少里排
        int boxW = fixedWithin(s.width(), availW);
        int boxH = s.height().kind() == SizeSpec.Kind.FIXED ? s.height().value() : availH;
        int innerW = inner(boxW, s.padLeft() + s.padRight());
        int innerH = inner(boxH, s.padTop() + s.padBottom());

        long contentW = 0;
        long contentH = 0;
        switch (n.node.type()) {
            case TEXT -> {
                n.lines = wrap(text(n.node), innerW, tm, s.wrap(), s.maxLines());
                contentW = widest(n.lines, tm);
                contentH = (long) n.lines.size() * tm.lineHeight();
            }
            case IMAGE -> {
                int[] px = n.imageSizes == null ? null : n.imageSizes.get(n.node.str("src", ""));
                // 用不了的图（不在包里、超限、坏文件）没有原始尺寸，按占位尺寸排：
                // 按 0 排的话 Renderer 的 sized 判定不成立，连占位图都不画，作者看到的是"图没了"
                contentW = n.node.num("w", px == null ? MISSING_IMAGE : px[0]);
                contentH = n.node.num("h", px == null ? MISSING_IMAGE : px[1]);
            }
            case ICON -> contentW = contentH = n.node.num("size", 8);
            case ITEM -> contentW = contentH = n.node.num("size", 16);
            case BADGE -> {
                int count = n.node.num("count", 0);
                if (count != 0) {
                    contentW = tm.width(count > 99 ? "99+" : String.valueOf(count)) + 4L;
                    contentH = tm.lineHeight();
                }
            }
            case PROGRESS -> {
                contentW = bounded(innerW);
                contentH = n.node.num("height", 4);
            }
            case DIVIDER -> {
                // 竖线的高在 arrange 里取所在行的高：测量时取可用高，auto 高的行会被撑到整屏
                boolean vertical = n.node.flag("vertical", false);
                contentW = vertical ? 1 : bounded(innerW);
                contentH = vertical ? 0 : 1;
            }
            case SPACER -> {
                // 父节点只取它主轴那一轴（§5.2 交叉轴 0）；size 为 0 的弹性 spacer 已由样式第 4 层压成 0×0
                int size = n.node.num("size", 0);
                if (size > 0) contentW = contentH = size;
            }
            case TEXT_INPUT -> {
                contentW = 80;
                contentH = tm.lineHeight() + 6;
            }
            case TOGGLE -> {
                String label = n.node.str("label", n.node.str("i18n", null));
                contentW = (label != null ? tm.width(label) + 4L : 0) + TOGGLE_W;
                contentH = Math.max(tm.lineHeight(), TOGGLE_H);
            }
            case TAB_BAR -> {
                contentW = bounded(innerW);
                contentH = Math.max(tm.lineHeight(), TAB_ICON) + 4L;
            }
            case GRID -> {
                int cols = gridCols(n);
                List<LayoutNode> cells = occupying(n.children);
                int[] colW = columns(bounded(innerW), cols, s.gap());
                int rows = (cells.size() + cols - 1) / cols;
                for (int r = 0; r < rows; r++) {
                    int rowH = 0;
                    for (int k = 0; k < cols && r * cols + k < cells.size(); k++) {
                        LayoutNode c = cells.get(r * cols + k);
                        measure(c, colW[k], innerH, tm);
                        rowH = Math.max(rowH, c.measuredH);
                    }
                    contentH += rowH + (r < rows - 1 ? s.gap() : 0);
                }
                for (LayoutNode c : n.children) {
                    if (!occupies(c)) measure(c, 0, 0, tm);
                }
                contentW = bounded(innerW);
            }
            case LIST -> {
                int itemHeight = n.node.num("item-height", 0);
                int count = n.children.size();
                if (itemHeight > 0) {
                    // 定高：只算总高，一个子节点都不测，可见的项在画之前由 layoutItem 排
                    n.contentH = count == 0 ? 0 : clampInt((long) count * itemHeight + (long) s.gap() * (count - 1));
                } else {
                    n.cumHeights = new int[count + 1];
                    long acc = 0;
                    boolean seen = false;
                    for (int i = 0; i < count; i++) {
                        LayoutNode c = n.children.get(i);
                        measure(c, innerW, UNBOUNDED, tm);
                        if (occupies(c) && seen) acc += s.gap();
                        n.cumHeights[i] = clampInt(acc);
                        acc += c.measuredH;
                        seen |= occupies(c);
                    }
                    n.cumHeights[count] = clampInt(acc);
                    n.contentH = clampInt(acc);
                }
                contentW = bounded(innerW);
                // 自身只占可用高，内容总高另存；放进无限高的容器里时没有「可用高」，只能按内容展开
                contentH = unbounded(innerH) ? n.contentH : innerH;
            }
            case SCROLL -> {
                long acc = 0;
                boolean seen = false;
                for (LayoutNode c : n.children) {
                    measure(c, innerW, UNBOUNDED, tm);
                    if (occupies(c) && seen) acc += s.gap();
                    acc += c.measuredH;
                    seen |= occupies(c);
                }
                n.contentH = clampInt(acc);
                contentW = bounded(innerW);
                contentH = unbounded(innerH) ? n.contentH : innerH;
            }
            default -> {
                // §5.3「有 children 时忽略 text」看的是写了没有，不是显示了没有：children 全被藏起来的按钮不该变回文字按钮
                if (n.node.type() == NodeType.BUTTON && n.node.children().isEmpty()) {
                    n.lines = wrap(text(n.node), innerW, tm, false, 1);
                    contentW = widest(n.lines, tm);
                    contentH = tm.lineHeight();
                } else {
                    Layout axis = axisOf(n);
                    boolean seen = false;
                    for (LayoutNode c : n.children) {
                        measure(c, innerW, innerH, tm);
                        boolean gap = occupies(c) && seen;
                        boolean spacer = c.node.type() == NodeType.SPACER;
                        switch (axis) {
                            case STACK -> {
                                contentW = Math.max(contentW, spacer ? 0 : c.measuredW);
                                contentH = Math.max(contentH, spacer ? 0 : c.measuredH);
                            }
                            case COLUMN -> {
                                contentW = Math.max(contentW, spacer ? 0 : c.measuredW);
                                contentH += c.measuredH + (gap ? s.gap() : 0);
                            }
                            case ROW -> {
                                contentH = Math.max(contentH, spacer ? 0 : c.measuredH);
                                contentW += c.measuredW + (gap ? s.gap() : 0);
                            }
                        }
                        seen |= occupies(c);
                    }
                }
            }
        }

        n.measuredW = outer(s.width(), contentW, availW, s.padLeft() + s.padRight());
        n.measuredH = outer(s.height(), contentH, availH, s.padTop() + s.padBottom());
        if (n.node.type() == NodeType.BUTTON) {                 // §5.3 最小可点区域
            n.measuredW = Math.max(n.measuredW, MIN_BUTTON_W);
            n.measuredH = Math.max(n.measuredH, MIN_BUTTON_H);
        }
        if (!unbounded(availW)) n.measuredW = Math.min(n.measuredW, availW);
    }

    /** auto 是内容加 padding；fill 是可用尺寸，可用尺寸无限时退化成 auto；数字就是外尺寸本身，已含 padding。 */
    private static int outer(SizeSpec spec, long content, int avail, int pads) {
        long v = switch (spec.kind()) {
            case AUTO -> content + pads;
            case FILL -> unbounded(avail) ? content + pads : avail;
            case FIXED -> spec.value();
        };
        return clampInt(v);
    }

    /** 写死的尺寸夹在可用尺寸以内；其余情况就是可用尺寸。 */
    private static int fixedWithin(SizeSpec spec, int avail) {
        if (spec.kind() != SizeSpec.Kind.FIXED) return avail;
        return unbounded(avail) ? spec.value() : Math.min(spec.value(), avail);
    }

    // ============================================================
    //  arrange
    // ============================================================

    private static void arrange(LayoutNode n, int x, int y, int w, int h) {
        n.x = x;
        n.y = y;
        n.w = Math.max(0, w);
        n.h = Math.max(0, h);
        if (n.children.isEmpty()) return;

        Style s = n.style;
        int cx = clampInt((long) x + s.padLeft());
        int cy = clampInt((long) y + s.padTop());
        int cw = Math.max(0, n.w - s.padLeft() - s.padRight());
        int ch = Math.max(0, n.h - s.padTop() - s.padBottom());

        switch (n.node.type()) {
            case GRID -> arrangeGrid(n, cx, cy, cw);
            case SCROLL -> arrangeFlow(n, cx, cy, cw, n.contentH, true);   // 内容坐标系，主轴没有剩余
            case LIST -> arrangeList(n, cx, cy, cw);
            default -> {
                Layout axis = axisOf(n);
                if (axis == Layout.STACK) {
                    arrangeStack(n, cx, cy, cw, ch);
                } else {
                    arrangeFlow(n, cx, cy, cw, ch, axis == Layout.COLUMN);
                }
            }
        }
    }

    /**
     * 纵向或横向流式排列（§7.5）。剩余为正时先按 grow、再按 justify 分；剩余为负时按 start 紧密排列，
     * 超出部分由父的裁剪区切掉——不按比例收缩（文字会挤成一团还看不出原因）、不产生负尺寸、不重新测量。
     */
    private static void arrangeFlow(LayoutNode n, int cx, int cy, int cw, int ch, boolean vertical) {
        Style s = n.style;
        List<LayoutNode> kids = n.children;
        int count = kids.size();
        int occupying = 0;
        long sumMain = 0;
        long totalGrow = 0;
        for (LayoutNode c : kids) {
            if (occupies(c)) occupying++;
            sumMain += vertical ? c.measuredH : c.measuredW;
            totalGrow += c.style.grow();
        }
        long free = (vertical ? ch : cw) - (long) s.gap() * Math.max(0, occupying - 1) - sumMain;

        long[] extra = new long[count];
        if (free > 0 && totalGrow > 0) {
            long remain = free;
            for (int i = 0; i < count; i++) {
                extra[i] = free * kids.get(i).style.grow() / totalGrow;
                remain -= extra[i];
            }
            // 整数除法的余数全给第一个 grow > 0 的子，不平摊
            for (int i = 0; i < count; i++) {
                if (kids.get(i).style.grow() > 0) {
                    extra[i] += remain;
                    break;
                }
            }
            free = 0;
        }

        long lead = 0;
        long between = 0;
        if (free > 0) {
            switch (s.justify()) {
                case START -> { }
                case CENTER -> lead = free / 2;
                case END -> lead = free;
                case BETWEEN -> {
                    if (occupying > 1) between = free / (occupying - 1);
                    else lead = free / 2;
                }
            }
        }

        long cursor = (vertical ? cy : cx) + lead;
        boolean seen = false;
        int crossAvail = vertical ? cw : ch;
        for (int i = 0; i < count; i++) {
            LayoutNode c = kids.get(i);
            if (occupies(c) && seen) cursor += s.gap() + between;
            int main = clampInt((vertical ? c.measuredH : c.measuredW) + extra[i]);
            int cross;
            if (!occupies(c)) {
                cross = 0;                               // count 为 0 的角标拉伸时也是 0×0
            } else if (s.align() == Align.STRETCH || !vertical && verticalDivider(c)) {
                cross = crossAvail;                      // 竖线在行里取行高
            } else if (c.node.type() == NodeType.SPACER) {
                cross = 0;
            } else {
                cross = Math.min(vertical ? c.measuredW : c.measuredH, crossAvail);
            }
            int crossPos = clampInt((long) (vertical ? cx : cy) + crossOffset(s.align(), crossAvail, cross));
            if (vertical) {
                arrange(c, crossPos, clampInt(cursor), cross, main);
            } else {
                arrange(c, clampInt(cursor), crossPos, main, cross);
            }
            cursor += main;
            seen |= occupies(c);
        }
    }

    /** 层叠：每个子节点各自结算。align 管横向、justify 管纵向，和 column 的交叉轴、主轴同一套含义；stretch 两个方向都撑满。 */
    private static void arrangeStack(LayoutNode n, int cx, int cy, int cw, int ch) {
        Style s = n.style;
        boolean stretch = s.align() == Align.STRETCH;
        for (LayoutNode c : n.children) {
            boolean grows = stretch && occupies(c);
            int w = grows ? cw : Math.min(c.measuredW, cw);
            int h = grows || verticalDivider(c) ? ch : Math.min(c.measuredH, ch);
            arrange(c, clampInt((long) cx + crossOffset(s.align(), cw, w)),
                    clampInt((long) cy + mainOffset(s.justify(), ch, h)), w, h);
        }
    }

    /** 等宽网格：列宽的余数给最后一列（§5.1），格子宽度就是列宽，纵向按 align 在行高里摆。count 为 0 的角标不占格子。 */
    private static void arrangeGrid(LayoutNode n, int cx, int cy, int cw) {
        Style s = n.style;
        int cols = gridCols(n);
        List<LayoutNode> cells = occupying(n.children);
        int[] colW = columns(cw, cols, s.gap());
        long rowY = cy;
        for (int r = 0; r * cols < cells.size(); r++) {
            int rowH = 0;
            for (int k = 0; k < cols && r * cols + k < cells.size(); k++) {
                rowH = Math.max(rowH, cells.get(r * cols + k).measuredH);
            }
            long colX = cx;
            for (int k = 0; k < cols && r * cols + k < cells.size(); k++) {
                LayoutNode c = cells.get(r * cols + k);
                int h = s.align() == Align.STRETCH ? rowH : c.measuredH;
                arrange(c, clampInt(colX), clampInt(rowY + crossOffset(s.align(), rowH, h)), colW[k], h);
                colX += colW[k] + s.gap();
            }
            rowY += rowH + s.gap();
        }
        for (LayoutNode c : n.children) {
            if (!occupies(c)) arrange(c, cx, cy, 0, 0);
        }
    }

    /** 不定高 list 按累积高度表摆；定高 list 什么都不做，见 {@link #layoutItem}。 */
    private static void arrangeList(LayoutNode n, int cx, int cy, int cw) {
        if (n.node.num("item-height", 0) > 0) return;
        Style s = n.style;
        for (int i = 0; i < n.children.size(); i++) {
            LayoutNode c = n.children.get(i);
            int w = itemWidth(s, c, cw);
            arrange(c, clampInt((long) cx + crossOffset(s.align(), cw, w)), clampInt((long) cy + n.cumHeights[i]),
                    w, c.measuredH);
        }
    }

    /** list 的项的宽：拉伸时占满（count 为 0 的角标除外），spacer 交叉轴为 0，其余取测出的宽。 */
    private static int itemWidth(Style list, LayoutNode c, int cw) {
        if (list.align() == Align.STRETCH && occupies(c)) return cw;
        return c.node.type() == NodeType.SPACER ? 0 : Math.min(c.measuredW, cw);
    }

    private static int crossOffset(Align a, int avail, int size) {
        return switch (a) {
            case START, STRETCH -> 0;
            case CENTER -> Math.max(0, (avail - size) / 2);
            case END -> Math.max(0, avail - size);
        };
    }

    private static int mainOffset(Justify j, int avail, int size) {
        return switch (j) {
            case START, BETWEEN -> 0;
            case CENTER -> Math.max(0, (avail - size) / 2);
            case END -> Math.max(0, avail - size);
        };
    }

    // ============================================================
    //  文字
    // ============================================================

    /**
     * 断行（§5.2）。逐字累加宽度，超过 maxW 时：当前字是 CJK 就在这里断；否则回退到本行最后一个空格处断，
     * 本行没有空格就在这里硬断；断点上的一串空格整个吞掉。'\n' 强制断行、不计宽度。wrap 为 false 时每段各截成一行。
     * 超过 maxLines 时截到那么多行，最后一行末尾补省略号。
     */
    static List<String> wrap(String text, int maxW, TextMeasure tm, boolean wrap, int maxLines) {
        List<String> raw = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            if (wrap && maxW > 0) {
                breakInto(paragraph, maxW, tm, raw);
            } else {
                raw.add(line(paragraph.codePoints().toArray(), 0, paragraph.codePointCount(0, paragraph.length())));
            }
        }
        boolean cut = maxLines > 0 && raw.size() > maxLines;
        if (cut) raw = raw.subList(0, maxLines);
        List<String> lines = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            // 截掉后面几行时，最后一行本身可能放得下、truncate 不会加省略号，所以先拼上再截；每行只截一次，不会出两个省略号
            boolean last = cut && i == raw.size() - 1;
            String line = last ? raw.get(i) + "…" : raw.get(i);
            lines.add(!wrap || maxW <= 0 || last ? tm.truncate(line, Math.max(0, maxW)) : line);
        }
        return Collections.unmodifiableList(lines);
    }

    private static void breakInto(String paragraph, int maxW, TextMeasure tm, List<String> out) {
        int[] cps = paragraph.codePoints().toArray();
        int[] widths = new int[cps.length];
        for (int i = 0; i < cps.length; i++) widths[i] = tm.width(new String(cps, i, 1));

        int before = out.size();
        int start = 0;
        long lineW = 0;
        int lastSpace = -1;
        int i = 0;
        while (i < cps.length) {
            if (lineW + widths[i] > maxW && i > start) {
                if (cps[i] == ' ') {
                    emit(out, cps, start, i);
                    while (i < cps.length && cps[i] == ' ') i++;
                    start = i;
                    lineW = 0;
                    lastSpace = -1;
                    continue;
                }
                if (!cjk(cps[i]) && lastSpace > start) {
                    // lastSpace 是一串空格里的最后一个，它和 i 之间没有空格
                    emit(out, cps, start, lastSpace);
                    start = lastSpace + 1;
                    lineW = sum(widths, start, i);
                } else {
                    emit(out, cps, start, i);
                    start = i;
                    lineW = 0;
                }
                lastSpace = -1;
                continue;   // 同一个字在新的一行里再判一次：退回空格之后这一行仍可能放不下它
            }
            if (cps[i] == ' ') lastSpace = i;
            lineW += widths[i];
            i++;
        }
        // 段尾的空格正好在断点上被吞掉时，后面已经没有字了，不补一行空行
        if (start < cps.length || out.size() == before) out.add(line(cps, start, cps.length));
    }

    /** 断出一行；去掉行尾空格后是空的就不加：段首的一串空格放不下首词时，不该单独占出一行空行。 */
    private static void emit(List<String> out, int[] cps, int start, int end) {
        String s = line(cps, start, end);
        if (!s.isEmpty()) out.add(s);
    }

    /** 一行，去掉行尾空格：留着的话 text-align: right 的行对不齐。 */
    private static String line(int[] cps, int start, int end) {
        while (end > start && cps[end - 1] == ' ') end--;
        return new String(cps, start, end - start);
    }

    private static boolean cjk(int cp) {
        return cp >= 0x4E00 && cp <= 0x9FFF || cp >= 0x3000 && cp <= 0x303F || cp >= 0xFF00 && cp <= 0xFFEF;
    }

    private static long sum(int[] widths, int from, int to) {
        long s = 0;
        for (int i = from; i < to; i++) s += widths[i];
        return s;
    }

    private static int widest(List<String> lines, TextMeasure tm) {
        int w = 0;
        for (String line : lines) w = Math.max(w, tm.width(line));
        return w;
    }

    /** 翻译表在包里（§5.2），布局这层没有：只写了 i18n 时按 key 本身量，实例化时要先把译文填进 text。 */
    private static String text(Node node) {
        return node.str("text", node.str("i18n", ""));
    }

    // ============================================================
    //  小工具
    // ============================================================

    /** column / row / stack 的排法由类型决定，样式里的 layout 只对 box 与 button 生效（§6.5）。 */
    private static Layout axisOf(LayoutNode n) {
        return switch (n.node.type()) {
            case ROW -> Layout.ROW;
            case STACK -> Layout.STACK;
            case BOX, BUTTON -> n.style.layout();
            default -> Layout.COLUMN;
        };
    }

    /** count 为 0 的角标不参与 gap、不占格子（§5.2）：否则行里、网格里会留一道空。 */
    private static boolean occupies(LayoutNode c) {
        return !(c.node.type() == NodeType.BADGE && c.node.num("count", 0) == 0);
    }

    private static List<LayoutNode> occupying(List<LayoutNode> kids) {
        List<LayoutNode> out = new ArrayList<>(kids.size());
        for (LayoutNode c : kids) {
            if (occupies(c)) out.add(c);
        }
        return out;
    }

    private static boolean verticalDivider(LayoutNode c) {
        return c.node.type() == NodeType.DIVIDER && c.node.flag("vertical", false);
    }

    private static int gridCols(LayoutNode n) {
        return Math.max(1, Math.min(6, n.node.num("cols", 3)));
    }

    /** 每列宽度，余数给最后一列。 */
    private static int[] columns(int width, int cols, int gap) {
        int usable = (int) Math.max(0, (long) width - (long) gap * (cols - 1));
        int[] out = new int[cols];
        int base = usable / cols;
        for (int k = 0; k < cols; k++) out[k] = base;
        out[cols - 1] = usable - base * (cols - 1);
        return out;
    }

    private static boolean unbounded(int size) {
        return size >= UNBOUNDED;
    }

    private static int bounded(int size) {
        return unbounded(size) ? 0 : size;
    }

    /** 去掉 padding 后的可用尺寸。无限减去 padding 仍是无限：否则 scroll 里一层带 padding 的框就让 fill 算出几亿像素。 */
    private static int inner(int size, int pads) {
        return unbounded(size) ? UNBOUNDED : Math.max(0, size - pads);
    }

    private static int clampInt(long v) {
        return (int) Math.max(0, Math.min(v, UNBOUNDED));
    }
}
