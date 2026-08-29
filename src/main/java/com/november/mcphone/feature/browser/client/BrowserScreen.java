package com.november.mcphone.feature.browser.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.november.mcphone.core.client.FontPalette;
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
 * 浏览器界面：一块居中、占屏幕九成的大面板，装整个网页。
 * 坐标有两套，别混：带 px 后缀的是真实像素（Chromium 用），其余是 GUI 缩放后的坐标，差一个 guiScale 倍数。
 *
 * 与 NeoForge 1.21.1 那一支的差别只有三处，全是原版 API 的形状变了，
 * 逻辑一行没动：
 *
 *   drawBrowser      整个 BufferBuilder 的写法。1.21 是
 *                    Tesselator.begin(...) 直接给一个 BufferBuilder、
 *                    addVertex().setUv().setColor() 链式；1.20.1 要先
 *                    getBuilder() 再 begin()，且每个顶点末尾必须 endVertex()
 *   render           renderBackground 在 1.20.1 上只收 GuiGraphics
 *   mouseScrolled    1.20.1 是三参（一个 delta），1.21 拆成了 scrollX/scrollY
 *
 * MCEF 自己的 API 两边一模一样，所以 {@link McefBackend} 是逐字搬过来的。
 */
public final class BrowserScreen extends Screen {

    private static final String HOME_URL = "https://www.bing.com";

    /** 地址栏里打的不像地址时拿它去搜。与 HOME_URL 同一家，换搜索引擎两处一起换 */
    private static final String SEARCH_URL = "https://www.bing.com/search?q=";

    /** 面板四周留白占屏幕短边的比例 */
    private static final float MARGIN_RATIO = 0.05f;

    /** 顶部地址栏那一条的高度 */
    private static final int BAR_H = 22;

    private static final int NAV_BTN_W = 22;

    private static final float NAV_GLYPH_SCALE = 1.6f;

    // 修饰键掩码用 GLFW 那一套，不是 AWT 的：MCEF 把它原样塞进 Cef 事件并按 GLFW 值判断，
    // 混用不报错，只会让快捷键永远不触发
    private static final int MOD_SHIFT = 1;   // GLFW_MOD_SHIFT
    private static final int MOD_CTRL = 2;    // GLFW_MOD_CONTROL
    private static final int MOD_ALT = 4;     // GLFW_MOD_ALT

    /** 退出后回到哪儿，通常是手机界面 */
    private final Screen parent;

    private IBrowser browser;

    /** 后端不可用时显示的原因。为 null 表示一切正常 */
    private Component failure;

    private EditBox urlBox;

    // 面板与视口的几何，每帧 layout 时算一次，输入处理复用
    private int panelX, panelY, panelW, panelH;
    private int viewX, viewY, viewW, viewH;

    /** 工具条那一行的 y。它在面板外面（上方留白里），不占网页空间 */
    private int barY;

    /** 上一次告诉浏览器的尺寸，用来避免每帧都 resize */
    private int lastPxW = -1, lastPxH = -1;

    /** 地址栏里显示的是不是玩家正在编辑的内容。是的话就别用网页地址覆盖它 */
    private boolean urlEdited = false;

    /**
     * 正在由代码写入地址栏，此刻的 responder 回调不算"玩家在编辑"——
     * 否则从第一次同步网页地址起 urlEdited 就永远为 true，地址栏再也不跟随网页。
     */
    private boolean writingUrl = false;

    /**
     * 哪些鼠标键是在视口里按下的，按位记（第 n 位对应 button n）。
     * 松手要不要送必须看"当初在哪按的"：MCEF 内部的 btnMask 漏清一位，
     * 之后每次鼠标移动都会被网页当成"按住键在拖"，且不会自己恢复。
     */
    private int viewportButtons = 0;

