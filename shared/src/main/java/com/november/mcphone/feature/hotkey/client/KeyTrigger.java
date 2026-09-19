package com.november.mcphone.feature.hotkey.client;

import com.november.mcphone.MCphone;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * 让某一个第三方模组的键位功能响一次 —— 不按键、不模拟 GLFW、不反射它的类。
 *
 * <h2>这是干什么的</h2>
 *
 * 科技模组盔甲的模块键、鞘翅模组的模式切换键，一年按不了几次，却各占一个顺手键位。
 * 这个类让它们在手机里点得到：拿到那个 {@link KeyMapping}，往它自己的「按下边沿队列」
 * 里放一次，模组自己那段 {@code while (key.consumeClick())} 就会照常跑。
 * 真正动字段的那几句在 {@link HotkeyBackend} 里，三个目标各一份：neoforge 与 forge 用 AT 把那个私有字段
 * 放开（Forge 那份的条目写的是 SRG 名），fabric 用 access widener。
 *
 * <h2>两条路：先问声明，再问结果</h2>
 *
 * 有一类模组在 {@code mc.screen != null} 时会跳过自己的轮询（自己写了一句
 * {@code if (mc.screen != null) return;}），而手机界面<b>就是</b>一个 {@code Screen}。
 * 对它们来说，手机开着时注入进去的那一下没人取。
 *
 * <p>更要紧的是：Forge 与 NeoForge 都给键位加了「上下文」，{@code KeyConflictContext.IN_GAME} 的
 * 定义就是「没有界面才算数」，而做游戏内动作的键位基本都选它 —— 于是同一个原因还有
 * 第二个入口（{@code isDown()} 那条路会判上下文；{@code consumeClick()} 本身<b>不</b>判，
 * 所以「点一下＝按一次」这条路在手机开着时仍然可能直接生效，不必一律先关界面）。
 * 玩家在列表里看到的那个小标签就是它，见 {@link HotkeyContext}。
 *
 * <p><b>先问声明</b>：声明了只在世界里生效的（{@link HotkeyContext#WORLD}）直接收起手机再注入，
 * 一次到位 —— 不做那一下注定没人取的就地注入，玩家看到的就是"点一下、手机让开、动作发生"。
 *
 * <p><b>再问结果</b>：没声明的（原版键位与 {@code UNIVERSAL} 都是这个）先就地注入，
 * 隔一拍读回那个计数器。归 0 ＝ 有模组轮询到了，这一下真的生效了；纹丝不动 ＝ 没人在看它，
 * 这才收起手机再注入一次 —— 那时 {@code mc.screen == null}，判了界面的模组就看得见了。
 * 两次都没人取，就是这个键位手机触发不了，如实告诉玩家。
 *
 * <h2>第三条：键位声明的修饰键</h2>
 *
 * 在 Forge 系的目标上，「按一下」其实是三个条件：<b>边沿 + 场景 + 修饰键</b>。
 * 神化那个开「世界层级选择」的键位（默认 Ctrl + T）在自己的客户端 tick 里写的是：
 *
 * <pre>{@code
 * while (OPEN_WORLD_TIER_SELECT.consumeClick() && OPEN_WORLD_TIER_SELECT.isConflictContextAndModifierActive()) { ... }
 * }</pre>
 *
 * 后半个条件的实现是 {@code Screen.hasControlDown()} —— 读 GLFW 的<b>物理</b>按键状态。
 * 遥控器注进去的是一次边沿，没有任何键被按住，于是 {@code consumeClick()} 取走了这一下
 * （队列归 0），而 {@code &&} 右边是 false：界面不开、动作不发生，而本类看到队列归 0
 * 还会报 {@link Outcome#OK}。<b>这正是那种"安静地不生效"的失效时刻</b> ——
 * 它不报错，玩家以为自己点成功了。神化这一个键位就是这么点不动的。
 *
 * <p>所以触发之前先请 {@link HotkeyBackend#suspendModifier} 把这个键位声明的修饰键
 * <b>让开</b>，这一下有了结果再 {@link HotkeyBackend#restoreModifier} <b>收回</b>。
 * 让开之后等式的右边变成 {@code KeyModifier.NONE.isActive(IN_GAME)}，而它对 IN_GAME
 * 恒为 true，模组那段 {@code while} 的条件整体成立，动作照常发生。
 *
 * <p><b>这不是"退化到靠键盘键位触发"。</b> 寻址照旧按<b>对象</b>，键码一个字都没动：
 * 键位绑的是 T 还是没绑、玩家有没有把它解绑，都不影响（见 {@link HotkeyBackend}
 * 的「为什么按对象，不按键」）—— 让开的只是那个键位自己声明的"要按住 Ctrl"这一条。
 * 反过来，合成一次 GLFW 按键、或者调 {@code KeyMapping.click(Key)} 那条路在这里
 * 从最开始就是走不通的：它们都要先有一个键码，而"不必为这些功能绑键位"正是这个功能的初衷。
 *
 * <p><b>每一个出口都要收回</b>，与下面队列那条规矩同一个道理。出口一共四条 ——
 * {@link #succeed}、{@link #giveUp}（等超时与"界面关不掉"都走它）、中途离开世界、
 * 以及 {@code available()} 突然变 false 那一支（那一支只收得回修饰键，队列收不回来，
 * 见那里的注释）—— 四条都必须把修饰键放回去。硬崩恰好落在这段窗口里时，那个键位的修饰键
 * 会停在"没有"上，直到重绑或重启；这个窗口由 {@link #TOTAL_MAX_TICKS} 兜着（最坏 30 拍，
 * 约 1.5 秒），按可忽略处理，但它正是这条规矩必须严格执行的理由。
 * 让开与收回都是幂等的，多收一次的代价远小于忘了收。
 *
 * <p><b>窗口期的代价说清楚</b>：这几秒里那个键位在全局按键索引表里从"Ctrl 那一桶"挪到了
 * "没有修饰键"那一桶（{@link HotkeyBackend#suspendModifier} 干的就是这件事）。
 * 于是：物理按一下那个键码会命中它（平时不会命中），而物理按 Ctrl + 那个键码反而不命中
 * （平时会命中）。窗口很短、这时玩家正看着手机，但它是一个真实的差别，不当作"不会有"。
 * 为什么不改用 AT 把那个私有字段直接改掉（那样索引表就不动、这个差别几乎消失）——
 * 理由写在 {@link HotkeyBackend#suspendModifier} 的注释里。
 *
 * <p><b>还有一类够不着</b>：模组自己去查 {@code Screen.hasControlDown()}、读按住类的
 * {@code isDown()}、或者自己听原始输入事件的 —— 它们要的不是"边沿 + 场景 + 修饰键"这三样，
 * 而是别的东西（物理按键状态、按住状态、原始输入事件）。见下面「覆盖边界」。
 *
 * <h2>每一条出口都要把队列收回来</h2>
 *
 * <b>「关掉界面会把队列清干净」是假的。</b> {@code KeyMapping.releaseAll()} 在
 * {@code Minecraft.setScreen} 里那一句落在 {@code if (guiScreen != null)} 分支中 ——
 * <b>只有开界面才清，关界面走 else 分支，什么都不清</b>（1.21.1 原版，全游戏只有这一个调用点）。
 *
 * <p>所以这个类必须自己负责：凡是「我们注进去的那一次没人消费」的出口
 * （放弃触发、等超时、中途离开世界、以及收起手机之后重新注入之前），都要
 * {@link HotkeyBackend#reset} 一次。不收的话它会一直躺在队列里，等那个模组以后
 * 开始轮询时<b>迟到地响一次</b> —— 玩家看到一个自己没按过的键位突然生效，
 * 而且无从解释。宁可这一次不响应。
 *
 * <h2>收起界面这一下为什么是 {@code mc.setScreen(null)}，不是 {@link Screen#onClose()}</h2>
 *
 * 因为 {@code onClose()} 并不是"关掉界面"。它在 1.21.1 里的实现是
 * {@code minecraft.popGuiLayer()}，而 {@code ClientHooks.popGuiLayer} 在 GUI 层栈非空时
 * 做的是<b>把下面那一层恢复出来</b>（栈空才退化成 {@code setScreen(null)}）——
 * 于是"关手机"会变成"手机关不掉、只闪一下"。本功能要的就是关掉，所以直接说 null。
 *
 * <p>该跑的收尾一样会跑：{@code setScreen} 会调旧界面的 {@code removed()}，而手机的真收尾
 * 在那里（{@code PhoneScreen.removed → shutdown}）。手机那边并没有在 {@code onClose()} 里
 * 做任何事（{@code PhoneScreen.onClose()} 只有一句 {@code super.onClose()}），跳过它不丢东西。
 *
 * <h2>收起手机之后，副手 HUD 上那部还亮着</h2>
 *
 * 手机挂在副手 HUD 上时它是<b>一直开着</b>的（见 {@code PhoneHud}）：收起全屏那副面孔不等于
 * 关机 —— 它会回到屏幕角落继续亮着，玩家再按一次唤出键才回到正中。所以"点完手机还在"
 * 可能只是这个，不是没关掉；日志里那行 {@code 收起手机之后 mc.screen 仍然不是空的} 才是
 * 真的判断依据。
 *
 * <h2>一次只处理一个</h2>
 *
 * 上一个还没走完就再来一下的话，直接忽略：重开一个的话，上一个已经注入、还没人取的那一次
 * 会留在队列里，等模组下一轮轮询时<b>迟到地响一次</b> —— 玩家会看到动作莫名其妙发生。
 * 宁可这一下不响应（玩家再点一次就是），也不要制造一个延迟的意外。
 *
 * <h2>覆盖边界（要如实说，也是 App 页面要显示的那句话）</h2>
 *
 * <ul>
 *   <li>覆盖：在自己的 tick 里轮询 {@code consumeClick()} 的模组 —— 这类占多数；</li>
 *   <li>覆盖：轮询的同时还要求"在世界上"（{@link HotkeyContext#WORLD}）或"按住某个修饰键"
 *       的模组 —— 前一件由收起手机满足，后一件由 {@link HotkeyBackend#suspendModifier} 满足，
 *       见上面「第三条」；</li>
 *   <li>不覆盖：按住类（读 {@code isDown()} 连续生效的）—— 这个功能只做「点一下＝按一次」；</li>
 *   <li>不覆盖：自己听原始输入事件、或直接查物理按键状态的模组。本仓自己就有一个这样的键
 *       （{@code PhoneKeys.HUD_INTERACT}，它必须查 {@code InputConstants.isKeyDown}）——
 *       它们<b>完全不碰</b> {@code consumeClick()} 队列的话，会被 {@link Outcome#UNSUPPORTED}
 *       如实识别出来，不是静默失灵；而"既轮询队列、又另加一句自己的物理键判断"那种
 *       就落到下面那一条上。</li>
 *   <li><b>还有一类必须说清楚</b>：模组把点击<b>取走</b>了、却因为某个我们补不上的条件
 *       而什么都没做（例如它自己查 {@code Screen.hasControlDown()}）。这类本类分辨不出来 ——
 *       「队列归 0」是它唯一能观察到的信号，那一下确实被模组取走了。它会被报成 OK，
 *       而实际什么都没发生。这是已知的、写在明处的边界，不是"应该不会发生"。</li>
 * </ul>
 *
 * <h2>结果怎么交给界面</h2>
 *
 * 结果可能产生在页面已经被销毁之后（收起手机那一条路必然如此），所以它留在本类里，
 * 由页面在<b>全屏那一帧</b> {@link #consumeOutcome() 取走}。<b>本类不负责显示</b>，
 * 页面也不该在 {@code onClose} 时把它清掉 —— 那样这句话就永远不会被玩家看到。
 *
 * <p><b>为什么强调"全屏那一帧"</b>：副手 HUD 上那部手机是不关机的（{@code PhoneScreen.removed}
 * 里的 {@code hudOwned} 那一格），所以收起全屏那副面孔之后，这一页会继续在屏幕角落被
 * 每帧渲染（{@code PhoneHud.render → renderAsHud}），位置编辑器的预览也是同一条路。
 * 要是让角落那一份把结果取走，玩家重开手机时它已经没了 —— 那句话就成了只有缩略图见过的东西。
 *
 * <p>它另外有两条寿命规矩：被取走即清（不重放）；离开世界时无条件清掉（见 {@link #tick()}）
 * —— 那句话是给玩家在<b>这一局</b>里看的，断线之后换个世界再弹出来是无中生有。
 */
public final class KeyTrigger {

    private KeyTrigger() {}

    /** 一次触发的结果。界面拿它显示「这个键位手机触发不了」 */
    public enum Outcome {
        /** 还没有结果，或者上一条结果已经被界面取走 */
        NONE,
        /** 模组把这一下取走了 —— 真的生效了 */
        OK,
        /** 注入两次都没人消费：这个模组不通过 KeyMapping 消费键盘，手机触发不了它 */
        UNSUPPORTED
    }

    /** 注入之后等几拍再判「没人取」。一拍是 50ms；给两拍是为了容忍模组在 tick 里晚一步轮询 */
    private static final int GRACE_TICKS = 2;

    /**
     * 单段的等待上限（拍）。
     *
     * <p>不能没有：模组的轮询有可能永远不看它，而没有上限的状态机会一直挂在 pending 上，
     * 于是玩家之后再点任何键位都被「一次只处理一个」挡掉 —— 手机看起来就是坏了。
     *
     * <p><b>它是"单段"的</b>：{@link #injectWithNoScreen} 会把 {@link Pending#age} 归零
     * （第二段重新计时），所以一次触发可能花掉两段。见 {@link #TOTAL_MAX_TICKS}。
     */
    private static final int MAX_TICKS = 20;

    /**
     * 一次触发的<b>总</b>等待上限（拍），不随分段归零。
     *
     * <p>为什么在单段上限之外还要这一条：{@link #injectWithNoScreen} 会把分段的
     * {@link Pending#age} 归零，于是"关界面卡住"与"注入后没人取"两段的上限会<b>叠加</b>
     * （最坏 40 拍 ≈ 2 秒）。而这段时间里，那个键位声明的修饰键是被让开着的
     * （见类注释「第三条」的"窗口期的代价"）：窗口越长，物理按键命中错位的概率越大，
     * 所以它得有一个不随分段重置的总上限。30 拍（≈1.5 秒）对正常路径绰绰有余
     * —— 走的通的路是"收手机 1~2 拍 + 注入后 1~2 拍"。
     */
    private static final int TOTAL_MAX_TICKS = 30;

    private enum Stage {
        /**
         * 刚登记、还没决定走哪条路。
         *
         * <p>正常情况下同一拍就离开它（{@link #trigger} 紧接着就会写上真正的阶段）。
         * 还看到它，说明那一拍在"登记"与"写阶段"之间抛了异常（{@link #trigger} 里
         * 先挂 {@code pending} 再接修饰键租约，正是为了这种情况下租约还能被收回）——
         * 那一次触发整个作废，走 {@link #giveUp}，连同让开的修饰键一起收回。
         *
         * <p>没有这个取值的话，那种情况下 {@code stage} 是 {@code null}，下一拍的
         * {@code switch} 会 NPE —— 那就是"为了防一次租约残留，换来游戏崩掉"。
         */
        STARTING,
        /** 界面开着时就地注入的，等模组消费（键位没声明只在世界里生效的那一类） */
        INJECTED_IN_GUI,
        /** 键位声明了只在世界里生效：先请手机让开，注入留到没有界面之后 —— 一次到位 */
        CLOSE_FIRST,
        /** 没人消费，已经请界面关掉，等它真的关掉 */
        CLOSING,
        /** 界面关掉之后重新注入的，等模组消费 */
        INJECTED_NO_GUI
    }

    private static final class Pending {
        final KeyMapping mapping;
        Stage stage = Stage.STARTING;

        /** 这一段（{@link Stage} 的那两段）已经等了几拍。{@link #injectWithNoScreen} 会把它归零 */
        int age;

        /** 这一次触发一共等了几拍。<b>不随分段归零</b>，见 {@link #TOTAL_MAX_TICKS} */
        int totalAge;

        /** 我们请让开的那个界面。用来分辨"关不掉"是不是它自己又回来了 */
        Screen closed;

        /** "收起之后还有界面挡着"这件事只记一行日志，别每拍刷一次 */
        boolean warnedBlocked;

        Pending(KeyMapping mapping) {
            this.mapping = mapping;
        }
    }

    private static Pending pending;

    private static Outcome outcome = Outcome.NONE;

    /** 这个目标能不能真的触发（false 时 App 根本不登记，见 {@link HotkeyBackend#available()}） */
    public static boolean available() {
        return HotkeyBackend.available();
    }

    /**
     * 触发一次。
     *
     * <p>上一个还没走完时忽略这一下，理由见类注释「一次只处理一个」。
     */
    public static void trigger(KeyMapping mapping) {
        if (mapping == null || !available()) return;
        if (pending != null) {
            MCphone.LOGGER.info("[MCphone] 遥控器：上一个（{}）还没走完，忽略这一下",
                    pending.mapping.getName());
            return;
        }

        // 新的一次触发作废上一条结果，免得界面把上一次的"触发不了"挂在这一行上
        outcome = Outcome.NONE;

        Pending p = new Pending(mapping);
        Minecraft mc = Minecraft.getInstance();

        // 【先把这一次挂上，再让开修饰键】。顺序不能反：suspendModifier 之后再抛异常
        // （哪怕是 LOGGER 那一行），就会变成"有租约、没有 pending"—— 此后没有任何一拍
        // 会去收它，玩家的 Ctrl 就永久停在"没有"上。先挂 pending，tick() 那一侧才有机会
        // 走 release() 把租约兜回来。
        // 挂上时它的阶段是 Stage.STARTING（不是 null）：真在这中间抛了，下一拍走 STARTING
        // 那一支，把这一次作废、连带把租约收回 —— 见那个取值的注释。
        pending = p;

        // 【先让开声明的修饰键】，理由见类注释「第三条：键位声明的修饰键」。
        // 放在这里而不是首次注入之前：这一次触发可能要走"就地注入 → 收手机 → 再注入"两段，
        // 租约得跨过整段。收回那一边统一走 release()。
        if (HotkeyBackend.gatesOnModifier(mapping)) {
            HotkeyBackend.suspendModifier(mapping);
            MCphone.LOGGER.info("[MCphone] 遥控器：{} 声明了修饰键，触发期间替玩家让开（有结果就收回）",
                    mapping.getName());
        }

        // 键位自己声明了"只在世界里生效"（Forge / NeoForge 的 KeyConflictContext.IN_GAME）时，
        // 手机开着注入的那一次它不会取 —— 手机界面就是一个界面。那就别浪费这一下：
        // 直接请手机让开，注入留到没有界面之后，玩家看到的是一次到位。
        // 见 HotkeyContext 的类注释。
        if (mc.screen != null && HotkeyBackend.contextOf(mapping).closePhoneFirst()) {
            p.stage = Stage.CLOSE_FIRST;
            MCphone.LOGGER.info("[MCphone] 遥控器：{} 声明只在世界里生效，先收起手机再触发",
                    mapping.getName());
            return;
        }

        // 界面开着也先直接注入：consumeClick() 不判上下文，不判界面的模组当场就能吃到，
        // 那样玩家不用为了按一下而退出手机
        HotkeyBackend.reset(mapping);
        HotkeyBackend.injectClick(mapping);
        p.stage = mc.screen == null ? Stage.INJECTED_NO_GUI : Stage.INJECTED_IN_GUI;

        MCphone.LOGGER.info("[MCphone] 遥控器：{}（阶段 {}）", mapping.getName(), p.stage);
    }

    /**
     * 取走最近一次结果，取走之后就没了。
     *
     * <p>「取走」而不是「读」：结果可能产生在页面销毁之后，页面下次进来时取它一次就够，
     * 不该每次开这个 App 都把上一次的旧结果重放一遍。
     */
    public static Outcome consumeOutcome() {
        Outcome out = outcome;
        outcome = Outcome.NONE;
        return out;
    }

    /**
     * 由 {@link com.november.mcphone.core.client.ClientTicks#tick()} 每客户端 tick 调一次。
     *
     * <p>放那儿而不是各目标自己订阅：那个文件就是共用侧「要 tick 的功能」的唯一入口，
     * 它的类注释写着「加一个功能＝在这个文件里加一行，平台文件一个都不用动」。
     */
    public static void tick() {
        Pending p = pending;

        if (!available()) {
            // 【这里不能调 reset】：三份 HotkeyBackend 的 available() 与那几个动作方法是一体的 ——
            // false 意味着这一档压根没接上（没开 clickCount 的口子、或者放开字段的那份声明没生效），
            // 那时 reset 同样不可信。所以只能丢掉 + 留一行日志。
            //
            // 今天走不到这里：能建出 pending 必先过 trigger 的 available() 门，而三份
            // HotkeyBackend.available() 现在都返回常量 true（neoforge 与 forge 是 AT、
            // fabric 是 access widener，三边都把 KeyMapping.clickCount 放开了）。写在这儿是为了
            // 它哪天变成运行期可变（比如加一个配置开关）时不至于变成"注入永远残留、而且一声不响"
            // —— 那一天得先给那一档一个不抛的 reset。
            //
            // 修饰键那一半<b>不一样，照收</b>：restoreModifier 走的是公开的
            // setKeyModifierAndCode，与"这一档有没有把 clickCount 放开"无关 —— 它是这里唯一
            // 还收得回来的东西。不收的话，那一个键位会一直按"不需要修饰键"算数。
            if (p != null) {
                HotkeyBackend.restoreModifier(p.mapping);
                pending = null;
                MCphone.LOGGER.warn("[MCphone] 遥控器：目标中途变得不可用了，这一次触发作废"
                        + "（队列收不回来：这一档不能调 reset，见上；让开的修饰键已放回）");
            }
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            // 不在世界里：待办的那一次作废，上一条结果也一并清掉。
            //
            // 【为什么结果也要清，为什么这一段要放在 p == null 的早退之前】：
            // 「触发不了」那句话是给玩家在【这一局】里看的，断线之后换个世界再弹出来
            // 是无中生有。而结果与 pending 是各自独立的两个字段 —— 放在早退之后的话，
            // "没有待办、但留着一条结果"这个最常见的组合就永远清不掉。
            outcome = Outcome.NONE;
            if (p == null) return;

            // 队列照收 —— 它挂在 mapping 那个对象上，而对象不会跟着世界消失；
            // 让开的修饰键也一样要放回去（它挂在同一个对象上，更不会自己还原）
            release(p);
            pending = null;
            MCphone.LOGGER.info("[MCphone] 遥控器：{} 中途离开了世界，这一次作废（注入已收回、修饰键已放回）",
                    p.mapping.getName());
            return;
        }

        if (p == null) return;

        p.age++;
        p.totalAge++;
        // 总上限先判：它是这两段加起来的那条线，超了就是这一次触发整体太久（见 TOTAL_MAX_TICKS）
        if (p.totalAge > TOTAL_MAX_TICKS) {
            giveUp(p, "总时长超过上限");
            return;
        }
        if (p.age > MAX_TICKS) {
            giveUp(p, "等太久了");
            return;
        }

        // 归 0 就是被消费了。唯一会误判的窗口：这期间有别的界面被打开
        // （setScreen 非 null 时会 releaseAll 把队列清零）。而本页 capturesKeyboard() 为 true
        // 会吃掉所有按键、点手机外面是"关"不是"开"，所以玩家自己走不出这条路径；
        // 窗口也只有两拍。机制上存在，眼下够不着，记在这儿备查。
        boolean consumed = HotkeyBackend.pending(p.mapping) == 0;

        switch (p.stage) {
            case STARTING -> {
                // 登记了、但那一拍没能走到"写阶段"（trigger 在中间抛了）。这一次作废，
                // 连同让开的修饰键一起收回 —— 见 Stage.STARTING 的注释。
                // 【这里不能看 consumed】：这个阶段压根没注入过，队列本来就是空的，
                // 拿它当"有人消费了"会把一次根本没发生的触发报成成功。
                giveUp(p, "登记之后没能开始");
            }
            case INJECTED_IN_GUI -> {
                if (consumed) {
                    succeed(p);
                } else if (p.age >= GRACE_TICKS) {
                    // 没人取：多半是它判了 mc.screen == null。
                    // 【先把这一下收回来再关界面】：关界面不会清队列（见类注释），
                    // 不收的话它会留着，等模组以后轮询时迟到地响一次
                    HotkeyBackend.reset(p.mapping);
                    askScreenToLeave(p, mc);
                }
            }
            case CLOSE_FIRST -> {
                // 键位自己声明了"界面开着时不算数"，所以这一次连试都不试：直接请手机让开，
                // 注入留到没有界面之后（见 HotkeyContext 的类注释）。
                //
                // 【这一档不能看 consumed】：本阶段压根没注入过，队列本来就是空的，
                // 拿它当"有人消费了"会把一次根本没发生的触发报成成功
                HotkeyBackend.reset(p.mapping);
                askScreenToLeave(p, mc);
            }
            case CLOSING -> {
                Screen now = mc.screen;
                if (now != null) {
                    // 还没关干净，再等一拍。第一次发现有界面挡着时把"是谁"记下来：
                    // 正常情况一拍就没了；一直不走，说明有别的东西把界面又顶了回来，
                    // 而玩家那边只会看到"手机没关掉"。那一行日志是唯一的线索。
                    if (!p.warnedBlocked) {
                        p.warnedBlocked = true;
                        MCphone.LOGGER.warn("[MCphone] 遥控器：收起手机之后 mc.screen 仍然不是空的"
                                        + "（{}）—— {}",
                                now.getClass().getName(),
                                now == p.closed ? "刚收起来的那个界面又回来了"
                                                : "顶上来的是另一个界面");
                    }
                    return;
                }
                injectWithNoScreen(p);
            }
            case INJECTED_NO_GUI -> {
                if (consumed) {
                    succeed(p);
                } else if (p.age >= GRACE_TICKS) {
                    giveUp(p, "没有模组消费");
                }
            }
        }
    }

    /**
     * 请当前这个界面让开，然后进入 {@link Stage#CLOSING} 等它真的让开。
     *
     * <p>界面已经不在了（玩家自己把手机收了）就直接走"没有界面"那一步 —— 那正是我们要的
     * 状态，没有理由放弃：进到这里的两条路都刚收过队列，此刻注入进去不会与谁混在一起。
     */
    private static void askScreenToLeave(Pending p, Minecraft mc) {
        Screen screen = mc.screen;
        if (screen == null) {
            injectWithNoScreen(p);
            return;
        }

        // 【为什么是 setScreen(null) 而不是 screen.onClose()】：onClose() 走的是 popGuiLayer()，
        // GUI 层栈非空时它恢复的是下面那一层 —— "手机关不掉、只闪一下"就是这么来的。
        // 详见类注释那一节。
        //
        // 【为什么要 catch】：最可能的一种是原版的前置断言 —— Minecraft.setScreen 开头就是
        //   if (guiScreen == null && clientLevelTeardownInProgress) throw new IllegalStateException(...)
        // 单机断线时正处在这个状态（level/player 还非空、teardown 已置位），而这一下
        // 是从 ClientTicks.tick() 冒出去的 —— 不接住就直接崩游戏。
        //
        // 【为什么还要把异常本身记下来】：它不是唯一一种。手机自己的收尾链
        // （{@code PhoneScreen.removed → shutdown}）与别的模组的 {@code ScreenEvent.Closing}
        // 监听器都挂在这条路上，它们抛出来的是真 bug（本仓的或别人的）。而这一句分不出是
        // 哪一种（{@code clientLevelTeardownInProgress} 是私有字段，读不到），所以不装作分得出：
        // 接住是为了别让玩家崩，连同栈一起写日志是为了不静默 —— 只记一句"世界正在卸载"
        // 会让真 bug 顶着这个名字从日志里消失，那正是本仓不允许的那种失败。
        p.closed = screen;
        try {
            mc.setScreen(null);
            p.stage = Stage.CLOSING;
        } catch (RuntimeException e) {
            MCphone.LOGGER.warn("[MCphone] 遥控器：收起手机时 {} 抛出异常，这一次作废（队列已收回）",
                    e.getClass().getName(), e);
            giveUp(p, "界面关不掉：" + e.getClass().getSimpleName());
        }
    }

    /** 没有界面挡着了：清一次队列再注入，等模组把这一下取走 */
    private static void injectWithNoScreen(Pending p) {
        // 关界面的过程里可能又发生过一次 releaseAll（比如中间闪了一下别的界面），
        // 所以这里再收一次再注入，保证队列里恰好只有我们这一次
        HotkeyBackend.reset(p.mapping);
        HotkeyBackend.injectClick(p.mapping);
        p.stage = Stage.INJECTED_NO_GUI;
        p.age = 0;                          // 重新计时，否则总上限会把第二阶段掐掉
    }

    private static void succeed(Pending p) {
        // 【先收修饰键，再清 pending】：反过来的话（先清 pending 再收），
        // restoreModifier 一抛就没人再收它了 —— 而它的失败模式正是"玩家的 Ctrl 没了"。
        // 顺序与 {@link #release} 是同一个道理。
        HotkeyBackend.restoreModifier(p.mapping);
        pending = null;
        outcome = Outcome.OK;
        // 【这里只收修饰键，不 reset】：队列这时候本来就是空的（上面那句刚读出来就是 0，
        // 而客户端单线程，从读到这儿没有别的东西能往里塞），所以 reset 是多余的一次写。
        // 多余不等于危险，但不写更省事：我们只动自己改过的东西。
        MCphone.LOGGER.info("[MCphone] 遥控器：{} → 模组取走了这一下", p.mapping.getName());
    }

    /**
     * 一次触发的<b>收尾</b>：把注进去、还没人取的那一下收回，并把替玩家让开的修饰键放回去。
     *
     * <p>「放弃」与「离开世界」这两条出口走这里；成功那条在 {@link #succeed} 里只收修饰键
     * （理由写在那儿）；{@code available()} 变 false 那一支只收修饰键（队列收不回来，
     * 理由写在 {@code tick()} 里那一支的注释）。<b>四条出口都必须收修饰键</b> ——
     * 两条都是"不收就会留下迟到后果"的东西：队列不收，那个模组以后开始轮询时会
     * <b>迟到地响一次</b>；修饰键不收，那个键位会一直按"不需要修饰键"算数
     * （见类注释「第三条」）。
     *
     * <p>【注入之前那几处 {@code reset} 不走这里】：{@link #injectWithNoScreen} 与
     * {@code INJECTED_IN_GUI} 发现没人取时都要清一次队列再继续，那都不是出口 ——
     * 那时租约必须留着，因为紧接着还要再注入一次。
     *
     * <p>两个动作都是幂等的，所以这里不必判断"这一次到底让开过没有"。
     */
    private static void release(Pending p) {
        HotkeyBackend.reset(p.mapping);
        HotkeyBackend.restoreModifier(p.mapping);
    }

    /** 放弃这一次。{@code why} 只进日志，玩家看到的是页面那句统一的话 */
    private static void giveUp(Pending p, String why) {
        // 放弃就要把队列收回来，否则那一次会迟到地响；修饰键同理（见类注释「第三条」）
        release(p);
        pending = null;
        outcome = Outcome.UNSUPPORTED;
        MCphone.LOGGER.info("[MCphone] 遥控器：{} → 没有模组消费（{}），手机触发不了这个键位",
                p.mapping.getName(), why);
    }
}
