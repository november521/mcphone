package com.november.mcphone.test;

import com.november.mcphone.platform.client.port.PhoneEditBox;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import java.lang.reflect.Field;

/** 无窗口断言只隔离系统输入法通知，焦点、选区、编辑及输入过滤仍执行真实原版逻辑。 */
public final class TestEditBoxFactory {
    private TestEditBoxFactory() {}

    public static PhoneEditBox create(Font font, int x, int y, int width, int height, Component message) {
        return new HeadlessEditBox(font, x, y, width, height, message);
    }

    private static final class HeadlessEditBox extends PhoneEditBox {
        private static final Field EDITABLE = editableField();

        private static Field editableField() {
            try {
                Field field = PhoneEditBox.class.getSuperclass().getDeclaredField("isEditable");
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException error) {
                throw new ExceptionInInitializerError(error);
            }
        }

        private HeadlessEditBox(Font font, int x, int y, int width, int height, Component message) {
            super(font, x, y, width, height, message);
        }

        @Override
        public void setFocused(boolean focused) {
            try {
                boolean editable = EDITABLE.getBoolean(this);
                try {
                    // 仅在原版焦点方法执行期间关闭输入法通知条件；不改变测试的可编辑状态。
                    EDITABLE.setBoolean(this, false);
                    super.setFocused(focused);
                } finally {
                    EDITABLE.setBoolean(this, editable);
                }
            } catch (IllegalAccessException error) {
                throw new AssertionError("无法隔离无窗口测试的输入法通知", error);
            }
        }
    }
}
