package com.november.mcphone.core.script.server.economy;

import com.november.mcphone.MCphone;
import com.november.mcphone.api.economy.Currency;
import com.november.mcphone.api.economy.EscrowId;
import com.november.mcphone.api.economy.ICurrencyProvider;
import com.november.mcphone.api.economy.TxnReason;
import com.november.mcphone.api.economy.TxnResult;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.time.Instant;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 货币这一摊跟着服务器生死（§15.5）。
 *
 * <p>开服：读存档 → 标出流水比存档超前的那截（存档点之后的） → 扫超时托管 → <b>最后才开网关</b>：扫描完成之前不接受任何货币调用。
 * 停服：先关网关，<b>要在停 worker 之前</b>（理由见 {@link CurrencyGateway#close()}）。
 *
 * <p>超时托管除了开服扫一次，运行中每 {@link #SWEEP_INTERVAL_MS} 再扫一次（{@link #tick()}）：只在开服扫，
 * 服务器跑得越久越不守 7 天的规则。
 *
 * <h2>注册表：谁提供哪种钱（S15f 接线）</h2>
 *
 * 唯一一份 {@link CurrencyRegistry} 由本类持有（{@link #registry()}），与服务器/世界生命周期同轴：
 * 开服建、停服 {@link CurrencyRegistry#clear()}；provider 查找就是它的 {@code get}，不再是写死的 {@code null}。
 * {@code ctx.currency.*}（S15g 接线）与这里的超时托管查找拿到的是<b>同一个实例</b> ——
 * 两份实例会让"同一种货币只有一个实例"这条守恒前提失效（见 {@link CurrencyRegistry#register}）。
 *
 * <p><b>S15d′ 起按配置注册</b>（{@code serverconfig/mcphone-economy.json}）：
 * {@link EconomyConfig} 读表 → {@link EconomyProviders#plan} 定"注册/跳过 + 默认货币" →
 * {@link EconomyProviders#build} 构造 → 本类按<b>配置顺序</b>注册。
 * 存档里出现过、配置里没有的货币仍按 {@code builtin} 兜底注册 + WARN（不许把玩家已有的钱变不可达）。
 * 新世界没有配置、也没有存档货币时注册表为空，{@code ctx.currency.default()} 回 null
 * （App 该 {@code ctx.fail} 而不是崩，§22.8）。
 *
 * <p><b>没到货的档不注册、也不挂空壳</b>（{@code ctx} 上不出现"有属性但永远不可用"的货币，E12/E20/E33）：
 * <ul>
 *   <li>{@code scoreboard} —— S15d′ 起按配置注册（{@code platform/Scores} 接缝已在）；</li>
 *   <li>{@code adapter} —— 还没有已到货的 {@link AdapterProvider.ExternalWallet} 实例（目标模组未定）
 *       ⇒ 一律跳过 + 明确日志；</li>
 *   <li>{@code emc_legacy} —— 只有旧 {@code api/cost} 钱包（{@code IEmcWallet}）真的在场才注册，
 *       否则跳过 + 明确日志。</li>
 * </ul>
 */
public final class EconomyRuntime {

    /** 超时退款写进流水的 {@code reason.kind}。 */
    public static final String TIMEOUT_REFUND_KIND = "escrow_timeout";

    /** 运行中扫超时托管的间隔。超时本身是 {@link EscrowLedger#DEFAULT_TIMEOUT_MS}（7 天），这里只管多久看一次。 */
    public static final long SWEEP_INTERVAL_MS = 5L * 60 * 1000;

    private static volatile EconomyRuntime current;

    private final EconomyData data;
    private final TxnLog log;
    private final CurrencyGateway gateway;
    /** 货币 id → 它的 provider。生产里就是 {@link #registry} 的 get（同一个实例）。 */
    private final Function<String, ICurrencyProvider> providers;
    /**
     * 唯一一份货币注册表，与服务器/世界生命周期同轴（开服建、停服 {@link CurrencyRegistry#clear()}）。
     * {@code null} 只出现在断言测试直接注入 provider 查找函数的那个构造器里。
     */
    private final CurrencyRegistry registry;
    /**
     * 本次注册的"配置面注意事项"（plan warning + "没有默认货币"这类），供命令面查（对抗 D′4）：
     * 光进日志的话，服主在 S2/S5/S6 三种终止态下只能看到一行 warn，对不上账。
     */
    private final List<String> configNotes;
    private long nextSweepAt;
    private int lastOrphaned;
    private int lastFailed;
    /** 退款时 provider 抛过异常的托管：钱退没退出去不知道，这次运行里不再自动退（见 {@link #sweepEscrow}） */
    private final Set<EscrowId> suspect = new HashSet<>();

    /** 断言测试用：直接喂一个 provider 查找函数（{@link #registry()} 为 null）。 */
    EconomyRuntime(EconomyData data, TxnLog log, CurrencyGateway gateway,
                   Function<String, ICurrencyProvider> providers, long now) {
        this(data, log, gateway, null, providers, List.of(), now);
    }

    private EconomyRuntime(EconomyData data, TxnLog log, CurrencyGateway gateway,
                           CurrencyRegistry registry, List<String> configNotes, long now) {
        this(data, log, gateway, registry, registry::get, configNotes, now);
    }

    private EconomyRuntime(EconomyData data, TxnLog log, CurrencyGateway gateway,
                           CurrencyRegistry registry,
                           Function<String, ICurrencyProvider> providers, List<String> configNotes, long now) {
        this.data = data;
        this.log = log;
        this.gateway = gateway;
        this.registry = registry;
        this.configNotes = List.copyOf(configNotes);
        this.providers = providers;
        this.nextSweepAt = now + SWEEP_INTERVAL_MS;
    }

    /** 开服时在主线程上调。重复调会先把上一份关掉（在 {@link #install} 里；这里再兜一句，幂等）。 */
    public static synchronized void start(MinecraftServer server) {
        // 先停掉上一份再建新日志/网关：否则在最外面这个窗口里，旧的 gateway 还开着、
        // 旧 runtime 还是 current，而新日志已经开跑了一次 sweep（P3）。多调一次 stop() 零代价。
        stop();
        EconomyData data = EconomyData.get(server);
        // 整份锁住的存档不接进流水：它永远不写存档点，接上了流水就会替它自动补存档点、说它"存过了"
        TxnLog log = new TxnLog(server.getWorldPath(LevelResource.ROOT).resolve("mcphone").resolve("economy"),
                ZoneId.systemDefault(), data.wholeLock() == null ? data : null);
        Instant now = Instant.now();
        // 整份锁住时存档读不出来，流水比它超前多少无从谈起；而且锁住的存档永远不写存档点，报了每次开服都会重报
        if (data.wholeLock() == null) log.noteRestart(now);
        data.onSave(() -> log.checkpoint(Instant.now()));
        log.sweep(now);
        // S15d′：先把货币配置读出来（坏条目只丢自己、不崩服），再建网关与注册表
        EconomyConfig.Result cfg = EconomyConfig.load(
                EconomyConfig.pathIn(server.getWorldPath(LevelResource.ROOT)));
        for (String p : cfg.problems()) MCphone.LOGGER.warn("[MCphone] 货币配置：{}", p);
        CurrencyGateway gateway = new CurrencyGateway(server::execute,
                () -> Thread.currentThread() == server.getRunningThread());
        // 顺序：建注册表 → 按配置注册 → 扫超时托管（用注册表找 provider）→ 最后才开网关。
        // 扫描发生在主线程上，而网关的 call 在主线程直接执行、不受"还没 open"影响（见 CurrencyGateway.call）
        install(data, log, gateway, System.nanoTime() / 1_000_000, cfg.specs(), () -> server);
    }

    /**
     * 建注册表、注册已到货的档、扫一趟超时托管、开网关、挂上 {@link #current}。
     * <b>会先 {@link #stop()} 把上一份关掉</b>，所以重复调用是幂等的（单人游戏连续开关世界不会残留上一个世界）。
     *
     * <p>与 {@code start(MinecraftServer)} 分开是为了能在 {@code docs/} 的断言测试里跑完整生命周期 ——
     * 那边起不了服务器，但能喂一本 {@link EconomyData} 与一个假网关。
     */
    static synchronized EconomyRuntime install(EconomyData data, TxnLog log,
                                               CurrencyGateway gateway, long now) {
        return install(data, log, gateway, now, null, null);
    }

    /**
     * S15d′ 的接线版：{@code specs} 来自 {@link EconomyConfig}；{@code server} 给
     * {@link ScoreboardProvider} 用。{@code specs == null} = 旧兜底（只按存档注册 builtin，
     * 供断言测试与"配置没到"的降级）。
     */
    static synchronized EconomyRuntime install(EconomyData data, TxnLog log, CurrencyGateway gateway,
                                               long now, List<CurrencySpec> specs,
                                               Supplier<MinecraftServer> server) {
        stop();
        EconomyRuntime r = wire(data, log, gateway, now, specs, server);
        r.sweepNow();
        gateway.open();
        current = r;
        return r;
    }

    /**
     * 建出唯一一份注册表并按配置注册，得到 runtime。开服与断言测试都走这里，
     * 保证 {@code ctx.currency} 与超时托管查找不会各建一份。
     */
    static EconomyRuntime wire(EconomyData data, TxnLog log, CurrencyGateway gateway, long now) {
        return wire(data, log, gateway, now, null, null);
    }

    static EconomyRuntime wire(EconomyData data, TxnLog log, CurrencyGateway gateway, long now,
                               List<CurrencySpec> specs, Supplier<MinecraftServer> server) {
        CurrencyRegistry registry = new CurrencyRegistry(gateway);
        List<String> notes;
        if (specs == null) {
            registerAvailable(registry, data, log);   // 旧兜底：没有配置时的行为，一个字节不变
            notes = List.of();
        } else {
            notes = registerConfigured(registry, data, log, specs, server);
        }
        return new EconomyRuntime(data, log, gateway, registry, notes, now);
    }

    /**
     * 按配置注册（S15d′）。顺序 = 配置顺序；跳过项各打一行原因；
     * <b>存档兜底</b>：存档里出现过、配置里没有的货币仍按 builtin 注册 + 一条 WARN
     * （漏掉等于把玩家已经拥有的钱变成不可达，卡约束 3）。
     */
    private static List<String> registerConfigured(CurrencyRegistry registry, EconomyData data, TxnLog log,
                                                   List<CurrencySpec> specs, Supplier<MinecraftServer> server) {
        Set<String> configured = new java.util.LinkedHashSet<>();
        List<String> notes = new java.util.ArrayList<>();
        EconomyProviders.Plan plan = EconomyProviders.plan(specs, EconomyProviders.hasEmcWallet());
        for (String w : plan.warnings()) {
            MCphone.LOGGER.warn("[MCphone] 货币配置：{}", w);
            notes.add(w);
        }
        for (EconomyProviders.Planned p : plan.entries()) {
            CurrencySpec spec = p.spec();
            configured.add(spec.id());
            if (!p.register()) {
                MCphone.LOGGER.warn("[MCphone] 货币 '{}'（provider={}）本次不注册：{}",
                        spec.id(), spec.provider(), skipText(p.skipReasonKey()));
                continue;
            }
            ICurrencyProvider provider = EconomyProviders.build(p, data, server, log);
            if (registry.register(provider, p.isDefault())) {
                MCphone.LOGGER.info("[MCphone] 货币 '{}'：provider={} decimals={} default={} max={}",
                        spec.id(), spec.provider(), spec.decimals(), p.isDefault(),
                        spec.effectiveMax() == Long.MAX_VALUE ? "无上限" : String.valueOf(spec.effectiveMax()));
            }
        }
        for (String id : data.currencyIds()) {
            if (configured.contains(id)) continue;
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) {
                MCphone.LOGGER.warn("[MCphone] 货币注册表：存档里的 id {} 不是合法的 ResourceLocation，跳过", id);
                continue;
            }
            Currency currency = new Currency(rl, Component.literal(rl.getPath()), "", 0, null);
            BuiltinProvider p = new BuiltinProvider(currency, data, data.escrow(), log,
                    System::currentTimeMillis, false, Long.MAX_VALUE);
            if (registry.register(p, false)) {
                MCphone.LOGGER.warn("[MCphone] 存档里的货币 '{}' 不在配置里，仍按 builtin 注册"
                        + "（要让它带元数据/走别的档，请写进 mcphone-economy.json）", id);
            }
        }
        // D′4：没有默认货币要单独说一句，并留给命令面查 —— 只在启动日志里的话服主对不上账
        if (registry.defaultCurrency() == null) {
            String note = "本服没有默认货币（default=true 都落在未注册的档上，或配置里没写）；"
                    + "ctx.currency.default() 回 null";
            MCphone.LOGGER.warn("[MCphone] {}", note);
            notes.add(note);
        }
        return notes;
    }

    /** 本次注册的配置面注意事项（命令面用；见 {@link #registerConfigured}）。 */
    List<String> configNotes() {
        return configNotes;
    }

    /** 跳过原因键 → 服主看得懂的一句话。 */
    private static String skipText(String reasonKey) {
        return switch (reasonKey) {
            case EconomyProviders.SKIP_EMC_NO_WALLET -> "本服没有在用的 EMC 钱包（emc_legacy 需要真钱包在场）";
            case EconomyProviders.SKIP_ADAPTER_NO_BRIDGE -> "adapter 档还没有实现（目标模组未定），不挂空壳";
            default -> reasonKey;
        };
    }

    /**
     * <b>旧兜底路径</b>（{@code specs == null} 时走）：只为世界存档里已经出现过的货币按 {@code builtin}
     * 注册一份。S15d′ 起生产走 {@link #registerConfigured}（按配置注册）；这条留给断言测试与
     * "配置没读到"的降级，行为与 S15f 时代一致。
     *
     * <p>元数据只从 id 推：显示名取 path、符号空、小数位 0。它只影响显示（E19），
     * 对账与超时退款都不看这些。
     */
    private static void registerAvailable(CurrencyRegistry registry, EconomyData data, TxnLog log) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (String id : data.currencyIds()) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null) {
                // 存档里的 id 不是合法 ResourceLocation：本来也花不出去，跳过并说出来，别让它把开服打断
                MCphone.LOGGER.warn("[MCphone] 货币注册表：存档里的 id {} 不是合法的 ResourceLocation，跳过", id);
                continue;
            }
            Currency currency = new Currency(rl, Component.literal(rl.getPath()), "", 0, null);
            BuiltinProvider p = new BuiltinProvider(currency, data, data.escrow(), log,
                    System::currentTimeMillis, false, Long.MAX_VALUE);
            // isDefault=false：默认货币是配置决定的事（S15d′ 之后由 registerConfigured 拿 plan 决定）
            if (registry.register(p, false)) ids.add(id);
        }
        if (!ids.isEmpty()) {
            MCphone.LOGGER.info("[MCphone] 货币注册表：按 builtin 档注册了 {} 种（{}）",
                    ids.size(), String.join("、", ids));
        }
    }

    /**
     * 每个服务端 tick 结束时在主线程上调。到点才扫：间隔按单调时钟算 —— tick 数在服务器卡的时候不准，
     * 墙钟往回拨的话会停扫那么久。
     */
    public static void tick() {
        EconomyRuntime r = current;
        if (r != null) r.sweepIfDue(System.nanoTime() / 1_000_000);
    }

    /** 到点就扫一次。幂等：只退没结清、已到期的，退过的已经结清。{@code nowMonotonicMs} 只拿来比间隔。 */
    void sweepIfDue(long nowMonotonicMs) {
        if (nowMonotonicMs < nextSweepAt) return;
        nextSweepAt = nowMonotonicMs + SWEEP_INTERVAL_MS;
        sweepNow();
    }

    /**
     * 扫一趟，什么都不往外抛：开服时抛出去服务器就起不来；tick 里抛出去会穿过事件总线把服务器弄崩，
     * Fabric 上还会连带跳过同一个 tick 里的别的活。provider 抛的在 {@link #sweepEscrow} 里逐笔接住，这里接的是剩下的。
     */
    void sweepNow() {
        try {
            report(sweepEscrow(data.escrow(), providers, suspect));
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable e) {
            MCphone.LOGGER.error("[MCphone] 扫超时托管时出错，下次再扫", e);
        }
    }

    // 找不到 provider 的只在数目变了时说一次：每 5 分钟报同一句会把日志刷满
    private void report(Sweep s) {
        if (s.orphaned() != lastOrphaned && s.orphaned() > 0) {
            MCphone.LOGGER.warn("[MCphone] 有 {} 笔托管已超时，但那种货币还没注册，先不退 —— 钱仍在托管里", s.orphaned());
        }
        lastOrphaned = s.orphaned();
        if (s.refunded() > 0) MCphone.LOGGER.info("[MCphone] 超时托管：退了 {} 笔", s.refunded());
        // 被拒的每 5 分钟会再试一次、再被拒一次：同一个数只说一次
        if (s.failed() != lastFailed && s.failed() > 0) {
            MCphone.LOGGER.warn("[MCphone] 超时托管：{} 笔退款被拒（那种货币现在用不了），下次再试", s.failed());
        }
        lastFailed = s.failed();
    }

    /** 停服时在主线程上调，<b>在 {@code ScriptWorkers.stop()} 之前</b>。重复调安全（已经是 null 就是空操作）。 */
    public static synchronized void stop() {
        EconomyRuntime r = current;
        current = null;
        if (r != null) {
            // 先关网关（还在排队的调用一律取消）、再清注册表：清掉之后谁都拿不到 provider，
            // 上一个世界的实例不会跟着静态表进下一个世界（单人游戏连续开关世界）
            r.gateway.close();
            if (r.registry != null) r.registry.clear();
        }
    }

    /** 没开服、或者已经停了就是 null。 */
    public static EconomyRuntime current() {
        return current;
    }

    /**
     * 唯一一份货币注册表 —— {@code ctx.currency.*}（S15g 接线）与超时托管查找用的是<b>同一个实例</b>。
     * 直接注入 provider 查找函数的测试运行时为 {@code null}。
     *
     * <p><b>线程约定（S15g 接线前必须定死）</b>：注册表是普通 {@code LinkedHashMap}，现在只有主线程上的
     * {@code sweepNow} 读它，{@code registry()} 也还没有生产调用点。S15g 把 {@code ctx.currency} 接上之后，
     * worker 上的 {@code get()} 会与主线程上的 {@code register()}/{@code clear()} 并发 —— 要么把表换成并发结构，
     * 要么明文规定"注册表只在主线程读"。在那之前不许接。
     */
    public CurrencyRegistry registry() {
        return registry;
    }

    public EconomyData data() {
        return data;
    }

    public TxnLog log() {
        return log;
    }

    public CurrencyGateway gateway() {
        return gateway;
    }

    /**
     * 当前这一份的主线程网关；<b>没装经济、或已停服就是 null</b>。
     *
     * <p>S18 的 {@code ctx.score} 借它回主线程：{@code Scoreboard} 和账本一样不是线程安全的，
     * 不该为了计分板再养一个队列。网关一关（停服顺序里在脚本之前），计分板调用就和货币一样
     * 拿到"正在停"的拒绝 —— 这正是我们要的。
     */
    public static CurrencyGateway gatewayOrNull() {
        EconomyRuntime r = current;
        return r == null ? null : r.gateway;
    }

    /**
     * @param refunded 退成了几笔
     * @param failed   provider 拒了几笔（比如那种货币的存档锁住了）—— 还在托管里，下次再试
     * @param orphaned 找不到 provider 的几笔 —— 没动
     * @param pruned   清掉了几条很久以前已结清的
     * @param suspect  这一趟新出现的、退款时 provider 抛了异常的几笔 —— 之后不再自动退
     */
    public record Sweep(int refunded, int failed, int orphaned, int pruned, int suspect) {
    }

    /**
     * 超时托管退回原主（§22.4，默认 7 天），并清掉很久以前已结清的条目。
     *
     * <p>退款走那种货币自己的 provider：钱要回到它原来的地方（builtin 的余额、计分板、外部钱包），
     * 流水也由它记。<b>不许绕过 provider 直接改账</b> —— 那样计分板档的钱会退进一本它根本不用的账里。
     */
    public static Sweep sweepEscrow(EscrowLedger escrow, Function<String, ICurrencyProvider> providers) {
        return sweepEscrow(escrow, providers, new HashSet<>());
    }

    /**
     * <b>provider 拒了（返回不是 OK）下次再试，provider 抛了就不再自动试</b>：拒了说明它没动钱；抛了则不知道 ——
     * 外部钱包可能先记上了钱、再在通知或保存那一步抛出来，每 5 分钟自动再退一次就是每 5 分钟多给一份。
     * 抛过的记进 {@code suspect}，逐笔打 ERROR 带上托管号与金额，由服主核对。
     */
    static Sweep sweepEscrow(EscrowLedger escrow, Function<String, ICurrencyProvider> providers, Set<EscrowId> suspect) {
        int refunded = 0, failed = 0, orphaned = 0, suspected = 0;
        Set<String> stacked = new HashSet<>();
        for (Map.Entry<EscrowId, EscrowLedger.Entry> e : escrow.expired()) {
            if (suspect.contains(e.getKey())) continue;
            // 逐笔接住：一种货币的 provider 抛了，别的货币照样退、已结清的照样清
            try {
                ICurrencyProvider p = providers.apply(e.getValue().currencyId());
                if (p == null) {
                    orphaned++;
                    continue;
                }
                TxnResult r = p.refund(e.getKey(), new TxnReason(TIMEOUT_REFUND_KIND, e.getKey().value().toString()));
                if (r == TxnResult.OK) refunded++;
                else if (r != null) failed++;
                else {
                    // 没给结果和抛了一样是结果不明：当成"拒了"就会每 5 分钟再退一次
                    suspect.add(e.getKey());
                    suspected++;
                    EscrowLedger.Entry v = e.getValue();
                    MCphone.LOGGER.error("[MCphone] ⚠ 超时托管 {}（{} 最小单位的 {}，原主 {}）退款时 provider 没给结果（返回了 null），钱退没退出去不知道。"
                            + "这次运行里不再自动退。核对原主在那种货币里的余额：没到账就重启，开服时会再试一次；已经到账的话重启会再退一次，"
                            + "目前只能手改存档（SavedData 与快照一起）把这笔标成已结清", e.getKey().value(), v.amount(), v.currencyId(), v.owner());
                }
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable ex) {
                // 不只接 RuntimeException：外部经济模组换了版本，抛的是 NoSuchMethodError 之类的 LinkageError
                suspect.add(e.getKey());
                suspected++;
                EscrowLedger.Entry v = e.getValue();
                ProviderFailure f = ProviderFailure.of(ex);
                // 堆栈每种货币每趟只打一次：provider 整个坏掉时每笔都是同一个堆栈
                MCphone.LOGGER.error("[MCphone] ⚠ 超时托管 {}（{} 最小单位的 {}，原主 {}）退款时 provider 抛了异常（{}），钱退没退出去不知道。"
                        + "这次运行里不再自动退。核对原主在那种货币里的余额：没到账就重启，开服时会再试一次；已经到账的话重启会再退一次，"
                        + "目前只能手改存档（SavedData 与快照一起）把这笔标成已结清", e.getKey().value(), v.amount(), v.currencyId(), v.owner(),
                        f.getMessage(), stacked.add(v.currencyId()) ? f : null);
            }
        }
        return new Sweep(refunded, failed, orphaned, escrow.pruneSettled(), suspected);
    }
}
