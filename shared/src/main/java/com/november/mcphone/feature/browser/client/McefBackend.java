package com.november.mcphone.feature.browser.client;

import com.cinemamod.mcef.MCEF;
import com.cinemamod.mcef.MCEFBrowser;
import net.minecraft.network.chat.Component;

/**
 * 基于 MCEF 的浏览器后端，整个模组里唯一碰 MCEF 类型的类。
 * 能直接 import MCEF 是因为只有 {@link BrowserBackends#installDefault()} 确认装了之后才会加载它，
 * 别处直接 new McefBackend() 会让没装 MCEF 的玩家崩。MCEF 是 LGPL，只能照它的公开 API 写，不能抄它的代码进来。
 */
public final class McefBackend implements IBrowserBackend {

    @Override
    public boolean isAvailable() {
        // 装了不等于能用：MCEF 要先下载约 200 MB 原生库并初始化，期间 isInitialized() 为 false
        return MCEF.isInitialized();
    }

    @Override
    public Component unavailableReason() {
        return Component.translatable("mcphone.browser.mcef_not_ready");
    }

    @Override
    public IBrowser create(String url, int width, int height) {
        if (!isAvailable()) return null;

        // transparent = false：否则没设背景色的网页会半透明压在面板底纹上
        MCEFBrowser browser = MCEF.createBrowser(url, false, Math.max(1, width), Math.max(1, height));
        return new McefBrowser(browser);
    }

    /** 把 MCEFBrowser 包成 {@link IBrowser}，MCEFBrowser 这个类型到此为止。 */
    private static final class McefBrowser implements IBrowser {

        private final MCEFBrowser handle;

        /** close 调两次的话 MCEF 会对已释放的原生对象动手 */
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
            // 宽或高为 0 时 Chromium 会除零
            handle.resize(Math.max(1, width), Math.max(1, height));
        }

        @Override public void mouseMove(int x, int y) { handle.sendMouseMove(x, y); }

        @Override public void mousePress(int x, int y, int button) { handle.sendMousePress(x, y, button); }

        @Override public void mouseRelease(int x, int y, int button) { handle.sendMouseRelease(x, y, button); }

        /** modifiers 必须原样传：MCEF 靠它做 Ctrl+滚轮缩放，写 0 等于把缩放锁死 */
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

        /** getURL 刚建好时可能返回 null，统一成空串 */
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
