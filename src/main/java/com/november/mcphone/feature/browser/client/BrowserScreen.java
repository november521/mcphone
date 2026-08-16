package com.november.mcphone.feature.browser.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.feature.browser.client.BrowserBackends;
import com.november.mcphone.feature.browser.client.IBrowser;
import com.november.mcphone.feature.browser.client.IBrowserBackend;
import com.november.mcphone.core.client.GuiUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 浏览器界面 —— 一块比手机大得多的面板，装整个网页。
 *
 * ============================================================
 * 为什么不画在手机屏幕里
 * ============================================================
 *
 * 手机屏幕是 120×200 像素。网页塞进去不是"小"，是根本读不了——一行正文占不下
 * 十个字。所以浏览器是唯一一个跳出机身的 App：点开后是一块居中的大面板，
 * 占屏幕九成，退出就回到手机。
 *
 * 不做成满屏是刻意的：留一圈边框，玩家才看得出这是手机里开出来的一个东西，
 * 而不是把游戏切走了。
 *
 * ============================================================
 * 坐标有两套，别混
 * ============================================================
 *
 * Minecraft 的界面坐标是 GUI 缩放【之后】的，而 Chromium 渲染用的是真实像素。
 * 两者差一个 guiScale 倍数。传错的直接后果是：网页画面糊成一团（尺寸给小了），
 * 或者鼠标点在 A 处、网页以为你点了 B 处（坐标没换算）。
 *
 * 本类的约定：带 px 后缀的是真实像素，其余都是 GUI 坐标。
 */
public final class BrowserScreen extends Screen {

    /**
     * 打开时的默认地址。
     *
     * 只此一处，想换主页改这一行。刻意不做成配置项：配置要读要写要同步，
     * 而"改主页"这个需求还没出现过，先留常量，真有人要再说。
     */
    private static final String HOME_URL = "https://www.bing.com";

    /**
     * 地址栏里打的不像地址时，拿它去搜。
     *
     * 与 HOME_URL 是同一家，换搜索引擎时两处一起换。没有并成一个常量：首页是个
     * 光地址，这个后面要拼查询串，形状不一样，拼接的写法藏在一个常量里更容易出错。
     */
    private static final String SEARCH_URL = "https://www.bing.com/search?q=";

    /** 面板四周留出的边距占屏幕的比例。九成面积，一成留白 */
    private static final float MARGIN_RATIO = 0.05f;

    /** 顶部地址栏那一条的高度 */
    private static final int BAR_H = 22;

    /** 导航按钮的宽度。三个键并排，够手指头找得到 */
    private static final int NAV_BTN_W = 22;

    /** 导航图标的放大倍数。默认字号在这块大面板上显得像针尖 */
    private static final float NAV_GLYPH_SCALE = 1.6f;

    // ============================================================
    //  送给网页的修饰键掩码 —— 用 GLFW 那一套，不是 AWT 的
    // ============================================================
    //
    // 这件事必须写在这儿：MCEF 把我们给的 modifiers 原样塞进 CefKeyEvent 与
    // CefMouseWheelEvent，而它自己判断时用的就是 GLFW 的值——字节码里 Alt 判的是
    // 4，那是 GLFW_MOD_ALT；AWT 的 InputEvent 里 4 是 META、ALT 是 8。
    //
    // 混用不会报错，只会让快捷键永远不触发，而这种毛病极难查。

    private static final int MOD_SHIFT = 1;   // GLFW_MOD_SHIFT
    private static final int MOD_CTRL = 2;    // GLFW_MOD_CONTROL
    private static final int MOD_ALT = 4;     // GLFW_MOD_ALT

    /** 退出后回到哪儿。通常是手机界面 */
    private final Screen parent;

    private IBrowser browser;

    /** 后端不可用时显示的原因。为 null 表示一切正常 */
    private Component failure;

    private EditBox urlBox;

    // 面板与视口的几何，每帧 layout 时算一次，输入处理复用。
    // 1.1.22 起视口就是整块面板——工具条挪到了面板【上方】的留白里
    private int panelX, panelY, panelW, panelH;
    private int viewX, viewY, viewW, viewH;

