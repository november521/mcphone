package com.november.mcphone.api.client.ui;

import com.november.mcphone.core.client.GuiUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 画一页手机界面所需要的一切 —— 传给 {@link IPhonePage} 的上下文。
 *
 * 为什么是一个对象，不是十个参数
 *
 * MCphone 内部那些页面的渲染签名长这样：
 *
 *   render(g, phoneLeft, phoneTop, screenW, screenH, statusH, navH,
 *          mouseX, mouseY, partialTick, font)
 *
 * 内部代码这么写没问题——要加个参数，改的是自己家的十几处调用。但把它原样开放
 * 出去就是个陷阱：以后想多给一个信息（比如"现在是不是横屏"），所有附属的
 * render 签名当场对不上，全部编译不过。
 *
 * 换成一个上下文对象，加信息只是多一个访问器方法，谁都不用改。这就是
 * {@link com.november.mcphone.api.MCphoneApi} 兼容策略第三条的具体样子。
 *
 * 坐标说明
 *
 * {@link #x()} / {@link #y()} / {@link #width()} / {@link #height()} 给的是
 * 【内容区】——已经扣掉了顶部状态栏与底部导航栏。直接在这个矩形里画就行，
 * 不用知道那两条有多高，我们改了它们的高度你也不受影响。
 *
 * 这些是屏幕绝对坐标，可以直接传给 GuiGraphics，不需要再加偏移。
 *
 * 生命周期
 *
 * 每帧新建一个，只在那一帧里有效。【别存起来】——存下来的那个对象里的鼠标
 * 位置和 GuiGraphics 下一帧就过期了，拿它画东西的后果是画在错的地方，或者
 * 直接对着已经关掉的渲染状态动手。
 */
public final class PhoneCanvas {

    private final GuiGraphics graphics;
    private final Font font;
    private final int x, y, width, height;
    private final int mouseX, mouseY;
    private final float partialTick;
    private final PhoneStyle style;

    /**
     * 由 MCphone 构造，附属不需要自己 new。
     *
     * 参数多是因为它把原来那十个位置参数收在了一处——这正是它存在的意义。
     */
    public PhoneCanvas(GuiGraphics graphics, Font font,
                       int x, int y, int width, int height,
                       int mouseX, int mouseY, float partialTick,
                       PhoneStyle style) {
        this.graphics = graphics;
        this.font = font;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.mouseX = mouseX;
        this.mouseY = mouseY;
        this.partialTick = partialTick;
        this.style = style;
    }

    /**
     * 原版的绘制句柄。画什么都从它走。
     *
     * <b>只有一件事别用它做：裁剪。</b> {@code graphics().enableScissor(...)} 收的是
     * <b>窗口坐标</b>，而且不看 PoseStack；而玩家可以在「设置 → 界面大小」里把整个手机
     * 放大（75%–300%）。你手上这些坐标是没缩放的手机坐标，直接交给原版，裁剪框就停在
     * 100% 时的位置和大小上——内容被切掉一块，而且只在倍数不是 100% 时出现，你自己在
     * 100% 下怎么测都是好的。用 {@link #clipped} 代替，它替你把矩形过一遍当前的变换矩阵。
     *
     * 别的都不用担心：这里给的 x/y/宽高，以及送进 {@code mouseClicked} / {@code mouseScrolled}
     * 的鼠标坐标，两边一起换算过了。
     */
    public GuiGraphics graphics() { return graphics; }

    /**
     * 在一个矩形里画，越界的部分裁掉 —— 做可滚动的列表、长文本时用它。
     *
     * <pre>{@code
     * canvas.clipped(canvas.x(), canvas.y(), canvas.width(), canvas.height(), () -> {
     *     int y = canvas.y() - scrollPx;
     *     for (String line : lines) {
     *         canvas.graphics().drawString(canvas.font(), line, canvas.x(), y, color, false);
     *         y += canvas.font().lineHeight;
     *     }
     * });
     * }</pre>
     *
     * <b>别自己调 {@code graphics().enableScissor(...)}。</b> 原版那句收的是窗口坐标，
     * 而且【不看 PoseStack】——手机整体是可以被玩家放大的（设置 → 界面大小），页面里
     * 那些坐标是没缩放过的手机坐标，直接交给原版，裁剪框就停在没缩放时的位置和大小上，
     * 文字会被切掉一块，而且只在倍数不是 100% 时出现。这个方法替你把矩形过一遍当前的
     * 变换矩阵，缩放几层都对。
     *
     * 为什么只给这一种写法、不给一对 enable/disable：裁剪是全局状态，enable 之后没能走到
     * disable 的话，这一帧剩下的所有东西都会被切在你那个框里——你的页面抛一次异常，玩家
     * 看到的是整个游戏界面缺了一块。这里的 body 抛了照样会把裁剪收回去（异常继续往上抛，
     * 由 MCphone 按老规矩关掉你这一页）。
     *
     * 可以嵌套，内外两层取交集，与原版的裁剪栈一致。宽或高不为正时什么都不画，
     * 但 <b>body 照样会执行</b>——你在里面顺手做的测量（算内容高度、滚动上限之类）
     * 不会因为某一帧矩形退化成空而停摆。
     *
     * @param x    左边界（屏幕绝对坐标，与 {@link #x()} 同一套）
     * @param y    上边界
     * @param w    宽
     * @param h    高
     * @param body 裁剪生效期间画的东西
     */
    public void clipped(int x, int y, int w, int h, Runnable body) {
        GuiUtil.clipped(graphics, x, y, x + w, y + h, body);
    }

    /** 手机界面用的字体。用它量宽度、画字符串 */
    public Font font() { return font; }

    /** 内容区左边界（屏幕绝对坐标） */
    public int x() { return x; }

    /** 内容区上边界（屏幕绝对坐标，已扣掉状态栏） */
    public int y() { return y; }

    /** 内容区宽度 */
    public int width() { return width; }

    /** 内容区高度（已扣掉状态栏与导航栏） */
    public int height() { return height; }

    /** 鼠标 x。悬停判定用 */
    public int mouseX() { return mouseX; }

    /** 鼠标 y */
    public int mouseY() { return mouseY; }

    /** 这一帧的插值系数。做动画时用 */
    public float partialTick() { return partialTick; }

    /** 手机当前的配色。照着画，你的页面才像手机里的东西而不是外来户 */
    public PhoneStyle style() { return style; }

    /**
     * 鼠标在不在这个矩形里。省得每个页面各写一遍命中判定。
     *
     * 矩形用的是与 {@link #x()} 同一套【屏幕绝对坐标】，不是相对内容区的偏移——
     * 参数名里那个 r 是历史遗留，别照着它传相对坐标。
     */
    public boolean hovered(int rx, int ry, int rw, int rh) {
        return mouseX >= rx && mouseX < rx + rw && mouseY >= ry && mouseY < ry + rh;
    }

    /** 鼠标在不在内容区里 */
    public boolean hoveredContent() {
        return hovered(x, y, width, height);
    }
}
