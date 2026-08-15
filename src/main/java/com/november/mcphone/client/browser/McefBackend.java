package com.november.mcphone.client.browser;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import net.minecraft.network.chat.Component;

/**
 * 基于 MCEF 的浏览器后端 —— 整个模组里唯一碰 MCEF 的类。
 *
 * ============================================================
 * MCEF 是什么，我们用它的哪一部分
 * ============================================================
 *
 * MCEF（CinemaMod 维护）把 Chromium 嵌进 Minecraft，做的事是"离屏渲染一个网页，
 * 把结果刷进一张 OpenGL 纹理"。它不提供任何界面——地址栏、前进后退、面板布局
 * 全是我们自己画的。
 *
 * 我们只用它这几个方法：建浏览器、改尺寸、取纹理、送输入、关掉。加上 JCEF 的
 * 导航方法（loadURL / goBack / reload / getURL），一共十来个。
 *
 * ============================================================
 * 许可证：为什么是"链接"而不是"抄"
 * ============================================================
 *
 * MCEF 是 LGPL 2.1，本模组是 MIT。LGPL 相对 GPL 放宽的正是这一点：链接它的
 * 程序不必跟着变成 LGPL。而它是玩家自己安装的独立模组 jar，玩家随时可以换成
 * 自己改过的版本——LGPL 要求的"可替换性"天然满足。
 *
 * 但这也意味着：**它的代码一行都不能抄进来**。本类是照着它的公开 API 自己写的，
 * 不是从它的 ExampleScreen 复制的。往这里加东西时守住这条。
 *
 * ============================================================
 * 这个类为什么可以直接 import MCEF
 * ============================================================
 *
 * 因为它只会在 {@link BrowserBackends#installDefault()} 确认装了 MCEF 之后才被
 * 加载。没装的话，谁都不会提到这个类名，JVM 也就不会去解析它——
 * NoClassDefFoundError 无从发生。这条链子只要有一环写错（比如别处直接
 * new McefBackend()），没装 MCEF 的玩家就会崩，所以别那么干。
 */
public final class McefBackend implements IBrowserBackend {

    @Override
    public boolean isAvailable() {
        // 装了 MCEF 不等于能用：它要先下载约 200 MB 的 java-cef 原生库、再完成
        // 初始化。玩家第一次进游戏时这一步可能要等好几分钟，期间 isInitialized()
        // 是 false，我们就该老实说"还没准备好"，而不是建一个必然失败的浏览器
        return MCEF.isInitialized();
    }

    @Override
    public Component unavailableReason() {
        return Component.translatable("mcphone.browser.mcef_not_ready");
    }

    @Override
    public IBrowser create(String url, int width, int height) {
        if (!isAvailable()) return null;

        // transparent = false：网页背后不透出手机界面。true 的话，没有设置背景色
        // 的网页会变成半透明，正文压在我们的面板底纹上，读都读不了
        MCEFBrowser browser = MCEF.createBrowser(url, false, Math.max(1, width), Math.max(1, height));
        return new McefBrowser(browser);
    }

    /**
     * 把 MCEFBrowser 包成我们自己的 {@link IBrowser}。
     *
     * 这一层看着只是转发，但它是整个抽象的落点：MCEFBrowser 这个类型到此为止，
     * 界面层拿到的是 IBrowser。换后端时要改的就只有这个文件。
     */
    private static final class McefBrowser implements IBrowser {

        private final MCEFBrowser handle;

        /** 关过了没有。close 被调用两次的话，MCEF 那边会对着已释放的原生对象动手 */
        private boolean closed = false;

        McefBrowser(MCEFBrowser handle) {
            this.handle = handle;
        }

        @Override
        public int textureId() {
            return handle.getRenderer().getTextureID();
        }

        @Override
        public void resize(int width, int height) {
            // 宽或高为 0 时 Chromium 会算出除零，直接躲开
            handle.resize(Math.max(1, width), Math.max(1, height));
        }

        @Override public void mouseMove(int x, int y) { handle.sendMouseMove(x, y); }

        @Override public void mousePress(int x, int y, int button) { handle.sendMousePress(x, y, button); }

        @Override public void mouseRelease(int x, int y, int button) { handle.sendMouseRelease(x, y, button); }

        /**
         * modifiers 要原样传下去，不能图省事写 0。
         *
         * MCEF 的 sendMouseWheel 第一件事就是查它：Ctrl 位置上了就改缩放级别
         * （getZoomLevel / setZoomLevel，范围 ±9）而不是滚动页面。这个开关
         * （browserControls）在 MCEFBrowser 的构造器里默认就是开的，也就是说
         * 缩放本来就能用——1.1.30 之前传 0，等于把它锁死了。
         */
        @Override
        public void mouseWheel(int x, int y, double amount, int modifiers) {
            handle.sendMouseWheel(x, y, amount, modifiers);
        }

        @Override
        public void keyPress(int keyCode, long scanCode, int modifiers) {
            handle.sendKeyPress(keyCode, scanCode, modifiers);
        }

        @Override
        public void keyRelease(int keyCode, long scanCode, int modifiers) {
            handle.sendKeyRelease(keyCode, scanCode, modifiers);
        }

        @Override
        public void charTyped(char c, int modifiers) {
            handle.sendKeyTyped(c, modifiers);
        }

        @Override public void setFocus(boolean focus) { handle.setFocus(focus); }

        @Override public void loadUrl(String url) { handle.loadURL(url); }

        /** getURL 在刚建好、还没开始加载时可能返回 null，统一成空串免得外面判空 */
        @Override
        public String currentUrl() {
            String url = handle.getURL();
            return url == null ? "" : url;
        }

        @Override public boolean canGoBack() { return handle.canGoBack(); }

        @Override public void goBack() { handle.goBack(); }

        @Override public boolean canGoForward() { return handle.canGoForward(); }

        @Override public void goForward() { handle.goForward(); }

        @Override public void reload() { handle.reload(); }

        @Override public boolean isLoading() { return handle.isLoading(); }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            handle.close();
        }
    }
}
