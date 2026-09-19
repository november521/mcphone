package com.november.mcphone.feature.hotkey.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 修饰键那一半（{@code KeyModifier}）在 1.20.1-forge 上的断言测试 ——
 * 「按下」三件事（边沿 / 场景 / 修饰键）里第三件钉得住的部分。
 *
 * <h2>这份测试为什么住在平台目录，而不是共用 docs/</h2>
 *
 * 因为它<b>必须</b> import 加载器类型：{@code KeyModifier} 与 {@code KeyConflictContext} 住在
 * {@code net.minecraftforge.client.settings} 里，是 Forge 给 {@code KeyMapping} 打的补丁。
 * 而共用的 {@code docs/HotkeyTest.java} 的类注释里写明：那一份要在三个目标上用同一份源码
 * 编过并跑过，所以它一个 {@code net.minecraftforge.*} / {@code net.neoforged.*} /
 * {@code net.fabricmc.*} 都不许写。判据就是这句「有没有加载器类型」：
 * 三边同源的东西归共用那份，只有一支才有的类型归这一支自己的
 * {@code platforms/1.20.1-forge/docs/}（构建里的 assertTests 会扫到这个目录，
 * 见 {@code gradle/mcphone-checks.gradle} 里那句平台自己的 {@code fileTree('docs')}）。
 *
 * <h2>这里钉得住什么</h2>
 *
 * 修复的推理是三步，前两步与最后一条边界都在这里：
 *
 * <ol>
 *   <li>让开的动作能把修饰键那一栏改成 {@code NONE}（第 1、3 组）；</li>
 *   <li>改成 {@code NONE} 之后，模组那句条件里的修饰键这一半恒成立（第 2 组）；</li>
 *   <li>从头到尾键码一个字都没动，没声明修饰键的键位也不受影响（第 4 组）。</li>
 * </ol>
 *
 * <p>第 5 组是反方向的一次复核：把神化那段 {@code while} 用一个替身跑一遍，
 * 让"点击被取走却不发生动作"这个形状本身可运行。但那是<b>替身</b>，承重的仍然是第 2 组
 * —— 理由写在第 5 组的注释里，别把它当独立证据用。
 *
 * <p>这三步都不需要游戏实例：{@code KeyMapping} 的构造只做两件事 —— 把键码交给
 * {@code InputConstants.Type.getOrCreate} 换一个 Key、把字段填上；{@code setKeyModifierAndCode}
 * 只改字段与内部那张按键索引表。所以本文件是一个普通 JVM 程序（自带 {@code main()}、
 * 自己数断言、失败 {@code System.exit(1)}），没有 JUnit，也没有 {@code Minecraft} 实例。
 *
 * <h2>哪一环这里钉不住：{@code CONTROL.isActive(...)} 那条链要真游戏</h2>
 *
 * 第 2 组只证明 {@code NONE} 那一侧恒成立，<b>没有</b>证明 {@code CONTROL} 那一侧真的是坏的
 * —— 在这里也证明不了。{@code KeyModifier.CONTROL.isActive(ctx)} 的实现最终落到
 * {@code Screen.hasControlDown()}，而那个静态方法要 {@code Minecraft.getInstance().getWindow()}：
 * 无头 JVM 里 instance 是 null，调用它当场 NPE，而这个 NPE 与"修复对不对"毫无关系。
 *
 * <p>所以本文件<b>一次都不调</b> {@code CONTROL.isActive(...)}，也不调
 * {@code isConflictContextAndModifierActive()}（它内部无条件走同一条链）。
 * 那两个只能在真客户端里验，也就是本仓那份手动进游戏跑的验收单。
 * 这一条不是图省事：把 NPE 混进断言里，红的时候分不清是修复错了还是环境不对。
 */
