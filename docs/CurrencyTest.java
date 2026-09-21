package com.november.mcphone.api.economy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.november.mcphone.core.script.server.economy.Amounts;
import com.november.mcphone.core.script.server.economy.BalanceStore;
import com.november.mcphone.core.script.server.economy.BuiltinProvider;
import com.november.mcphone.core.script.server.economy.AdapterProvider;
import com.november.mcphone.core.script.server.economy.CurrencyRegistry;
import com.november.mcphone.core.script.server.economy.CurrencySpec;
import com.november.mcphone.core.script.server.economy.ScoreboardProvider;
import com.november.mcphone.core.script.server.economy.EconomyAudit;
import com.november.mcphone.core.script.server.economy.LegacyWalletProvider;
import com.november.mcphone.core.script.server.economy.EscrowLedger;
import com.november.mcphone.core.script.server.economy.TxnLog;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 货币 SDK 的契约（施工方案 §22.9 的五条不变量、§22.11）。
 *
 * <p><b>这里测的是规则，不是发布出去的那个实现</b> —— {@code ICurrencyProvider} 的实现是 S15 的交付物。
 * 本步交付的是契约，而契约里真正能出错的部分（金额判定、溢出、守恒、格式化）都收在
 * {@link Balances} 里，各实现照着调就不会各漏各的。下面那个 {@code Ledger} 是一份参照实现，
 * 证明这套规则自洽、且守恒。
 *
 * <p><b>这里测不了的</b>：托管落盘、流水文件、跨重启 —— 都要服务端（S15）。
 *
 * <p>跑法：{@code ./gradlew assertTests}（在 {@code platforms/<目标名>/} 下）。
 */
public class CurrencyTest {

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

    static String rejects(Runnable body) {
        try {
            body.run();
            return null;
        } catch (RuntimeException e) {
            return e.getMessage();
        }
    }

    // ---------------------------------------------------------------- §22.9 不变量

    static void amountMustBePositive() {
        eq(Balances.checkAmount(1), TxnResult.OK, "正数");
        eq(Balances.checkAmount(0), TxnResult.INVALID, "0 拒 —— 只是往流水里灌垃圾");
        eq(Balances.checkAmount(-1), TxnResult.INVALID, "负数拒");
        eq(Balances.checkAmount(Long.MIN_VALUE), TxnResult.INVALID, "最小值也拒");

        // 这是最经典的那个洞：pay(to, -1000) 实现成"from 减 amount、to 加 amount"就是反向偷钱
        eq(Balances.checkDebit(0, -1000, false), TxnResult.INVALID, "负数扣款先被金额判据拦住");
        eq(Balances.checkCredit(0, -1000, 1_000_000), TxnResult.INVALID, "负数入账同样");
    }

    static void ceilingDoesNotWrap() {
        eq(Balances.checkCredit(900, 100, 1000), TxnResult.OK, "刚好到顶");
        eq(Balances.checkCredit(900, 101, 1000), TxnResult.LIMIT, "超一点就 LIMIT");
        eq(Balances.checkCredit(Long.MAX_VALUE, 1, Long.MAX_VALUE), TxnResult.LIMIT,
                "加法溢出返回 LIMIT，不许回绕 —— 回绕会让余额爆表的账户收一笔钱之后变成负数");
        eq(Balances.checkCredit(Long.MAX_VALUE - 1, 2, Long.MAX_VALUE), TxnResult.LIMIT, "差一点溢出");
    }

    static void debits() {
        eq(Balances.checkDebit(1000, 1000, false), TxnResult.OK, "刚好够");
        eq(Balances.checkDebit(999, 1000, false), TxnResult.INSUFFICIENT, "差一点");
        eq(Balances.checkDebit(0, 1000, true), TxnResult.OK, "允许负余额时透支放行");
        eq(Balances.checkDebit(Long.MIN_VALUE + 1, 2, true), TxnResult.LIMIT, "允许负数也不许绕到正数去");
    }

    // ---------------------------------------------------------------- 显示与解析

    static void formatParse() {
        eq(Balances.format(1234, 2), "12.34", "§22.3 ② 举的那个例子");
        eq(Balances.format(0, 2), "0.00", "零");
        eq(Balances.format(5, 2), "0.05", "不足一位要补零");
        eq(Balances.format(-5, 2), "-0.05", "负数的补零在绝对值上做，不然会拼出 -0.-5");
        eq(Balances.format(1234, 0), "1234", "没有小数位就是整数");
        eq(Balances.format(Long.MAX_VALUE, 2), "92233720368547758.07", "long 的顶");

        eq(Balances.parse("12.34", 2), 1234L, "往返");
        eq(Balances.parse("12", 2), 1200L, "省略小数位补零");
        eq(Balances.parse("12.3", 2), 1230L, "少写一位补零");
        eq(Balances.parse("-12.34", 2), -1234L, "负数");
        eq(Balances.parse(".5", 2), 50L, "省略整数位");
        check(rejects(() -> Balances.parse("12.345", 2)) != null,
                "小数位多于 decimals 要抛 —— 悄悄抹掉一位就是悄悄改了金额");
        check(rejects(() -> Balances.parse("abc", 2)) != null, "不是十进制拒");
        check(rejects(() -> Balances.parse(null, 2)) != null, "null 拒");
        check(rejects(() -> Balances.parse("", 2)) != null, "空串拒");

        for (long v : new long[]{0, 1, 99, 100, 12345, -1, -12345, Long.MAX_VALUE}) {
            eq(Balances.parse(Balances.format(v, 2), 2), v, "format→parse 往返 " + v);
        }
    }

    // ---------------------------------------------------------------- 值类型

    static void txnReason() {
        eq(new TxnReason("market:buy", "order-1").kind(), "market:buy", "kind");
        eq(new TxnReason(null, null).ref(), "", "null 归一成空串");
        check(rejects(() -> new TxnReason("a|b", "x")) != null,
                "竖线拒 —— 流水行按竖线分隔，放进去就能伪造一列");
        check(rejects(() -> new TxnReason("a\nb", "x")) != null,
                "换行拒 —— 一个换行就能在流水里伪造出一整行不存在的交易");
        String withNul = "x" + (char) 0 + "y";
        check(rejects(() -> new TxnReason("a", withNul)) != null, "控制字符拒");
        check(rejects(() -> new TxnReason("x".repeat(TxnReason.MAX_KIND + 1), "y")) != null, "kind 超长拒");
        check(rejects(() -> new TxnReason("y", "x".repeat(TxnReason.MAX_REF + 1))) != null, "ref 超长拒");

        // 没有 appId 这一格：调用者自报身份 = 审计作废
        eq(TxnReason.class.getRecordComponents().length, 2, "只有 kind 与 ref，appId 由宿主盖章");
    }

