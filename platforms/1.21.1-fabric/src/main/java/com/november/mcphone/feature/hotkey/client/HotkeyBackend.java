package com.november.mcphone.feature.hotkey.client;

import net.minecraft.client.KeyMapping;

/**
 * 「遥控器」在 1.21.1-fabric 上的落点：把一次「按下」塞进某个 {@link KeyMapping} 的边沿队列。
 *
 * <h2>为什么动的是这个字段</h2>
 *
 * 模组消费键位的写法只有那么几种，其中覆盖最广的一种是：在自己的客户端 tick 里
 *
 * <pre>{@code
 * while (myKey.consumeClick()) { ...做事... }
 * }</pre>
 *
 * 而 {@code consumeClick()} 的全部实现就是「队列里有货就减一，然后说 true」
 * （{@code KeyMapping.consumeClick()}，它不判界面、也不判上下文）。所以往那个队列里
 * 放一次，模组自己那段轮询就会照常跑 —— 不需要反射它的类、不需要知道它做了什么。
 *
 * <p>原版把那个队列声明成 {@code private int clickCount}，本类要做的就是让它可读写。
 * 入口怎么来的，见下一节。
 *
 * <h2>入口为什么是 access widener，而不是本平台已有的 mixin accessor</h2>
 *
 * 本目标已经有现成的手法：{@code mixin/client/KeyMappingAccessor} 就是给 {@code KeyMapping}
 * 的私有字段 {@code key} 开的一个 {@code @Accessor} 读口。给 {@code clickCount} 照抄一个
 * accessor 看起来更"就地"、也更省事。
 *
 * <p>但这里的判据不是省事，是<b>字段在编译期与运行期两个阶段都必须真的可写</b>：
 *
 * <ul>
 *   <li><b>access widener</b>（{@code src/main/resources/mcphone.accesswidener}，由
 *       {@code build.gradle} 的 {@code loom.accessWidenerPath} 登记）：Loom 在<b>编译期</b>
 *       就把规则应用到 Minecraft jar 上 —— javac 看见的 {@code KeyMapping} 里那个字段已经是
 *       public，所以本类里直接写 {@code mapping.clickCount++} 编得过；运行期 fabric-loader
 *       应用同一份规则，字段真的可写。一处声明，两个阶段。</li>
 *   <li><b>mixin accessor</b> 只在<b>真实游戏里</b>成立：访问器方法是 mixin 在类加载时织进去的，
 *       纯 JVM（没有 mixin bootstrap）里它根本不存在 —— 那是一条编得过、跑必炸的路。</li>
 * </ul>
 *
 * <p>「纯 JVM」为什么在这里是判据而不是洁癖：{@code docs/} 下那些带 main() 的断言测试由
 * <b>每个目标</b>编译并运行（挂在 {@code check} 上，CI 逐目标跑 {@code ./gradlew build}），
 * 其中那条注入回路要 new 一个 {@code KeyMapping}、注入一次、再把计数器读回来 ——
 * 那是一个没有游戏、没有 mixin 的 JVM。只有"编译期与运行期同时生效"的声明才过得了它。
 *
 * <p>代价如实写下来：access widener 是<b>整个 jar 一份</b>的全局声明，不是只对本类生效的
 * 局部口子 —— 从 {@code fabric.mod.json} 到加载器，谁都能看见这个字段变成 public。
 * 本仓要的就是它在纯 JVM 里可读写，而这个诉求没有第二种满足方式，所以认下这个代价。
 * （打包出去之后由加载器读的是 {@code fabric.mod.json} 里 {@code "accessWidener"} 那一处，
 * 与 build.gradle 里那句 {@code accessWidenerPath} 缺一不可。）
 *
 * <h2>为什么按对象，不按键</h2>
 *
 * 原版公开的 {@code KeyMapping.click(InputConstants.Key)} 与 {@code KeyMapping.set(Key, boolean)}
 * 都是<b>按「键」寻址</b>的：它们走内部的 {@code KeyMappingLookup.getAll(key)}，把绑在
 * 那个键上的<b>全部</b> mapping 一起处理。这在你想「代替玩家按一下那个键」时是对的，
 * 在本功能里是错的，而且是两头的错：
 *
 * <ul>
 *   <li>一个键上被好几个模组分别绑了快捷键时（很常见），一次触发会把它们<b>全部</b>叫起来
 *       —— 那正是这个功能要解决的那种乱，不是它该制造出来的；</li>
 *   <li>玩家为了腾出键位而把模组的键位<b>解绑</b>之后，按「键」根本寻址不到它
 *       —— 而「腾出键位」正是这个功能的初衷。</li>
 * </ul>
 *
 * 所以这里要的是 {@code mc.options.keyMappings} 里那<b>一个</b>实例，直接动它自己的计数器。
 * 那个数组是公开的，对象拿得到；缺的只是读写入口，就是上面那个 access widener 补的。
 *
 * <h2>为什么这一支没有修饰键这件事</h2>
 *
 * Forge 系的目标上，键位作者常把条件写成一整句
 * {@code consumeClick() && isConflictContextAndModifierActive()} —— 后半截里除了「场景」，
 * 还有<b>修饰键</b>（{@code Ctrl + T} 里的那个 Ctrl，也就是 {@code KeyModifier.CONTROL}），
 * 而它的 {@code isActive()} 读的是 GLFW 的物理按键状态。遥控器只注了一次「边沿」、
 * 没有任何键被按住，于是那一句 {@code &&} 右边是 false：动作不发生，而遥控器看到队列归 0
 * 还报成功（完整的失效过程写在 neoforge 那份的「「按下」的完整契约」一节）。
 * 那两份因此多了 {@code gatesOnModifier / suspendModifier / restoreModifier}。
 *
 * <p><b>这件事在本目标上不存在，不是"还没做"。</b>{@code KeyModifier} 与
 * {@code IKeyConflictContext} 一样，都是 Forge / NeoForge 给 {@code KeyMapping} 打的补丁：
 * 原版类上没有那个字段、没有那个访问器，模组<b>没有办法</b>在 {@code KeyMapping} 上声明
 * 修饰键。既然声明根本不存在，就没有"已声明却被挡住"的键位，也没有那个要修的等式
 * —— 能被让开的东西是空的。
 *
 * <p>那 Fabric 上的模组想写 Ctrl + T 怎么办？它只能自己去查 {@code Screen.hasControlDown()}
 * —— 那正是 Forge 系 {@code KeyModifier} 内部干的事，而模组自己写的那一句遥控器够不着；
 * 这类键位遥控器够不着，而结果分两种、不能混为一谈：<b>完全不碰</b>
 * {@code consumeClick()} 队列的（例如只听原始输入事件），那一次注入没人取，会被如实报成
 * "触发不了"；<b>既轮询队列、又自己加了一句物理键判断</b>的，点击会被它取走却什么都不做，
 * 于是被报成成功 —— 两种都写在 {@link KeyTrigger} 的覆盖边界一节里。
 * 反过来那条路（干脆替它合成一次真的 Ctrl 按键）走不通：
 * 那要动的是全局输入状态 —— 按下与抬起必须配对，期间所有读物理按键的地方都会看见它，
 * 而"不合成输入、不要求绑键"正是这个功能一开始就定下的边界。
 *
 * <p><b>共用侧照样调这三个方法。</b>签名与另外那两份逐字相同（共用侧直接 import），
 * 调用点因此不必按目标写分支，本目标这三个实现只是空转 —— 不抛、不留痕迹。
 * 这与上一节 {@link #contextOf} 恒为 {@link HotkeyContext#ANY} 是同一件事的两面：
 * 本平台缺的那个声明，如实按缺了处理。
 *
 * <h2>为什么非要有 {@link #reset} 不可</h2>
 *
 * 因为「关掉界面会把队列清干净」这件事是<b>假的</b>。{@code KeyMapping.releaseAll()}
 * 在 {@code Minecraft.setScreen} 里被调用的那一句落在
 * {@code if (guiScreen != null)} 分支里 —— <b>只有开界面才清，关界面走的是 else 分支，
 * 什么都不清</b>（1.21.1 原版 {@code Minecraft.java}）。整个游戏里 {@code releaseAll()}
 * 只有那一个调用点。
 *
 * <p>所以「注入了一次、没人消费」这件事不会自己消失：它会留在队列里，等那个模组
 * 以后开始轮询时<b>迟到地响一次</b>。{@link KeyTrigger} 放弃一次触发、
 * 或者在收起手机之后重新注入之前，都必须自己把它收回 —— 就是 {@link #reset}。
 *
 * <h2>{@link #contextOf} 为什么恒为 {@link HotkeyContext#ANY}</h2>
 *
 * 「冲突上下文」（{@code IKeyConflictContext}）是 Forge / NeoForge 给 {@code KeyMapping}
 * 打的补丁：键位作者在那里声明"这个键该在哪儿管事"，{@code HotkeyContext} 就是那个声明的
 * 译文。Fabric 这边的 {@code KeyMapping} 是<b>没有打过那个补丁的原版类</b>：没有那个字段、
 * 没有那个访问器、没有任何声明可读。所以这里能给出来的答案只有一个 ——「没声明」，
 * 而 {@link HotkeyContext#ANY} 的定义正是这个：<b>不限，也是原版键位与"没声明过"的默认值</b>。
 * 这不是"Fabric 上先凑合"，而是这个概念在本平台上的字面事实。
 *
 * <p><b>不假装有</b>：如果在这里编一个 {@link HotkeyContext#WORLD} 或 {@link HotkeyContext#CUSTOM}
 * 出来，列表上那个小标签会显示一个不存在的声明，而触发那边会据此决定"要不要先收起手机"
 * —— 拿一个猜出来的值去改变行为，比不标更坏。如实说"没声明"，玩家看到的就是真的。
 *
 * <p><b>行为后果</b>（这一条必须写清）：{@code ANY.closePhoneFirst()} 为 false，于是
 * {@link KeyTrigger} 走那条通用路 —— 先在界面里就地注入一次，隔两拍读回计数器；没人取
 * （计数器纹丝不动）才收起手机、再注入一次。而 Fabric 上<b>每一个</b> {@code key.*} 类别的
 * KeyMapping 都是这个走法：原版这里没有可声明的上下文，模组也没法通过 {@code KeyMapping}
 * 声明"只在世界里生效"。代价是凡是判了 {@code mc.screen} 的模组要多花两拍、多一次收起手机；
 * 好处是不漏 —— 判界面的模组在第二次吃到，不判的第一次就吃到了。
 *
 * <h2>为什么不是一个接口</h2>
 *
 * 这个类在三个平台上各有一份同名同签名的副本 —— 与 {@code platform.Slots}、
 * {@code platform.StackCodecs} 同一个套路。共用侧（{@code feature/hotkey/client/KeyTrigger}）
 * 直接 import 它，于是「三份签名必须一致」这件事由编译器保证：共用侧要在所有目标上
 * 都编得过。三份里的方法体各写各的：本目标与 neoforge / forge 的差别只有三处
 * —— 入口是 access widener 而不是 {@code META-INF/accesstransformer.cfg}；
 * {@link #contextOf} 没有声明可读（见前面那一节）；以及修饰键那一套在这里没有对应物
 * （见「为什么这一支没有修饰键这件事」一节），三个方法全是空转。
 * 三处都是本平台上的字面事实，不是能力上的缺口。
 */
