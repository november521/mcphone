package com.november.mcphone.test;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** 无窗口断言的控件构造接缝，文本、选区和焦点继续使用原版逻辑。 */
public final class TestEditBoxFactory {
    private TestEditBoxFactory() {}

    public static EditBox create(Font font, int x, int y, int width, int height, Component message) {
        return new HeadlessEditBox(font, x, y, width, height, message);
    }

    private static final class HeadlessEditBox extends EditBox {
        private HeadlessEditBox(Font font, int x, int y, int width, int height, Component message) {
            super(font, x, y, width, height, message);
        }
        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            // 旧 EditBox 连无法识别的按键也先查窗口的 Shift。无窗口测试的未知键
            // 直接给出原版最终的 false；实际 GLFW 键仍执行原版，不模拟编辑结果。
            if (keyCode < 0 || keyCode > org.lwjgl.glfw.GLFW.GLFW_KEY_LAST) return false;
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
    }
}
