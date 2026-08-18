package com.november.mcphone.api.client.ui;

/**
 * 一页画在手机屏幕【里】的界面。
 *
 * ================================================================
 * 这个接口解决什么
 * ================================================================
 *
 * 在它之前，附属 App 被点开时只能这么办：
 *
 *   {@code Minecraft.getInstance().setScreen(new MyScreen());}
 *
 * 也就是整个跳出手机——状态栏没了、导航栏没了、壁纸没了、返回键要自己实现、
 * 关掉之后回哪儿也要自己记。而 MCphone 自带的聊天、记事本、相册全都是手机
 * 屏幕里的一页，共用这一切。
 *
 * 同一套 SPI 注册出来的东西，内建的能画在手机里、附属的只能跳出去，这不是
 * 开放，是内建 App 有后门。这个接口就是把那扇后门变成正门。
 *
 * ================================================================
 * 怎么用
 * ================================================================
 *
 * 在你的 App 里覆盖 {@code openPage()}，返回一页：
 *
 * {@snippet :
 * public final class CalculatorApp implements IPhoneApp {
 *
 *     @Override
 *     public IPhonePage openPage() {
 *         return new CalculatorPage();
 *     }
 *
 *     @Override
 *     public void onPress() {
 *         // 覆盖了 openPage 之后这里不会被调用，但接口要求实现它——
 *         // 留空即可，或者写一个旧版 MCphone 上的退路
 *     }
 * }
 * }
 *
 * 页面自己长这样：
 *
 * {@snippet :
 * public final class CalculatorPage implements IPhonePage {
 *
 *     @Override
 *     public void render(PhoneCanvas c) {
 *         c.graphics().drawString(c.font(), "1 + 1 = 2",
 *                 c.x() + 4, c.y() + 4, c.style().bodyColor(), false);
 *     }
 * }
 * }
 *
 * 画在 {@link PhoneCanvas#x()} 那个矩形里，状态栏和导航栏已经替你扣掉了。
 *
 * ================================================================
 * 只有 render 是必须实现的
 * ================================================================
 *
 * 其余全是 {@code default}，用得上再覆盖。这不只是图省事——按
 * {@link com.november.mcphone.api.MCphoneApi} 的兼容策略，以后往这个接口加
 * 能力也只会加 default 方法，你的页面不会因为 MCphone 升级而编译不过。
 *
 * ================================================================
 * 不需要自己处理的事
 * ================================================================
 *
 * 状态栏、导航栏、壁纸、手机外壳：MCphone 画。
 * 导航栏的返回键 ◁：默认退回主屏，你要拦就覆盖 {@link #onBack()}。
 * ESC：直接关机，页面拦不住。你的 {@link #onClose()} 照常会被调到，草稿在那儿存。
 * 页面切走时的清理：覆盖 {@link #onClose()}。
 *
 * ================================================================
 * 抛异常会怎样
 * ================================================================
 *
 * 每个回调都被兜住了，你这一页抛异常只会让这一页被关掉并记一条日志，不会
 * 拖垮整个手机界面，更不会崩游戏。但别指望这个——被兜掉的异常对玩家来说
 * 就是"点开这个 App 自己弹回去了"，一样难用。
 */
public interface IPhonePage {

    /**
     * 画这一页。每帧调用。
     *
     * @param canvas 这一帧的绘制上下文。【别存起来】，只在这次调用里有效
     */
    void render(PhoneCanvas canvas);

    /**
     * 鼠标点击。坐标是屏幕绝对坐标，与 {@link PhoneCanvas#x()} 同一套。
     *
     * @return true 表示这一下我处理了。返回 false 会落到 MCphone 的默认处理，
     *         而默认处理里"点手机外面＝关机"，所以页面内的空点击建议返回 true
     */
    default boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    /** 鼠标滚轮。@return true 表示已处理，不再往下传 */
    default boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        return false;
    }

    /**
     * 按键。
     *
     * ESC 不会走到这里——它由 MCphone 统一处理成"直接关机"。玩家按了
     * 退出却什么都没发生是最糟的一种失败，所以这个键不开放给页面拦截，
     * 也不允许页面把它改成别的意思。要在退出前收尾，覆盖 {@link #onClose()}。
     *
     * @return true 表示已处理
     */
    default boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    /** 字符输入。输入法提交的汉字与 Ctrl+V 粘贴走的都是这里 */
    default boolean charTyped(char codePoint, int modifiers) {
        return false;
    }

    /**
     * 你这一页有没有输入框。
     *
     * ================================================================
     * 不覆盖它会踩的坑
     * ================================================================
     *
     * 原版的背包键默认是 E。玩家在你的输入框里打字，一按到 e 就命中背包键，
     * 手机当场关掉、内容全丢——而打拼音时一定会按到 e，中文用户躲都躲不开。
     *
     * 返回 true 之后，按键会先给你的页面，不再落到背包键判定上。MCphone 自己
     * 的设备命名、聊天输入、笔记编辑三处都是这么处理的。
     *
     * 没有输入框就别返回 true：那会把背包键也吃掉，玩家没法用它关手机。
     */
    default boolean capturesKeyboard() {
        return false;
    }

    /**
     * 玩家按了导航栏的返回键 ◁。
     *
     * ESC 不走这里——它直接关机，见 {@link #keyPressed}。所以别把"退出前
     * 提醒保存"挂在这个方法上：玩家按 ESC 时它不会响。
     *
     * @return true 表示你自己处理了（比如页面内还有一层要退），MCphone 不动；
     *         false（默认）表示这一页到此为止，退回主屏
     */
    default boolean onBack() {
        return false;
    }

    /** 这一页刚被打开。拉数据、重置状态放这儿 */
    default void onOpen() {}

    /**
     * 这一页被切走了。
     *
     * 释放资源、保存草稿放这儿。它一定会被调用——玩家从 ◁ 走、按 ESC 走、
     * 关掉手机走、甚至断线走，都会走到这里。
     */
    default void onClose() {}
}
