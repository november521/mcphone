package com.november.mcphone.feature.settings.client;

import com.november.mcphone.core.client.PhoneChassis;
import com.november.mcphone.core.client.PhoneHud;
import com.november.mcphone.core.client.PhoneHudPlacement;
import com.november.mcphone.core.client.PhoneTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * 摆放副手 HUD 上那部手机 —— 拖到哪儿就是哪儿。
 *
 * 为什么是一整块屏幕，而不是手机设置里的一页
 *
 * 要摆的东西占的是【整个窗口】，而手机自己的屏幕只有 120×200。在那么小一块里放一个
 * 窗口缩略图去拖，一像素的手抖对应到实际画面上就是十几像素，摆不准；何况玩家真正想
 * 看的是"它压不压得到我的物品栏"，那件事只有按原尺寸摆在真画面上才看得出来。
 *
 * 所以这一页把手机按【真实大小】画在真实位置上，玩家直接拖它。原版 HUD（物品栏、
 * 血条、经验条）在这一层之下照常画着——{@code Gui.render} 发生在界面绘制之前——
 * 于是"会不会挡住"当场就有答案。
 *
 * 拖动落地成什么
 *
 * 落成锚点加偏移，不是一对坐标，理由见 {@link PhoneHudPlacement} 的类注释。松手时才
 * 落盘：拖一次会经过几十个位置，每个都写一次文件是没必要的。
 *
 * 关掉之后回游戏，而不是回开它的那一页
 *
 * 摆完位置最想看的就是它挂在画面上的样子，回游戏正好；回设置页反而挡着。
 *
 * 更要紧的是回不去：开这一页时手机那个界面被顶掉，会走一遍 removed() 把会话存了、
 * 附属页面关了、图片贴图放了。再把【同一个实例】设回去，得到的是一部内脏已经掏空的
 * 手机。副手上那部有 PhoneHud 兜着（它的 removed 不拆），从背包里开的那部没有——
 * 与其为一条返回路径给两种手机各留一套规矩，不如干脆不返回。
 */
public final class PhoneHudEditor extends Screen {

    /** 压在世界上的一层薄暗色。比手机界面那层 {@link PhoneTheme#COLOR_SCRIM} 淡得多——
     *  这一页的重点恰恰是"看清它和原版 HUD 的关系"，压太狠就白摆了 */
    private static final int COLOR_DIM = 0x40000000;

    /** 拖动时手机四周那圈提示线 */
    private static final int COLOR_OUTLINE = 0xCC66FF88;

    /** 没在拖时那圈线，淡一档 */
    private static final int COLOR_OUTLINE_IDLE = 0x66FFFFFF;

    /** 当前锚点的"零偏移"落点，画一个空框告诉玩家它会吸到哪儿 */
    private static final int COLOR_SNAP_HINT = 0x55FFDD44;

    private boolean dragging;

    /** 按下那一刻，光标相对机身左上角的位置。不记的话手机会"跳"到光标下 */
    private int grabX, grabY;

    public PhoneHudEditor() {
        super(Component.translatable("mcphone.hud.place"));
    }

