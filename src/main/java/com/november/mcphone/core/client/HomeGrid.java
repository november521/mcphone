package com.november.mcphone.core.client;

import com.november.mcphone.api.client.app.IPhoneApp;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * 主屏 —— App 图标网格、拖动排序、分页与翻页。
 *
 * 从 PhoneScreen 里搬出来的（1.7.3），拆分的第三刀，也是最大的一刀。
 * 那个类原本 1626 行，这一簇占了三百多行加十来个字段，而且状态散在渲染与
 * 四个输入处理器里 —— 是它最难读的一块。
 *
 * 它只认手机本地坐标
 *
 * 开机时整部手机会被缩放着画（{@code PhoneScreen.getAnimationScale}），
 * 所以鼠标坐标要先撤掉那层缩放才能和格子对上。那个反变换留在 PhoneScreen：
 * 动画是它的事。本类的四个输入方法收到的一律是【已经反变换过的】本地坐标。
 *
 * 搬家前这条边界破过一次 —— 反变换的算式被抄了一份放在命中判定里，改动画
 * 曲线时只有一个人被想起来，结果是"开机那一瞬间点图标点不准"。现在算式只
 * 有一处，本类连它长什么样都不知道。
 *
 * 它不知道点开 App 会去哪儿
 *
 * 松手判定是"这一下算点开还是算挪位置"，定完之后本类只把那个 App 记进
 * {@link #consumeLaunchRequest()}，由 PhoneScreen 取走再决定怎么打开。
 * 与会话列表、应用商店那边同一个原则：组件不该知道 PhoneScreen 的导航结构。
 *
 * 每帧的上下文
 *
 * phoneLeft/phoneTop、格子起点、字体、这一帧的时刻、开机动画放完没有 ——
 * 这几样每帧由 {@link #render} 带进来。输入方法用的是上一帧的值，与搬家前
 * 一致（那时它们直接读 PhoneScreen 的字段，同样是上一帧渲染时写的）。
 */
public final class HomeGrid {

    // ---- 每帧由 render 带进来的上下文 ----
    private int phoneLeft, phoneTop;
    private int gridStartX, gridStartY;
    private Font font;
    private long nowMs;
    private boolean animationDone;

    // ---- 主屏自己的状态 ----

    /** 鼠标停在第几个 App 上（全局下标），-1 表示没有 */
    private int hoveredAppIndex = -1;

    /** 当前第几页 */
    private int homePage = 0;

    /** 在机身内的空白处按下了 —— 横着拖它就是翻页 */
    private boolean pressedBlank;

    /** 翻页动画：从哪一页滑过来的，什么时候开始滑 */
    private int slideFromPage;
    private long pageSlideStartMs;

    /** 按下的是第几个 App。到底算点开还是算挪位置，松手时才定 */
    private int pressedAppIndex = -1;

    /** 按下点的本地坐标，用来量挪了多远 */
    private double pressX, pressY;

    private boolean draggingApp;
    private double dragX, dragY;

    /** 拖动时松手会插到哪儿（全局下标） */
    private int dragTargetIndex = -1;

    /** 拖着图标停在屏幕哪一边（-1 左 / 0 没有 / 1 右），停够久就翻页 */
    private int edgeDwellSide;
    private long edgeDwellStartMs;

    /** 松手判定为"点开"的那个 App，等 PhoneScreen 来取 */
    private IPhoneApp pendingLaunch;

    /**
     * 画主屏。
     *
     * @param localMouseX 已撤掉开机缩放的鼠标坐标，理由见类注释
     * @param nowMs       这一帧的时刻。由调用方统一取一次传进来 —— 同一帧里
     *                    翻页动画与边缘停留都要读它，各自取一次会对不齐
     */
    public void render(GuiGraphics g, int phoneLeft, int phoneTop, Font font,
                       long nowMs, boolean animationDone,
                       double localMouseX, double localMouseY) {
        this.phoneLeft = phoneLeft;
        this.phoneTop = phoneTop;

        // 格子起点自己算，不劳调用方传 —— 它就是机身左上角加两个常量，
        // 而这两个常量是网格自己的排版参数。让 PhoneScreen 存一份再递过来，
        // 等于把网格的几何知识拆到两个类里
        this.gridStartX = phoneLeft + PhoneTheme.APP_GRID_PADDING_LEFT;
        this.gridStartY = phoneTop + PhoneTheme.STATUS_BAR_HEIGHT
                + PhoneTheme.APP_GRID_PADDING_TOP;

        this.font = font;
        this.nowMs = nowMs;
        this.animationDone = animationDone;

        // 翻页动画走完就把起点清零。放在这里而不是藏在 slideProgress 里：
        // 那个方法在渲染与 hover 判定两处被当查询用，让它顺手改状态的话，
        // "谁先调到它"就成了行为的一部分 —— 这类耦合出问题时极难看出来
        if (pageSlideStartMs > 0 && nowMs - pageSlideStartMs >= PhoneTheme.PAGE_SLIDE_MS) {
            pageSlideStartMs = 0;
        }

        renderAppGrid(g);

        // 悬停在画完之后算，与搬家前一致（那时是 PhoneScreen.render 末尾那一行）
        updateAppHover(localMouseX, localMouseY);
    }

    // ---- 输入。坐标一律是已撤掉开机缩放的本地坐标 ----

    /**
     * 按下。
     *
     * @return true 表示这一下归主屏管；false 表示按在了空处，由 PhoneScreen
     *         决定是"机身内空白"（那就调 {@link #pressBlank}）还是"机身外"
     */
    public boolean mousePressed(double lx, double ly) {
        // 页码点抢在图标之前：它在图标区【下方】，两者不重叠，但先判它一次
        // 就不必担心将来图标区长高了压过来
        int dot = hitTestPageDot(lx, ly, pageCount());
        if (dot >= 0) {
            homePage = dot;
            hoveredAppIndex = -1;
            return true;
        }

        if (hoveredAppIndex >= 0) {
            // 先记着是哪一格，别急着开 —— 这一下可能是要把它拖走。
            // 到底算点开还是算挪位置，由 mouseReleased 定
            pressedAppIndex = hoveredAppIndex;
            pressX = lx;
            pressY = ly;
            dragTargetIndex = pressedAppIndex;
            draggingApp = false;
            return true;
        }
        return false;
    }

    /** 在机身内的空白处按下了。横着拖它就是翻页 */
    public void pressBlank(double lx, double ly) {
        pressedBlank = true;
        pressX = lx;
        pressY = ly;
    }

    public boolean mouseDragged(double lx, double ly) {
        // 没过阈值之前什么都不做，好让这一下还有机会被当成点击
        if (pressedAppIndex >= 0) {
            if (!draggingApp) {
                if (Math.abs(lx - pressX) < PhoneTheme.APP_DRAG_THRESHOLD
                        && Math.abs(ly - pressY) < PhoneTheme.APP_DRAG_THRESHOLD) {
                    return true;
                }
                draggingApp = true;
            }

            dragX = lx;
            dragY = ly;
            dragTargetIndex = dropIndexAt(lx, ly, PhoneScreenRegistry.getAppCount());
            return true;
        }

        // 空白处横着拖 = 翻页。真手机的划屏，鼠标上的等价物
        if (pressedBlank) {
            double moved = lx - pressX;
            if (Math.abs(moved) >= PhoneTheme.PAGE_SWIPE_THRESHOLD) {
                // 往左划＝内容跟着往左走＝看后面那一页
                goToPage(homePage + (moved < 0 ? 1 : -1));
                // 不管翻没翻成都重设起点：翻成了才能接着往下划连翻两页，
                // 没翻成（到头了）也得重设，否则按住不动会每帧重复触发
                pressX = lx;
            }
            return true;
        }
        return false;
    }

    /**
     * 松手 —— 这一下才定性：刚才那次按下算"点开"还是"挪位置"。
     *
     * 开 App 放在这里而不是按下时，就是为了留出这个判断的余地。代价是点击的
     * 响应晚了一个"松手"，收益是图标能拖；真手机也是松手才启动 App。
     */
    public boolean mouseReleased(double lx, double ly) {
        if (pressedBlank) {
            pressedBlank = false;
            return true;
        }
        if (pressedAppIndex >= 0) {
            int from = pressedAppIndex;
            int to = dragTargetIndex;
            boolean dragged = draggingApp;

            // 先把状态清干净再动作：调用方拿到 launch 请求后可能当场跳去别的
            // 界面，之后再改这几个字段就是在给一个已经不在的界面收尾
            pressedAppIndex = -1;
            dragTargetIndex = -1;
            draggingApp = false;

            if (dragged) {
                PhoneScreenRegistry.moveApp(from, to);
                // 顺序变了，原来那个 hover 下标指的已经不是同一个 App，
                // 留着会让高亮框停在错的格子上直到鼠标下次移动
                hoveredAppIndex = -1;
            } else {
                pendingLaunch = PhoneScreenRegistry.getApp(from);
            }
            return true;
        }
        return false;
    }

    /**
     * 滚轮翻页。真手机是横划，鼠标上最接近的等价物就是滚轮 —— 往下滚＝往后翻，
     * 与所有列表一致。
     *
     * 只有一页、或已经到头时也返回 true：把滚轮吃掉，别让它穿到下面去。
     */
    public boolean mouseScrolled(double scrollY) {
        if (scrollY == 0) return false;
        goToPage(homePage + (scrollY > 0 ? -1 : 1));
        return true;
    }

    /** 松手判定为"点开"的那个 App；没有则 null。取走即清 */
    public IPhoneApp consumeLaunchRequest() {
        IPhoneApp out = pendingLaunch;
        pendingLaunch = null;
        return out;
    }

    private void renderAppGrid(GuiGraphics g) {
        final int is = PhoneTheme.APP_ICON_SIZE;
        final int pageSize = pageSize();

        // 先结算边缘停留：这一帧可能就翻页了，翻完再算下面的预览才是对的
        updateEdgePageFlip();

        // 拖动时按"抽出来、再插进去"的结果画，而不是画原顺序再叠个提示：
        // 玩家看到的直接就是松手后的样子，不必先松手再确认自己摆对没有。
        // 用的是与 moveApp 同一个 HomeLayout.reorder，预览与落定不可能对不上
        // 只有拖动时才拷一份：reorder 会就地改，而 getApps() 交出来的是
        // Collections.unmodifiableList，改它会抛 UnsupportedOperationException。
        // 没拖的时候直接用那个只读视图，省掉每帧一次全量拷贝 —— 主屏是最常
        // 看的一页
        List<IPhoneApp> ordered = PhoneScreenRegistry.getApps();
        IPhoneApp floatingApp = null;
        int floatingIndex = -1;
        if (draggingApp && pressedAppIndex >= 0 && pressedAppIndex < ordered.size()) {
            ordered = new ArrayList<>(ordered);
            floatingApp = ordered.get(pressedAppIndex);
            floatingIndex = Math.max(0, Math.min(dragTargetIndex, ordered.size() - 1));
            HomeLayout.reorder(ordered, pressedAppIndex, floatingIndex);
        }

        // 每帧夹一次页码：卸载 App、换存档都可能让它指到不存在的页
        homePage = HomeLayout.clampPage(homePage, ordered.size(), pageSize);

        float slide = slideProgress();
        if (slide >= 1f) {
            renderPageIcons(g, ordered, homePage, 0, floatingIndex);
        } else {
            // 两页一起画，一进一出。裁到屏幕内，否则滑出去的那页会糊在机身边框上
            int dir = homePage > slideFromPage ? 1 : -1;
            int w = PhoneTheme.PHONE_WIDTH;
            int inX = Math.round((1f - slide) * dir * w);

            g.enableScissor(phoneLeft, phoneTop + PhoneTheme.STATUS_BAR_HEIGHT,
                    phoneLeft + w, dotsTop());
            renderPageIcons(g, ordered, slideFromPage, inX - dir * w, floatingIndex);
            renderPageIcons(g, ordered, homePage, inX, floatingIndex);
            g.disableScissor();
        }

        renderPageDots(g, HomeLayout.pageCount(ordered.size(), pageSize));
        renderEdgeHint(g);

        // 浮起的那张放最后画，才会盖在别的图标上面而不是被它们盖住。
        // 以鼠标为中心，手指按住哪儿它就在哪儿，不会跟手偏出去半格
        if (floatingApp != null) {
            int fx = (int) dragX - is / 2;
            int fy = (int) dragY - is / 2;
            floatingApp.renderIcon(g, fx, fy, is, 0);
            drawAppName(g, floatingApp.getDisplayName().getString(), fx, fy, is);
        }
    }

    /**
     * 画某一页的图标。
     *
     * @param ordered       已经算进拖动预览的完整顺序
     * @param page          画第几页
     * @param xOffset       整页横向偏移，翻页动画用；不在动画里时是 0
     * @param floatingIndex 正被拖着的那个的下标，-1 表示没有
     */
    private void renderPageIcons(GuiGraphics g, List<IPhoneApp> ordered,
                                 int page, int xOffset, int floatingIndex) {
        final int is = PhoneTheme.APP_ICON_SIZE;
        final int cols = PhoneTheme.APP_COLUMNS;
        final int cellW = is + PhoneTheme.APP_GRID_SPACING_X;
        final int cellH = appCellHeight();
        final int pageSize = pageSize();
        final int start = page * pageSize;

        for (int slot = 0; slot < pageSize; slot++) {
            int i = start + slot;
            if (i < 0 || i >= ordered.size()) break;

            int ix = gridStartX + (slot % cols) * cellW + xOffset;
            int iy = gridStartY + (slot / cols) * cellH;

            // 被拖的那一格只留个空槽——它本人跟着鼠标走，最后单独画
            if (i == floatingIndex) {
                PhoneSkin.drawOrFill(g, PhoneSkin.Element.HOME_DROP_SLOT,
                        ix, iy, is, is, PhoneTheme.COLOR_APP_DROP_SLOT);
                continue;
            }

            // 拖动中不画 hover 高亮：那会儿鼠标底下的格子表达的是"要插到这儿"，
            // 再高亮一次容易被理解成"松手是跟它对调"
            if (i == hoveredAppIndex && !draggingApp) {
                g.fill(ix - 2, iy - 2, ix + is + 2, iy + is + 2, PhoneTheme.COLOR_APP_PRESSED);
            }

            IPhoneApp app = ordered.get(i);
            app.renderIcon(g, ix, iy, is, 0);

            drawAppName(g, app.getDisplayName().getString(), ix, iy, is);
        }
    }

    /**
     * 拖着图标停在屏幕左右边上时，自动翻到相邻那一页。
     *
     * 没有这条路的话，App 根本挪不到别的页去：拖动只能落在【当前这一页】的格子里，
     * 而翻页要么得松手（松手就落定了）、要么得腾出另一只手滚滚轮。
     *
     * 每帧算一次而不是挂在 mouseDragged 上：玩家把图标停在边上不动时，鼠标不产生
     * 任何事件，挂在拖动事件上的计时器永远走不完。
     */
    private void updateEdgePageFlip() {
        if (!draggingApp) {
            edgeDwellSide = 0;
            edgeDwellStartMs = 0;
            return;
        }

        final int count = PhoneScreenRegistry.getAppCount();
        final int pageSize = pageSize();

        int side = 0;
        if (dragX < phoneLeft + PhoneTheme.PAGE_EDGE_WIDTH) {
            side = -1;
        } else if (dragX > phoneLeft + PhoneTheme.PHONE_WIDTH - PhoneTheme.PAGE_EDGE_WIDTH) {
            side = 1;
        }

        // 那个方向已经没有页了就当没停在边上——让提示条亮着、等半天却什么都不发生，
        // 比压根不亮更让人困惑
        if (side != 0
                && HomeLayout.clampPage(homePage + side, count, pageSize) == homePage) {
            side = 0;
        }

        if (side != edgeDwellSide) {
            edgeDwellSide = side;
            edgeDwellStartMs = nowMs;
        }
        if (side == 0) return;

        if (nowMs - edgeDwellStartMs >= PhoneTheme.PAGE_EDGE_DWELL_MS) {
            goToPage(homePage + side);
            edgeDwellStartMs = nowMs;   // 按住不放就接着往下翻

            // 页变了，同一个鼠标位置对应的落点也变了——不重算的话，预览还停在
            // 上一页的那一格上
            dragTargetIndex = dropIndexAt(dragX, dragY, count);
        }
    }

    /** 边缘提示条，随停留时长由浅到深。停满就翻页，所以它也是个进度条 */
    private void renderEdgeHint(GuiGraphics g) {
        if (edgeDwellSide == 0 || !draggingApp) return;

        float progress = Math.min(1f,
                (float) (nowMs - edgeDwellStartMs) / Math.max(1, PhoneTheme.PAGE_EDGE_DWELL_MS));

        final int w = PhoneTheme.PAGE_EDGE_WIDTH;
        int x = edgeDwellSide < 0 ? phoneLeft : phoneLeft + PhoneTheme.PHONE_WIDTH - w;
        int top = phoneTop + PhoneTheme.STATUS_BAR_HEIGHT;
        int h = dotsTop() - top;

        // 贴图靠 setColor 调制透明度整张淡入；画完必须还原，否则后面的东西
        // 会跟着一起变淡——这类"全屏莫名其妙变暗"的 bug 极难定位
        g.setColor(1f, 1f, 1f, progress);
        boolean drawn = PhoneSkin.draw(g, PhoneSkin.Element.HOME_PAGE_EDGE, x, top, w, h);
        g.setColor(1f, 1f, 1f, 1f);

        if (!drawn) {
            int alpha = (int) (progress * ((PhoneTheme.COLOR_PAGE_EDGE >>> 24) & 0xFF));
            g.fill(x, top, x + w, top + h,
                    (alpha << 24) | (PhoneTheme.COLOR_PAGE_EDGE & 0x00FFFFFF));
        }
    }

    /**
     * 翻页动画进度，1 表示已经停稳。
     *
     * 缓出（1-(1-t)²）而不是匀速：真手机的翻页是"甩出去再慢慢停住"，匀速滑动
     * 看着像幻灯片切换。
     */
    private float slideProgress() {
        if (pageSlideStartMs <= 0 || PhoneTheme.PAGE_SLIDE_MS <= 0) return 1f;

        long elapsed = nowMs - pageSlideStartMs;
        if (elapsed >= PhoneTheme.PAGE_SLIDE_MS) return 1f;

        float t = (float) elapsed / PhoneTheme.PAGE_SLIDE_MS;
        return 1f - (1f - t) * (1f - t);
    }

    /**
     * 底部那排页码点。
     *
     * 只有一页时【不画】：一个孤零零的点会让人以为还能往旁边划，划了没反应比
     * 什么都不画更让人困惑。
     */
    private void renderPageDots(GuiGraphics g, int pages) {
        if (pages <= 1) return;

        final int size = PhoneTheme.PAGE_DOT_SIZE;
        final int gap = PhoneTheme.PAGE_DOT_SPACING;

        int x = phoneLeft + (PhoneTheme.PHONE_WIDTH - (pages * size + (pages - 1) * gap)) / 2;
        int y = dotsTop() + (PhoneTheme.PAGE_DOTS_HEIGHT - size) / 2;

        for (int p = 0; p < pages; p++) {
            boolean active = p == homePage;
            PhoneSkin.drawOrFill(g,
                    active ? PhoneSkin.Element.HOME_PAGE_DOT_ACTIVE
                           : PhoneSkin.Element.HOME_PAGE_DOT,
                    x, y, size, size,
                    active ? PhoneTheme.COLOR_PAGE_DOT_ACTIVE : PhoneTheme.COLOR_PAGE_DOT);
            x += size + gap;
        }
    }

    /**
     * 点在了第几个页码点上，没点中返回 -1。
     *
     * 判定区比那 3×3 的点大一圈——按点本身的大小算的话，得对着三个像素点才能
     * 跳页，那不叫"能点"，叫"能瞄准"。
     */
    private int hitTestPageDot(double lx, double ly, int pages) {
        if (pages <= 1) return -1;

        int top = dotsTop();
        if (ly < top || ly >= top + PhoneTheme.PAGE_DOTS_HEIGHT) return -1;

        final int size = PhoneTheme.PAGE_DOT_SIZE;
        final int gap = PhoneTheme.PAGE_DOT_SPACING;
        final int step = size + gap;

        int x = phoneLeft + (PhoneTheme.PHONE_WIDTH - (pages * size + (pages - 1) * gap)) / 2;
        int idx = (int) Math.floor((lx - (x - gap / 2.0)) / step);
        return (idx >= 0 && idx < pages) ? idx : -1;
    }

    /**
     * 主屏一格的高度：图标加底下那行名字。
     *
     * 画、hover 判定、拖动落点判定三处都得用同一个值，抽出来是免得改一处漏两处——
     * 那种漏改的表现是"看着点在图标上，却没反应"，很难往格子高度上想。
     */
    private int appCellHeight() {
        return PhoneTheme.APP_ICON_SIZE
                + (int)(font.lineHeight * PhoneTheme.APP_NAME_SCALE) + 4;
    }

    /** 页码点那一条的顶边。图标区到此为止，再往下是导航栏 */
    private int dotsTop() {
        return phoneTop + PhoneTheme.PHONE_HEIGHT
                - PhoneTheme.NAV_BAR_HEIGHT - PhoneTheme.PAGE_DOTS_HEIGHT;
    }

    /** 这块屏幕一页放得下几行图标 */
    private int rowsPerPage() {
        return HomeLayout.rowsThatFit(dotsTop() - gridStartY, appCellHeight(), PhoneTheme.APP_ROWS);
    }

    /** 一页几个 App */
    private int pageSize() {
        return PhoneTheme.APP_COLUMNS * rowsPerPage();
    }

    /** 主屏一共几页 */
    private int pageCount() {
        return HomeLayout.pageCount(PhoneScreenRegistry.getAppCount(), pageSize());
    }

    /**
     * 翻到第几页。
     *
     * @return 真的换页了才 true；已经在头一页还要往前翻，返回 false
     */
    private boolean goToPage(int page) {
        int target = HomeLayout.clampPage(page, PhoneScreenRegistry.getAppCount(), pageSize());
        if (target == homePage) return false;

        slideFromPage = homePage;
        homePage = target;

        // 开场动画那 150ms 里不滑：裁剪矩形按屏幕坐标算，而那会儿整个手机
        // 正被缩放着画，两者对不上，滑出来的两页会在边缘被切歪
        pageSlideStartMs = animationDone ? System.currentTimeMillis() : 0;

        // 换页之后鼠标底下换成了另一个 App，旧的 hover 下标指的已经不是它了
        hoveredAppIndex = -1;
        return true;
    }

    /**
     * 鼠标位置对应的落点（全局下标），用于拖动时决定松手插到哪儿。
     *
     * 落点是"当前这一页的第几格"再加上页偏移——所以在第二页拖动时，松手插的是
     * 第二页的位置，而不是从头数的那一格。
     */
    private int dropIndexAt(double lx, double ly, int count) {
        if (count <= 0) return -1;

        int slot = HomeLayout.slotAt(lx, ly, gridStartX, gridStartY,
                PhoneTheme.APP_ICON_SIZE + PhoneTheme.APP_GRID_SPACING_X, appCellHeight(),
                PhoneTheme.APP_COLUMNS, rowsPerPage());

        return HomeLayout.dropIndex(homePage, slot, pageSize(), count);
    }

    /**
     * 画图标下面那行名字。
     *
     * 截断、居中、缩放三件事全在 {@link GuiUtil#drawIconLabel} 里，商店的
     * 格子共用同一份——这两处此前各抄了一遍变换代码，也各自都没做截断。
     *
     * 可用宽度是格子步距（图标宽 + 一个间距），不是图标宽：名字本来就允许
     * 比图标宽一点，只是不能宽到压着邻居。
     */
    private void drawAppName(GuiGraphics g, String name, int ix, int iy, int is) {
        GuiUtil.drawIconLabel(g, font, name, ix, iy, is,
                is + PhoneTheme.APP_GRID_SPACING_X,
                PhoneTheme.APP_NAME_SCALE, FontPalette.appName());
    }

    private void updateAppHover(double localX, double localY) {
        // 收的是【已经撤掉开机缩放】的手机本地坐标。反变换留在 PhoneScreen
        // 那边做 —— 动画是它的事，本类只认本地坐标。搬家前这两行在这儿，
        // 而那个算式与正主分开住过一次，改动画曲线时只有一个人被想起来，
        // 结果是"开机那一瞬间点图标点不准"，短到没人抓得住
        int lx = (int) localX;
        int ly = (int) localY;

        final int count = PhoneScreenRegistry.getAppCount();
        final int is = PhoneTheme.APP_ICON_SIZE;
        final int cols = PhoneTheme.APP_COLUMNS;
        final int cellW = is + PhoneTheme.APP_GRID_SPACING_X;
        final int cellH = appCellHeight();
        final int pageSize = pageSize();
        final int start = homePage * pageSize;

        // 只看当前这一页：别的页的图标压根没画出来，"停在"它们上面没有意义
        hoveredAppIndex = -1;

        // 正在翻页动画里就不认 hover：图标那会儿还在半路上，按它算命中会点开
        // 一个不在鼠标底下的 App
        if (slideProgress() < 1f) return;
        for (int slot = 0; slot < pageSize; slot++) {
            int i = start + slot;
            if (i >= count) break;

            int ix = gridStartX + (slot % cols) * cellW;
            int iy = gridStartY + (slot / cols) * cellH;
            if (lx >= ix && lx <= ix + is && ly >= iy && ly <= iy + is + 6) {
                hoveredAppIndex = i;   // 全局下标，不是 slot
                return;
            }
        }
    }
}
