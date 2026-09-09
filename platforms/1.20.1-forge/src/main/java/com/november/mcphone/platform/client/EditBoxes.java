package com.november.mcphone.platform.client;

import net.minecraft.client.gui.components.EditBox;

/**
 * 单行输入框上的一些跨版本对不上的动作。
 *
 * <h2>为什么要有这个门面</h2>
 *
 * {@code EditBox.moveCursorToEnd} 在 1.21 上收一个 {@code boolean select}
 * （要不要顺手把从原光标位到末尾那段选中），1.20.1 上<b>无参</b>。
 * 两支<b>没有</b>互相兼容的重载 —— 照哪一支写，另一支都编不过。
 *
 * <h2>为什么门面不带那个参数</h2>
 *
 * 全仓两处调用点用的都是「不选中」（1.21 侧写的是 {@code false}），
 * 而无参那一支的语义正是不选中。真需要「移到末尾并选中」时再加一个方法，
 * 别给这个方法补一个 1.20.1 上无法实现的参数。
 */
public final class EditBoxes {

    private EditBoxes() {}

    /** 光标移到末尾，不选中。 */
    public static void moveCursorToEnd(EditBox box) {
        box.moveCursorToEnd();
    }
}