public final class HotkeyBackend {

    private HotkeyBackend() {}

    /**
     * 这个目标能不能真的注入。false 时「遥控器」App 不登记，主屏上看不到它。
     *
     * <p>Fabric 上是 true：{@code clickCount} 由 {@code mcphone.accesswidener} 放开，
     * 编译期与运行期都成立（见类注释第一节），没有留任何"到运行才知道行不行"的东西。
     */
    public static boolean available() {
        return true;
    }

    /**
     * 按<b>对象</b>往边沿队列里放一次「按下」。
     *
     * <p>不按键寻址，所以绑在同一个键上的别的模组不受影响；也不管这个 mapping 当前绑的
     * 是哪个键、有没有被解绑 —— 字段就在对象上，与它绑了什么无关。
     */
    public static void injectClick(KeyMapping mapping) {
        mapping.clickCount++;
    }

    /**
     * 队列里还积着几次没被消费。注入之后隔一拍读这个数，就知道模组到底理没理这一下：
     * 归 0 ＝ 有人轮询到了；纹丝不动 ＝ 没人在看它（多半是它自己判了 {@code mc.screen == null}）。
     */
    public static int pending(KeyMapping mapping) {
        return mapping.clickCount;
    }

    /**
     * 把边沿队列清空 —— 收回我们注进去、还没人消费的那一次。
     *
     * <p>理由见类注释「为什么非要有 reset 不可」：原版只在<b>开</b>界面时清队列，
     * 关界面不清。不收的话，那一次会一直躺着，等模组以后轮询时迟到地响一下。
     *
     * <p>代价说清楚：如果玩家在点这一行的同一瞬间正好真按了那个键（队列里本来积着
     * 一次真按下），这一次会被一起抹掉。这个窗口是几十毫秒，而那是一个一年按不了
     * 几次的键位，所以按「抹掉」处理 —— 反过来（不做 reset）制造的是一个
     * <b>迟到的、玩家无从解释的</b>动作，比丢掉一次真按下糟得多。
     */
    public static void reset(KeyMapping mapping) {
        mapping.clickCount = 0;
    }