    /** 工具条那一行的 y。它在面板外面，不占网页空间 */
    private int barY;

    /** 上一次告诉浏览器的尺寸，用来避免每帧都 resize */
    private int lastPxW = -1, lastPxH = -1;

    /** 地址栏里显示的是不是玩家正在编辑的内容。是的话就别用网页地址覆盖它 */
    private boolean urlEdited = false;

    /**
     * 正在由代码写入地址栏，此刻的回调不算"玩家在编辑"。
     *
     * 不加这个开关的话：setValue 会触发 EditBox 的 responder，responder 把
     * urlEdited 置真，于是从第一次同步网页地址开始，地址栏就再也不更新了——
     * 玩家点了链接、页面跳了，地址栏却停在首页，看起来像"跳转没发生"。
     */
    private boolean writingUrl = false;

    /**
     * 哪些鼠标键是在视口里按下的，按位记（第 n 位对应 button n）。
     *
     * ============================================================
     * 为什么非记不可
     * ============================================================
     *
     * MCEF 内部维护一个 btnMask：sendMousePress 把对应的位【或】进去，
     * sendMouseRelease 才把它清掉，而 sendMouseMove 每次都把当前的 btnMask
     * 当作 modifier 发给 Chromium。
     *
     * 于是漏送一次松手的代价不是"这一下没生效"，而是那个位再也清不掉——此后
     * 【每一次鼠标移动都会被网页当成"按住键在拖"】：满页乱选文字、点什么都变成
     * 拖拽，而且不会自己恢复。
     *
     * 而漏送是很容易发生的：玩家在网页里选中一段文字往外拖一点再松手，指针已经
     * 不在视口里了。所以判断"这次松手要不要送"不能看指针现在在哪，要看当初是在
     * 哪按下的。
     */
    private int viewportButtons = 0;

    public BrowserScreen(Screen parent) {
        super(Component.translatable("mcphone.app.browser"));
        this.parent = parent;
    }

    // ============================================================
    //  生命周期
    // ============================================================

    @Override
    protected void init() {
        super.init();
        layout();

        urlBox = new EditBox(font, panelX + NAV_BTN_W * 3 + 6, barY + 4,
                panelW - NAV_BTN_W * 3 - 20, BAR_H - 8,
                Component.translatable("mcphone.browser.url"));
        urlBox.setMaxLength(2000);
        // 只有玩家自己敲进去的才算编辑，代码写入不算——理由见 writingUrl 的注释
        urlBox.setResponder(s -> { if (!writingUrl) urlEdited = true; });
        addRenderableWidget(urlBox);

        // 新的地址栏是空的，"玩家正在编辑"这个状态也得跟着清掉。
        //
        // init() 在窗口大小变化时会被再调一次：rebuildWidgets 清掉旧部件，上面
        // new 出一个空的 EditBox——而 urlEdited 是实例字段，活了下来。玩家如果
        // 正好在改地址栏时拖了一下窗口，它就永远停在 true，同步条件 !urlEdited
        // 再也不成立，地址栏从此空着、不跟随网页，除非点一下视口才复位。
        //
        // 与 1.1.24 修掉的"地址栏冻死"是同一类毛病，只是触发条件换成了 resize。
        urlEdited = false;

        // init() 在窗口大小变化时会被再调一次。浏览器只建一次，之后只改尺寸——
        // 每次重建都会丢掉玩家的浏览历史与页面状态，而窗口拖一下就触发
        if (browser == null && failure == null) {
            IBrowserBackend backend = BrowserBackends.get();
            if (!backend.isAvailable()) {
                failure = backend.unavailableReason();
            } else {
                browser = backend.create(HOME_URL, toPx(viewW), toPx(viewH));
                if (browser == null) failure = backend.unavailableReason();
            }
        }
        syncBrowserSize();
    }

