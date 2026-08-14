package com.november.mcphone.client.browser;

/**
 * 一个已经打开的浏览器。
 *
 * ============================================================
 * 这个接口存在的唯一理由：把后端换掉时只改一个类
 * ============================================================
 *
 * 目前唯一的实现是 {@link McefBackend} 里那个包装 MCEF 的类。MCEF 是个好东西，
 * 但它是别人的项目、LGPL、最后一次发版在 2025 年初——把它的类型散布到界面代码
 * 里，将来想换后端就得把整个浏览器 App 重写一遍。
 *
 * 所以这里的方法签名【一个 MCEF / JCEF 类型都不许出现】。界面层只认这个接口。
 *
 * ============================================================
 * 为什么返回 GL 纹理 id，而不是让后端自己画
 * ============================================================
 *
 * 看着像抽象漏了，但反过来更糟：画一个带纹理的四边形用的是版本特定的
 * Minecraft API（1.20.1 与 1.21.x 的 BufferBuilder 写法完全不同）。让后端负责
 * 画，等于把移植成本挪进了本该最稳定的那一层。
 *
 * 纹理 id 是两边都认的通用货币：后端只管把画面刷进一张 GL 纹理，怎么贴到屏幕上
 * 是界面层的事，也只有界面层需要跟着 MC 版本改。
 */
public interface IBrowser {

    /**
     * 当前画面所在的 OpenGL 纹理 id。
     *
     * 每帧都可能变（浏览器随时在刷新），所以每次绘制前都要重新取，别缓存。
     */
    int textureId();

    /**
     * 改变浏览器的渲染尺寸。单位是【真实像素】，不是 GUI 缩放后的坐标——
     * 传 GUI 坐标进去会得到一张糊掉的画面。
     */
    void resize(int width, int height);

    // ---- 输入。坐标同样是相对浏览器左上角的【真实像素】 ----

    void mouseMove(int x, int y);

    void mousePress(int x, int y, int button);

    void mouseRelease(int x, int y, int button);

    /** @param amount 滚动量，正数向上 */
    void mouseWheel(int x, int y, double amount);

    void keyPress(int keyCode, long scanCode, int modifiers);

    void keyRelease(int keyCode, long scanCode, int modifiers);

    /** 输入了一个字符。与 keyPress 分开，因为字符输入要经过输入法与键盘布局 */
    void charTyped(char c, int modifiers);

    /** 浏览器有没有焦点。没有焦点时网页里的输入框不会闪光标 */
    void setFocus(boolean focus);

    // ---- 导航 ----

    void loadUrl(String url);

    /** 当前地址。刚建好还没加载完时可能返回空串 */
    String currentUrl();

    boolean canGoBack();

    void goBack();

    boolean canGoForward();

    void goForward();

    void reload();

    /**
     * 关掉它，释放原生资源。
     *
     * 必须调用。浏览器背后是一个真的 Chromium 渲染进程，忘了关的话它会一直
     * 活着——玩家关掉界面之后，一个看不见的网页还在后台放视频。
     */
    void close();
}
