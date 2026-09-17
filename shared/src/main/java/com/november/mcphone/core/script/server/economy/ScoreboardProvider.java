package com.november.mcphone.core.script.server.economy;

import com.november.mcphone.api.economy.Balances;
import com.november.mcphone.api.economy.Currency;
import com.november.mcphone.api.economy.EscrowId;
import com.november.mcphone.api.economy.HoldResult;
import com.november.mcphone.api.economy.ICurrencyProvider;
import com.november.mcphone.api.economy.TxnReason;
import com.november.mcphone.api.economy.TxnResult;
import net.minecraft.server.MinecraftServer;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 把账记在原版计分板上（施工方案 §22.7 的 scoreboard 一行、§18.6）。
 *
 * <h2>这一档的卖点</h2>
 *
 * 服主不装任何经济模组就有一本账，而且<b>用原版 {@code /scoreboard} 就能看、能改</b>，
 * 也能和别的插件共用同一本。积分、签到天数、活动进度这些服主本来就想用计分板管的东西，
 * 因此不会出现「App 里 1200 分、计分板上 800 分」这种两个真相（§18.6）。
 *
 * <h2>能力边界（如实标注，不假装有 builtin 的全部语义）</h2>
 *
 * <ul>
 *   <li><b>分值是 32 位整数</b>。SDK 的金额是 {@code long}，所以 {@link #maxBalance()}
 *       被压到不超过 {@link Integer#MAX_VALUE}；会越界的一律 {@link TxnResult#LIMIT}，
 *       <b>不靠回绕假装成功</b>（§22.9）。注意这是<b>最小单位</b>的上限：
 *       {@code decimals=2} 时它只相当于两千一百万出头的「元」。</li>
 *   <li><b>服主与别的插件随时能改那个数</b>。这是特性不是漏洞 —— 这一档就是给想互通的服主用的。
 *       App 下次读到的就是新值。</li>
 *   <li><b>按玩家名记账</b>，不是 UUID：否则服主得敲一串 UUID，这一档就白做了。
 *       代价是<b>改名会丢账</b>，以及从没上过线的玩家查不到名字 → {@link TxnResult#UNAVAILABLE}。</li>
 *   <li><b>原子性靠宿主串行化</b>：{@code Scoreboard} 不是线程安全的，也没有事务。
 *       本实现要求调用发生在服务端主线程上，不在就返回 {@link TxnResult#UNAVAILABLE} ——
 *       <b>绝不「能调用但不守恒」</b>（§20.4）。</li>
 *   <li><b>托管支持</b>：托管记录在宿主的 {@code escrow.dat}（§22.4），与 provider 无关，
 *       provider 只要能原子地扣。上面那条串行化成立时它就成立。</li>
 * </ul>
 *
 * <h2>货币的 objective 必须落在脚本写不到的命名空间</h2>
 *
 * §18.6 规定脚本只能读写自己命名空间前缀的 objective（{@code myapp_*}）。货币的 objective
 * 因此叫 {@code mcphone_eco_<id 的 path>} —— 落进 {@code myapp_*} 的话，App 直接写那个数就把
 * §22.9 的五条不变量整条绕过去了，负余额、上限、{@code amount<=0} 全都挡不住。
 *
 * <p><b>眼下仓库里还没有脚本侧的计分板 API</b>，所以这条隔离目前是一条命名不变量：
 * {@link #scriptWritable} 写在这里，是给将来那个 API 调的判据，不是说现在有人在调。
 */
public final class ScoreboardProvider implements ICurrencyProvider {

    /** 货币 objective 的前缀。 */
    public static final String OBJECTIVE_PREFIX = "mcphone_eco_";

    /**
     * 宿主保留的 objective 前缀，<b>脚本一律写不到</b>。
     *
     * <p>光有「只许写 {@code <命名空间>_} 开头的」不够：一个把自己的命名空间取名叫
     * {@code mcphone} 的 App，{@code "mcphone_eco_coin".startsWith("mcphone_")} 为真 ——
     * 它就能直接改余额，§22.9 的五条不变量全部绕过。所以前缀判定之前先挡这一道。
     */
    public static final String RESERVED_PREFIX = "mcphone_";

    /** 计分板分值的上限 —— 它是 int。 */
    public static final long SCORE_MAX = Integer.MAX_VALUE;

    /** 计分板分值的下限。允许负余额时也不许越过它。 */
    public static final long SCORE_MIN = Integer.MIN_VALUE;

    private final Currency currency;
    private final Supplier<MinecraftServer> server;
    private final EscrowLedger escrow;
    private final TxnLog log;
    private final LongSupplier clock;
    private final boolean allowNegative;
    private final long maxBalance;
    private final String objective;

    /** 这一次调用是哪个 App 发起的。<b>由宿主盖章，不采信调用方</b>（§22.10）。 */
    private final ThreadLocal<String> callingApp = ThreadLocal.withInitial(() -> "-");

    private final Object lock = new Object();

    public ScoreboardProvider(Currency currency, Supplier<MinecraftServer> server,
                              EscrowLedger escrow, TxnLog log, LongSupplier clock,
                              boolean allowNegative, long maxBalance) {
        this.currency = currency;
        this.server = server;
        this.escrow = escrow;
        this.log = log;
        this.clock = clock;
        this.allowNegative = allowNegative;
        this.maxBalance = clampMax(maxBalance);
        this.objective = objectiveFor(currency);
    }

    // ---------------------------------------------------------------- 纯函数（能不起服务器就测）

    /**
     * 这种货币记在哪个 objective 上。
     *
     * <p>字符限 {@code [_a-z0-9]}：{@code /scoreboard} 的参数是不带引号的 word，
     * 超出这个集合服主就得加引号，而这一档的全部意义就是他能直接敲。
     *
     * <h2>为什么带 namespace，还要再缀四位摘要</h2>
     *
     * 只取 {@code path} 的话 {@code server:coin} 与 {@code shop:coin} 落到同一个 objective ——
     * <b>两种货币共用一个数</b>：在便宜那种上 mint、在贵的那种上花掉，两本账各自的守恒同时被打破。
     *
     * <p>把 namespace 也拼进来还不够：字符集只有 {@code [_a-z0-9]}，
     * {@code a:b_c} 与 {@code a_b:c} 归一之后仍然都是 {@code a_b_c}。所以末尾缀四位
     * 完整 id 的 SHA-256 —— 名字还是能敲的 word，而两种不同的 id 不会落到同一本账上。
     */
    public static String objectiveFor(Currency currency) {
        String id = currency.id().toString();
        StringBuilder sb = new StringBuilder(OBJECTIVE_PREFIX);
        for (char c : id.toLowerCase(Locale.ROOT).toCharArray()) {
            sb.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') ? c : '_');
        }
        return sb.append('_').append(shortHash(id)).toString();
    }

    /** 完整 id 的 SHA-256 前两字节，写成四位十六进制。 */
    private static String shortHash(String id) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return String.format("%02x%02x", h[0], h[1]);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 脚本能不能写这个 objective（§18.6：只许写自己命名空间前缀的）。
     *
     * <p><b>货币的 objective 必须让它返回 false</b>，否则 §22.9 的不变量全部可绕。
     */
    public static boolean scriptWritable(String objective, String scriptNamespace) {
        if (objective == null || scriptNamespace == null || scriptNamespace.isEmpty()) return false;
        if (objective.startsWith(RESERVED_PREFIX)) return false;   // 宿主保留，谁都写不到
        return objective.startsWith(scriptNamespace + "_");
    }

    /** 配置里写多大都压到 int 能装下的范围。 */
    public static long clampMax(long configured) {
        if (configured <= 0) return SCORE_MAX;
        return Math.min(configured, SCORE_MAX);
    }

    /**
     * 入账判定。先走 {@link Balances} 那份共用判据，再加一条 int 范围 ——
     * <b>越界返回 LIMIT，不许靠 int 回绕看起来成功</b>。
     */
    public static TxnResult checkCreditInt(long current, long amount, long maxBalance) {
        TxnResult r = Balances.checkCredit(current, amount, maxBalance);
        if (r != TxnResult.OK) return r;
        long after;
        try {
            after = Math.addExact(current, amount);
        } catch (ArithmeticException e) {
            return TxnResult.LIMIT;
        }
        return after > SCORE_MAX ? TxnResult.LIMIT : TxnResult.OK;
    }

    /** 扣款判定。同上，越过 int 下限也是 LIMIT。 */
    public static TxnResult checkDebitInt(long current, long amount, boolean allowNegative) {
        TxnResult r = Balances.checkDebit(current, amount, allowNegative);
        if (r != TxnResult.OK) return r;
        long after;
        try {
            after = Math.subtractExact(current, amount);
        } catch (ArithmeticException e) {
            return TxnResult.LIMIT;
        }
        return after < SCORE_MIN ? TxnResult.LIMIT : TxnResult.OK;
    }

    // ---------------------------------------------------------------- 接口

    public String objective() {
        return objective;
    }

    /** 宿主在进入脚本调用前盖章。 */
    public void enterApp(String appId) {
        callingApp.set(appId == null ? "-" : appId);
    }

    public void leaveApp() {
        callingApp.remove();
    }

    private String id() {
        return currency.id().toString();
    }

    @Override
    public Currency currency() {
        return currency;
    }

    /**
     * 服务端起来了、在主线程上、objective 建得出来 —— 三条都满足才可用。
     *
     * <p>差哪一条 {@link #unavailableReasonKey()} 说得出来，<b>不静默降级</b>。
     */
    @Override
    public boolean isAvailable() {
        MinecraftServer s = server == null ? null : server.get();
        if (s == null || !onServerThread(s)) return false;
        return Scores.ensureObjective(s, objective, currency.symbol().isEmpty()
                ? currency.id().getPath() : currency.symbol());
    }

    @Override
    public String unavailableReasonKey() {
        MinecraftServer s = server == null ? null : server.get();
        if (s == null) return "mcphone.economy.scoreboard.no_server";
        if (!onServerThread(s)) return "mcphone.economy.scoreboard.off_thread";
        if (!Scores.hasObjective(s, objective)) return "mcphone.economy.scoreboard.no_objective";
        return "";
    }

    private static boolean onServerThread(MinecraftServer s) {
        // 【要的是原子性，不是线程归属】—— 但 Scoreboard 既不是线程安全的、也没有事务，
        // 于是"在权威结构上原子地读改写"在这一档就只能落成"在主线程上、且同一把锁内"
        return Thread.currentThread() == s.getRunningThread();
    }

    /**
     * 余额。
     *
     * <p><b>用不了的时候返回 0，与「真的有 0 块」分不出来</b> —— 接口这个方法只有一个 long，
     * 没有报错的通道（{@link ICurrencyProvider#balance}）。调用方要先问 {@link #isAvailable()}；
     * 那才是这一档说得出「差哪一条」的地方。
     */
    @Override
    public long balance(UUID player) {
        MinecraftServer s = ready();
        if (s == null || player == null) return 0;
        String holder = Scores.nameOf(s, player);
        if (holder == null) return 0;
        return Scores.get(s, objective, holder);
    }

    @Override
    public boolean allowNegative() {
        return allowNegative;
    }

    @Override
    public long maxBalance() {
        return maxBalance;
    }

    /** 可用就返回服务器，否则 null。调用方一律把 null 变成 UNAVAILABLE。 */
    private MinecraftServer ready() {
        MinecraftServer s = server == null ? null : server.get();
        if (s == null || !onServerThread(s)) return null;
        return Scores.ensureObjective(s, objective, currency.symbol().isEmpty()
                ? currency.id().getPath() : currency.symbol()) ? s : null;
    }

    @Override
    public TxnResult transfer(UUID from, UUID to, long amount, TxnReason reason) {
        // 【金额与两端的判定排在可用性判定之前】：amount <= 0 与"自己转自己"一律 INVALID
        // （§22.9、E25），不许因为"这会儿正好用不了"就变成 UNAVAILABLE ——
        // 那让调用方以为重试一下就能过。两条判据都在 Balances 那一份里，不各写一遍
        if (Balances.checkAmount(amount) != TxnResult.OK
                || Balances.checkParties(from, to) != TxnResult.OK) {
            return record(TxnLog.Kind.TRANSFER, from, to, amount, reason, TxnResult.INVALID);
        }
        MinecraftServer s = ready();
        if (s == null) return record(TxnLog.Kind.TRANSFER, from, to, amount, reason, TxnResult.UNAVAILABLE);
        synchronized (lock) {
            String a = Scores.nameOf(s, from);
            String b = Scores.nameOf(s, to);
            if (a == null || b == null) {
                return record(TxnLog.Kind.TRANSFER, from, to, amount, reason, TxnResult.UNAVAILABLE);
            }
            if (a.equals(b)) {
                // 两个不同的 UUID 落到同一个计分板持有者（改过名、档案缓存里旧 UUID 还指着这个名字）。
                // 这一档按名记账、判定按 UUID，这里就是那条缝 —— 放过去就是上面那条凭空造币
                return record(TxnLog.Kind.TRANSFER, from, to, amount, reason, TxnResult.INVALID);
            }
            long av = Scores.get(s, objective, a);
            long bv = Scores.get(s, objective, b);
            TxnResult r = checkDebitInt(av, amount, allowNegative);
            if (r != TxnResult.OK) return record(TxnLog.Kind.TRANSFER, from, to, amount, reason, r);
            r = checkCreditInt(bv, amount, maxBalance);
            if (r != TxnResult.OK) return record(TxnLog.Kind.TRANSFER, from, to, amount, reason, r);
            // 两端在同一把锁、同一个 tick 里改完：中间态不许存在（§22.3 ④）。
            // 第一笔写不进去就整笔作废；第二笔写不进去要把第一笔退回去 ——
            // 退回去用的是读到的原值 av，不是"再减一次"，因为这中间没有别人能插进来
            if (!write(s, a, av - amount)) {
                return record(TxnLog.Kind.TRANSFER, from, to, amount, reason, TxnResult.UNAVAILABLE);
            }
            if (!write(s, b, bv + amount)) {
                write(s, a, av);
                return record(TxnLog.Kind.TRANSFER, from, to, amount, reason, TxnResult.UNAVAILABLE);
            }
        }
        return record(TxnLog.Kind.TRANSFER, from, to, amount, reason, TxnResult.OK);
    }

    @Override
    public TxnResult mint(UUID to, long amount, TxnReason reason) {
        if (to == null || Balances.checkAmount(amount) != TxnResult.OK) {
            return record(TxnLog.Kind.MINT, null, to, amount, reason, TxnResult.INVALID);
        }
        MinecraftServer s = ready();
        if (s == null) return record(TxnLog.Kind.MINT, null, to, amount, reason, TxnResult.UNAVAILABLE);
        synchronized (lock) {
            String h = Scores.nameOf(s, to);
            if (h == null) return record(TxnLog.Kind.MINT, null, to, amount, reason, TxnResult.UNAVAILABLE);
            long v = Scores.get(s, objective, h);
            TxnResult r = checkCreditInt(v, amount, maxBalance);
            if (r != TxnResult.OK) return record(TxnLog.Kind.MINT, null, to, amount, reason, r);
            if (!write(s, h, v + amount)) {
                return record(TxnLog.Kind.MINT, null, to, amount, reason, TxnResult.UNAVAILABLE);
            }
        }
        return record(TxnLog.Kind.MINT, null, to, amount, reason, TxnResult.OK);
    }

    @Override
    public TxnResult burn(UUID from, long amount, TxnReason reason) {
        if (from == null || Balances.checkAmount(amount) != TxnResult.OK) {
            return record(TxnLog.Kind.BURN, from, null, amount, reason, TxnResult.INVALID);
        }
        MinecraftServer s = ready();
        if (s == null) return record(TxnLog.Kind.BURN, from, null, amount, reason, TxnResult.UNAVAILABLE);
        synchronized (lock) {
            String h = Scores.nameOf(s, from);
            if (h == null) return record(TxnLog.Kind.BURN, from, null, amount, reason, TxnResult.UNAVAILABLE);
            long v = Scores.get(s, objective, h);
            TxnResult r = checkDebitInt(v, amount, allowNegative);
            if (r != TxnResult.OK) return record(TxnLog.Kind.BURN, from, null, amount, reason, r);
            if (!write(s, h, v - amount)) {
                return record(TxnLog.Kind.BURN, from, null, amount, reason, TxnResult.UNAVAILABLE);
            }
        }
        return record(TxnLog.Kind.BURN, from, null, amount, reason, TxnResult.OK);
    }

    @Override
    public HoldResult hold(UUID from, UUID beneficiary, long amount, TxnReason reason) {
        if (from == null || beneficiary == null || Balances.checkAmount(amount) != TxnResult.OK) {
            record(TxnLog.Kind.HOLD, from, beneficiary, amount, reason, TxnResult.INVALID);
            return HoldResult.fail(TxnResult.INVALID);
        }
        MinecraftServer s = ready();
        if (s == null) {
            record(TxnLog.Kind.HOLD, from, beneficiary, amount, reason, TxnResult.UNAVAILABLE);
            return HoldResult.fail(TxnResult.UNAVAILABLE);
        }
        EscrowId eid;
        synchronized (lock) {
            String h = Scores.nameOf(s, from);
            if (h == null) {
                record(TxnLog.Kind.HOLD, from, beneficiary, amount, reason, TxnResult.UNAVAILABLE);
                return HoldResult.fail(TxnResult.UNAVAILABLE);
            }
            long v = Scores.get(s, objective, h);
            TxnResult r = checkDebitInt(v, amount, allowNegative);
            if (r != TxnResult.OK) {
                record(TxnLog.Kind.HOLD, from, beneficiary, amount, reason, r);
                return HoldResult.fail(r);
            }
            // 当场扣款，受益人此刻定死、之后不可更改（§22.3 ⑤）。
            // 扣款先、建托管后：扣不动就没有这笔托管，不会留下一张没有钱的托管单
            if (!write(s, h, v - amount)) {
                record(TxnLog.Kind.HOLD, from, beneficiary, amount, reason, TxnResult.UNAVAILABLE);
                return HoldResult.fail(TxnResult.UNAVAILABLE);
            }
            eid = escrow.create(from, beneficiary, id(), amount);
        }
        record(TxnLog.Kind.HOLD, from, beneficiary, amount, reason, TxnResult.OK);
        return HoldResult.ok(eid);
    }

    @Override
    public TxnResult release(EscrowId id, TxnReason reason) {
        return settle(id, reason, true);
    }

    @Override
    public TxnResult refund(EscrowId id, TxnReason reason) {
        return settle(id, reason, false);
    }

    /**
     * 放款给受益人、或者退给原主。<b>方向是创建时定死的，这里只能二选一。</b>
     *
     * <h2>为什么先写分值、后标结算</h2>
     *
     * 反过来的话，{@code Scores.set} 一旦抛异常（1.20.3+ 在只读 objective 上就会抛），
     * 托管已经标成结算、钱却没到账，而且异常穿过 synchronized 逃出去，<b>连流水都不进</b> ——
     * 那笔钱查无对证。先写后标的话，写失败就什么都没动，这一笔可以重来，
     * 也就是 §22.3 ④ 要的「要么全成要么全不成」。
     *
     * <p><b>整段在同一把锁里</b>：{@code settled()} 的判断与 {@code settle()} 的执行之间
     * 不许有别人插进来，否则同一笔托管能放两次款。
     */
    private TxnResult settle(EscrowId eid, TxnReason reason, boolean toBeneficiary) {
        TxnLog.Kind kind = toBeneficiary ? TxnLog.Kind.RELEASE : TxnLog.Kind.REFUND;
        synchronized (lock) {
            EscrowLedger.Entry e = escrow.get(eid);
            if (e == null) return record(kind, null, null, 0, reason, TxnResult.UNKNOWN_ESCROW);
            TxnResult wrongCurrency = Balances.checkEscrowCurrency(e.currencyId(), id());
            if (wrongCurrency != TxnResult.OK) {
                return record(kind, e.owner(), e.beneficiary(), e.amount(), reason, wrongCurrency);
            }
            if (e.settled()) {
                return record(kind, e.owner(), e.beneficiary(), e.amount(), reason, TxnResult.ALREADY_SETTLED);
            }
            MinecraftServer s = ready();
            UUID target = toBeneficiary ? e.beneficiary() : e.owner();
            if (s == null) {
                return record(kind, e.owner(), target, e.amount(), reason, TxnResult.UNAVAILABLE);
            }
            String h = Scores.nameOf(s, target);
            if (h == null) return record(kind, e.owner(), target, e.amount(), reason, TxnResult.UNAVAILABLE);
            long v = Scores.get(s, objective, h);
            TxnResult r = checkCreditInt(v, e.amount(), maxBalance);
            if (r != TxnResult.OK) return record(kind, e.owner(), target, e.amount(), reason, r);
            if (!write(s, h, v + e.amount())) {
                return record(kind, e.owner(), target, e.amount(), reason, TxnResult.UNAVAILABLE);
            }
            // 走不到 false：settled() 的判断与这一句在同一把锁里
            escrow.settle(eid);
            return record(kind, e.owner(), target, e.amount(), reason, TxnResult.OK);
        }
    }

    /**
     * 写一格分值。<b>写不进去返回 false，不让异常逃出去。</b>
     *
     * <p>{@link #ready()} 已经把已知的写不进去的原因（objective 不在、只读）挡在前面了，
     * 这一道是兜底：计分板是服主与别的插件也在改的东西，它在两次调用之间被换成别的样子
     * 不是不可能的事。异常逃出去的话，转账就会停在只扣了一边的中间态上。
     */
    private boolean write(MinecraftServer s, String holder, long value) {
        try {
            Scores.set(s, objective, holder, (int) value);
            return true;
        } catch (RuntimeException ex) {
            com.november.mcphone.MCphone.LOGGER.warn(
                    "[MCphone] 计分板写不进去 {}/{}：{}", objective, holder, ex.toString());
            return false;
        }
    }

    /** 每一笔都进流水，<b>失败的也进</b>（§22.3 ⑥）。 */
    private TxnResult record(TxnLog.Kind kind, UUID from, UUID to, long amount,
                             TxnReason reason, TxnResult result) {
        if (log != null) {
            log.append(Instant.ofEpochMilli(clock.getAsLong()), id(), kind, from, to,
                    amount, callingApp.get(), reason, result);
        }
        return result;
    }
}