    /**
     * 算出工具条、面板与视口的位置。init 与每帧渲染前都要保证它是新的。
     *
     * 工具条【不在】面板里，而是浮在面板上方的留白里。原先它嵌在面板顶部，
     * 一来吃掉网页的高度，二来看着像网页自己的一部分。挪出来之后面板整块都是
     * 网页，工具条也真的"更上方"了。
     *
     * 上留白因此要保证放得下它，所以单独算，不跟左右底共用一个值。
     */
    private void layout() {
        int base = (int) (Math.min(width, height) * MARGIN_RATIO);
        int side = Math.max(8, base);
        int top = Math.max(BAR_H + 6, base);
        int bottom = Math.max(8, base);

        panelX = side;
        panelW = width - side * 2;
        panelY = top;
        panelH = height - top - bottom;

        // 工具条贴着面板上沿往上放，留 3 像素缝
        barY = panelY - BAR_H - 3;

        // 视口＝整块面板
        viewX = panelX;
        viewY = panelY;
        viewW = panelW;
        viewH = panelH;
    }

    /** GUI 坐标 → 真实像素 */
    private int toPx(double guiValue) {
        return (int) Math.round(guiValue * minecraft.getWindow().getGuiScale());
    }

    /** 视口尺寸变了才通知浏览器。resize 会让 Chromium 重排整页，不能每帧调 */
    private void syncBrowserSize() {
        if (browser == null) return;
        int pxW = Math.max(1, toPx(viewW));
        int pxH = Math.max(1, toPx(viewH));
        if (pxW == lastPxW && pxH == lastPxH) return;
        browser.resize(pxW, pxH);
        lastPxW = pxW;
        lastPxH = pxH;
    }

    @Override
    public void onClose() {
        // 这里只管回到手机。浏览器的释放在 removed() 里——setScreen 会先调它
        minecraft.setScreen(parent);
    }

    /**
     * 界面被换掉时释放浏览器。
     *
     * ============================================================
     * 为什么写在 removed() 而不是 onClose()
     * ============================================================
     *
     * onClose 只在玩家【主动关闭】（按 ESC）时走。而 Minecraft.setScreen 换界面
     * 时调的是另一个：
     *
     *     if (old != null && guiScreen != old) { ...; old.removed(); }
     *
     * 断线、退回主菜单、世界卸载、死亡界面弹出、别的模组切界面——这些路径一条
     * 都不经过 onClose。释放写在那儿的话，浏览器就不会被关，而它背后是一个真的
     * Chromium 渲染进程：玩家已经离开界面了，一个看不见的网页还在放视频、发网络
     * 请求，直到退出游戏。
     *
     * removed() 覆盖全部退出路径，包括 ESC——onClose 里那句 setScreen 自己就会
     * 触发它。窗口大小变化走的是 init()，不经过这里，不会误伤。
     */
    @Override
    public void removed() {
        super.removed();
        if (browser != null) {
            browser.close();
            browser = null;
        }
    }

    /** 浏览器自己在跑，界面不需要暂停游戏 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ============================================================
    //  渲染
    // ============================================================

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        layout();
        syncBrowserSize();

        // 暗化与模糊只画这一次。
        //
        // 【别在末尾调 super.render()】：Screen.render 的第一行就是
        // this.renderBackground(...)，那会把这层暗化【再画一遍，盖在网页上】——
        // 表现就是网页糊了一层雾，而且只有在网页上才看得出来。部件（地址栏）
        // 由本方法末尾自己画，不借 super 的手。
        renderBackground(g, mouseX, mouseY, partialTick);

        // 面板底 —— 贴图优先，没贴图用主题色兜底
        PhoneSkin.drawOrFill(g, PhoneSkin.Element.BROWSER_PANEL,
                panelX, panelY, panelW, panelH, PhoneTheme.COLOR_SCREEN_BG);

        renderBar(g, mouseX, mouseY);

        if (browser != null) {
            drawBrowser(g);
        } else {
            String msg = (failure != null ? failure : Component.empty()).getString();
            g.drawCenteredString(font, msg, viewX + viewW / 2,
                    viewY + viewH / 2 - font.lineHeight, PhoneTheme.FONT_COLOR_BODY);
            g.drawCenteredString(font,
                    Component.translatable("mcphone.browser.need_mcef").getString(),
                    viewX + viewW / 2, viewY + viewH / 2 + 2, PhoneTheme.FONT_COLOR_SUBTLE);
        }

        // 地址栏自己画。见上面那段注释：这里【不能】调 super.render()
        urlBox.render(g, mouseX, mouseY, partialTick);
    }

    /**
     * 把浏览器那张纹理贴到视口上。
     *
     * 这是全类唯一一处版本相关的代码：1.20.1 与 1.21.x 的 BufferBuilder 写法
     * 完全不同。移植时改这一个方法就够，其余部分与 MC 版本无关。
     *
     * 用矩阵重载而不是裸坐标：GuiGraphics 的位姿此刻未必是单位阵（父类可能平移
     * 过），忽略它会让画面偏移，而这种偏移只在某些缩放下才看得出来。
     */
    private void drawBrowser(GuiGraphics g) {
        int texture = browser.textureId();
        if (texture <= 0) return;

        Matrix4f matrix = g.pose().last().pose();

        RenderSystem.disableDepthTest();
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);

