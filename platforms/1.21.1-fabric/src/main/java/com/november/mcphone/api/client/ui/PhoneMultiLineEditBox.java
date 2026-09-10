package com.november.mcphone.api.client.ui;

import com.november.mcphone.core.client.GuiUtil;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;

/**
 * 原版的多行输入框，只把它【自己那句裁剪】换成认 pose 的那一句。起于 1.10.4（API 代号 2）。
 *
 * 怎么用
 *
 * 和原版 {@link MultiLineEditBox} 一模一样地 new 出来，每帧
 * {@code box.render(canvas.graphics(), mouseX, mouseY, partialTick)} 画一次，鼠标键盘照旧
 * 转发给它。【不用把 {@link PhoneCanvas} 交给它】——缩放是从 GuiGraphics 当前的变换矩阵里
 * 读的，手机套了几层缩放它都跟得上。你自己画的东西要裁剪仍然用 {@link PhoneCanvas#clipped}，
 * 两者互不相干。
 *
 * 为什么必须有这么一个类
 *
 * {@link MultiLineEditBox} 的正文是它自己裁的：父类 {@code AbstractScrollWidget.renderWidget}
 * 里紧贴着 {@code renderContents} 的那对 {@code enableScissor}/{@code disableScissor}，交上去的
 * 是控件自己的 x/y/宽高。而原版那句【完全不看 PoseStack】——整部手机是套在一层 pose 缩放里
 * 画的（{@code PhoneScreen} 绕手机中心 scale，倍数是开机动画 × {@code PhoneScale}），控件那几个
 * 数又是没缩放的手机坐标，于是内容缩放了、裁剪框没缩。
 *
 * 这就是 1.9.3 那个"界面放大后文字被裁掉"的同一个根因（见 {@link GuiUtil#enableScissor}）。
 * 那次修的是页面【自己调】的那句，管不到控件在自己内部调的那句：换个人踩，坑没变。
 *
 * 症状比页面那次更狠，因为控件的框比整页小得多、缩放后偏得也更多。本体笔记 App 的正文框是
 * (4,14)–(108,161)（120×200 的屏，见 {@code NoteEditor}），界面大小 150% 时画得出来的
 * 【只剩】本地 y∈[43,140]、x∈[23,91] 这一块，而正文是从 (8,18) 开始一行 9 像素往下写的：
 * 前两行整行不见、第三行只剩最底下一丝，每行左右还各少一截（左 15 像素、右 13）。三行以内
 * 的笔记于是"打了字，什么都没出现"——这正是它被当成 bug 报上来的样子。200% 吃掉前四行、
 * 300% 吃掉前六行。反过来倍数小于 100% 时框比控件大，滚动时正文会溢到框外面去。
 *
 * 任何 {@code AbstractScrollWidget} 的子类都是这个下场。【单行的 {@code EditBox} 没这个问题】，
 * 它靠算宽度截断，不开裁剪，直接摆进手机就行。
 *
 * 为什么是整段抄过来
 *
 * 那两句在父类的 {@code renderWidget} 正中间，中间夹着 pose 与 {@code renderContents}，没有
 * 任何钩子能只换掉裁剪——只能整个覆写。抄的是 1.21.1 的父类，唯一的改动是那对 scissor 换成
 * {@link GuiUtil#clipped}（它把矩形先过一遍当前的变换矩阵）。【升 MC 大版本时要照着新的父类
 * 再核一遍这一段】——父类改了而这里没跟，表现是背景或滚动条不对，不会报错。
 *
 * 类是 {@code final} 的，故意的：正文那几行是一份原版实现的镜像，得跟着原版走，留继承口子
 * 等于把这份镜像也变成不能改的 API。要改行为就在外面包一层。
 */
public final class PhoneMultiLineEditBox extends MultiLineEditBox {

    public PhoneMultiLineEditBox(Font font, int x, int y, int width, int height,
                                 Component placeholder, Component message) {
        super(font, x, y, width, height, placeholder, message);
    }

    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (!this.visible) return;

        this.renderBackground(g);
        GuiUtil.clipped(g, this.getX() + 1, this.getY() + 1,
                this.getX() + this.width - 1, this.getY() + this.height - 1, () -> {
            g.pose().pushPose();
            g.pose().translate(0.0, -this.scrollAmount(), 0.0);
            this.renderContents(g, mouseX, mouseY, partialTick);
            g.pose().popPose();
        });
        this.renderDecorations(g);
    }
}