    public BrowserScreen(Screen parent) {
        super(Component.translatable("mcphone.app.browser"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();
        layout();

        urlBox = new EditBox(font, panelX + NAV_BTN_W * 3 + 6, barY + 4,
                panelW - NAV_BTN_W * 3 - 20, BAR_H - 8,
                Component.translatable("mcphone.browser.url"));
        urlBox.setMaxLength(2000);
        urlBox.setResponder(s -> { if (!writingUrl) urlEdited = true; });
        addRenderableWidget(urlBox);

        // init() 在窗口大小变化时会被再调一次：EditBox 重建成空的，而 urlEdited 是实例字段，
        // 不清掉的话正在编辑时拖一下窗口，它就永远停在 true，地址栏从此不跟随网页
        urlEdited = false;

        // 浏览器只建一次，之后只改尺寸——每次重建都会丢掉浏览历史与页面状态
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

    /** 算出工具条、面板与视口的位置。工具条浮在面板上方的留白里，所以上留白单独算 */
    private void layout() {
        int base = (int) (Math.min(width, height) * MARGIN_RATIO);
        int side = Math.max(8, base);
        int top = Math.max(BAR_H + 6, base);
        int bottom = Math.max(8, base);

        panelX = side;
        panelW = width - side * 2;
        panelY = top;
        panelH = height - top - bottom;

        barY = panelY - BAR_H - 3;

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
        // 只管回到手机。浏览器的释放在 removed() 里——setScreen 会先调它
        minecraft.setScreen(parent);
    }

    /**
     * 释放浏览器写在 removed() 而不是 onClose()：断线、退回主菜单、别的模组切界面
     * 都不经过 onClose，只有 setScreen 必经的 removed() 兜得住全部退出路径。
     */
    @Override
    public void removed() {
        super.removed();
        if (browser != null) {
            browser.close();
            browser = null;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        layout();
        syncBrowserSize();

        // 暗化只画这一次，部件（地址栏）由本方法末尾自己画。
        // 别在末尾调 super.render()：Screen.render 会把暗化再画一遍，盖在网页上
        //
        // 1.20.1 的 renderBackground 只收 GuiGraphics；1.21 多了鼠标坐标与 partialTick
        renderBackground(g);

        PhoneSkin.drawOrFill(g, PhoneSkin.Element.BROWSER_PANEL,
                panelX, panelY, panelW, panelH, PhoneTheme.COLOR_SCREEN_BG);

        renderBar(g, mouseX, mouseY);

        if (browser != null) {
            drawBrowser(g);
        } else {
            String msg = (failure != null ? failure : Component.empty()).getString();
            g.drawCenteredString(font, msg, viewX + viewW / 2,
                    viewY + viewH / 2 - font.lineHeight, FontPalette.body());
            g.drawCenteredString(font,
                    Component.translatable("mcphone.browser.need_mcef").getString(),
                    viewX + viewW / 2, viewY + viewH / 2 + 2, FontPalette.subtle());
        }

        urlBox.render(g, mouseX, mouseY, partialTick);
    }

    /**
     * 把浏览器那张纹理贴到视口上。全类唯一版本相关的方法（BufferBuilder 写法随 MC 版本变），移植时改这里就够。
     * 用矩阵重载而不是裸坐标：GuiGraphics 的位姿此刻未必是单位阵。
     *
     * 【这一支与 1.21 差得最多的就是这十几行】，那边是：
     *
     *     BufferBuilder buffer = Tesselator.getInstance().begin(mode, format);
     *     buffer.addVertex(matrix, x, y, z).setUv(u, v).setColor(r, g, b, a);
     *     BufferUploader.drawWithShader(buffer.build());
     *
     * 1.20.1 上 Tesselator 不给 begin，要先 getBuilder() 拿到那个【长期复用】的
     * BufferBuilder 再 begin；写顶点的方法叫 vertex/uv/color，而且【每个顶点末尾
     * 必须 endVertex()】—— 漏了不会编译报错，运行时才抛"顶点没填满"。
     *
     * 属性的调用顺序也不是自由的：必须与 POSITION_TEX_COLOR 声明的顺序
     * （Position → UV0 → Color）一致，调换同样是运行期才发作。
     */
    private void drawBrowser(GuiGraphics g) {
        int texture = browser.textureId();
        if (texture <= 0) return;

        Matrix4f matrix = g.pose().last().pose();

        RenderSystem.disableDepthTest();
        RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texture);

        BufferBuilder buffer = Tesselator.getInstance().getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        // v 轴是反的：Chromium 的画面原点在左上，OpenGL 纹理坐标原点在左下
        buffer.vertex(matrix, viewX, viewY + viewH, 0)
                .uv(0f, 1f).color(255, 255, 255, 255).endVertex();
        buffer.vertex(matrix, viewX + viewW, viewY + viewH, 0)
                .uv(1f, 1f).color(255, 255, 255, 255).endVertex();
        buffer.vertex(matrix, viewX + viewW, viewY, 0)
                .uv(1f, 0f).color(255, 255, 255, 255).endVertex();
        buffer.vertex(matrix, viewX, viewY, 0)
                .uv(0f, 0f).color(255, 255, 255, 255).endVertex();
        BufferUploader.drawWithShader(buffer.end());

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

        // 玩家没在编辑时，地址栏跟着网页走；正在编辑时不覆盖，否则打字打到一半会被刷掉
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
     * 一个导航键。图标放大画：先 translate 到目标位置再 scale、在原点画——
     * 缩放一个非原点坐标会让图标越大偏得越多。
     */
    private void drawNavButton(GuiGraphics g, int index, String glyph,
                               int mouseX, int mouseY, boolean enabled) {
        int x = panelX + 2 + index * NAV_BTN_W;
        int y = barY + 2;
        int h = BAR_H - 4;
        boolean hovered = enabled && GuiUtil.hit(mouseX, mouseY, x, y, NAV_BTN_W, h);

        if (hovered) g.fill(x, y, x + NAV_BTN_W, y + h, PhoneTheme.COLOR_APP_PRESSED);

        int color = !enabled ? PhoneTheme.COLOR_BUTTON_DISABLED
                : hovered ? FontPalette.title()
                : FontPalette.body();

        float sc = NAV_GLYPH_SCALE;
        float gw = font.width(glyph) * sc;
        float gh = font.lineHeight * sc;

        g.pose().pushPose();
        g.pose().translate(x + (NAV_BTN_W - gw) / 2f, y + (h - gh) / 2f, 0);
        g.pose().scale(sc, sc, 1f);
        g.drawString(font, glyph, 0, 0, color, false);
        g.pose().popPose();
    }

    /** 正在加载时右上角亮一个点：亮了说明点击送到了、导航发起了，没亮说明点击没到网页 */
    private void drawLoadingDot(GuiGraphics g) {
        if (browser == null || !browser.isLoading()) return;
        int size = 5;
        int x = panelX + panelW - size - 3;
        int y = barY + (BAR_H - size) / 2;
        g.fill(x, y, x + size, y + size, FontPalette.price());
    }

    private boolean inViewport(double mx, double my) {
        return GuiUtil.hit(mx, my, viewX, viewY, viewW, viewH);
    }

    /** GUI 坐标 → 相对视口左上角的真实像素。先夹回视口内：拖拽时指针会跑到外面 */
    private int browserX(double mouseX) {
        return toPx(Math.max(viewX, Math.min(mouseX, viewX + viewW)) - viewX);
    }

    private int browserY(double mouseY) {
        return toPx(Math.max(viewY, Math.min(mouseY, viewY + viewH)) - viewY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
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

    /** 松手看的是"当初在哪按的"，不是"现在指针在哪"——理由见 viewportButtons */
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
        // 正拖着时即使指针出了视口也要接着送：断了的话网页里的选区会停在边界上
        if (browser != null && (viewportButtons != 0 || inViewport(mouseX, mouseY))) {
            browser.mouseMove(browserX(mouseX), browserY(mouseY));
        }
        super.mouseMoved(mouseX, mouseY);
    }

    /**
     * 【签名必须对得上】：1.20.1 的 Screen.mouseScrolled 是三参，1.21 才拆成
     * scrollX / scrollY 两个。照那边写成四参不会报错，只是【永远不会被调到】
     * —— 网页滚不动却查不出原因。@Override 是这里唯一的守卫。
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (browser != null && inViewport(mouseX, mouseY)) {
            browser.mouseWheel(browserX(mouseX), browserY(mouseY), scrollY, currentModifiers());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollY);
    }

    /**
     * 此刻按着哪些修饰键。mouseScrolled 的签名里没有 modifiers，只能自己查，
     * 不查的话 Ctrl+滚轮缩放永远不触发。Screen 的这几个静态方法在 macOS 上把 Command 当 Ctrl，不用分平台。
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
        if (urlBox.isFocused()) {
            if (keyCode == 257 || keyCode == 335) {   // GLFW_KEY_ENTER / KP_ENTER
                navigateToTypedUrl();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        // ESC 关界面，不转发给网页
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
     * 地址还是搜索词：带 :// 原样打开；带空格一定是搜索词；剩下有点当域名补 https://，没点去搜。
     * localhost 这类不带点的主机名会被判成搜索词，接受这个代价——真要用的人打得出 http://localhost。
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
