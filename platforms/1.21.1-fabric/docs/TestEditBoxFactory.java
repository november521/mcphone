package com.november.mcphone.test;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** 无窗口断言的控件构造接缝，旧版本可直接使用原版控件。 */
public final class TestEditBoxFactory {
    private TestEditBoxFactory() {}

    public static EditBox create(Font font, int x, int y, int width, int height, Component message) {
        return new EditBox(font, x, y, width, height, message);
    }
}