    static void holdResult() {
        EscrowId id = new EscrowId(UUID.randomUUID());
        eq(HoldResult.ok(id).result(), TxnResult.OK, "成功");
        eq(HoldResult.ok(id).id(), id, "成功带号");
        eq(HoldResult.fail(TxnResult.INSUFFICIENT).id(), null, "失败不带号");
        check(rejects(() -> new HoldResult(TxnResult.OK, null)) != null, "OK 必须带号");
        check(rejects(() -> new HoldResult(TxnResult.FAILED, id)) != null, "失败不许带号");
        check(rejects(() -> new EscrowId(null)) != null, "托管号不能为 null");
    }

    /** §23.4：不许复用错误码。这几个必须各自存在，别合并。 */
    static void distinctCodes() {
        for (String name : new String[]{"OK", "INSUFFICIENT", "LIMIT", "UNAVAILABLE", "INVALID",
                "NOT_AUTHORIZED", "UNKNOWN_ESCROW", "ALREADY_SETTLED", "FAILED"}) {
            boolean found = false;
            for (TxnResult r : TxnResult.values()) if (r.name().equals(name)) found = true;
            check(found, "TxnResult 要有 " + name);
        }
        check(TxnResult.INVALID != TxnResult.ALREADY_SETTLED,
                "「已经结过了」不许与「金额非法」共用一个码：调用方分不出该重试还是该报错");
        check(TxnResult.FAILED != TxnResult.UNKNOWN_ESCROW,
                "「不是本 provider 的托管号」不许落进 FAILED：多货币服上这条路一定会走到");
    }

    // ---------------------------------------------------------------- 参照实现：守恒

    /** 一份最小的账本，只用来证明这套规则自洽。真正的实现是 S15 的事。 */
    static final class Ledger {
        final Map<UUID, Long> bal = new HashMap<>();
        final Map<UUID, long[]> escrow = new HashMap<>();   // id → {金额, 已结算标志}
        final Map<UUID, UUID> owner = new HashMap<>();
        final Map<UUID, UUID> beneficiary = new HashMap<>();
        final long max;

        Ledger(long max) {
            this.max = max;
        }

        long get(UUID p) {
            return bal.getOrDefault(p, 0L);
        }

        TxnResult mint(UUID to, long amt) {
            TxnResult r = Balances.checkCredit(get(to), amt, max);
            if (r != TxnResult.OK) return r;
            bal.put(to, get(to) + amt);
            return TxnResult.OK;
        }

        TxnResult transfer(UUID from, UUID to, long amt) {
            TxnResult r = Balances.checkDebit(get(from), amt, false);
            if (r != TxnResult.OK) return r;
            r = Balances.checkCredit(get(to), amt, max);
            if (r != TxnResult.OK) return r;
            // 两端在同一步里改完：中间态不许存在（§22.3 ④）
            bal.put(from, get(from) - amt);
            bal.put(to, get(to) + amt);
            return TxnResult.OK;
        }

        HoldResult hold(UUID from, UUID to, long amt) {
            TxnResult r = Balances.checkDebit(get(from), amt, false);
            if (r != TxnResult.OK) return HoldResult.fail(r);
            bal.put(from, get(from) - amt);
            UUID id = UUID.randomUUID();
            escrow.put(id, new long[]{amt, 0});
            owner.put(id, from);
            beneficiary.put(id, to);
            return HoldResult.ok(new EscrowId(id));
        }

        TxnResult settle(EscrowId id, boolean toBeneficiary) {
            long[] e = escrow.get(id.value());
            if (e == null) return TxnResult.UNKNOWN_ESCROW;
            if (e[1] != 0) return TxnResult.ALREADY_SETTLED;
            UUID target = toBeneficiary ? beneficiary.get(id.value()) : owner.get(id.value());
            TxnResult r = Balances.checkCredit(get(target), e[0], max);
            if (r != TxnResult.OK) return r;
            bal.put(target, get(target) + e[0]);
            e[1] = 1;
            return TxnResult.OK;
        }

        /** 所有余额 + 托管中的钱。除 mint / burn 外这个数不许变（§22.9）。 */
        long total() {
            long t = 0;
            for (long v : bal.values()) t += v;
            for (Map.Entry<UUID, long[]> e : escrow.entrySet()) if (e.getValue()[1] == 0) t += e.getValue()[0];
            return t;
        }
    }

    static void conservation() {
        Ledger l = new Ledger(1_000_000);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();

        eq(l.mint(a, 10_000), TxnResult.OK, "铸 100.00 给 A");
        eq(l.total(), 10_000L, "总量");

        eq(l.transfer(a, b, 2_500), TxnResult.OK, "转账成功");
        eq(l.get(a), 7_500L, "付款方扣了");
        eq(l.get(b), 2_500L, "收款方加了");
        eq(l.total(), 10_000L, "转账不改变总量");

        eq(l.transfer(a, b, 999_999), TxnResult.INSUFFICIENT, "不够就不动");
        eq(l.total(), 10_000L, "失败的转账一分钱都没动");
        eq(l.get(a), 7_500L, "失败后付款方原样");

        eq(l.transfer(a, b, -1000), TxnResult.INVALID, "负数转账拒");
        eq(l.get(b), 2_500L, "被偷方原样 —— 这条不过就是最经典的那个洞");

        // 托管：钱离开 A 但还在系统里
        HoldResult h = l.hold(a, b, 5_000);
        eq(h.result(), TxnResult.OK, "托管成功");
        eq(l.get(a), 2_500L, "托管当场扣款");
        eq(l.total(), 10_000L, "托管中的钱还算在总量里 —— 不然对账会少一笔");

        eq(l.settle(h.id(), true), TxnResult.OK, "放款");
        eq(l.get(b), 7_500L, "受益人收到");
        eq(l.total(), 10_000L, "放款不改变总量");
        eq(l.settle(h.id(), true), TxnResult.ALREADY_SETTLED, "重复放款认得出来，不是 FAILED");
        eq(l.settle(new EscrowId(UUID.randomUUID()), true), TxnResult.UNKNOWN_ESCROW, "不认识的托管号");
        eq(l.total(), 10_000L, "两次失败的结算都没动钱");

        // 退款走的是原主那条路
        HoldResult h2 = l.hold(b, a, 1_000);
        eq(l.settle(h2.id(), false), TxnResult.OK, "退款");
        eq(l.get(b), 7_500L, "退回原主");
        eq(l.total(), 10_000L, "退款不改变总量");
    }

