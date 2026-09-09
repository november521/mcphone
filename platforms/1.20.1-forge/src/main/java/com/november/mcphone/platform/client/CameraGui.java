package com.november.mcphone.platform.client;

/**
 * 相机取景时原版 HUD 怎么藏 —— <b>两支的答案正好相反</b>。
 *
 * <h2>这一支为什么是 false</h2>
 *
 * NeoForge 那一支置 true（等效原版 F1）：那边的 {@code RenderGuiEvent} 不受 {@code hideGui}
 * 影响，照样派发，取景框画得出来。
 *
 * Forge 1.20.1 上不行。{@code RenderGuiEvent} 是 {@code ForgeGui}（{@code Gui} 的子类）
 * 在自己的 render 里派发的，而 {@code GameRenderer} 那边是：
 *
 * <pre>    if (!hideGui || screen != null) { gui.render(...) }</pre>
 *
 * 也就是说 {@code hideGui} 一置 true，整个 {@code gui.render} 被跳过，<b>事件根本不发</b>，
 * 取景框跟着 HUD 一起没了 —— 这正是玩家报的「F1 会把框也省略掉」。
 *
 * 所以这一支反过来：{@code hideGui} 强制为 false 让渲染链路照常走，HUD 改由
 * {@code CameraHandler} 逐个取消 {@code RenderGuiOverlayEvent} 来藏。
 * 拍照那几帧另说，见 {@code CameraHandler.onRenderTickStart}。
 *
 * <h2>为什么不是一个 if</h2>
 *
 * 值本身只有 true / false，但<b>为什么是这个值</b>要跟着各自的渲染链路走。
 * 写成 shared/ 里的一个 {@code if (是不是 NeoForge)}，上面这段论证就没有落脚的地方，
 * 而下一个人看到的是一个没有理由的分支。
 */
public final class CameraGui {

    private CameraGui() {}

    /** 进入取景时 {@code Minecraft.options.hideGui} 该置成什么。 */
    public static boolean hideGuiWhileFraming() {
        return false;
    }
}
