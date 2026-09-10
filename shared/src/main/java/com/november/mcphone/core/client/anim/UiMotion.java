package com.november.mcphone.core.client.anim;

/**
 * 过渡时长的唯一来源与帧率无关的渐近工具。从 AtomChat 0.1.8 移植（MIT），
 * 原文件 {@code ui/UiMotion.java}。
 *
 * 两条铁律（移植自 AtomChat 的踩坑记录）：
 *
 * 1. 这里的时长是"到达目标的**总时间**"，不是时间常数。旧写法
 *    {@code v += (target - v) * dt / D} 是渐近衰减，标称 120ms 的 hover
 *    实际要 ~550ms 才消失、而且永远到不了 0——这就是"粘滞/拖尾"感的来源。
 * 2. 过渡在到达的那一帧直接钉在目标值，所以状态（hover 高亮、滚动条 alpha、
 *    弹层淡出）总是精确落在 0 或 1，而不是永远悬在 0.999。
 */
public final class UiMotion {
    /** 面板开合：滑动 + 淡入。 */
    public static final long PANEL_MS = 150;
    /**
     * 新消息入场。刻意是全 UI 最慢的过渡：这是内容揭示，不是输入响应，
     * 透明度在 200ms 以下走完，眼睛根本来不及识别成"淡入"。
     */
    public static final long MESSAGE_MS = 220;
    /** 发完消息吸底回位。 */
    public static final long SCROLL_SNAP_MS = 110;
    /** 滚轮滚动滑行。 */
    public static final long SCROLL_WHEEL_MS = 180;
    /** 按钮 hover / 按下 tint。 */
    public static final long HOVER_MS = 90;
    /** 滚动条淡入淡出。 */
    public static final long SCROLLBAR_FADE_MS = 140;
    /** 滚动条 hover 强调。 */
    public static final long SCROLLBAR_EMPHASIS_MS = 100;
    /** 弹层 / 右键菜单弹出。 */
    public static final long POPUP_MS = 110;
    /** tab 内容推入 + 指示器滑动。 */
    public static final long TAB_MS = 200;
    /** 输入栏按行增高/缩回。 */
    public static final long INPUT_GROW_MS = 110;
    /**
     * 开关的旋钮行程。介于 hover(90) 与 tab push(200) 之间：这是直接操作
     * 控件，得读起来像滑动而不是跳变，同时要在手指抬起前落定。
     */
    public static final long TOGGLE_MS = 140;

    private UiMotion() {
    }

    /**
     * 把 {@code value} 朝 {@code target} 移动 {@code elapsedMs} 在
     * {@code durationMs} 中占的比例，剩余距离小于本帧步长时直接返回目标。
     *
     * 帧率无关：60fps 和 200fps 的客户端用同一个墙钟时间清掉同一个高亮，
     * 而且都精确落在 0 或 1。
     */
    public static float approach(float value, float target, float elapsedMs, long durationMs) {
        if (durationMs <= 0L) {
            return target;
        }
        float step = elapsedMs / (float) durationMs;
        if (step >= 1.0F) {
            return target;
        }
        float diff = target - value;
        if (Math.abs(diff) <= step) {
            return target;
        }
        return value + Math.signum(diff) * step;
    }
}