    // ================================================================ 真 provider（§22.7 builtin）

    /** 内存里的余额表。真正的是世界级存档 EconomyData，见 EconomyDataTest。 */
    static final class MemBalances implements BalanceStore {
        final java.util.Map<String, Long> m = new ConcurrentHashMap<>();

        static String k(UUID p, String c) {
            return p + "/" + c;
        }

        public long get(UUID p, String c) {
            return m.getOrDefault(k(p, c), 0L);
        }

        public void set(UUID p, String c, long v) {
            m.put(k(p, c), v);
        }

        public java.util.Map<UUID, Long> all(String c) {
            java.util.Map<UUID, Long> out = new java.util.HashMap<>();
            for (var e : m.entrySet()) {
                String[] parts = e.getKey().split("/", 2);
                if (parts.length == 2 && parts[1].equals(c)) out.put(UUID.fromString(parts[0]), e.getValue());
            }
            return out;
        }
    }

    static Currency coin() {
        return new Currency(ResourceLocation.tryParse("myserver:coin"),
                Component.literal("金币"), "G", 2, null);
    }

    static Path tmpDir(String name) throws Exception {
        Path p = Files.createTempDirectory("mcphone-econ-" + name);
        p.toFile().deleteOnExit();
        return p;
    }

    static BuiltinProvider provider(MemBalances bal, EscrowLedger esc, TxnLog log, AtomicLong clock, long max) {
        return new BuiltinProvider(coin(), bal, esc, log, clock::get, false, max);
    }

    /** §22.9：amount ≤ 0 一律 INVALID，而且【被拒后两侧余额都不变】。 */
    static void nonPositiveRejected() throws Exception {
        MemBalances bal = new MemBalances();
        AtomicLong t = new AtomicLong(0);
        EscrowLedger esc = new EscrowLedger(t::get);
        TxnLog log = new TxnLog(tmpDir("np"), java.time.ZoneId.of("UTC"));
        BuiltinProvider p = provider(bal, esc, log, t, 1_000_000);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        p.mint(a, 10_000, new TxnReason("test", "1"));
        p.mint(b, 5_000, new TxnReason("test", "2"));

        for (long bad : new long[]{0, -1, -1000, Long.MIN_VALUE}) {
            eq(p.transfer(a, b, bad, new TxnReason("test", "x")), TxnResult.INVALID, "转账 " + bad + " 拒");
            eq(p.balance(a), 10_000L, "被拒后付款方原样（" + bad + "）");
            eq(p.balance(b), 5_000L, "被拒后收款方原样 —— 这条不过就是反向偷钱（" + bad + "）");
        }
    }

    /** §22.9：超上限与 Long.MAX_VALUE 都返回 LIMIT，不回绕。 */
    static void ceilingAndOverflow() throws Exception {
        MemBalances bal = new MemBalances();
        AtomicLong t = new AtomicLong(0);
        TxnLog log = new TxnLog(tmpDir("lim"), java.time.ZoneId.of("UTC"));
        BuiltinProvider p = provider(bal, new EscrowLedger(t::get), log, t, 1_000);
        UUID a = UUID.randomUUID();
        eq(p.mint(a, 1_000, new TxnReason("t", "1")), TxnResult.OK, "刚好到顶");
        eq(p.mint(a, 1, new TxnReason("t", "2")), TxnResult.LIMIT, "超一点就 LIMIT");
        eq(p.balance(a), 1_000L, "被拒之后余额不动");

        BuiltinProvider big = provider(bal, new EscrowLedger(t::get), log, t, Long.MAX_VALUE);
        UUID c = UUID.randomUUID();
        big.mint(c, Long.MAX_VALUE, new TxnReason("t", "3"));
        eq(big.mint(c, 1, new TxnReason("t", "4")), TxnResult.LIMIT, "Long.MAX_VALUE 再加一是 LIMIT，不回绕");
        check(big.balance(c) > 0, "没有绕成负数");
    }

