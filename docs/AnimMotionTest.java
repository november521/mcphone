import com.november.mcphone.core.client.anim.Animator;
import com.november.mcphone.core.client.anim.Easing;
import com.november.mcphone.core.client.anim.UiMotion;

import java.util.ArrayList;
import java.util.List;

/**
 * Easing / Animator / UiMotion 三个纯类的断言测试（从 AtomChat 移植时的行为钉死）。
 *
 * 跑法（这三个类不依赖 Minecraft，直接 javac/java 即可）：
 *
 *   javac -d /tmp/animtest \
 *     src/main/java/com/november/mcphone/core/client/anim/*.java \
 *     docs/AnimMotionTest.java
 *   java -cp /tmp/animtest AnimMotionTest
 */
public class AnimMotionTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void check(boolean cond, String what) {
        checks++;
        if (!cond) failures.add(what);
    }

    static void near(float actual, float expected, float eps, String what) {
        checks++;
        if (Math.abs(actual - expected) > eps) {
            failures.add(what + "  期望 " + expected + "，实际 " + actual);
        }
    }

    public static void main(String[] args) {
        // ---- Easing：端点与单调性 ----
        near(Easing.linear(0f), 0f, 1e-4f, "linear(0)=0");
        near(Easing.linear(1f), 1f, 1e-4f, "linear(1)=1");
        near(Easing.easeOutCubic(0f), 0f, 1e-4f, "easeOutCubic(0)=0");
        near(Easing.easeOutCubic(1f), 1f, 1e-4f, "easeOutCubic(1)=1");
        near(Easing.easeInOutCubic(0.5f), 0.5f, 1e-4f, "easeInOutCubic(0.5)=0.5");
        near(Easing.easeOutQuad(0f), 0f, 1e-4f, "easeOutQuad(0)=0");
        near(Easing.easeOutQuad(1f), 1f, 1e-4f, "easeOutQuad(1)=1");
        check(Easing.easeOutQuad(0.5f) < Easing.easeOutCubic(0.5f),
                "easeOutQuad 在中点比 easeOutCubic 更柔和（进度更小）");

        // ---- UiMotion.approach：总时长到点即钉死、不超调 ----
        near(UiMotion.approach(0.5f, 1f, 90f, 90L), 1f, 1e-4f, "approach 走满时长直接到目标");
        near(UiMotion.approach(0.8f, 0f, 100f, 100L), 0f, 1e-4f, "approach 回落走满直接到 0");
        near(UiMotion.approach(0.5f, 1f, 45f, 90L), 1f, 1e-4f, "剩余距离 <= 步长时钉到目标");
        float partial = UiMotion.approach(0f, 1f, 45f, 90L);
        check(partial > 0f && partial < 1f, "approach 半程停在 (0,1) 区间");
        near(UiMotion.approach(0.5f, 0.6f, 0f, 90L), 0.5f, 1e-4f, "approach 0ms 不动");

        // ---- Animator：到达即 done、值精确落在终点 ----
        Animator anim = new Animator(Easing::easeOutCubic);
        anim.animateTo(100f, 5f);
        anim.update(50f);
        check(!anim.isDone(), "Animator 半程未 done");
        anim.update(60f);
        check(anim.isDone(), "Animator 累计 110ms 后 done");
        near(anim.getValue(), 5f, 1e-4f, "Animator 终点值精确");
        anim.setValue(2f);
        check(anim.isDone(), "setValue 立即 done");
        near(anim.getValue(), 2f, 1e-4f, "setValue 立即取值");

        if (!failures.isEmpty()) {
            System.out.println("FAIL " + failures.size() + " / " + checks);
            for (String f : failures) {
                System.out.println("  - " + f);
            }
            System.exit(1);
        }
        System.out.println("PASS " + checks + " checks");
    }
}
