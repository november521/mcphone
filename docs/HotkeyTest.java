package com.november.mcphone.feature.hotkey.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「遥控器」（快捷键触发）的纯逻辑，以及后端注入回路。
 *
 * <h2>这份测试为什么非要存在不可</h2>
 *
 * docs/ 下这几份 *Test.java 由【每个目标】分别编译并运行，一起挂在 check 上，
 * CI 的 build.yml 逐目标跑 {@code ./gradlew build} —— 每个目标的构建里都会跑它一次。
 *
 * <p>而「遥控器」这次移植里有一件事，除了这里没有第二处能验：
 *
 * <ol>
 *   <li>「那份实现能不能编过」由编译器保证 —— 共用侧的 {@link KeyTrigger} 直接 import
 *       各目标的 {@link HotkeyBackend}，签名不一致就是某个目标编不过；</li>
 *   <li>但「<b>运行期</b> {@code KeyMapping.clickCount} 到底可不可写」，编译器一个字都不保证。
 *       AT / access widener 是构建期往字节码上打的补丁：补丁语法对、编译器就认得，于是编得过 ——
 *       <b>「编得过」与「那个字段真的放开了」是两件事</b>。只有把那个字段读一遍、写一遍才知道。</li>
 * </ol>
 *
 * <p>所以第 4 组不是「顺手多测一点逻辑」，它是这份文件存在的全部理由：在纯 JVM 里造一个
 * <b>真的</b> {@link KeyMapping}，让 {@link HotkeyBackend} 往它的边沿队列里注一次、读回来、
 * 清掉，最后用原版 {@code consumeClick()} 取走。
 *
 * <h2>这里测得了什么</h2>
 *
 * <ul>
 *   <li>{@link HotkeyContext}：只有 {@link HotkeyContext#WORLD} 该「先收起手机」，
 *       其余三个都该 false；四个取值的 {@code labelKey()} 非空且两两不同。</li>
 *   <li>{@link HotkeyGroups}：默认只展开置顶那一段，点一下收起、再点一下展开，
 *       动一个分类不许影响置顶那一段。</li>
 *   <li>三个目标的可用性判据：{@code available()} 必须为 true —— 也就是「三个目标都移植完了」
 *       这条结论的机器判据，不靠人看。</li>
 *   <li>注入回路本体：{@code pending} / {@code injectClick} / {@code reset} 的实际行为，
 *       以及它与原版 {@code consumeClick()} 能不能接上（见上）。</li>
 *   <li>{@code contextOf()}：对一个刚造出来、没声明过上下文的键位返回 {@link HotkeyContext#ANY}。</li>
 *   <li><b>修饰键租约的空转面</b>：没声明修饰键的键位上，{@code gatesOnModifier} 恒 false，
 *       {@code suspendModifier} / {@code restoreModifier} 怎么调都不许留下痕迹。
 *       有内容的断言在平台自己的 docs/ 里，理由见下一节。</li>
 * </ul>
 *
 * <h2>这里测不了什么</h2>
 *
 * <ul>
 *   <li><b>真实游戏里那个模组会不会来轮询。</b> 这里只证明「那一下确实躺进了队列，
 *       而且 {@code consumeClick()} 取得走」。至于模组自己那段
 *       {@code while (myKey.consumeClick())} 会不会真的跑，要一个真模组在真客户端里 tick。</li>
 *   <li><b>收起手机那条路。</b> 它在 {@link KeyTrigger#tick()} 里，而那条路一进去就要读
 *       {@code Minecraft.getInstance()} / {@code mc.screen} / {@code mc.level} ——
 *       这些在无头 JVM 里是 null。本文件刻意一次都不碰它们：碰了这里就变成「测 NPE 什么时候出现」，
 *       而不是测注入回路。</li>
 *   <li><b>界面开着时的注入。</b> 同上，那要 {@code mc.screen != null} 才走得进去。</li>
 *   <li><b>上下文取值的真实分布。</b> 这里只测「没声明过 → ANY」。要让一个键位真的声明成
 *       {@code IN_GAME}，得把它注册进真客户端的 {@code Options} —— 那是游戏里的事。</li>
 *   <li><b>跨 tick 的时序与「一次只处理一个」。</b> 那两个性质长在 {@link KeyTrigger} 的状态机上，
 *       而它们每一步都要 {@code Minecraft} 实例。这里没有 tick，也就没有时序。</li>
 *   <li><b>修饰键的真语义（声明了修饰键的键位）。</b>「这个键位声明的到底是不是 Ctrl」
 *       「让开之后它变成什么」「让开前那个值记在哪」全都要 {@code KeyModifier} /
 *       {@code KeyConflictContext} 这两个 Forge 系专有的类型才说得出口，而本节最后那段
 *       不许本文件 import 任何加载器包 —— 所以这里只钉得住「没有修饰键时三件事都空转」。
 *       真正的租约（让开 → 幂等 → 收回 → 幂等，且键码一个字不动）钉在平台自己的
 *       {@code platforms/<目标>/docs/HotkeyModifierTest.java}：那一份只对着一个目标编译、
 *       只在那一个目标上跑，才准写加载器类型。两条路都够不到的是最后一跳 ——
 *       {@code KeyModifier.CONTROL.isActive()} 走 {@code Screen.hasControlDown()} →
 *       {@code Minecraft.getInstance().getWindow()}，无头 JVM 里必 NPE，只能在真客户端里验。</li>
 * </ul>
 *
 * <h2>为什么只接 RuntimeException，不接 Error</h2>
 *
 * 「这一格没接上就当场抛出来，不许静默什么都不做」是本仓对这类门面的规矩
 * （{@code PhoneKeys.Key.mapping()} 与三个平台的 {@link HotkeyBackend} 都照它写）——
 * forge / fabric 两份曾经就是那个形状：{@code available()} 为 false，其余方法抛
 * {@code IllegalStateException}。这里接住它、记成一条失败、把后面几组跑完，比让它把
 * 整份测试打断更有用 —— 而它是<b>失败</b>，不是通过，这一点不含糊。
 *
 * <p>而字段没被 AT / access widener 放开时抛的是 <b>{@code IllegalAccessError}</b>（它是个 Error）。
 * 那个【不接】：它正是本文件要抓的东西，必须当场炸出来，绝不能被这里压成一行失败摘要。
 *
 * <p>同一件事的另一面：本文件不许 import {@code net.minecraftforge.*} / {@code net.neoforged.*} /
 * {@code net.fabricmc.*}。它要在三个目标上用【同一份源码】编过并跑过，碰了加载器包就只在一边成立。
 */
public final class HotkeyTest {

    private HotkeyTest() {}

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
     * 跑一组断言。只接 {@link RuntimeException}，理由见类注释「为什么只接 RuntimeException，不接 Error」。
     *
     * <p>{@code what} 带序号（与契约里那五组一一对应），失败摘要里才看得出是哪一组没跑完。
     */
    static void group(String what, Runnable body) {
        try {
            body.run();
        } catch (RuntimeException e) {
            failures.add(what + "：整组没跑完，" + e.getClass().getName() + "：" + e.getMessage());
        }
    }

    /** 1. {@link HotkeyContext}：谁该先收起手机，以及四个标签键互不相同。 */
    static void contexts() {
        // 四个取值一个个点名钉住。【也正因为点了名】，以后加了第五个取值、而它又被写成
        // true 的话，下面那个循环会把它一起钉上 —— 光写这四行会漏掉新来的那个。
        check(HotkeyContext.WORLD.closePhoneFirst(),
                "WORLD：键位自己声明了只在世界里生效，应当先收起手机再注入");
        check(!HotkeyContext.GUI.closePhoneFirst(),
                "GUI：本来就只有界面里才算数，收起手机等于把它关掉");
        check(!HotkeyContext.ANY.closePhoneFirst(),
                "ANY：不限，先就地注入一次就够（没人取还有收手机重来的退路）");
        check(!HotkeyContext.CUSTOM.closePhoneFirst(),
                "CUSTOM：模组自定义的上下文我们不知道是什么意思，不许替它猜");

        for (HotkeyContext c : HotkeyContext.values()) {
            eq(c.closePhoneFirst(), c == HotkeyContext.WORLD, c + "：只有 WORLD 该先收起手机");
        }

        // 标签键是页面上那句话的翻译键。空了显示成空白，撞了显示成别人的那句话 —— 两种都静默。
        Set<String> seen = new HashSet<>();
        for (HotkeyContext c : HotkeyContext.values()) {
            String label = c.labelKey();
            check(label != null && !label.isEmpty(), c + "：labelKey 不许为空");
            check(seen.add(label), c + "：labelKey 与别的取值撞了（" + label + "）");
        }
    }

    /** 2. {@link HotkeyGroups}：折叠状态。它是静态的，所以这里改完要还原。 */
    static void groups() {
        String movement = "key.categories.movement";

        check(HotkeyGroups.isOpen(HotkeyGroups.PINNED), "默认：置顶那一段是展开的");
        check(!HotkeyGroups.isOpen(movement), "默认：分类是收起的（上百个键位摊开没法找）");
        check(!HotkeyGroups.isOpen("key.categories.redstone"), "没碰过的组要返回 false");

        HotkeyGroups.toggle(HotkeyGroups.PINNED);
        check(!HotkeyGroups.isOpen(HotkeyGroups.PINNED), "点一下置顶表头：要收起来");
        HotkeyGroups.toggle(HotkeyGroups.PINNED);
        check(HotkeyGroups.isOpen(HotkeyGroups.PINNED), "再点一下置顶表头：要展开回来");

        HotkeyGroups.toggle(movement);
        check(HotkeyGroups.isOpen(movement), "点一下分类表头：要展开");
        check(HotkeyGroups.isOpen(HotkeyGroups.PINNED), "展开一个分类不许动到置顶那一段");
        HotkeyGroups.toggle(movement);
        check(!HotkeyGroups.isOpen(movement), "再点一下分类表头：要收起来");
        check(HotkeyGroups.isOpen(HotkeyGroups.PINNED), "收起一个分类也不许动到置顶那一段");

        // 到这里状态已还原（PINNED 展开、movement 收起），下面的组不依赖它，但下一个人读得省心
    }

    /** 3. 可用性：三边都该是 true。这一组就是「三个目标都移植完了」的机器判据。 */
    static void availability() {
        check(HotkeyBackend.available(),
                "本目标的 HotkeyBackend 必须可用 —— 为 false 就说明这个目标的 clickCount 还没放开");
        check(KeyTrigger.available(), "KeyTrigger.available() 也必须是 true（遥控器 App 靠它决定登不登记）");
        eq(KeyTrigger.available(), HotkeyBackend.available(),
                "两个 available() 必须一致：KeyTrigger 那个只是转调，不该各写各的");
    }

    /**
     * 造一个真的 {@link KeyMapping}，不注册进任何 {@code Options}。
     *
     * <p>这个构造方法在两个目标上都是 public，而且<b>不碰 GLFW、不碰 {@code Minecraft.getInstance()}</b>
     * —— 它只做两件事：把键码交给 {@code InputConstants.Type.getOrCreate} 换一个 Key，再把字段填上。
     * （会调 GLFW 的是那个「把键码翻成人看的名字」的显示用 lambda，只有控制界面渲染那一行才走到它。）
     */
    static KeyMapping newMapping(String name) {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, InputConstants.KEY_A, "key.categories.misc");
    }

    /**
     * 4. 注入回路。<b>本文件存在的主要理由</b>：这几行是唯一能验到
     * 「AT / access widener 真的把 {@code KeyMapping.clickCount} 放开了」的地方。
     *
     * <p>顺序不是随手排的：先证明队列本来是空的，再证明我们注进去的<b>恰好一次</b>，
     * 再证明 {@code reset} 收得回来，最后才用原版 {@code consumeClick()} 取走 ——
     * 少任何一步，「注入生效了」都可以是巧合。
     */
    static void injection() {
        KeyMapping mapping = newMapping("key.mcphone.hotkey.test");

        eq(HotkeyBackend.pending(mapping), 0, "刚造出来的键位：边沿队列应当是空的");
        HotkeyBackend.injectClick(mapping);
        eq(HotkeyBackend.pending(mapping), 1, "注入一次之后：队列里恰好攒了 1 次");
        HotkeyBackend.reset(mapping);
        eq(HotkeyBackend.pending(mapping), 0,
                "reset 之后：队列清空 —— 「注入了没人取」时唯一的收尾手段，收不回来那一次会迟到地响");

        HotkeyBackend.injectClick(mapping);
        check(mapping.consumeClick(),
                "注入进去的那一下，模组那句 consumeClick() 必须取得到 —— 运行期那个字段真的被放开了");
        eq(HotkeyBackend.pending(mapping), 0, "取走之后：队列归 0");
        check(!mapping.consumeClick(),
                "一次注入只该产生一次「按下」：取走之后再取就没有了（按住类键位这条路本来就不覆盖）");
    }

    /** 5. 没声明过上下文的键位：Forge/NeoForge 上是 UNIVERSAL，Fabric 上没这个概念，三边都该是 ANY。 */
    static void contextOfDefault() {
        KeyMapping fresh = newMapping("key.mcphone.hotkey.test.context");
        eq(HotkeyBackend.contextOf(fresh), HotkeyContext.ANY,
                "没声明过上下文的键位：三边都该是 ANY（默认不许被当成「只在世界里生效」）");
    }

    /**
     * 6. 修饰键租约的<b>空转面</b>：没有声明修饰键的键位上，三件事都不许有副作用。
     * （有内容的断言在平台自己的 docs/ 里，理由见类注释最后那段「不许 import 加载器包」。）
     */
    static void modifierGates() {
        KeyMapping plain = newMapping("key.mcphone.hotkey.test.modifier");

        // 把「没声明修饰键」判成「要求修饰键」的代价是双向的：面板上会给一个根本没有修饰键的
        // 键位标出 Ctrl/Shift，玩家照着提示去按、还是点不动；而让开/收回那一对也会为它白记一笔。
        check(!HotkeyBackend.gatesOnModifier(plain),
                "没声明修饰键的键位：gatesOnModifier 必须是 false（判成 true，面板就会把一个不存在的修饰键说成有）");

        // 让开一次：空转就该是彻底的，不许把状态改成「看着像要求修饰键」。
        HotkeyBackend.suspendModifier(plain);
        check(!HotkeyBackend.gatesOnModifier(plain),
                "让开一个没声明修饰键的键位之后：仍然不许变成「要求修饰键」");

        HotkeyBackend.restoreModifier(plain);
        check(!HotkeyBackend.gatesOnModifier(plain),
                "收回之后：仍然不许变成「要求修饰键」");

        // 【多调一次必须不抛】。KeyTrigger 的每一个出口都会调一次 restoreModifier（成功、放弃、
        // 中途离开世界），而「这一次到底让开过没有」它自己不必记 —— 那一笔记在 HotkeyBackend 里，
        // 晚到的那几次本来就该是空转。这里多调的这一次就是那个晚到的出口：它要是抛，
        // 玩家看到的是遥控器在收尾时炸掉，而不是一次普通的触发失败。
        HotkeyBackend.restoreModifier(plain);
        check(!HotkeyBackend.gatesOnModifier(plain),
                "收回多调一次不许抛、也不许留下痕迹（KeyTrigger 的每个出口都会调它一次）");
    }

    public static void main(String[] args) {
        group("1 上下文声明", HotkeyTest::contexts);
        group("2 展开状态", HotkeyTest::groups);
        group("3 可用性", HotkeyTest::availability);
        group("4 注入回路", HotkeyTest::injection);
        group("5 默认上下文", HotkeyTest::contextOfDefault);
        group("6 修饰键空转", HotkeyTest::modifierGates);

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
