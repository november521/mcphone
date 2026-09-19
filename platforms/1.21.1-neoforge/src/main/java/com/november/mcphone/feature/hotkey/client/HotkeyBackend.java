package com.november.mcphone.feature.hotkey.client;

import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.settings.IKeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.client.settings.KeyModifier;

import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 「遥控器」在 1.21.1-neoforge 上的落点：把一次「按下」塞进某个 KeyMapping 的边沿队列。
 *
 * <h2>为什么是这个字段</h2>
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
 * 那个数组是公开的，对象拿得到；缺的只是读写入口，由本目标的
 * {@code META-INF/accesstransformer.cfg} 把 {@code clickCount} 放成 public 补上。
 *
 * <h2>「按下」的完整契约：边沿 + 场景 + 修饰键</h2>
 *
 * 键位作者写的往往不是"这个键被按了"，而是三个条件同时成立：
 *
 * <pre>{@code
 * while (OPEN_WORLD_TIER_SELECT.consumeClick() && OPEN_WORLD_TIER_SELECT.isConflictContextAndModifierActive()) { ... }
 * }</pre>
 *
 * <p>（这一段逐字来自神化 Apotheosis 1.21 的 {@code AdventureKeys.handleKeys}：那个键位默认绑
 * Ctrl + T，"世界层级选择"那个界面就是它开的，而它正是遥控器点不动的那一个。）
 *
 * <p>三件事分别是：<b>边沿</b>（队列里有货 —— {@link #injectClick} 管）、
 * <b>场景</b>（{@code KeyConflictContext.IN_GAME.isActive()} 就是 {@code mc.screen == null}
 * —— 由 {@link KeyTrigger} 先收起手机满足）、<b>修饰键</b>（{@code KeyModifier.CONTROL.isActive()}）。
 *
 * <p>前两件本来就是齐的，第三件不是。{@code isConflictContextAndModifierActive()} 的实现是
 * {@code getKeyConflictContext().isActive() && getKeyModifier().isActive(getKeyConflictContext())}，
 * 而后半截对 CONTROL 来说就是 {@code Screen.hasControlDown()} —— 读的是 GLFW 的<b>物理</b>按键状态。
 * 遥控器注进去的是一次"边沿"，没有任何按键被按住，于是 {@code consumeClick()} 取走了这一下
 * （队列归 0），而 {@code &&} 右边是 false：动作不发生、界面不开，{@link KeyTrigger} 那边
 * 看到队列归 0 还会报成功。这就是那种"点下去有反应、模组什么也没做"的失效时刻 ——
 * 它不报错，只是安静地不生效。
 *
 * <p>所以本目标多了两件事：注入之前把这个键位声明的修饰键<b>让开</b>（{@link #suspendModifier}），
 * 这一下有了结果再<b>收回</b>（{@link #restoreModifier}）。让开之后等式的右边变成
 * {@code KeyModifier.NONE.isActive(IN_GAME)}，而它对 IN_GAME 恒为 true
 * （{@code NONE.isActive} 只在"上下文不是 IN_GAME"时才去查有没有别的修饰键按着），
 * 于是模组那段 {@code while} 的条件整体成立，动作照常发生。
 *
 * <p><b>这不等于要玩家绑一个键。</b>让开的是修饰键那个<i>声明</i>，键码一个字都没动：
 * 寻址照旧按<b>对象</b>（见上一节），键位绑的是 T 还是没绑、玩家有没有把它解绑，都不影响
 * —— "不必为这些功能绑键位"正是这个功能的初衷。反面那两条路（合成一次 GLFW 按键、
 * 或者调 {@code KeyMapping.click(Key)}）都得先有一个键码，解绑之后根本寻址不到，
 * 所以它们在这件事上从一开始就是走不通的，不是"还没做"。
 *
 * <p><b>代价与边界（如实写）</b>：
 *
 * <ul>
 *   <li><b>时限</b>：租约从 {@code KeyTrigger.trigger} 那一刻取，到这一次触发判出结果
 *       （成功或放弃）为止，上限是 {@code KeyTrigger.TOTAL_MAX_TICKS}（30 拍 ≈ 1.5 秒）。
 *       注意起点不是"注入那一刻"：声明只在世界里生效的键位还要先把手机收起来。</li>
 *   <li><b>窗口里那个键位的按法短暂变了</b>：{@code setKeyModifierAndCode} 会把它在
 *       {@code KeyMappingLookup} 里从 CONTROL 那一桶挪到"没有修饰键"那一桶（收回时挪回来）。
 *       于是这 1.5 秒里两个方向都变了：<b>物理按一下那个键码会命中它</b>（平时不会 —— 平时
 *       它在 CONTROL 桶里，不按 Ctrl 根本轮不到），而<b>物理按 Ctrl + 那个键码反而不命中</b>
 *       （平时会）。窗口很短、这时玩家正看着手机，但它是真实差别，不当作"不会有"。</li>
 *   <li><b>硬崩</b>（进程被强杀）恰好落在窗口里时，那个键位的修饰键会停在"没有"上，
 *       直到玩家重绑一次或重启游戏。<b>一个例外如实写在这儿</b>：那段窗口里若正好存过一次
 *       {@code options.txt}（键位与修饰键都写在里面），这个残留就落了盘，重启也带不回来
 *       —— 窗口 1.5 秒、又不经过设置界面，概率按可忽略处理。但"每一个出口都要收回"
 *       这条规矩必须严格执行，理由正是这两条。</li>
 *   <li>只处理键位<b>自己声明</b>的修饰键。模组若自己去查 {@code Screen.hasControlDown()}、
 *       或者读按住类的 {@code isDown()}，这一套够不着它 —— 而结果分两种，不能混为一谈：
 *       <b>完全不碰</b> {@code consumeClick()} 队列的（例如只听原始输入事件），那一次注入
 *       没人取，会被如实报成"触发不了"；<b>既轮询队列、又自己加了一句物理键判断</b>的
 *       （神化那个键位就是这个形状），点击会被它取走却什么都不做，于是被报成成功
 *       —— 这一点写在 {@code KeyTrigger} 的覆盖边界一节里。</li>
 *   <li><b>为什么走公开的 {@code setKeyModifierAndCode}，不 AT 那个私有字段</b>：
 *       AT 只改字段、不动 {@code KeyMappingLookup}，上面那条"按法短暂变了"几乎就没了，
 *       代码也短。没走那条路的理由有两条：一是那个字段是 Forge 补丁加进
 *       {@code KeyMapping} 的，AT 能不能稳定打上去没有验过（本机编不了这两个 Forge 目标）；
 *       二是会故意造成"桶里的修饰键"与"字段里的修饰键"不一致 —— 窗口里只要有人对
 *       这个键位调一次 {@code setKeyModifierAndCode}（玩家打开设置重绑或点重置），
 *       CONTROL 桶就会留下一条悬挂记录，之后 Ctrl + 那个键可能<b>响两次</b>。
 *       两种坏法都低概率，这里选了"字段与桶始终一致"的那一种。</li>
 *   <li>这一套只有 Forge 系的目标有：{@code KeyModifier} 是 Forge / NeoForge 给
 *       {@code KeyMapping} 打的补丁，Fabric 上的原版类里没有这个概念，
 *       那一份的这三个方法都是空转（见那份的类注释）。</li>
 * </ul>
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
 * <h2>这里还有一件与「注入」无关的事</h2>
 *
 * {@link #contextOf}：读键位<b>自己声明</b>的生效场景（NeoForge 的 {@code KeyConflictContext}）。
 * 它有两个用处 —— 列表上那个小标签，以及"触发时要不要先收起手机"。之所以落在本类而不在别处：
 * "冲突上下文"这个概念只有这个目标有，共用侧只认 {@link HotkeyContext} 那几个值。
 *
 * <h2>为什么不是一个接口</h2>
 *
 * 这个类在三个平台上各有一份同名同签名的副本 —— 与 {@code platform.Slots}、
 * {@code platform.StackCodecs} 同一个套路。共用侧（{@code feature/hotkey/client/KeyTrigger}）
 * 直接 import 它，于是「三份签名必须一致」这件事由编译器保证：共用侧要在所有目标上
 * 都编得过。
 *
 * <p>三份的差别只有三处：<b>怎么把那个字段放开</b>、<b>有没有冲突上下文可读</b>、
 * 以及<b>有没有修饰键可让开</b>。前者 neoforge 与 forge 用
 * {@code META-INF/accesstransformer.cfg}、fabric 用 {@code mcphone.accesswidener}；
 * 中间那个只有 neoforge / forge 有（本类这一支），fabric 一律回 {@link HotkeyContext#ANY}；
 * 后者也只有 neoforge / forge 有，fabric 那三个方法全是空转。
 *
 * <p>注意两支 AT 的<b>条目写法不一样</b>：ModDevGradle 认 mojmap 名（本目录那份写的就是
 * {@code clickCount}），ForgeGradle 认 SRG 名（forge 那份写的是 {@code f_90818_}）。
 * 名字不同不是笔误，别顺手把它们统一了 —— 一统一就有一边开了个不存在的字段。
 */
public final class HotkeyBackend {

    private HotkeyBackend() {}

    /** 这个目标能不能真的注入。false 时「遥控器」App 不登记，主屏上看不到它 */
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
     * 这个键位<b>声明了修饰键</b>吗 —— {@code Ctrl + T} 里的那个 Ctrl。
     *
     * <p>只有"要不要替玩家让开它"这一个用处，所以语义与那件事绑死：{@code NONE} 之外都算。
     * {@code KeyModifier} 是枚举（{@code NONE / SHIFT / CONTROL / ALT}），没有自定义取值的余地。
     */
    public static boolean gatesOnModifier(KeyMapping mapping) {
        return mapping.getKeyModifier() != KeyModifier.NONE;
    }

    /**
     * 替玩家把声明的修饰键<b>让开</b>，理由见类注释「「按下」的完整契约」那一节。
     *
     * <p>做法是把它改成 {@code NONE}，原来的值记在 {@link #SUSPENDED} 里等着
     * {@link #restoreModifier} 放回去。没声明过修饰键（{@code NONE}）时什么都不做。
     *
     * <p><b>幂等</b>：同一个键位连着让开两次，第二次直接返回 —— 否则第二次会把
     * "已经被改成 NONE 的值"当成原值记下来，收回时就把玩家的 Ctrl 弄丢了。
     */
    public static void suspendModifier(KeyMapping mapping) {
        KeyModifier original = mapping.getKeyModifier();
        if (original == KeyModifier.NONE) return;                        // 没声明过：没有可让开的
        if (SUSPENDED.putIfAbsent(mapping, original) != null) return;    // 已经让开着了：别把 NONE 记成原值
        mapping.setKeyModifierAndCode(KeyModifier.NONE, mapping.getKey());
    }

    /**
     * 把让开的修饰键放回去。<b>{@link KeyTrigger} 的每一个出口都要调它</b>
     * （成功、放弃、中途离开世界），理由见类注释里那段"代价与边界"。
     *
     * <p><b>幂等</b>：没让开过（表里没有这个键位）就什么都不做 ——
     * 所以调用方不必自己记"这一次到底让开过没有"。
     */
    public static void restoreModifier(KeyMapping mapping) {
        KeyModifier original = SUSPENDED.remove(mapping);
        if (original == null) return;
        mapping.setKeyModifierAndCode(original, mapping.getKey());
    }

    /**
     * 已经让开修饰键的那几个键位 → 它们各自原来的修饰键。
     *
     * <p>按<b>身份</b>索引：{@code KeyMapping} 没有重写 {@code equals}，这里要的就是"同一个对象"
     * 这个语义。写入与读取都在客户端线程上（{@link KeyTrigger#tick()} 与玩家那一下点击），
     * 所以不用并发容器 —— 而同一个键位只可能有一条记录，这也正是上面那两条幂等判断能成立的前提。
     */
    private static final Map<KeyMapping, KeyModifier> SUSPENDED = new IdentityHashMap<>();

    /**
     * 这个键位声明自己在哪儿生效。
     *
     * <p>NeoForge 给每个 {@code KeyMapping} 配了一个 {@code IKeyConflictContext}，默认是
     * {@code UNIVERSAL}（原版那两种构造方法出来的键位都是它 —— 字段的初值就是它），
     * 模组可以在建键位时换成 {@code IN_GAME} / {@code GUI}，也可以实现一个自己的
     * —— 它是<b>接口</b>，不是枚举。
     *
     * <p>所以这里不猜"自己实现的那种是什么意思"，如实交给 {@link HotkeyContext#CUSTOM}：
     * 标签怎么写、触发时要不要先收起手机，都由那个枚举决定。
     */
    public static HotkeyContext contextOf(KeyMapping mapping) {
        IKeyConflictContext ctx = mapping.getKeyConflictContext();
        if (ctx == KeyConflictContext.IN_GAME) return HotkeyContext.WORLD;
        if (ctx == KeyConflictContext.GUI) return HotkeyContext.GUI;
        if (ctx == KeyConflictContext.UNIVERSAL) return HotkeyContext.ANY;
        // 自定义上下文（接口形式）如实标成"其它"；ctx 为 null 是病态用法
        // （setKeyConflictContext 不拦 null），当"没声明"处理 —— 不值得为它 NPE 掉整个列表的渲染。
        return ctx == null ? HotkeyContext.ANY : HotkeyContext.CUSTOM;
    }
}