    /** 摆位置的时候游戏该继续跑：玩家要看的是真实画面，不是一张定格 */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, COLOR_DIM);

        int x = PhoneHudPlacement.originX(this.width, this.height);
        int y = PhoneHudPlacement.originY(this.width, this.height);
        int w = PhoneHudPlacement.width(this.width, this.height);
        int h = PhoneHudPlacement.height(this.width, this.height);

        drawSnapHint(g, w, h);

        // 手机在副手上时画真的那一部——摆的是自己那一页的内容，比一块占位黑屏好判断
        if (!PhoneHud.renderPreview(g, partialTick)) {
            drawStandIn(g, x, y, w, h);
        }

        drawOutline(g, x, y, w, h,
                dragging || hitsPhone(mouseX, mouseY) ? COLOR_OUTLINE : COLOR_OUTLINE_IDLE);

        drawHints(g);
    }

    /**
     * 当前锚点在零偏移时的落点，画个空框。
     *
     * 有它玩家才明白"锚点"是什么意思：拖到接近某个角时框会跳到那个角上，一眼就看出
     * 手机记的是"贴着这个角"，而不是记了一对会随分辨率失效的坐标。
     */
    private void drawSnapHint(GuiGraphics g, int w, int h) {
        int sx = PhoneHudPlacement.anchor().col() * (this.width - w) / 2;
        int sy = PhoneHudPlacement.anchor().row() * (this.height - h) / 2;
        drawOutline(g, sx, sy, w, h, COLOR_SNAP_HINT);
    }

    /** 手机不在副手时的占位：一部空壳，尺寸与真的一模一样，摆位置足够了 */
    private void drawStandIn(GuiGraphics g, int x, int y, int w, int h) {
        float s = PhoneHudPlacement.effectiveScale(this.width, this.height);
        int b = PhoneTheme.PHONE_BORDER;

        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(s, s, 1.0F);

        // 缩放之后原点就是机身左上角，屏幕内区域再往里缩一个边框
        PhoneChassis.drawScreenBackground(g, b, b);
        PhoneChassis.drawStatusBar(g, this.font, b, b);
        // 导航栏的悬停判定收一对不可能命中的坐标：这只是张预览，不该有键亮着
        PhoneChassis.drawNavBar(g, this.font, b, b, -1, -1);
        PhoneChassis.drawFrame(g, b, b);

        g.pose().popPose();
    }

    private void drawOutline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    /** 顶上一行怎么操作，底下一行当前是什么 */
    private void drawHints(GuiGraphics g) {
        String how = Component.translatable("mcphone.hud.place_hint").getString();
        g.drawCenteredString(this.font, how, this.width / 2, 8, 0xFFFFFFFF);

        String anchorName = Component.translatable(PhoneHudPlacement.anchor().translationKey()).getString();
        String what = Component.translatable("mcphone.hud.place_readout",
                anchorName,
                PhoneHudPlacement.offsetX() + ", " + PhoneHudPlacement.offsetY(),
                PhoneHudPlacement.percent() + "%").getString();
        g.drawCenteredString(this.font, what, this.width / 2, this.height - 16, 0xFFBBBBBB);
    }

    //  输入

    private boolean hitsPhone(double mx, double my) {
        int x = PhoneHudPlacement.originX(this.width, this.height);
        int y = PhoneHudPlacement.originY(this.width, this.height);
        int w = PhoneHudPlacement.width(this.width, this.height);
        int h = PhoneHudPlacement.height(this.width, this.height);
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0 && hitsPhone(mx, my)) {
            dragging = true;
            grabX = (int) Math.round(mx) - PhoneHudPlacement.originX(this.width, this.height);
            grabY = (int) Math.round(my) - PhoneHudPlacement.originY(this.width, this.height);
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!dragging) return super.mouseDragged(mx, my, button, dx, dy);
        apply(mx, my, false);
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging) {
            dragging = false;
            // 松手才落盘，理由见类注释
            apply(mx, my, true);
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    /** 把光标位置换算成一份锚点加偏移。夹取与推算都在 {@link PhoneHudPlacement#place} 里 */
    private void apply(double mx, double my, boolean commit) {
        PhoneHudPlacement.Placement p = PhoneHudPlacement.place(
                (int) Math.round(mx) - grabX,
                (int) Math.round(my) - grabY,
                this.width, this.height);

        if (commit) {
            PhoneHudPlacement.setPlacement(p.anchor(), p.offsetX(), p.offsetY());
        } else {
            PhoneHudPlacement.preview(p.anchor(), p.offsetX(), p.offsetY());
        }
    }

    /**
     * 滚轮改大小。
     *
     * 改完要把位置重算一次：手机变大变小之后，"贴着右下角"这件事对应的左上角坐标变了，
     * 而偏移是相对锚点记的，本来就跟着走。这里真正要防的是另一头——变大之后机身可能
     * 顶出窗口，得把偏移夹回来。
     *
     * 滚一格只改不落盘，落盘留到 {@link #removed()}。滚轮一拨就是十几格，每格写两次
     * 配置文件（倍数一次、夹回来的位置一次）纯属糟蹋 —— 与拖动条上那套算法同一个道理。
     */
    @Override
    public boolean mouseScrolled(double mx, double my, double scrollX, double scrollY) {
        if (scrollY == 0) return super.mouseScrolled(mx, my, scrollX, scrollY);

        PhoneHudPlacement.previewPercent(PhoneHudPlacement.percent()
                + (scrollY > 0 ? PhoneHudPlacement.STEP_PERCENT : -PhoneHudPlacement.STEP_PERCENT));
        clampIntoWindow(false);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_R) {
            PhoneHudPlacement.reset();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * 窗口被拖小、或者手机被调大之后，把它按现在的尺寸重新夹进窗口。
     *
     * @param commit 落不落盘。滚轮连拨时传 false，只在最后收尾时写一次
     */
    private void clampIntoWindow(boolean commit) {
        PhoneHudPlacement.Placement p =
                PhoneHudPlacement.clampIntoWindow(this.width, this.height);
        if (commit) {
            PhoneHudPlacement.setPlacement(p.anchor(), p.offsetX(), p.offsetY());
        } else {
            PhoneHudPlacement.preview(p.anchor(), p.offsetX(), p.offsetY());
        }
    }

    @Override
    public void resize(Minecraft mc, int w, int h) {
        super.resize(mc, w, h);
        clampIntoWindow(true);
    }

    /**
     * 收尾时把这一趟摆出来的位置与大小一起落盘。
     *
     * 用 removed() 而不是 onClose()：被别的界面顶掉时 onClose 不触发，而那一下同样是
     * "摆完了"——不写的话这一趟白摆。位置无条件写一次而不是只在 dragging 时写：滚轮
     * 改大小时也会把位置夹动，那条路上没有"松手"这个时刻。
     */
    @Override
    public void removed() {
        dragging = false;
        clampIntoWindow(true);
        PhoneHudPlacement.commitPercent();
        super.removed();
    }
}
