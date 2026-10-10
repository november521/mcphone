package com.november.mcphone.platform.client.port;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2f;
import org.joml.Matrix3x2fStack;
import org.joml.Vector2f;
import sun.misc.Unsafe;

/** 使用真实新旧矩阵适配复现右下角坐标戳，再验证相机覆盖层和原版上下文的位置。 */
public final class CameraGraphicsPoseTest {
    private static int checks;

    private static GuiGraphicsExtractor graphics() throws Exception {
        // 此用例只访问矩阵，不初始化窗口、材质图集或 GPU；其余提取器字段不会被使用。
        var unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        var nativeGraphics = (GuiGraphicsExtractor) ((Unsafe) unsafeField.get(null)).allocateInstance(GuiGraphicsExtractor.class);
        var poseField = GuiGraphicsExtractor.class.getDeclaredField("pose");
        poseField.setAccessible(true);
        poseField.set(nativeGraphics, new Matrix3x2fStack(16));
        return nativeGraphics;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void point(GuiGraphicsExtractor nativeGraphics, float x, float y, float expectedX, float expectedY) {
        var actual = nativeGraphics.pose().transformPosition(x, y, new Vector2f());
        check(Math.abs(actual.x - expectedX) < 0.001f && Math.abs(actual.y - expectedY) < 0.001f,
                "坐标错位：(" + x + ", " + y + ") -> " + actual + "，预期 " + expectedX + ", " + expectedY);
    }

    private static void balanced(GuiGraphicsExtractor nativeGraphics) {
        try {
            nativeGraphics.pose().popMatrix();
        } catch (IllegalStateException expected) {
            checks++;
            return;
        }
        throw new AssertionError("相机绘制结束后原版矩阵栈多出一层");
    }

    private static void drawStamp(PhoneGraphics graphics, int width, int height, int scale) {
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(width - 100 * scale, height - 16 * scale, 0);
            graphics.pose().scale(scale, scale, 1);
            // 文字提交与 fill 最终都会经过这个真实的矩阵同步入口。
            point(graphics.nativeGraphics(), 0, 0, width - 100 * scale, height - 16 * scale);
        } finally {
            graphics.pose().popPose();
        }
    }

    public static void main(String[] args) throws Exception {
        for (int[] display : new int[][]{{1920, 1080}, {2560, 1440}, {1280, 720}, {3440, 1440}}) {
            for (int guiScale = 1; guiScale <= 4; guiScale++) {
                int width = (display[0] + guiScale - 1) / guiScale;
                int height = (display[1] + guiScale - 1) / guiScale;
                var nativeGraphics = graphics();
                PhoneGraphics.withIsolatedPose(nativeGraphics, graphics -> {
                    drawStamp(graphics, width, height, 2);
                    var overlay = graphics.nativeGraphics();
                    point(overlay, width / 2, height / 2, width / 2, height / 2);
                    int margin = Math.max(4, (int) (Math.min(width, height) * 0.04f));
                    point(overlay, margin, margin, margin, margin);
                    point(overlay, width - margin, margin, width - margin, margin);
                    point(overlay, margin, height - margin, margin, height - margin);
                    point(overlay, width - margin, height - margin, width - margin, height - margin);
                    point(overlay, 0, 0, 0, 0);
                    point(overlay, width, height, width, height);
                });
                point(nativeGraphics, 0, 0, 0, 0);
                balanced(nativeGraphics);
            }
        }

        var nativeGraphics = graphics();
        PhoneGraphics.withIsolatedPose(nativeGraphics, graphics -> {
            drawStamp(graphics, 640, 360, 2);
            // 拍照的干净帧会在这里提前返回，不再绘制覆盖层。
        });
        point(nativeGraphics, 0, 0, 0, 0);
        balanced(nativeGraphics);

        var callerPose = new Matrix3x2f().translate(31, 17).scale(1.5f);
        nativeGraphics.pose().set(callerPose);
        var expectedError = new IllegalStateException("绘制中断");
        try {
            PhoneGraphics.withIsolatedPose(nativeGraphics, graphics -> {
                graphics.pose().translate(420, 280, 0);
                graphics.nativeGraphics();
                throw expectedError;
            });
            throw new AssertionError("绘制异常没有传回调用方");
        } catch (IllegalStateException actual) {
            check(actual == expectedError, "保留原始绘制异常");
        }
        check(nativeGraphics.pose().equals(callerPose, 0.001f), "异常退出仍恢复调用方原有平移和缩放");
        balanced(nativeGraphics);

        PhoneGraphics.withIsolatedPose(nativeGraphics, graphics -> {
            check(graphics.nativeGraphics().pose().equals(callerPose, 0.001f), "不清除调用方合法的父级变换");
            PhoneGraphics.withIsolatedPose(nativeGraphics, nested -> {
                nested.pose().translate(100, 200, 0);
                nested.nativeGraphics();
            });
            check(nativeGraphics.pose().equals(callerPose, 0.001f), "嵌套绘制恢复父级矩阵");
        });
        check(nativeGraphics.pose().equals(callerPose, 0.001f), "绘制结束不污染后续 HUD");
        balanced(nativeGraphics);
        System.out.println("全部通过：" + checks + " 条相机绘制矩阵断言");
    }
}