    /**
     * 这个键位声明了修饰键吗 —— <b>本目标上恒为 false</b>。
     *
     * <p>原版 {@code KeyMapping} 上没有 {@code KeyModifier} 这个概念（那是 Forge 系打的补丁），
     * 模组声明不了，所以这里不存在"要点亮却点不亮"的那一类。理由与后果写在类注释的
     * 「为什么这一支没有修饰键这件事」一节里。
     *
     * <p>参数不用是对的：本方法不看它。签名与另外两个目标逐字相同（共用侧直接 import），
     * 所以照旧收着 —— 共用侧的调用点因此不必按目标写分支。
     */
    public static boolean gatesOnModifier(KeyMapping mapping) {
        return false;
    }

    /**
     * 替玩家把声明的修饰键让开 —— <b>本目标上没有可让开的东西</b>，所以什么都不做。
     *
     * <p>不抛、不留痕迹：这里连静态表都没有（对比另外两份那个 {@code SUSPENDED}），
     * 因为"已经让开过一次"这种状态在本平台上不会出现，"恢复原先的修饰键"自然也无从谈起。
     */
    public static void suspendModifier(KeyMapping mapping) {
        // 空转：原版 KeyMapping 上没有修饰键可让开，见类注释那一节
    }

    /**
     * 把让开的修饰键放回去 —— <b>本目标上没有让开过</b>，所以什么都不做。
     *
     * <p>与 {@link #suspendModifier} 一样是空转（空函数天然幂等）。共用侧每一个出口都会调它，
     * 所以"不抛"这条是硬要求：它不能因为这里没有东西可恢复而失败。
     */
    public static void restoreModifier(KeyMapping mapping) {
        // 空转：见类注释那一节
    }

    /**
     * 这个键位声明自己在哪儿生效。
     *
     * <p>Fabric 上恒为 {@link HotkeyContext#ANY} —— 原版 {@code KeyMapping} 上没有
     * 「冲突上下文」这个概念，没有任何声明可读，"没声明"就是这里唯一如实的答案。
     * 完整的取舍与它带来的行为后果（{@link KeyTrigger} 走"先就地注入、没人取再收手机"
     * 那条通用路）写在类注释的 {@code contextOf 为什么恒为 ANY} 一节里。
     *
     * <p>参数不用是对的：本方法不看它。签名与另外两个目标逐字相同（共用侧直接 import），
     * 所以照旧收着，免得三份里出现一个"长得不一样"的。
     */
    public static HotkeyContext contextOf(KeyMapping mapping) {
        return HotkeyContext.ANY;
    }
}