        BufferBuilder buffer = Tesselator.getInstance()
                .begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        // v 轴是反的：Chromium 的画面原点在左上，OpenGL 纹理坐标原点在左下
        buffer.addVertex(matrix, viewX, viewY + viewH, 0)
                .setUv(0f, 1f).setColor(255, 255, 255, 255);
        buffer.addVertex(matrix, viewX + viewW, viewY + viewH, 0)
                .setUv(1f, 1f).setColor(255, 255, 255, 255);
        buffer.addVertex(matrix, viewX + viewW, viewY, 0)
                .setUv(1f, 0f).setColor(255, 255, 255, 255);
        buffer.addVertex(matrix, viewX, viewY, 0)
                .setUv(0f, 0f).setColor(255, 255, 255, 255);
        BufferUploader.drawWithShader(buffer.build());

        // 把纹理槽还原。留着的话，之后画别的东西会莫名其妙糊上一层网页
        RenderSystem.setShaderTexture(0, 0);
        RenderSystem.enableDepthTest();
    }

    /** 顶部那一条：后退、前进、刷新，加地址栏 */
    private void renderBar(GuiGraphics g, int mouseX, int mouseY) {
        PhoneSkin.drawOrFill(g, PhoneSkin.Element.BROWSER_BAR,
                panelX, barY, panelW, BAR_H, PhoneTheme.COLOR_STATUS_BAR);

        drawNavButton(g, 0, "◀", mouseX, mouseY, browser != null && browser.canGoBack());
        drawNavButton(g, 1, "▶", mouseX, mouseY, browser != null && browser.canGoForward());
        drawNavButton(g, 2, "↻", mouseX, mouseY, browser != null);
        drawLoadingDot(g);

        // 玩家没在编辑时，地址栏跟着网页走——点了链接、跳转之后要能看到新地址。
        // 正在编辑时不覆盖，否则打字打到一半会被网页刷掉
        if (browser != null && !urlEdited && !urlBox.isFocused()) {
            String current = browser.currentUrl();
            if (!current.equals(urlBox.getValue())) {
                writingUrl = true;
                urlBox.setValue(current);
                writingUrl = false;
            }
        }
    }

    /**
     * 一个导航键。
     *
     * 图标要放大画：默认字号在这块大面板上像针尖，尤其刷新那个 ↻。放大用的是
     * 与主屏 App 名同一套写法——先 translate 到目标位置再 scale，最后在原点画。
     * 缩放一个非原点坐标会让图标越大偏得越多，1.0.42 在 App 名上栽过一次。
     */
    private void drawNavButton(GuiGraphics g, int index, String glyph,
                               int mouseX, int mouseY, boolean enabled) {
        int x = panelX + 2 + index * NAV_BTN_W;
        int y = barY + 2;
        int h = BAR_H - 4;
        boolean hovered = enabled && GuiUtil.hit(mouseX, mouseY, x, y, NAV_BTN_W, h);

        // 悬停时给个底，让人看得出这是个键而不是一个字
        if (hovered) g.fill(x, y, x + NAV_BTN_W, y + h, PhoneTheme.COLOR_APP_PRESSED);

        int color = !enabled ? PhoneTheme.COLOR_BUTTON_DISABLED
                : hovered ? PhoneTheme.FONT_COLOR_TITLE
                : PhoneTheme.FONT_COLOR_BODY;

        float sc = NAV_GLYPH_SCALE;
        float gw = font.width(glyph) * sc;
        float gh = font.lineHeight * sc;

        g.pose().pushPose();
        g.pose().translate(x + (NAV_BTN_W - gw) / 2f, y + (h - gh) / 2f, 0);
        g.pose().scale(sc, sc, 1f);
        g.drawString(font, glyph, 0, 0, color, false);
        g.pose().popPose();
    }

    /**
     * 加载指示 —— 正在加载时右上角亮一个点。
     *
     * 它不只是装饰：点了链接之后这里亮一下，就说明点击送到了、导航发起了；
     * 没亮说明点击根本没到网页。少了它，这两种毛病在玩家眼里长得一模一样，
     * 只能靠翻日志区分。
     */
    private void drawLoadingDot(GuiGraphics g) {
        if (browser == null || !browser.isLoading()) return;
        int size = 5;
        int x = panelX + panelW - size - 3;
        int y = barY + (BAR_H - size) / 2;
        g.fill(x, y, x + size, y + size, PhoneTheme.FONT_COLOR_PRICE);
    }

    private boolean inViewport(double mx, double my) {
        return GuiUtil.hit(mx, my, viewX, viewY, viewW, viewH);
    }

    /**
     * GUI 坐标 → 相对视口左上角的真实像素。
     *
     * 先把坐标【夹回视口内】再换算。指针在视口里时夹取什么也没做；跑到外面时
     * （拖拽会有这种情况，见 viewportButtons）网页拿到的是边界上的一个合法坐标，
     * 而不是负数或者超出画面的值。
     */
    private int browserX(double mouseX) {
        return toPx(Math.max(viewX, Math.min(mouseX, viewX + viewW)) - viewX);
    }

    private int browserY(double mouseY) {
        return toPx(Math.max(viewY, Math.min(mouseY, viewY + viewH)) - viewY);
    }

    // ============================================================
    //  输入
    // ============================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 导航按钮
        for (int i = 0; i < 3; i++) {
            int x = panelX + 2 + i * NAV_BTN_W;
            if (GuiUtil.hit(mouseX, mouseY, x, barY + 2, NAV_BTN_W, BAR_H - 4)) {
                onNavButton(i);
                return true;
            }
        }

        if (inViewport(mouseX, mouseY) && browser != null) {
            urlBox.setFocused(false);
            urlEdited = false;
            browser.setFocus(true);
            // 记下这个键是在视口里按的，松手时无论指针在哪都得送过去
            viewportButtons |= 1 << button;
            browser.mousePress(browserX(mouseX), browserY(mouseY), button);
            return true;
        }

        // 点到地址栏或别处，交给部件；同时把焦点从网页收回来，否则打字会两边都收到
        if (browser != null) browser.setFocus(false);
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void onNavButton(int index) {
        if (browser == null) return;
        switch (index) {
            case 0 -> { if (browser.canGoBack()) browser.goBack(); }
            case 1 -> { if (browser.canGoForward()) browser.goForward(); }
            default -> browser.reload();
        }
        urlEdited = false;
    }

    /**
     * 松手看的是"当初在哪按的"，不是"现在指针在哪"。
     *
     * 拖出视口再松手是常事（选中一段文字往外一带），照指针位置判断的话这一次
     * 松手就丢了，而丢掉的后果不止这一下——理由见 viewportButtons 的注释。
     */
    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean pressedHere = (viewportButtons & (1 << button)) != 0;
        viewportButtons &= ~(1 << button);

        if (pressedHere && browser != null) {
            browser.mouseRelease(browserX(mouseX), browserY(mouseY), button);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        // 正拖着的时候即使指针出了视口也要接着送：网页靠这些移动更新选区，
        // 断了的话选中范围会停在边界上，玩家看着像"拖不动了"
        if (browser != null && (viewportButtons != 0 || inViewport(mouseX, mouseY))) {
            browser.mouseMove(browserX(mouseX), browserY(mouseY));
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (browser != null && inViewport(mouseX, mouseY)) {
            browser.mouseWheel(browserX(mouseX), browserY(mouseY), scrollY, currentModifiers());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * 此刻按着哪些修饰键。
     *
     * mouseScrolled 的签名里没有 modifiers，只能自己查——而不查的代价是
     * Ctrl+滚轮缩放永远不触发（后端拿它区分"滚动"和"缩放"）。
     *
     * Screen 这三个静态方法在 macOS 上把 Command 当作 Ctrl，正好对上"Mac 上用
     * ⌘ 缩放"的习惯，不用另外分平台。
     */
    private static int currentModifiers() {
        int mods = 0;
        if (hasShiftDown()) mods |= MOD_SHIFT;
        if (hasControlDown()) mods |= MOD_CTRL;
        if (hasAltDown()) mods |= MOD_ALT;
        return mods;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // 地址栏拿着焦点时，键盘归它。回车＝跳转
        if (urlBox.isFocused()) {
            if (keyCode == 257 || keyCode == 335) {   // GLFW_KEY_ENTER / KP_ENTER
                navigateToTypedUrl();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        // ESC 关界面，不转发给网页：否则玩家会发现自己按了退出却什么都没发生
        if (keyCode == 256) {
            onClose();
            return true;
        }

        if (browser != null) {
            browser.keyPress(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean keyReleased(int keyCode, int scanCode, int modifiers) {
        if (!urlBox.isFocused() && browser != null) {
            browser.keyRelease(keyCode, scanCode, modifiers);
            return true;
        }
        return super.keyReleased(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (urlBox.isFocused()) return super.charTyped(codePoint, modifiers);
        if (codePoint == 0) return false;
        if (browser != null) {
            browser.charTyped(codePoint, modifiers);
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    /** 把地址栏里的东西打开：像地址就当地址，不像就拿去搜 */
    private void navigateToTypedUrl() {
        if (browser == null) return;
        String input = urlBox.getValue().trim();
        if (input.isEmpty()) return;

        browser.loadUrl(toUrl(input));
        urlBox.setFocused(false);
        urlEdited = false;
        browser.setFocus(true);
    }

    /**
     * 玩家敲进去的这一串，是地址还是搜索词。
     *
     * ============================================================
     * 为什么要判，不能一律补 https://
     * ============================================================
     *
     * 地址栏兼搜索框是现在所有浏览器的默认行为，玩家一定会直接在里面打关键词。
     * 一律补协议头的话，"红石中继器怎么做"会变成 https://红石中继器怎么做——一个
     * 必然打不开的地址，而玩家只会觉得这浏览器搜不了东西。
     *
     * ============================================================
     * 规则只有三条，刻意简单
     * ============================================================
     *
     * 一、带 :// 就是地址，原样打开。file:// 之类也走这条。
     * 二、带空格的一定不是地址——域名里不允许有空格。
     * 三、剩下的看有没有点：有点当域名补 https://，没点当搜索词。
     *
     * 中文关键词天然既没有点也没有空格，正好落进第三条的搜索那一半。
     *
     * 已知会被判成搜索词的：localhost、不带点的内网主机名。接受这个代价——真要
     * 开本地服务的人打得出 http://localhost，而反过来把每个不带点的词都先当域名
     * 去解析，代价是玩家每搜一个词都要先等一次 DNS 失败。
     */
    private static String toUrl(String input) {
        if (input.contains("://")) return input;
        if (!input.contains(" ") && input.contains(".")) return "https://" + input;
        return SEARCH_URL + URLEncoder.encode(input, StandardCharsets.UTF_8);
    }

    @Override
    public void resize(Minecraft minecraft, int w, int h) {
        super.resize(minecraft, w, h);
        layout();
        syncBrowserSize();
    }
}
