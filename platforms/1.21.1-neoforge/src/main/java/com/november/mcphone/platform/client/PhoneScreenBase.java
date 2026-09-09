package com.november.mcphone.platform.client;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 手机这一族界面的基类 —— <b>它存在的唯一理由是 {@code mouseScrolled} 的签名在版本之间变了。</b>
 *
 * <h2>差在哪儿</h2>
 *
 * 1.21 把 {@code Screen.mouseScrolled} 从三参（一个滚动量）改成了四参（横竖各一个）。
 * 这是<b>覆写</b>，不是调用：照另一支的形状写不会编译报错，只会<b>永远不被调到</b> ——
 * 滚轮在那一支上安静地失灵，没有任何东西会说话。
 *
 * <h2>为什么静态门面接不住这一处</h2>
 *
 * 门面能改「怎么调一个方法」，改不了「一个方法长什么样」。要让子类摆脱这个签名，
 * 只能由一个<b>每支一份的基类</b>替它覆写掉那个方法，再转调一个两支同形的中立方法。
 * 子类于是只实现中立那一半，可以整个进共用层。
 *
 * <h2>中立那一半为什么是四个参数</h2>
 *
 * 取信息多的那一支的形状。1.20.1 上横向滚动量恒为 0 —— 那是事实，不是丢信息；
 * 反过来把 1.21 的横向量砍掉才是丢。
 */
public abstract class PhoneScreenBase extends Screen {

    protected PhoneScreenBase(Component title) {
        super(title);
    }

    /** 1.21 起是四参：横竖两个滚动量。 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return onScroll(mouseX, mouseY, scrollX, scrollY)
                || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /**
     * 滚轮。返回 true 表示这一下被这个界面吃掉了，false 交回原版。
     *
     * <p>{@code scrollX} 在 1.20.1 上恒为 0。
     */
    protected boolean onScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        return false;
    }
}