public class HotkeyModifierTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) {
            failures.add(what + "  期望 " + expected + "，实际 " + actual);
        }
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures.add(what);
    }

    /**
     * 跑一组断言。任何 {@link RuntimeException} 都记成一条失败而不是把整份测试打断 ——
     * 「suspend / restore 不许抛」这件事正是靠它验的（抛了这里就有一行失败，不会静默过去）。
     */
    static void group(String what, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            failures.add(what + "：整组没跑完，" + e.getClass().getName() + "：" + e.getMessage());
        }
    }

    /** 声明了 Ctrl 的键位 —— 与神化那个开「世界层级选择」的默认绑定同形。 */
    static KeyMapping gated() {
        return new KeyMapping("key.mcphone.hotkey.test.gated",
                KeyConflictContext.IN_GAME, KeyModifier.CONTROL,
                InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_T, "key.categories.misc");
    }

    /** 没声明修饰键的普通键位。 */
    static KeyMapping plain() {
        return new KeyMapping("key.mcphone.hotkey.test.plain",
                InputConstants.Type.KEYSYM, InputConstants.KEY_A, "key.categories.misc");
    }

    /**
     * 1. 声明了修饰键的键位必须被认出来。
     *
     * <p>认不出来的后果是：遥控器不会替它让开修饰键，而定点读它的模组那句
     * {@code consumeClick() && isConflictContextAndModifierActive()} 会取走这一下却什么都不做。
     */
    static void gatedIsRecognised() {
        KeyMapping gated = gated();
        eq(gated.getKeyModifier(), KeyModifier.CONTROL, "构造出来就该是 CONTROL");
        check(HotkeyBackend.gatesOnModifier(gated),
                "声明了 Ctrl 的键位：gatesOnModifier 必须为 true，否则没人替它让开修饰键");
    }

    /**
     * 2. 让开之后成立的那一环：{@code NONE.isActive(IN_GAME)} 恒为 true。
     *
     * <p>模组那句条件是 {@code consumeClick() && isConflictContextAndModifierActive()}，
     * 而后半截是 {@code 上下文.isActive() && 修饰键.isActive(上下文)}。让开之后修饰键= NONE，
     * 这一条断言的就是那一半恒成立 —— <b>于是整个 {@code &&} 成立、动作照常发生</b>。
     *
     * <p>它不需要游戏实例：{@code NONE.isActive(IN_GAME)} 在 IN_GAME 这一支上直接给 true，
     * 不会走到读物理按键那条链（对比类注释「哪一环这里钉不住」那一节）。
     */
    static void noneIsActiveInGame() {
        check(KeyModifier.NONE.isActive(KeyConflictContext.IN_GAME),
                "NONE.isActive(IN_GAME) 必须为 true；这是修复成立的那一环，"
                        + "为 false 就意味着「让开」之后模组那句条件仍然不成立");

        // 「那个修饰键就是物理 Ctrl 键」——matches 只是比较键码，不读按键状态，这里钉得住。
        // 绝对不能换成 CONTROL.isActive(...)：那条链要 Minecraft 实例，见类注释。
        check(KeyModifier.CONTROL.matches(
                        InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_LEFT_CONTROL)),
                "CONTROL.matches(左 Ctrl) 必须为 true：声明的那个修饰键就是物理 Ctrl 键");
        check(!KeyModifier.CONTROL.matches(
                        InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_T)),
                "CONTROL.matches(T) 必须为 false：修饰键是 Ctrl，不是键位本身那个 T");
    }

    /**
     * 3. 让开与收回，重点是两条幂等 —— 这一组是本文件里最该存在的一组。
     *
     * <p>第一次让开之后连着再让开一次，第二次必须什么都不做：实现若照着"当前值"记原值，
     * 第二次会把 {@code NONE} 记下来，收回时玩家的 Ctrl 就永远停在 NONE 上了。
     */
    static void suspendAndRestoreAreIdempotent() {
        KeyMapping gated = gated();

        HotkeyBackend.suspendModifier(gated);
        eq(gated.getKeyModifier(), KeyModifier.NONE, "让开之后：修饰键那一栏是 NONE");
        check(!HotkeyBackend.gatesOnModifier(gated),
                "让开之后：gatesOnModifier 要为 false（它此刻确实没声明修饰键了）");

        HotkeyBackend.suspendModifier(gated);
        eq(gated.getKeyModifier(), KeyModifier.NONE, "连着让开两次：第二次也得留在 NONE（幂等）");

        HotkeyBackend.restoreModifier(gated);
        eq(gated.getKeyModifier(), KeyModifier.CONTROL,
                "收回之后必须回到 CONTROL —— 若第二次的 NONE 被当成原值记下，这里会停在 NONE，玩家的 Ctrl 就丢了");

        HotkeyBackend.restoreModifier(gated);
        eq(gated.getKeyModifier(), KeyModifier.CONTROL, "再收一次：不抛，也不许把 CONTROL 弄丢（幂等）");

        // 收干净了：这个键位此刻与刚造出来时一样，下面的组不受它影响
        check(HotkeyBackend.gatesOnModifier(gated), "收回之后：gatesOnModifier 回到 true");
    }

    /**
     * 4. 本次改动的硬边界：租约只动修饰键那一栏，键码与别的键位一个字都不许碰。
     *
     * <p>「遥控器不靠键位绑定」是这个功能的前提（见 {@code HotkeyBackend} 类注释
     * 「为什么按对象，不按键」）：玩家把键位解绑之后它照样要能用，所以键码一旦被顺手改掉，
     * 等于把这个前提拆了。
     */
    static void keyCodeAndPlainMappingAreUntouched() {
        InputConstants.Key t = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_T);

        KeyMapping gated = gated();
        eq(gated.getKey(), t, "刚造出来：键码是 T");
        HotkeyBackend.suspendModifier(gated);
        eq(gated.getKey(), t, "让开修饰键之后：键码一个字都不许动");
        HotkeyBackend.restoreModifier(gated);
        eq(gated.getKey(), t, "收回之后：键码还是 T");

        // 本来就没声明修饰键的键位：三个方法都该是空转，而且不留痕迹 ——
        // 没有静态表可以留痕迹（这一支的 SUSPENDED 只在真让开过之后才有记录）。
        InputConstants.Key a = InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_A);
        KeyMapping plain = plain();
        check(!HotkeyBackend.gatesOnModifier(plain),
                "没声明修饰键的普通键位：gatesOnModifier 必须为 false（否则遥控器会去让开一个不存在的东西）");
        HotkeyBackend.suspendModifier(plain);
        eq(plain.getKeyModifier(), KeyModifier.NONE, "空转之后：仍然是 NONE（它本来就没声明过）");
        HotkeyBackend.restoreModifier(plain);
        eq(plain.getKeyModifier(), KeyModifier.NONE, "再空转一次收回：还是 NONE，不许变成别的值");
        eq(plain.getKey(), a, "空转不许碰键码");
    }

    /**
     * 「门」的替身：真实现是 {@code getKeyConflictContext().isActive() && getKeyModifier().isActive(...)}，
     * 而后半截对 CONTROL 来说就是 {@code Screen.hasControlDown()}（读 GLFW 物理按键）。
     *
     * <p>遥控器的场景里没有人按着物理键，所以那一半只能是 false —— <b>除非这个键位被让开了</b>
     * （让开正是遥控器唯一会去改的一件事）。前半截这里不参与：它要
     * {@code Minecraft.getInstance()}，见类注释「哪一环这里钉不住」。
     */
    static boolean gateOpen(KeyMapping m) {
        return !HotkeyBackend.gatesOnModifier(m);
    }

    /**
     * 神化 {@code AdventureKeys} 那段 while 的<b>可运行替身</b>：
     * 门开着才做事，而点击【无论门开不开】都会被 {@code consumeClick()} 取走。
     *
     * <p>它替掉的是两处读不到的东西：真实现里 {@code &&} 右边读 GLFW 的物理按键状态
     * （无头 JVM 里必 NPE），而遥控器在那个场景下能改变的只有"声明的修饰键还在不在"这一件事。
     */
    static boolean modWouldAct(KeyMapping m) {
        return m.consumeClick() && gateOpen(m);
    }

    /**
     * 5. 那台「安静失效」的机器：点击被取走、动作却不发生。
     *
     * <p>第 1~4 组钉的是租约这件工具的往返；这一组把神化那段 {@code while} 用一个替身
     * 跑一遍。<b>它是替身，不是独立证据</b>：{@link #gateOpen} 用的就是本仓自己的
     * {@code gatesOnModifier}，等于用"让开之后它不再要求修饰键"推"让开之后门开了"。
     * 这一组真正新增的、独立于本仓实现的事实只有一条：{@code consumeClick()} 会把点击
     * <b>先取走</b>、之后才轮到门（原版 {@code KeyMapping} 的语义）—— 所以门不开的时候，
     * 那一下也已经被吃掉了，玩家那边就表现为"报成功、什么都没发生"。
     * 「让开之后门开了」这件事的独立依据是第 2 组。
     *
     * <p>还有一处这里钉不住：真的 {@code isConflictContextAndModifierActive()} 还要求
     * {@code mc.screen == null}（那一条由 KeyTrigger 先收起手机满足），而那要 Minecraft 实例。
     */
    static void silentNoOpIsReal() {
        KeyMapping gated = gated();

        // 让开之前：点击被吃掉，动作不发生
        HotkeyBackend.injectClick(gated);
        check(!modWouldAct(gated),
                "让开之前：模组那句 while 不该有任何动作 —— 这就是「点下去有反应、模组什么也没做」那台机器");
        eq(HotkeyBackend.pending(gated), 0,
                "……而且那一下是【已经被取走】的（队列归 0）：门开不开都轮不到下一次，"
                        + "这正是它「安静」的原因，也是 KeyTrigger 会把这一下报成成功的原因");

        // 让开之后：同一个替身，这一次动作发生了
        HotkeyBackend.suspendModifier(gated);
        HotkeyBackend.injectClick(gated);
        check(modWouldAct(gated), "让开之后：同一个替身必须做出动作 —— 这就是这次修复的全部效果");

        HotkeyBackend.restoreModifier(gated);
        check(gated.getKeyModifier() == KeyModifier.CONTROL, "这一组自己也把修饰键收干净了，别留给后面");

        // 【替身不等于真游戏】：真的 isConflictContextAndModifierActive() 还要求
        // mc.screen == null（那一条由 KeyTrigger 先收起手机满足），而那要 Minecraft 实例。
        // 所以这一组证明的是"遥控器把键位声明的那一半补齐了"，不是"神化的界面真的开了"。
    }

    public static void main(String[] args) {
        group("1 认出声明的修饰键", HotkeyModifierTest::gatedIsRecognised);
        group("2 NONE 在 IN_GAME 下恒成立", HotkeyModifierTest::noneIsActiveInGame);
        group("3 让开与收回（幂等）", HotkeyModifierTest::suspendAndRestoreAreIdempotent);
        group("4 键码与普通键位不受影响", HotkeyModifierTest::keyCodeAndPlainMappingAreUntouched);
        group("5 安静失效那一台机器", HotkeyModifierTest::silentNoOpIsReal);

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
