package com.november.mcphone.platform.client;

/**
 * 相机取景时原版 HUD 怎么藏 —— <b>两支的答案正好相反</b>。
 *
 * <h2>差在哪儿</h2>
 *
 * 这一支（NeoForge）用原版的 {@code hideGui}：{@code RenderGuiEvent} <b>不受它影响</b>，
 * 照样派发，所以取景框画得出来，而准星与物品栏跟着 vanilla 一起藏掉 —— 等效玩家按 F1。
 *
 * Forge 1.20.1 上这条不成立，那一支必须反过来，理由写在它那一份里。
 *
 * <h2>为什么不是一个 if</h2>
 *
 * 值本身只有 true / false，但<b>为什么是这个值</b>要跟着各自的渲染链路走。
 * 写成 shared/ 里的一个 {@code if (是不是 NeoForge)}，那段论证就没有落脚的地方，
 * 而下一个人看到的是一个没有理由的分支。
 */
public final class CameraGui {

    private CameraGui() {}

    /** 进入取景时 {@code Minecraft.options.hideGui} 该置成什么。 */
    public static boolean hideGuiWhileFraming() {
        return true;
    }
}
