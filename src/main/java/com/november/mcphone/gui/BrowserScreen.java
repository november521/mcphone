package com.november.mcphone.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.november.mcphone.client.browser.BrowserBackends;
import com.november.mcphone.client.browser.IBrowser;
import com.november.mcphone.client.browser.IBrowserBackend;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

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

    /** 面板四周留出的边距占屏幕的比例。九成面积，一成留白 */
    private static final float MARGIN_RATIO = 0.05f;

    /** 顶部地址栏那一条的高度 */
    private static final int BAR_H = 18;

    /** 导航按钮的宽度 */
    private static final int NAV_BTN_W = 14;

    /** 退出后回到哪儿。通常是手机界面 */
    private final Screen parent;

    private IBrowser browser;

    /** 后端不可用时显示的原因。为 null 表示一切正常 */
    private Component failure;

    private EditBox urlBox;

    // 面板与视口的几何，每帧 layout 时算一次，输入处理复用
    private int panelX, panelY, panelW, panelH;
    private int viewX, viewY, viewW, viewH;

    /** 上一次告诉浏览器的尺寸，用来避免每帧都 resize */
    private int lastPxW = -1, lastPxH = -1;

    /** 地址栏里显示的是不是玩家正在编辑的内容。是的话就别用网页地址覆盖它 */
    private boolean urlEdited = false;

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

        urlBox = new EditBox(font, viewX + NAV_BTN_W * 3 + 6, panelY + 3,
                panelW - NAV_BTN_W * 3 - 10, BAR_H - 6,
                Component.translatable("mcphone.browser.url"));
        urlBox.setMaxLength(2000);
        urlBox.setResponder(s -> urlEdited = true);
        addRenderableWidget(urlBox);

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

    /** 算出面板与视口的位置。init 与每帧渲染前都要保证它是新的 */
    private void layout() {
        int margin = Math.max(8, (int) (Math.min(width, height) * MARGIN_RATIO));
        panelX = margin;
        panelY = margin;
        panelW = width - margin * 2;
        panelH = height - margin * 2;

        viewX = panelX;
        viewY = panelY + BAR_H;
        viewW = panelW;
        viewH = panelH - BAR_H;
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
        // 必须关。浏览器背后是一个真的 Chromium 渲染进程，不关的话玩家退出界面
        // 之后，一个看不见的网页还在后台放视频、发网络请求
        if (browser != null) {
            browser.close();
            browser = null;
        }
        minecraft.setScreen(parent);
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

        // 部件（地址栏）画在最后，免得被网页盖住
        super.render(g, mouseX, mouseY, partialTick);
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
                panelX, panelY, panelW, BAR_H, PhoneTheme.COLOR_STATUS_BAR);

        drawNavButton(g, 0, "◀", mouseX, mouseY, browser != null && browser.canGoBack());
        drawNavButton(g, 1, "▶", mouseX, mouseY, browser != null && browser.canGoForward());
        drawNavButton(g, 2, "↻", mouseX, mouseY, browser != null);

        // 玩家没在编辑时，地址栏跟着网页走——点了链接、跳转之后要能看到新地址。
        // 正在编辑时不覆盖，否则打字打到一半会被网页刷掉
        if (browser != null && !urlEdited && !urlBox.isFocused()) {
            String current = browser.currentUrl();
            if (!current.equals(urlBox.getValue())) urlBox.setValue(current);
        }
    }

    private void drawNavButton(GuiGraphics g, int index, String glyph,
                               int mouseX, int mouseY, boolean enabled) {
        int x = panelX + 2 + index * NAV_BTN_W;
        int y = panelY + 2;
        boolean hovered = enabled && hit(mouseX, mouseY, x, y, NAV_BTN_W, BAR_H - 4);

        int color = !enabled ? PhoneTheme.COLOR_BUTTON_DISABLED
                : hovered ? PhoneTheme.FONT_COLOR_TITLE
                : PhoneTheme.FONT_COLOR_BODY;
        g.drawCenteredString(font, glyph, x + NAV_BTN_W / 2, y + 3, color);
    }

    private static boolean hit(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private boolean inViewport(double mx, double my) {
        return hit(mx, my, viewX, viewY, viewW, viewH);
    }

    /** GUI 坐标 → 相对视口左上角的真实像素 */
    private int browserX(double mouseX) { return toPx(mouseX - viewX); }

    private int browserY(double mouseY) { return toPx(mouseY - viewY); }

    // ============================================================
    //  输入
    // ============================================================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // 导航按钮
        for (int i = 0; i < 3; i++) {
            int x = panelX + 2 + i * NAV_BTN_W;
            if (hit(mouseX, mouseY, x, panelY + 2, NAV_BTN_W, BAR_H - 4)) {
                onNavButton(i);
                return true;
            }
        }

        if (inViewport(mouseX, mouseY) && browser != null) {
            urlBox.setFocused(false);
            urlEdited = false;
            browser.setFocus(true);
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

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (browser != null && inViewport(mouseX, mouseY)) {
            browser.mouseRelease(browserX(mouseX), browserY(mouseY), button);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (browser != null && inViewport(mouseX, mouseY)) {
            browser.mouseMove(browserX(mouseX), browserY(mouseY));
        }
        super.mouseMoved(mouseX, mouseY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (browser != null && inViewport(mouseX, mouseY)) {
            browser.mouseWheel(browserX(mouseX), browserY(mouseY), scrollY);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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

    /**
     * 把地址栏里的东西当地址打开。
     *
     * 没写协议的补 https://。不补的话 Chromium 会把 "bilibili.com" 当成一个
     * 相对路径，得到一个打不开的地址，而玩家只会觉得"输网址没反应"。
     */
    private void navigateToTypedUrl() {
        if (browser == null) return;
        String input = urlBox.getValue().trim();
        if (input.isEmpty()) return;

        String url = input.contains("://") ? input : "https://" + input;
        browser.loadUrl(url);
        urlBox.setFocused(false);
        urlEdited = false;
        browser.setFocus(true);
    }

    @Override
    public void resize(Minecraft minecraft, int w, int h) {
        super.resize(minecraft, w, h);
        layout();
        syncBrowserSize();
    }
}