    /** §22.12：1000 次并发 transfer 之后总额不变。 */
    static void concurrentConservation() throws Exception {
        MemBalances bal = new MemBalances();
        AtomicLong t = new AtomicLong(0);
        TxnLog log = new TxnLog(tmpDir("conc"), java.time.ZoneId.of("UTC"));
        BuiltinProvider p = provider(bal, new EscrowLedger(t::get), log, t, Long.MAX_VALUE);

        UUID[] players = new UUID[8];
        for (int i = 0; i < players.length; i++) {
            players[i] = UUID.randomUUID();
            p.mint(players[i], 100_000, new TxnReason("t", "seed" + i));
        }
        long before = 0;
        for (UUID u : players) before += p.balance(u);

        int threads = 8, perThread = 125;                   // 8 × 125 = 1000
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            final int seed = i;
            Thread th = new Thread(() -> {
                try {
                    go.await();
                    java.util.Random r = new java.util.Random(seed);
                    for (int k = 0; k < perThread; k++) {
                        UUID from = players[r.nextInt(players.length)];
                        UUID to = players[r.nextInt(players.length)];
                        if (from.equals(to)) continue;
                        p.transfer(from, to, 1 + r.nextInt(100), new TxnReason("t", "c" + k));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
            th.setDaemon(true);
            th.start();
        }
        go.countDown();
        check(done.await(30, java.util.concurrent.TimeUnit.SECONDS), "1000 次并发转账跑完了");

        long after = 0;
        for (UUID u : players) after += p.balance(u);
        eq(after, before, "1000 次并发转账之后总额不变（守恒）");
        for (UUID u : players) check(p.balance(u) >= 0, "没有谁变成负数");
    }

    /** §22.4：托管受益人此刻定死；重复释放/退款要认得出来。 */
    static void escrow() throws Exception {
        MemBalances bal = new MemBalances();
        AtomicLong t = new AtomicLong(0);
        EscrowLedger esc = new EscrowLedger(t::get);
        TxnLog log = new TxnLog(tmpDir("esc"), java.time.ZoneId.of("UTC"));
        BuiltinProvider p = provider(bal, esc, log, t, Long.MAX_VALUE);
        UUID buyer = UUID.randomUUID(), seller = UUID.randomUUID();
        p.mint(buyer, 10_000, new TxnReason("t", "seed"));

        HoldResult h = p.hold(buyer, seller, 5_000, new TxnReason("market", "order-1"));
        eq(h.result(), TxnResult.OK, "托管成功");
        eq(p.balance(buyer), 5_000L, "托管当场扣款");
        eq(p.balance(seller), 0L, "受益人还没拿到");
        eq(esc.held("myserver:coin"), 5_000L, "钱押在托管里 —— 对账要把它算进总量");

        eq(p.release(h.id(), new TxnReason("market", "deliver-1")), TxnResult.OK, "放款");
        eq(p.balance(seller), 5_000L, "受益人收到");
        eq(esc.held("myserver:coin"), 0L, "托管里没有了");

        eq(p.release(h.id(), new TxnReason("market", "deliver-1")), TxnResult.ALREADY_SETTLED,
                "重复放款认得出来，不是 FAILED");
        eq(p.refund(h.id(), new TxnReason("market", "cancel")), TxnResult.ALREADY_SETTLED,
                "放过款就不能再退");
        eq(p.balance(seller), 5_000L, "重复结算没有把钱变多");

        // 退款那条路
        HoldResult h2 = p.hold(buyer, seller, 1_000, new TxnReason("market", "order-2"));
        long sellerBefore = p.balance(seller);
        eq(p.balance(buyer), 4_000L, "第二笔托管当场又扣了 1000");
        eq(p.refund(h2.id(), new TxnReason("market", "cancel-2")), TxnResult.OK, "退款");
        eq(p.balance(buyer), 5_000L, "钱退回原主");
        eq(p.balance(seller), sellerBefore, "受益人一分没多 —— 退款的方向是创建时定死的");

        eq(p.release(new EscrowId(UUID.randomUUID()), new TxnReason("t", "x")),
                TxnResult.UNKNOWN_ESCROW, "不认识的托管号");
    }

    /** §22.4：超时（默认 7 天）的托管会被扫出来。 */
    static void escrowTimeout() {
        AtomicLong t = new AtomicLong(0);
        EscrowLedger esc = new EscrowLedger(t::get);
        EscrowId id = esc.create(UUID.randomUUID(), UUID.randomUUID(), "myserver:coin", 100);
        eq(esc.expired().size(), 0, "刚建的不算超时");
        t.set(EscrowLedger.DEFAULT_TIMEOUT_MS - 1);
        eq(esc.expired().size(), 0, "差一毫秒还不算");
        t.set(EscrowLedger.DEFAULT_TIMEOUT_MS);
        eq(esc.expired().size(), 1, "到 7 天就该退了");
        esc.settle(id);
        eq(esc.expired().size(), 0, "结算过的不再算超时");
        eq(EscrowLedger.DEFAULT_TIMEOUT_MS, 7L * 24 * 3600 * 1000, "§22.4 默认 7 天");
    }

    /** §22.10：对账平；人为改余额之后能报出不平。 */
    static void audit() throws Exception {
        MemBalances bal = new MemBalances();
        AtomicLong t = new AtomicLong(0);
        EscrowLedger esc = new EscrowLedger(t::get);
        TxnLog log = new TxnLog(tmpDir("audit"), java.time.ZoneId.of("UTC"));
        BuiltinProvider p = provider(bal, esc, log, t, Long.MAX_VALUE);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();

        p.mint(a, 10_000, new TxnReason("t", "1"));
        p.transfer(a, b, 3_000, new TxnReason("t", "2"));
        p.burn(b, 1_000, new TxnReason("t", "3"));
        p.hold(a, b, 2_000, new TxnReason("t", "4"));

        EconomyAudit.Result r = EconomyAudit.run("myserver:coin", bal, esc, log);
        eq(r.minted(), 10_000L, "铸造总额");
        eq(r.burned(), 1_000L, "销毁总额");
        eq(r.expected(), 9_000L, "应有 = 铸造 − 销毁");
        eq(r.actual(), 9_000L, "实际 = 余额 + 托管");
        check(r.balanced(), "对账平：" + r.describe());

        // 人为改存档：给 a 凭空加 500
        bal.set(a, "myserver:coin", bal.get(a, "myserver:coin") + 500);
        EconomyAudit.Result bad = EconomyAudit.run("myserver:coin", bal, esc, log);
        check(!bad.balanced(), "改过存档之后必须报不平");
        eq(bad.delta(), 500L, "差额正好是被塞进去的那 500");
        check(bad.describe().contains("⚠"), "不平那一行要显眼，别埋在日志里");
    }

    /** §22.7：emc_legacy 的能力缺失是明写的，不是悄悄降级。 */
    static void emcLegacy() {
        LegacyWalletProvider p = new LegacyWalletProvider(coin());
        eq(p.hold(UUID.randomUUID(), UUID.randomUUID(), 100, new TxnReason("t", "1")).result(),
                TxnResult.UNAVAILABLE, "没有托管 —— 所以市场类 App 不能用它");
        eq(p.transfer(UUID.randomUUID(), UUID.randomUUID(), 100, new TxnReason("t", "2")),
                TxnResult.UNAVAILABLE, "旧接口没有存入这一侧，转账做不到");
        eq(p.mint(UUID.randomUUID(), 100, new TxnReason("t", "3")), TxnResult.UNAVAILABLE, "不能铸造");
        check(!p.unavailableReasonKey().isEmpty(), "要给得出理由的本地化键");
        check(!LegacyWalletProvider.supportsEscrow(p), "市场类 App 的安装判定据此把它标为不可用");

        MemBalances bal = new MemBalances();
        AtomicLong t = new AtomicLong(0);
        check(LegacyWalletProvider.supportsEscrow(
                        new BuiltinProvider(coin(), bal, new EscrowLedger(t::get), null, t::get, false, 100)),
                "builtin 支持托管");
    }

    /** §22.8：没有默认货币时 default() 返回 null，不抛。 */
    static void registry() {
        CurrencyRegistry reg = new CurrencyRegistry();
        eq(reg.defaultCurrency(), null, "什么都没配时 default() 是 null，不是抛异常");
        eq(reg.list().size(), 0, "list() 是空表");

        MemBalances bal = new MemBalances();
        AtomicLong t = new AtomicLong(0);
        reg.register(new BuiltinProvider(coin(), bal, new EscrowLedger(t::get), null, t::get, false, 100), false);
        eq(reg.defaultCurrency(), null, "注册了但没标默认，还是 null");
        eq(reg.list().size(), 1, "list() 有一个");

        // 【一种货币只许一个实例】（E25）：静默覆盖的话先注册的那个还在别处被引用着，两个实例各拿各的锁写同一份账
        var first = reg.get("myserver:coin");
        check(!reg.register(new LegacyWalletProvider(coin()), true),
                "同一种货币的第二个提供者要拒掉");
        check(reg.get("myserver:coin") == first, "被拒之后注册表原封不动");
        eq(reg.defaultCurrency(), null, "被拒的那次也不许把自己标成默认");
        eq(reg.list().size(), 1, "list() 还是一个");
        check(reg.register(reg.get("myserver:coin"), false), "同一个实例再注册一次是幂等的，不拒");

        // 换一种货币才谈得上标默认
        reg.register(new LegacyWalletProvider(coin("gold", 2)), true);
        eq(reg.defaultCurrency(), "myserver:gold", "标了默认就有了");
        eq(reg.list().size(), 2, "两种货币");
        check(reg.get("myserver:coin") != null, "按 id 取得到");
        check(reg.get("nope:none") == null, "取不到的返回 null");
    }

    /** 勘误 E18：脚本侧金额是 BigInt，宿主桥在边界切 BigInt ↔ long。 */
    static void bigIntBridge() {
        BigInteger big = new BigInteger("9007199254740993");
        eq(Amounts.toLong(big, "test"), 9007199254740993L, "9007199254740993 过桥精度无损");
        eq(Amounts.toScript(9007199254740993L), big, "反向也无损");
        eq(Amounts.toWire(9007199254740993L), "9007199254740993", "线格式是十进制字符串");
        eq(Amounts.fromWire("9007199254740993"), 9007199254740993L, "从线格式读回来");

        eq(Amounts.toLong(BigInteger.valueOf(Long.MAX_VALUE), "t"), Long.MAX_VALUE, "long 的顶过得去");

        // 超出 long 的要当场拒，不能让 longValueExact 抛成一个内部错误
        checks++;
        try {
            Amounts.toLong(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE), "t");
            failures.add("超出 long 的 BigInt 竟然收了");
        // ScriptAbort 是 extends Error 不是 RuntimeException（S13 实测：宿主抛
        // RuntimeException 时脚本一个 finally{return} 就能吞掉中断），所以这里要 catch Throwable
        } catch (Throwable e) {
            check(e.getMessage().contains("超出 long"), "报的是金额太大，实际 " + e.getMessage());
        }

        // 不是 BigInt 的一律拒：数字会丢精度，字符串是旧口径
        for (Object bad : new Object[]{Double.valueOf(1), Integer.valueOf(1), "123"}) {
            checks++;
            try {
                Amounts.toLong(bad, "t");
                failures.add(bad.getClass().getSimpleName() + " 竟然当金额收了");
            } catch (Throwable e) {
                check(e.getMessage().contains("BigInt"), "要提示写成 BigInt");
            }
        }
    }

    /** 流水：每一笔都进，失败的也进（§22.3 ⑥）。 */
    static void ledgerRecordsEverything() throws Exception {
        Path dir = tmpDir("log");
        AtomicLong t = new AtomicLong(1_700_000_000_000L);
        TxnLog log = new TxnLog(dir, java.time.ZoneId.of("UTC"));
        MemBalances bal = new MemBalances();
        BuiltinProvider p = provider(bal, new EscrowLedger(t::get), log, t, 1_000);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();

        p.mint(a, 500, new TxnReason("test", "ok"));
        p.transfer(a, b, 999_999, new TxnReason("test", "fail"));   // 余额不够，必失败

        Path f = dir.resolve("ledger").resolve("2023-11-14.log");
        check(Files.exists(f), "流水文件按日期建出来了：" + f);
        var lines = Files.readAllLines(f);
        eq(lines.size(), 2, "两笔都记了 —— 失败的也要进，那是查「谁在试探」的依据");
        check(lines.get(0).contains("|mint|"), "第一行是铸造");
        check(lines.get(0).endsWith("|OK"), "结果在最后一格");
        check(lines.get(1).contains("|transfer|"), "第二行是转账");
        check(lines.get(1).endsWith("|INSUFFICIENT"), "失败的结果也记下来了");

        eq(TxnLog.RETENTION_DAYS, 90, "§22.10 保留 90 天");
        eq(TxnLog.MAX_FILE_BYTES, 64L * 1024 * 1024, "§22.10 单日 64 MiB");
    }


    // ================================================================ §22.7 scoreboard 档

    private static Currency coin(String path, int decimals) {
        return new Currency(ResourceLocation.tryParse("myserver:" + path),
                Component.literal("金币"), "G", decimals, null);
    }

    /**
     * 分值是 32 位整数，金额是 long —— 上限必须压到 int，越界一律 LIMIT。
     *
     * <p>这一条是这一档最容易踩的地方：靠 {@code add} 的回绕"看起来成功"的话，
     * 一次入账就能把余额从二十亿翻成负二十亿，而 §22.9 说不许回绕。
     */
    static void scoreboardCeiling() {
        eq(ScoreboardProvider.SCORE_MAX, (long) Integer.MAX_VALUE, "上限就是 int 的上限");
        eq(ScoreboardProvider.clampMax(Long.MAX_VALUE), (long) Integer.MAX_VALUE,
                "配多大都压到 int");
        eq(ScoreboardProvider.clampMax(1000L), 1000L, "配得比 int 小就按配的来");
        eq(ScoreboardProvider.clampMax(0L), (long) Integer.MAX_VALUE, "0 表示用这一档自己的上限");

        long max = ScoreboardProvider.clampMax(Long.MAX_VALUE);
        eq(ScoreboardProvider.checkCreditInt(Integer.MAX_VALUE - 5L, 5L, max), TxnResult.OK,
                "刚好到顶，可以");
        eq(ScoreboardProvider.checkCreditInt(Integer.MAX_VALUE - 5L, 6L, max), TxnResult.LIMIT,
                "再多一个就越界 → LIMIT");
        eq(ScoreboardProvider.checkCreditInt(Integer.MAX_VALUE - 5L, Long.MAX_VALUE, max),
                TxnResult.LIMIT, "long 级的入账也是 LIMIT，不是溢出成负数");
        eq(ScoreboardProvider.checkCreditInt(0, Long.MAX_VALUE, max), TxnResult.LIMIT,
                "加法本身会溢出 —— Math.addExact 接住，仍是 LIMIT");

        // 允许负余额时，下限同样是 int 的下限
        eq(ScoreboardProvider.checkDebitInt(Integer.MIN_VALUE + 5L, 5L, true), TxnResult.OK,
                "刚好到底，可以");
        eq(ScoreboardProvider.checkDebitInt(Integer.MIN_VALUE + 5L, 6L, true), TxnResult.LIMIT,
                "再扣一个就越过 int 下限 → LIMIT");
        eq(ScoreboardProvider.checkDebitInt(100, 101, false), TxnResult.INSUFFICIENT,
                "不许负余额时不够就是 INSUFFICIENT，不是 LIMIT");
    }

    /** amount <= 0 一律 INVALID —— 负数转账等于从对方账上偷钱（§22.9）。 */
    static void scoreboardAmounts() {
        long max = ScoreboardProvider.clampMax(0);
        for (long bad : new long[]{0L, -1L, -1000L, Long.MIN_VALUE}) {
            eq(ScoreboardProvider.checkCreditInt(100, bad, max), TxnResult.INVALID,
                    "入账 " + bad + " → INVALID");
            eq(ScoreboardProvider.checkDebitInt(100, bad, false), TxnResult.INVALID,
                    "扣款 " + bad + " → INVALID");
            eq(ScoreboardProvider.checkDebitInt(100, bad, true), TxnResult.INVALID,
                    "允许负余额也不许 " + bad);
        }
    }

    /**
     * 货币的 objective 必须落在脚本写不到的命名空间（§18.6）。
     *
     * <p>落进 {@code myapp_*} 的话 App 直接写那个数，§22.9 的五条不变量整条绕过去。
     */
    static void scoreboardObjectiveNamespace() {
        String o = ScoreboardProvider.objectiveFor(coin("coin", 2));
        check(o.startsWith("mcphone_eco_myserver_coin_"), "objective 名带 namespace，实际 " + o);

        // 【两种货币不许落到同一本账上】：只取 path 的话 server:coin 与 shop:coin 会撞，
        // 那就是在便宜的那种上 mint、在贵的那种上花掉
        java.util.Map<String, String> seen = new HashMap<>();
        for (String id : new String[]{"server:coin", "shop:coin", "a:coin", "a:b_c", "a_b:c",
                "server:coin_x", "server:coin-x", "server:coin.x", "server:coin/x"}) {
            Currency c = new Currency(ResourceLocation.tryParse(id),
                    Component.literal("x"), "", 0, null);
            String obj = ScoreboardProvider.objectiveFor(c);
            check(!seen.containsKey(obj), id + " 与 " + seen.get(obj) + " 撞到了同一个 objective " + obj);
            seen.put(obj, id);
        }
        eq(seen.size(), 9, "九种 id 要落到九个不同的 objective");

        // 字符集：/scoreboard 的参数是不带引号的 word，超出就得加引号
        for (char c : o.toCharArray()) {
            check((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_',
                    "objective 名只许 [_a-z0-9]，出现了 '" + c + "'");
        }
        // ResourceLocation 的 path 本来就不许大写，但 . - / 是合法的，而 objective 名不收它们
        check(ScoreboardProvider.objectiveFor(coin("gold-coin.v2", 0))
                        .startsWith("mcphone_eco_myserver_gold_coin_v2_"), ". 与 - 归一成下划线");
        check(ScoreboardProvider.objectiveFor(coin("a/b", 0)).startsWith("mcphone_eco_myserver_a_b_"),
                "斜杠也归一 —— 否则 objective 名里出现 / ，服主敲命令要加引号");

        // 隔离：任何脚本命名空间都写不到它
        for (String ns : new String[]{"myapp", "mcphone", "mcphone_eco", "a", "shop"}) {
            check(!ScoreboardProvider.scriptWritable(o, ns),
                    "命名空间 " + ns + " 的脚本不许写货币 objective " + o);
        }
        check(ScoreboardProvider.scriptWritable("myapp_score", "myapp"),
                "脚本写自己那些照旧可以");
        // 光有「只许写 <命名空间>_ 开头的」不够：命名空间取名叫 mcphone 就前缀命中了
        check(!ScoreboardProvider.scriptWritable("mcphone_anything", "mcphone"),
                "宿主保留的前缀谁都写不到，哪怕命名空间正好叫 mcphone");
        check(!ScoreboardProvider.scriptWritable("myapp_score", null), "命名空间为 null 一律不许");
        check(!ScoreboardProvider.scriptWritable(null, "myapp"), "objective 为 null 一律不许");
        check(!ScoreboardProvider.scriptWritable("myapp_score", ""), "空命名空间不许 —— "
                + "否则前缀判定退化成「任何以下划线分隔的名字都能写」");
    }

    /** §22.7 的七个字段：读得出来，不认识的 provider 拒掉而不是静默退回 builtin。 */
    static void scoreboardSpec() {
        Map<String, Object> t = new HashMap<>();
        t.put("id", "server:coin");
        t.put("name", "金币");
        t.put("symbol", "G");
        t.put("decimals", 2);
        t.put("provider", "scoreboard");
        t.put("default", true);
        t.put("max", 5_000_000_000L);

        CurrencySpec spec = CurrencySpec.from(t);
        eq(spec.provider(), "scoreboard", "provider 读得出来");
        eq(spec.isDefault(), true, "default 读得出来");
        eq(spec.decimals(), 2, "decimals 读得出来");
        eq(spec.effectiveMax(), (long) Integer.MAX_VALUE,
                "scoreboard 档把 max 压到 int —— 配了五十亿也不行");
        eq(spec.toCurrency().id().toString(), "server:coin", "判定按 id（E19）");

        Map<String, Object> b = new HashMap<>(t);
        b.put("provider", "builtin");
        eq(CurrencySpec.from(b).effectiveMax(), 5_000_000_000L, "builtin 档不压");

        // 缺省值照 §22.7
        Map<String, Object> bare = new HashMap<>();
        bare.put("id", "server:x");
        CurrencySpec d = CurrencySpec.from(bare);
        eq(d.provider(), "builtin", "provider 缺省是 builtin");
        eq(d.isDefault(), false, "default 缺省是 false");
        eq(d.decimals(), 0, "decimals 缺省是 0");

        // 不认识的一律拒
        for (String bad : new String[]{"vault", "", "SCOREBOARD2", "builtin2"}) {
            Map<String, Object> m = new HashMap<>(t);
            m.put("provider", bad);
            boolean threw = false;
            try {
                CurrencySpec.from(m);
            } catch (IllegalArgumentException e) {
                threw = true;
            }
            check(threw, "provider='" + bad + "' 要拒掉，不许静默退回 builtin");
        }
        eq(CurrencySpec.from(mapWith(t, "provider", "SCOREBOARD")).provider(), "scoreboard",
                "大小写不敏感");

        // 必填与范围
        boolean threw = false;
        try {
            CurrencySpec.from(new HashMap<>());
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "缺 id 要拒");
        threw = false;
        try {
            CurrencySpec.from(mapWith(t, "decimals", 9));
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "decimals 超上限要拒");
    }

    private static Map<String, Object> mapWith(Map<String, Object> base, String k, Object v) {
        Map<String, Object> m = new HashMap<>(base);
        m.put(k, v);
        return m;
    }

    /**
     * 这几条判定必须排在「用不了」之前。
     *
     * <p>排在后面的话，一笔本该 INVALID 的调用会在服务器没起来时变成 UNAVAILABLE ——
     * 调用方以为重试一下就能过，而它永远过不了。
     */
    static void scoreboardRejectsBeforeAvailability() {
        final TxnReason RSN = new TxnReason("test:probe", "r1");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        EscrowLedger led = new EscrowLedger(() -> 0L);
        // server supplier 给 null：ready() 一定失败，所以凡是返回 INVALID 的都说明判在前面
        ScoreboardProvider p = new ScoreboardProvider(coin("coin", 2), () -> null,
                led, null, () -> 0L, false, 0);

        for (long bad : new long[]{0L, -1L, Long.MIN_VALUE}) {
            eq(p.transfer(a, b, bad, RSN), TxnResult.INVALID,
                    "transfer " + bad + " 要 INVALID，不是 UNAVAILABLE");
            eq(p.mint(a, bad, RSN), TxnResult.INVALID,
                    "mint " + bad + " 要 INVALID");
            eq(p.burn(a, bad, RSN), TxnResult.INVALID,
                    "burn " + bad + " 要 INVALID");
            eq(p.hold(a, b, bad, RSN).result(), TxnResult.INVALID,
                    "hold " + bad + " 要 INVALID");
        }

        // 【自己转给自己就是凭空造币】：两端读的是同一格分值，后一笔写覆盖前一笔
        eq(p.transfer(a, a, 100, RSN), TxnResult.INVALID,
                "自己转自己要当场拒，不能让它走到写分值那一步");

        // 【托管号要认货币】：一本 EscrowLedger 管多种货币，不比对就是 A 币换 B 币
        var eid = led.create(a, b, "other:gold", 100);
        eq(p.release(eid, RSN), TxnResult.UNKNOWN_ESCROW,
                "别的货币的托管号 → UNKNOWN_ESCROW，不许放款");
        eq(p.refund(eid, RSN), TxnResult.UNKNOWN_ESCROW,
                "退款同理");
        eq(led.get(eid).settled(), false, "被拒之后那笔托管原封不动");
    }

    /** 用不了的时候要说得出是哪一条，而且给的是本地化键不是自由文本（E19）。 */
    static void scoreboardUnavailable() {
        ScoreboardProvider p = new ScoreboardProvider(coin("coin", 2), () -> null,
                new EscrowLedger(() -> 0L), null, () -> 0L, false, 0);
        check(!p.isAvailable(), "没有服务器就是用不了");
        String key = p.unavailableReasonKey();
        check(key != null && !key.isEmpty(), "用不了时 reasonKey 不能为空");
        check(key.startsWith("mcphone."), "要是本地化键，不是自由文本：" + key);
        check(!key.contains(" "), "本地化键里不该有空格：" + key);
        check(p.objective().startsWith("mcphone_eco_myserver_coin_"), "objective 在构造时就定死");
        eq(p.maxBalance(), (long) Integer.MAX_VALUE, "上限压到了 int");
        // isAvailable()==false 时整档不可用：余额读不到，抛；其余操作一律 UNAVAILABLE。
        // 取代原先的「服务器不在时余额读成 0，不抛」—— 0 与「真的没钱」分不出来，调用方会据此做错决定（产品经理 2026-09-18 定）
        String thrown = null;
        try {
            p.balance(UUID.randomUUID());
        } catch (com.november.mcphone.core.script.server.economy.CurrencyUnavailableException e) {
            thrown = e.reasonKey();
        }
        eq(thrown, key, "服务器不在时读余额抛 UNAVAILABLE，原因与 unavailableReasonKey 一致，不返回 0");
        TxnReason r = new TxnReason("t", "r");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        eq(p.transfer(a, b, 1, r), TxnResult.UNAVAILABLE, "用不了时转账 UNAVAILABLE");
        eq(p.mint(a, 1, r), TxnResult.UNAVAILABLE, "用不了时铸造 UNAVAILABLE");
        eq(p.burn(a, 1, r), TxnResult.UNAVAILABLE, "用不了时销毁 UNAVAILABLE");
        eq(p.hold(a, b, 1, r).result(), TxnResult.UNAVAILABLE, "用不了时托管 UNAVAILABLE");
        boolean npe = false;
        try {
            p.balance(null);
        } catch (IllegalArgumentException e) {
            npe = true;
        }
        check(npe, "balance(null) 是调用方的错，抛 IllegalArgumentException，不返回 0");
    }


    // ================================================================ E25 两条共用不变量

    /** 一个只会记账、永远可用的假钱包，给 AdapterProvider 用。 */
    static final class MemWallet implements AdapterProvider.ExternalWallet {
        final Map<UUID, Long> m = new HashMap<>();

        public boolean available() {
            return true;
        }

        public long balance(UUID p) {
            return m.getOrDefault(p, 0L);
        }

        public boolean deposit(UUID p, long a) {
            m.merge(p, a, Long::sum);
            return true;
        }

        public boolean withdraw(UUID p, long a) {
            long cur = balance(p);
            if (cur < a) return false;
            m.put(p, cur - a);
            return true;
        }
    }

    /** {@code Balances.checkParties} 的真值表。 */
    static void partiesInvariant() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        eq(Balances.checkParties(a, b), TxnResult.OK, "两个不同的人，可以");
        eq(Balances.checkParties(a, a), TxnResult.INVALID, "同一个人 → INVALID");
        eq(Balances.checkParties(a, UUID.fromString(a.toString())), TxnResult.INVALID,
                "比的是值不是引用");
        eq(Balances.checkParties(null, b), TxnResult.INVALID, "from 为 null");
        eq(Balances.checkParties(a, null), TxnResult.INVALID, "to 为 null");
        eq(Balances.checkParties(null, null), TxnResult.INVALID, "两个都 null");
    }

    /** {@code Balances.checkEscrowCurrency} 的真值表。 */
    static void escrowCurrencyInvariant() {
        eq(Balances.checkEscrowCurrency("a:coin", "a:coin"), TxnResult.OK, "同一种货币");
        eq(Balances.checkEscrowCurrency("a:coin", "b:coin"), TxnResult.UNKNOWN_ESCROW,
                "namespace 不同就是两种货币");
        eq(Balances.checkEscrowCurrency("a:coin", "a:gold"), TxnResult.UNKNOWN_ESCROW, "path 不同");
        eq(Balances.checkEscrowCurrency("a:coin", "A:COIN"), TxnResult.UNKNOWN_ESCROW,
                "大小写敏感 —— id 本来就不许大写，宁可拒");
        eq(Balances.checkEscrowCurrency(null, "a:coin"), TxnResult.UNKNOWN_ESCROW, "托管方为 null");
        eq(Balances.checkEscrowCurrency("a:coin", null), TxnResult.UNKNOWN_ESCROW, "提供者为 null");
    }

    /**
     * <b>四种 provider 对同一个非法调用给同一个码。</b>
     *
     * <p>这一条才是「不许各写一遍」可验的部分：判据摆在 {@code Balances} 里不等于大家都调了它。
     */
    static void allProvidersRejectSelfTransfer() {
        final TxnReason RSN = new TxnReason("test:probe", "r1");
        UUID a = UUID.randomUUID();
        AtomicLong t = new AtomicLong(0);

        MemBalances bal = new MemBalances();
        bal.set(a, "myserver:coin", 1000);
        var builtin = new BuiltinProvider(coin(), bal, new EscrowLedger(t::get), null, t::get, false, 10000);

        MemWallet w = new MemWallet();
        w.deposit(a, 1000);
        var adapter = new AdapterProvider(coin(), w, new EscrowLedger(t::get), 10000);

        var legacy = new LegacyWalletProvider(coin());
        var scoreboard = new ScoreboardProvider(coin(), () -> null,
                new EscrowLedger(t::get), null, t::get, false, 0);

        // 生产路径上 to 是 UUID.fromString 出来的另一个对象；两端传同一个引用，判据写成 == 也照样绿
        UUID self = UUID.fromString(a.toString());
        check(self != a && self.equals(a), "自己转自己的两端得是值相同的两个对象");

        eq(builtin.transfer(a, self, 100, RSN), TxnResult.INVALID, "builtin 自己转自己");
        eq(adapter.transfer(a, self, 100, RSN), TxnResult.INVALID, "adapter 自己转自己");
        eq(legacy.transfer(a, self, 100, RSN), TxnResult.INVALID, "emc_legacy 自己转自己");
        eq(scoreboard.transfer(a, self, 100, RSN), TxnResult.INVALID, "scoreboard 自己转自己");

        // 【早退：两端余额不变】—— 走进读-判-写再拦就晚了
        eq(bal.get(a, "myserver:coin"), 1000L, "builtin 被拒之后余额一分没动");
        eq(w.balance(a), 1000L, "adapter 被拒之后余额一分没动");

        // 而正常的两人转账照走
        UUID b = UUID.randomUUID();
        eq(builtin.transfer(a, b, 100, RSN), TxnResult.OK, "两个人之间照转");
        eq(bal.get(a, "myserver:coin") + bal.get(b, "myserver:coin"), 1000L, "总额不变");
    }

    /** 托管号要认货币 —— 三种有托管的 provider 都要拒，而且那笔托管原封不动。 */
    static void allProvidersRejectForeignEscrow() {
        final TxnReason RSN = new TxnReason("test:probe", "r1");
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        AtomicLong t = new AtomicLong(0);

        // 一本托管账管两种货币：这正是 EscrowLedger 的 Entry 带 currencyId 的用意
        EscrowLedger led = new EscrowLedger(t::get);
        var foreign = led.create(a, b, "other:gold", 500);

        MemBalances bal = new MemBalances();
        var builtin = new BuiltinProvider(coin(), bal, led, null, t::get, false, 10000);
        var adapter = new AdapterProvider(coin(), new MemWallet(), led, 10000);
        var scoreboard = new ScoreboardProvider(coin(), () -> null, led, null, t::get, false, 0);

        eq(builtin.release(foreign, RSN), TxnResult.UNKNOWN_ESCROW, "builtin 拒别的货币的托管号");
        eq(adapter.release(foreign, RSN), TxnResult.UNKNOWN_ESCROW, "adapter 拒");
        eq(scoreboard.release(foreign, RSN), TxnResult.UNKNOWN_ESCROW, "scoreboard 拒");
        eq(builtin.refund(foreign, RSN), TxnResult.UNKNOWN_ESCROW, "退款同理");
        eq(adapter.refund(foreign, RSN), TxnResult.UNKNOWN_ESCROW, "退款同理");
        eq(scoreboard.refund(foreign, RSN), TxnResult.UNKNOWN_ESCROW, "退款同理");

        eq(led.get(foreign).settled(), false, "被拒之后那笔托管原封不动");
        eq(bal.get(b, "myserver:coin"), 0L, "更没有给受益人记上钱");
        eq(led.held("other:gold"), 500L, "那种货币托管中的钱还是 500");

        // 自己那种货币的托管号照常放款
        bal.set(a, "myserver:coin", 1000);
        var mine = builtin.hold(a, b, 300, RSN);
        eq(mine.result(), TxnResult.OK, "自己这种货币的托管建得出来");
        eq(builtin.release(mine.id(), RSN), TxnResult.OK, "也放得出去");
        eq(bal.get(b, "myserver:coin"), 300L, "受益人收到了");
    }

    /**
     * S15d′：同一条配置文件 → 同一个 {@code List<CurrencySpec>}（解析器是纯函数，
     * 换台机器 / 换次开服不该变）。坏配置的细节用例在 {@code EconomyConfigTest}。
     */
    static void economyConfigDeterministic() {
        String json = "{\"currency\":[{\"id\":\"test:coin\",\"decimals\":2},"
                + "{\"id\":\"test:gem\",\"provider\":\"scoreboard\"}]}";
        var a = com.november.mcphone.core.script.server.economy.EconomyConfig.parse(json);
        var b = com.november.mcphone.core.script.server.economy.EconomyConfig.parse(json);
        check(a.clean(), "配置解析干净");
        eq(a.specs(), b.specs(), "同一条配置 → 同一个 List<CurrencySpec>");
        eq(a.specs().get(1).provider(), "scoreboard", "scoreboard 档在表里");
    }

    public static void main(String[] args) throws Exception {
        nonPositiveRejected();
        ceilingAndOverflow();
        concurrentConservation();
        escrow();
        escrowTimeout();
        audit();
        emcLegacy();
        registry();
        bigIntBridge();
        ledgerRecordsEverything();
        amountMustBePositive();
        ceilingDoesNotWrap();
        debits();
        formatParse();
        txnReason();
        holdResult();
        distinctCodes();
        conservation();
        scoreboardCeiling();
        scoreboardAmounts();
        scoreboardObjectiveNamespace();
        scoreboardSpec();
        economyConfigDeterministic();
        partiesInvariant();
        escrowCurrencyInvariant();
        allProvidersRejectSelfTransfer();
        allProvidersRejectForeignEscrow();
        scoreboardRejectsBeforeAvailability();
        scoreboardUnavailable();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
