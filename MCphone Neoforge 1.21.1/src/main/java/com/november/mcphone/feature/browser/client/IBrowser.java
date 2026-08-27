package com.november.mcphone.feature.browser.client;

/**
 * 一个已经打开的浏览器。方法签名里不许出现 MCEF / JCEF 类型，换后端时只改 {@link McefBackend}。
 * 返回 GL 纹理 id 而不让后端自己画：贴四边形的写法随 MC 版本变，该留在界面层。
 */
public interface IBrowser {

    /** 每帧都可能变，每次绘制前重新取，别缓存 */
    int textureId();

    /** 单位是真实像素，不是 GUI 缩放后的坐标，传错画面会糊 */
    void resize(int width, int height);

    // 以下输入坐标同样是相对浏览器左上角的真实像素

    void mouseMove(int x, int y);

    void mousePress(int x, int y, int button);

    void mouseRelease(int x, int y, int button);

    /** modifiers 用 GLFW 掩码（SHIFT=1 / CTRL=2 / ALT=4），必须传真值，写死 0 会让 Ctrl+滚轮缩放静默失效 */
    void mouseWheel(int x, int y, double amount, int modifiers);

    void keyPress(int keyCode, long scanCode, int modifiers);

    void keyRelease(int keyCode, long scanCode, int modifiers);

    /** 与 keyPress 分开：字符输入要经过输入法与键盘布局 */
    void charTyped(char c, int modifiers);

    /** 没有焦点时网页里的输入框不会闪光标 */
    void setFocus(boolean focus);

    void loadUrl(String url);

    /** 刚建好还没加载时可能是空串 */
    String currentUrl();

    boolean canGoBack();

    void goBack();

    boolean canGoForward();

    void goForward();

    void reload();

    boolean isLoading();

    /** 必须调用：背后是真的 Chromium 渲染进程，不关会一直在后台活着 */
    void close();
}
