package com.november.mcphone.core.script.engine;

import com.november.mcphone.api.sdk.cycle.CycleKind;
import com.november.mcphone.api.sdk.cycle.CycleLabels;
import com.november.mcphone.core.script.net.ScriptErrorCode;
import com.november.mcphone.core.script.server.store.KvBackend;
import com.november.mcphone.core.script.server.store.SealedBackend;
import com.november.mcphone.core.script.server.store.SealedRecord;
import com.november.mcphone.core.script.server.store.StoreQuota;
import com.november.mcphone.api.economy.Balances;
import com.november.mcphone.api.economy.EscrowId;
import com.november.mcphone.api.economy.HoldResult;
import com.november.mcphone.api.economy.ICurrencyProvider;
import com.november.mcphone.api.economy.TxnReason;
import com.november.mcphone.api.economy.TxnResult;
import com.november.mcphone.core.script.server.economy.Amounts;
import com.november.mcphone.core.script.server.economy.CurrencyRegistry;
import com.november.mcphone.core.script.server.economy.ProviderFailure;
import com.november.mcphone.core.script.server.PlayerSnapshot;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 建 {@code ctx}（施工方案 §16.5 的表面、§32.7 的 P1 交付集）。
 *
 * <h2>只挂有后端的，不挂空壳</h2>
 *
 * §32.7 的 plain 档里，{@code ctx.store} / {@code ctx.sealed} / {@code ctx.quota} 是 S14 的，
 * {@code ctx.currency} 是 S15 的，{@code ctx.mailbox} 只有契约没有实现，
 * {@code ctx.predicate} 是 S18 的，{@code ctx.fetch} 是 S24 的。
 *
 * <p><b>没后端的一律不挂属性</b>，不挂一个返回 UNAVAILABLE 的壳。理由：
 * §16.7 判的是"{@code ctx} 上枚举不出表外的方法"——那是个<b>负向</b>判据，少挂不违反它；
 * 而多挂一个空壳会让 S14/S15 的实现者以为"授权审查在接口定下来的时候做过了"。
 * <b>每一个暴露出去的方法都是一次要重做的授权判定。</b>
 *
 * <h2>每一级都是手工建的对象</h2>
 *
 * 实测：把 Java 对象直接注入（{@code NativeJavaObject}）时，若为了让它能用而放行类访问，
 * 脚本 {@code ctx.player.getClass().getClassLoader()} 就能拿到 AppClassLoader。
 * 所以 {@code ctx} 与它下面的每一级都 {@code setPrototype(null)} + {@code setParentScope(null)} + 密封，
 * 值只许是 JS 原语、手工建的对象、或 {@link HostFn} 建的函数。
 */
public final class CtxBuilder {

    private CtxBuilder() {
    }

    /** 周期配置。真正的来源是 {@code mcphone-server.toml} 的 {@code [cycle]}（S14）。 */
    public record Cycle(ZoneId zone, LocalTime dailyAt) {
    }

    /** 能接上的后端。为 null 的那一项<b>整个不挂</b>。 */
    public record Backends(SharedState shared, ItemView item, Cycle cycle,
                           KvBackend store, SealedBackend sealed, CurrencyRegistry currencies,
                           /** 要不要挂"产意图"的能力节点（{@code ctx.give} / {@code ctx.loot} / {@code ctx.attr}）。
                            *  落地端由宿主注入；为 false 时整项不挂（E12：不挂空壳）。 */
                           boolean actionIntents,
                           /** 数据包谓词判定（S18 §18.3）。为 null 时整个 {@code ctx.predicate} 不挂。 */
                           PredicateView predicate,
                           /** 计分板读写（S18 §18.6）。为 null 时整个 {@code ctx.score} 不挂。 */
                           ScoreView score) {

        /** 不挂谓词/计分板的写法（S18 之前的路径与大多数断言）。 */
        public Backends(SharedState shared, ItemView item, Cycle cycle,
                        KvBackend store, SealedBackend sealed, CurrencyRegistry currencies,
                        boolean actionIntents) {
            this(shared, item, cycle, store, sealed, currencies, actionIntents, null, null);
        }

        /** 只加谓词的写法。 */
        public Backends(SharedState shared, ItemView item, Cycle cycle,
                        KvBackend store, SealedBackend sealed, CurrencyRegistry currencies,
                        boolean actionIntents, PredicateView predicate) {
            this(shared, item, cycle, store, sealed, currencies, actionIntents, predicate, null);
        }

        /** 只有 S13 那几样的旧写法。 */
        public Backends(SharedState shared, ItemView item, Cycle cycle) {
            this(shared, item, cycle, null, null, null, false);
        }

        /** S14 那一版。 */
        public Backends(SharedState shared, ItemView item, Cycle cycle,
                        KvBackend store, SealedBackend sealed) {
            this(shared, item, cycle, store, sealed, null, false);
        }

        /** S15 那一版（不挂能力节点）。 */
        public Backends(SharedState shared, ItemView item, Cycle cycle,
                        KvBackend store, SealedBackend sealed, CurrencyRegistry currencies) {
            this(shared, item, cycle, store, sealed, currencies, false);
        }
    }

    /** 能力门（S18）：拒绝时抛 {@link HostError#denied}，脚本可 catch、没接住按结果码回去。 */
    @FunctionalInterface
    public interface CapabilityGate {
        void require(String capabilityId);

        /** 没有门（断言/旧路径）时用它：全放行。 */
        CapabilityGate ALLOW_ALL = capabilityId -> {
        };
    }

    /** 数据包谓词判定（S18 §18.3）。宿主把平台门面注入进来，引擎只管挂节点。 */
    @FunctionalInterface
    public interface PredicateView {
        /**
         * @return {@code TRUE}/{@code FALSE} 判定结果；{@code null} = 本服没有这个谓词
         *         （配置错，不是判否 —— 脚本收到可接住的 {@link HostError#denied}）
         */
        Boolean test(String predicateId, PlayerSnapshot player);
    }

    /**
     * 计分板读写（S18 §18.6）。<b>实现方负责线程</b>：生产实现经 {@code CurrencyGateway}
     * 回主线程执行（{@code Scoreboard} 不是线程安全的）。
     *
     * <p>objective 名是<b>已经拼好前缀的完整名</b>（前缀由 {@link #scoreObjective} 生成），
     * 实现方不用再判断归属。实现里出问题（主线程忙、只读 objective、查不到玩家名）请抛
     * {@link HostError#denied}，脚本收到的是可接住的 {@code UNAVAILABLE}。
     */
    public interface ScoreView {
        int get(java.util.UUID player, String objective);

        void set(java.util.UUID player, String objective, int value);

        void add(java.util.UUID player, String objective, int value);
    }

    /** 脚本调 {@code ctx.ok} / {@code ctx.fail} 之后落在这里。 */
    public static final class Result {
        public ScriptErrorCode code;
        public String messageKey = "";
        public List<String> messageArgs = List.of();
        public String dataJson = "";
        public final java.util.List<String> logs = new java.util.ArrayList<>();
        /** worker 想对世界做的事（S18）。落地一律回主线程，落地前重查授权与能力。 */
        public final java.util.List<com.november.mcphone.core.script.server.ActionIntent> intents =
                new java.util.ArrayList<>();
    }

    private static final AtomicLong SEQ = new AtomicLong();

    /** 建一个 {@code ctx}。{@code result} 由调用方持有，求值结束后读它。 */
    public static ScriptableObject build(Context cx, Scriptable scope, String appId,
                                         PlayerSnapshot player, Backends backends, Result result) {
        return build(cx, scope, appId, player, backends, result, new MoneyLedger(), CapabilityGate.ALLOW_ALL);
    }

    public static ScriptableObject build(Context cx, Scriptable scope, String appId,
                                         PlayerSnapshot player, Backends backends, Result result,
                                         MoneyLedger ledger) {
        return build(cx, scope, appId, player, backends, result, ledger, CapabilityGate.ALLOW_ALL);
    }

    /**
     * 带能力门的建法（S18）。每个受能力约束的节点（{@code ctx.give} / {@code ctx.loot} /
     * {@code ctx.attr}）在调用时先过 {@code gate}：拒绝就抛可接住的 {@link HostError#denied}，
     * <b>意图一条都不产</b>（于是不可能出现"拒了但物品已经给了"）。
     */
    public static ScriptableObject build(Context cx, Scriptable scope, String appId,
                                         PlayerSnapshot player, Backends backends, Result result,
                                         MoneyLedger ledger, CapabilityGate gate) {
        ScriptableObject ctx = HostFn.obj(cx, scope);

        // ---- ctx.player：四个字段（§32.7），都是 JS 字符串，不是 Java 对象
        ScriptableObject p = HostFn.obj(cx, scope);
        ScriptableObject.putProperty(p, "uuid", player.uuid().toString());
        ScriptableObject.putProperty(p, "name", player.name());
        ScriptableObject.putProperty(p, "dimension", player.dimension());
        ScriptableObject.putProperty(p, "gameMode", player.gameMode());
        p.sealObject();
        ScriptableObject.putProperty(ctx, "player", p);

        // ---- ctx.time
        ScriptableObject time = HostFn.obj(cx, scope);
        HostFn.put(time, scope, "epochMillis", 0, (c, s, a) -> String.valueOf(System.currentTimeMillis()));
        HostFn.put(time, scope, "monotonicNanos", 0, (c, s, a) -> String.valueOf(System.nanoTime()));
        HostFn.put(time, scope, "seq", 0, (c, s, a) -> String.valueOf(SEQ.incrementAndGet()));
        time.sealObject();
        ScriptableObject.putProperty(ctx, "time", time);

        // ---- ctx.cycle（§23.3）：标签就是 §20.1 limit 守卫的 label
        if (backends.cycle() != null) {
            Cycle cfg = backends.cycle();
            ScriptableObject cycle = HostFn.obj(cx, scope);
            HostFn.put(cycle, scope, "label", 1, (c, s, a) -> {
                CycleKind k = CycleKind.of(HostFn.str(a, 0, "cycle.label"));
                if (k == null) throw HostError.unknownValue("cycle.label 只认 daily/weekly/monthly");
                return CycleLabels.label(k, Instant.now(), cfg.zone(), cfg.dailyAt());
            });
            HostFn.put(cycle, scope, "nextBoundary", 1, (c, s, a) -> {
                CycleKind k = CycleKind.of(HostFn.str(a, 0, "cycle.nextBoundary"));
                if (k == null) throw HostError.unknownValue("cycle.nextBoundary 只认 daily/weekly/monthly");
                // 十进制字符串：毫秒时间戳超过 2^53，用数字会静默丢精度（§23.3）
                return String.valueOf(CycleLabels.nextBoundary(k, Instant.now(), cfg.zone(), cfg.dailyAt()));
            });
            cycle.sealObject();
            ScriptableObject.putProperty(ctx, "cycle", cycle);
        }

        // ---- ctx.shared（§32.7 的 plain 档，限量竞争的唯一原语）
        if (backends.shared() != null) {
            SharedState st = backends.shared();
            ScriptableObject shared = HostFn.obj(cx, scope);
            HostFn.put(shared, scope, "get", 1, (c, s, a) -> {
                String v = st.get(appId, HostFn.str(a, 0, "shared.get"));
                return v == null ? null : v;
            });
            HostFn.put(shared, scope, "set", 2, (c, s, a) -> {
                st.set(appId, HostFn.str(a, 0, "shared.set"), HostFn.str(a, 1, "shared.set"));
                return Boolean.TRUE;
            });
            HostFn.put(shared, scope, "compareAndSet", 3, (c, s, a) -> {
                String key = HostFn.str(a, 0, "shared.compareAndSet");
                String expected = HostFn.present(a, 1) ? HostFn.str(a, 1, "shared.compareAndSet") : null;
                String next = HostFn.str(a, 2, "shared.compareAndSet");
                return st.compareAndSet(appId, key, expected, next);
            });
            shared.sealObject();
            ScriptableObject.putProperty(ctx, "shared", shared);
        }

        // ---- ctx.item（§23.3）：句柄只搬运，不解析也不构造
        if (backends.item() != null) {
            ItemView iv = backends.item();
            ScriptableObject item = HostFn.obj(cx, scope);
            HostFn.put(item, scope, "matches", 2, (c, s, a) ->
                    iv.matches(HostFn.str(a, 0, "item.matches"), HostFn.str(a, 1, "item.matches")));
            HostFn.put(item, scope, "displayName", 1, (c, s, a) ->
                    iv.displayName(HostFn.str(a, 0, "item.displayName")));
            HostFn.put(item, scope, "isDamaged", 1, (c, s, a) ->
                    iv.isDamaged(HostFn.str(a, 0, "item.isDamaged")));
            item.sealObject();
            ScriptableObject.putProperty(ctx, "item", item);
        }

        // ---- ctx.store（§16.5、§17.3）：每玩家的 KV。档位由"跑在哪一侧"决定，不在方法名里
        if (backends.store() != null) {
            KvBackend kv = backends.store();
            ScriptableObject store = HostFn.obj(cx, scope);
            HostFn.put(store, scope, "getString", 2, (c, s, a) -> {
                String v = kv.getString(appId, HostFn.str(a, 0, "store.getString"));
                return v != null ? v : (HostFn.present(a, 1) ? a[1] : null);
            });
            HostFn.put(store, scope, "setString", 2, (c, s, a) -> {
                translated(() -> kv.setString(appId, HostFn.str(a, 0, "store.setString"),
                        HostFn.str(a, 1, "store.setString")));
                return Boolean.TRUE;
            });
            // 数值一律按十进制字符串过：毫秒时间戳与计数会超过 2^53（§23.3 同一条理由）
            HostFn.put(store, scope, "getLong", 2, (c, s, a) -> {
                String v = kv.getString(appId, HostFn.str(a, 0, "store.getLong"));
                return v != null ? v : (HostFn.present(a, 1) ? a[1] : "0");
            });
            HostFn.put(store, scope, "setLong", 2, (c, s, a) -> {
                String key = HostFn.str(a, 0, "store.setLong");
                long value = HostFn.exactLong(a, 1, "store.setLong");
                translated(() -> kv.setString(appId, key, Long.toString(value)));
                return Boolean.TRUE;
            });
            HostFn.put(store, scope, "getBool", 2, (c, s, a) -> {
                String v = kv.getString(appId, HostFn.str(a, 0, "store.getBool"));
                return v == null ? (HostFn.present(a, 1) && HostFn.bool(a, 1, "store.getBool")) : "true".equals(v);
            });
            HostFn.put(store, scope, "setBool", 2, (c, s, a) -> {
                String key = HostFn.str(a, 0, "store.setBool");
                boolean value = HostFn.bool(a, 1, "store.setBool");
                translated(() -> kv.setString(appId, key, Boolean.toString(value)));
                return Boolean.TRUE;
            });
            HostFn.put(store, scope, "remove", 1, (c, s, a) -> {
                translated(() -> kv.remove(appId, HostFn.str(a, 0, "store.remove")));
                return Boolean.TRUE;
            });
            HostFn.put(store, scope, "keys", 0, (c, s, a) ->
                    c.newArray(s, kv.keys(appId).toArray()));
            store.sealObject();
            ScriptableObject.putProperty(ctx, "store", store);
        }

        // ---- ctx.sealed（§17.4.5）：只有真实可用的 get；未实现的 put 不暴露假能力
        if (backends.sealed() != null) {
            SealedBackend sb = backends.sealed();
            ScriptableObject sealed = HostFn.obj(cx, scope);
            HostFn.put(sealed, scope, "get", 1, (c, s, a) -> {
                SealedRecord r = sb.get(appId, HostFn.str(a, 0, "sealed.get"));
                return r == null ? null : java.util.Base64.getEncoder().encodeToString(r.cipher());
            });
            sealed.sealObject();
            ScriptableObject.putProperty(ctx, "sealed", sealed);
        }

        // ---- ctx.currency（§22.5）。金额进出都是 BigInt（勘误 E18），宿主在边界切 BigInt ↔ long
        // 【被拒的调用、玩家输错的数据是返回值，不中断】（S15h）：中断记过失，连着几次禁玩家、熔断整个 App ——
        // 那是在罚玩家。只有脚本自己写错（类型不对）才中断（记过失）；provider 在动钱时抛了或没给结果是结果不明，见 moneyCall
        if (backends.currencies() != null) {
            CurrencyRegistry reg = backends.currencies();
            ScriptableObject cur = HostFn.obj(cx, scope);

            // App 不许写死货币 id（§22.8）：没有默认货币时【返回 null，不抛】，App 该 ctx.fail 而不是崩
            HostFn.put(cur, scope, "default", 0, (c, s, a) -> reg.defaultCurrency());
            HostFn.put(cur, scope, "list", 0, (c, s, a) -> {
                java.util.List<Object> out = new java.util.ArrayList<>();
                for (var m : reg.list()) {
                    ScriptableObject o = HostFn.obj(c, s);
                    ScriptableObject.putProperty(o, "id", m.id().toString());
                    ScriptableObject.putProperty(o, "symbol", m.symbol());
                    ScriptableObject.putProperty(o, "decimals", m.decimals());
                    o.sealObject();
                    out.add(o);
                }
                return c.newArray(s, out.toArray());
            });

            // balance 只能读自己（§22.5）。读别人是 currency.read.other，granted 档，本步不给
            HostFn.put(cur, scope, "balance", 1, (c, s, a) -> {
                ICurrencyProvider prov = requireOrError(reg, strOrNull(a, 0, "currency.balance"));
                try {
                    return Amounts.toScript(prov.balance(player.uuid()));
                } catch (com.november.mcphone.core.script.server.economy.CurrencyUnavailableException e) {
                    // 抛脚本接得住的 Error，App 在 catch 里 ctx.fail('UNAVAILABLE')。
                    // 不返回 0 或 null：比大小时 null 也当 0，App 会告诉玩家他没钱。
                    // 不抛 ScriptAbort：那个接不住、还记过失，连着几次就把整个 App 熔断
                    throw HostError.unavailable(e.reasonKey());
                } catch (ScriptAbort e) {
                    logProviderAbort("balance", appId, e);
                    throw new ProviderAbort("balance", false);
                } catch (RuntimeException e) {
                    // provider 抛的别的：换成替身再往外抛，原来那个的 getMessage 可能自己会炸（见 ProviderFailure）
                    throw ProviderFailure.of(e);
                } catch (Error e) {
                    // Error 要保持 Error：换成 RuntimeException 的替身，脚本 finally { return } 就吞得掉了
                    throw new ProviderError(ProviderFailure.of(e));
                }
            });

            // format 必须用宿主（§22.5）：自己拼小数点，负数与不足位就各错各的
            HostFn.put(cur, scope, "format", 2, (c, s, a) -> {
                ICurrencyProvider prov = requireOrError(reg, strOrNull(a, 0, "currency.format"));
                // 金额缺了（常见是 parse 给的 null）或超出 long：和 pay 一样算"数不对"，抛接得住的 Error，不中断
                Long v = amountOrNull(a, 1, "currency.format");
                if (v == null) throw HostError.invalid(INVALID_AMOUNT);
                return Balances.format(v, prov.currency().decimals()) + " " + prov.currency().symbol();
            });

            // parse 收字符串，回 BigInt；失败必须是 catchable error，不能返回会继续流动的 null。
            HostFn.put(cur, scope, "parse", 2, (c, s, a) -> {
                ICurrencyProvider prov = requireOrError(reg, strOrNull(a, 0, "currency.parse"));
                try {
                    // 没给文本（null）Balances.parse 同样抛 NumberFormatException
                    if (!HostFn.present(a, 1)) throw HostError.invalid(INVALID_AMOUNT);
                    return Amounts.toScript(Balances.parse(HostFn.str(a, 1, "currency.parse"), prov.currency().decimals()));
                } catch (NumberFormatException e) {
                    throw HostError.invalid(INVALID_AMOUNT);
                }
            });

            // pay 的 from 恒为调用者（§22.6：这样它才是 plain 档）
            HostFn.put(cur, scope, "pay", 4, (c, s, a) -> {
                ledger.rejectFurther("currency.pay");
                String cid = strOrNull(a, 0, "currency.pay");
                ICurrencyProvider prov = reg.get(cid);
                if (prov == null) return TxnResult.UNAVAILABLE.name();
                java.util.UUID to = uuidOrNull(strOrNull(a, 1, "currency.pay"));
                Long amt = amountOrNull(a, 2, "currency.pay");
                TxnReason why = reasonOrNull(a, 3, "pay");
                if (to == null || amt == null || why == null) return TxnResult.INVALID.name();
                return moneyCall(ledger, "pay", appId, player.uuid(), cid, to, amt,
                        () -> prov.transfer(player.uuid(), to, amt, why)).name();
            });

            HostFn.put(cur, scope, "hold", 4, (c, s, a) -> {
                ledger.rejectFurther("currency.hold");
                String cid = strOrNull(a, 0, "currency.hold");
                ICurrencyProvider prov = reg.get(cid);
                if (prov == null) return TxnResult.UNAVAILABLE.name();
                java.util.UUID to = uuidOrNull(strOrNull(a, 1, "currency.hold"));
                Long amt = amountOrNull(a, 2, "currency.hold");
                TxnReason why = reasonOrNull(a, 3, "hold");
                if (to == null || amt == null || why == null) return TxnResult.INVALID.name();
                HoldResult h = moneyCall(ledger, "hold", appId, player.uuid(), cid, to, amt,
                        () -> prov.hold(player.uuid(), to, amt, why));
                return h.result() == TxnResult.OK ? h.id().value().toString() : h.result().name();
            });

            HostFn.put(cur, scope, "release", 3, (c, s, a) -> {
                ledger.rejectFurther("currency.release");
                String cid = strOrNull(a, 0, "currency.release");
                ICurrencyProvider prov = reg.get(cid);
                if (prov == null) return TxnResult.UNAVAILABLE.name();
                java.util.UUID id = uuidOrNull(strOrNull(a, 1, "currency.release"));
                if (id == null) return TxnResult.UNKNOWN_ESCROW.name();
                TxnReason why = reasonOrNull(a, 2, "release");
                if (why == null) return TxnResult.INVALID.name();
                return moneyCall(ledger, "release", appId, player.uuid(), cid, id, null,
                        () -> prov.release(new EscrowId(id), why)).name();
            });

            HostFn.put(cur, scope, "refund", 3, (c, s, a) -> {
                ledger.rejectFurther("currency.refund");
                String cid = strOrNull(a, 0, "currency.refund");
                ICurrencyProvider prov = reg.get(cid);
                if (prov == null) return TxnResult.UNAVAILABLE.name();
                java.util.UUID id = uuidOrNull(strOrNull(a, 1, "currency.refund"));
                if (id == null) return TxnResult.UNKNOWN_ESCROW.name();
                TxnReason why = reasonOrNull(a, 2, "refund");
                if (why == null) return TxnResult.INVALID.name();
                return moneyCall(ledger, "refund", appId, player.uuid(), cid, id, null,
                        () -> prov.refund(new EscrowId(id), why)).name();
            });

            // mint / burn 是 granted 档（§22.6）。本步没有能力表，一律 NOT_AUTHORIZED ——
            // 【不静默降级】：没批就明说没批，别让 App 以为成功了
            for (String granted : new String[]{"mint", "burn"}) {
                HostFn.put(cur, scope, granted, 3, (c, s, a) -> {
                    ledger.rejectFurther("currency." + granted);
                    return TxnResult.NOT_AUTHORIZED.name();
                });
            }

            cur.sealObject();
            ScriptableObject.putProperty(ctx, "currency", cur);
        }

        // ---- ctx.predicate（S18 §18.3）：引用服主数据包里的谓词，不自造条件语言。
        // 只读判定，plain 档，不过能力门；认不得的 id 是配置错，回"本服没有这个谓词"。
        if (backends.predicate() != null) {
            ScriptableObject predicate = HostFn.obj(cx, scope);
            HostFn.put(predicate, scope, "test", 1, (c, s, a) -> {
                String id = HostFn.str(a, 0, "predicate.test");
                Boolean r = backends.predicate().test(id, player);
                if (r == null) {
                    throw HostError.denied(ScriptErrorCode.UNAVAILABLE, NO_SUCH_PREDICATE,
                            "predicate.test 认不得：" + id);
                }
                return r;
            });
            predicate.sealObject();
            ScriptableObject.putProperty(ctx, "predicate", predicate);
        }

        // ---- ctx.score（S18 §18.6）：限 App 自己的前缀；读写由宿主经主线程往返执行。
        if (backends.score() != null) {
            ScriptableObject score = HostFn.obj(cx, scope);
            HostFn.put(score, scope, "get", 1, (c, s, a) ->
                    backends.score().get(player.uuid(), scoreObjective(appId, HostFn.str(a, 0, "score.get"))));
            HostFn.put(score, scope, "set", 2, (c, s, a) -> {
                backends.score().set(player.uuid(), scoreObjective(appId, HostFn.str(a, 0, "score.set")),
                        scoreValue(HostFn.exactLong(a, 1, "score.set")));
                return Boolean.TRUE;
            });
            HostFn.put(score, scope, "add", 2, (c, s, a) -> {
                backends.score().add(player.uuid(), scoreObjective(appId, HostFn.str(a, 0, "score.add")),
                        scoreValue(HostFn.exactLong(a, 1, "score.add")));
                return Boolean.TRUE;
            });
            score.sealObject();
            ScriptableObject.putProperty(ctx, "score", score);
        }

        // ---- ctx.give / ctx.loot / ctx.attr（S18）：只产意图，不在这里碰世界。
        // 节点存在与否由宿主决定（落地端没接上就不挂 —— E12 不挂空壳）。
        if (backends.actionIntents()) {
            HostFn.put(ctx, scope, "give", 2, (c, s, a) -> {
                gate.require("item.give");
                String itemId = HostFn.str(a, 0, "ctx.give");
                long n = HostFn.exactLong(a, 1, "ctx.give");
                if (n < 1 || n > com.november.mcphone.core.script.server.ActionIntent.MAX_GIVE) {
                    throw HostError.invalid("ctx.give 的数量要在 1.."
                            + com.november.mcphone.core.script.server.ActionIntent.MAX_GIVE + "，收到 " + n);
                }
                result.intents.add(com.november.mcphone.core.script.server.ActionIntent.itemGive(itemId, (int) n, ""));
                return null;
            });

            ScriptableObject loot = HostFn.obj(cx, scope);
            HostFn.put(loot, scope, "roll", 1, (c, s, a) -> {
                gate.require("loot.roll");
                result.intents.add(com.november.mcphone.core.script.server.ActionIntent.lootRoll(
                        HostFn.str(a, 0, "ctx.loot.roll")));
                return null;
            });
            loot.sealObject();
            ScriptableObject.putProperty(ctx, "loot", loot);

            ScriptableObject attr = HostFn.obj(cx, scope);
            HostFn.put(attr, scope, "grant", 2, (c, s, a) -> {
                gate.require("attr.grant");
                String attrId = HostFn.str(a, 0, "ctx.attr.grant");
                result.intents.add(com.november.mcphone.core.script.server.ActionIntent.attrGrant(
                        attrId, HostFn.num(a, 1, "ctx.attr.grant"), 0, modifierKey(appId, attrId)));
                return null;
            });
            HostFn.put(attr, scope, "revoke", 1, (c, s, a) -> {
                gate.require("attr.grant");
                String attrId = HostFn.str(a, 0, "ctx.attr.revoke");
                result.intents.add(com.november.mcphone.core.script.server.ActionIntent.attrRevoke(
                        attrId, modifierKey(appId, attrId)));
                return null;
            });
            attr.sealObject();
            ScriptableObject.putProperty(ctx, "attr", attr);
        }

        // ---- ctx.ok / ctx.fail / ctx.log
        HostFn.put(ctx, scope, "ok", 1, (c, s, a) -> {
            result.code = ScriptErrorCode.OK;
            result.dataJson = HostFn.present(a, 0) ? json(c, s, a[0]) : "";
            return Boolean.TRUE;
        });
        HostFn.put(ctx, scope, "fail", 3, (c, s, a) -> {
            String codeName = HostFn.str(a, 0, "ctx.fail");
            result.code = parse(codeName);
            result.dataJson = HostFn.present(a, 1) ? json(c, s, a[1]) : "";
            // messageKey 是本地化键，不是文本（§15.3）。键必须在 App 自己的 lang/*.json 里
            result.messageKey = HostFn.present(a, 2) ? HostFn.str(a, 2, "ctx.fail") : result.code.defaultMessageKey();
            return Boolean.TRUE;
        });
        HostFn.put(ctx, scope, "log", 1, (c, s, a) -> {
            if (result.logs.size() < 32) result.logs.add(LogText.filter(HostFn.str(a, 0, "ctx.log")));
            return Boolean.TRUE;
        });

        ctx.sealObject();
        return ctx;
    }

    /**
     * 完整 objective 名：{@code <App id 变形>_<App 自己起的名字>}。
     * App id 里不属于 {@code [a-z0-9_.-]} 的字符统一换成 {@code _}（{@code example:app} → {@code example_app_}）——
     * 服主在 {@code /scoreboard} 上一看前缀就知道是谁写的，别的插件的 objective 一概碰不到。
     */
    static String scoreObjective(String appId, String name) {
        if (name == null || name.isEmpty()) throw HostError.invalid("score 的名字不能为空");
        String full = scorePrefix(appId) + name;
        if (full.length() > 64) {
            throw HostError.invalid("score 的名字太长（含前缀最多 64）：" + full.length());
        }
        return full;
    }

    static String scorePrefix(String appId) {
        StringBuilder b = new StringBuilder(appId.length() + 1);
        for (int i = 0; i < appId.length(); i++) {
            char ch = appId.charAt(i);
            boolean ok = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')
                    || ch == '_' || ch == '.' || ch == '-';
            b.append(ok ? ch : '_');
        }
        return b.append('_').toString();
    }

    /** 计分板分值是 32 位整数：超出范围的数字不静默截断（和 {@code exactLong} 同一个口径）。 */
    static int scoreValue(long v) {
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
            throw HostError.invalid("计分板分值是 32 位整数，收到 " + v);
        }
        return (int) v;
    }

    /**
     * 属性修饰符在包内的 key：{@code <appId 的 path>/<属性 id 去冒号>}。
     * 落地端再拼成 {@code mcphone:script/<key>}。同一个 App 对同一个属性只有一条（可覆盖），
     * 不同 App 之间不会互相踩。
     */
    static String modifierKey(String appId, String attrId) {
        int colon = appId.indexOf(':');
        String path = colon >= 0 ? appId.substring(colon + 1) : appId;
        String key = path + "/" + attrId.replace(':', '/');
        if (key.length() > com.november.mcphone.core.script.server.ActionIntent.MAX_ID) {
            throw HostError.invalid("属性 id 拼出来的修饰符 key 太长：" + key.length());
        }
        return key;
    }

    /** 服务器上没有这种货币（多半是服主改了配置）：抛脚本接得住的 Error，不中断。 */
    private static ICurrencyProvider requireOrError(CurrencyRegistry reg, String id) {
        ICurrencyProvider p = reg.get(id);
        if (p == null) {
            throw HostError.unavailable(NO_SUCH_CURRENCY);
        }
        return p;
    }

    static final String NO_SUCH_CURRENCY = "mcphone.economy.no_such_currency";

    /** 谓词 id 这一支认不得时的本地化键（S18 §18.3）。 */
    static final String NO_SUCH_PREDICATE = "mcphone.script.predicate.unavailable";

    /**
     * 会动钱的 provider 调用。provider 抛了、或者没给结果 = 结果不明（可能已经动了一半）：打一条带来龙去脉与 provider 堆栈的 ERROR
     * 给服主核对，再抛 {@link OutcomeUnknown} —— 脚本接不住也吞不掉、拿到 UNKNOWN、不记过失。虚拟机级别的错误也一样换：
     * 它可能是第三方的子类、getMessage 会炸，原样抛出去日志渲染时照样出事。
     * 不改写成返回码：UNAVAILABLE 会让 App 当"没动"去重试。
     */
    private static <T> T moneyCall(MoneyLedger ledger, String what, String appId, java.util.UUID player,
                                   String currencyId, java.util.UUID other, Long amount,
                                   java.util.function.Supplier<T> op) {
        Throwable failure;
        try {
            T r = op.get();
            if (r != null) {
                ledger.movedMoney();
                return r;
            }
            failure = null;
        } catch (ScriptAbort e) {
            // The provider was entered. Even if budget observation replaces this Error while unwinding,
            // RhinoEvaluator must still know that the final result is UNKNOWN.
            ledger.movedMoney();
            logProviderAbort(what, appId, e);
            throw new ProviderAbort(what, true);
        } catch (Throwable e) {
            failure = e;
        }
        // A thrown/null provider result is itself an uncertain money attempt. Set the bit before logging
        // or constructing OutcomeUnknown so a later ScriptAbort cannot downgrade UNKNOWN to INTERNAL.
        ledger.movedMoney();
        // provider 抛来的那个不可信（getMessage / getStackTrace 自己可能会炸）：日志与 cause 都只用替身
        Throwable standIn = null;
        String detail = "货币调用结果不明";
        try {
            standIn = failure == null ? null : ProviderFailure.of(failure);
            detail = "货币调用结果不明：app=" + LogText.filter(appId) + " 玩家=" + player + " " + what + " " + LogText.filter(currencyId)
                    + " 对方或托管号=" + other + " 金额=" + (amount == null ? "-" : amount + "（最小单位）")
                    + " —— " + (failure == null ? "provider 没给结果（返回了 null）" : "provider 抛了 " + LogText.filter(standIn.getMessage()))
                    + "，钱可能已经动了一半，请核对";
            com.november.mcphone.MCphone.LOGGER.error("[MCphone] ⚠ {}", detail, standIn);
        } catch (Throwable ignored) {
            // 栈溢出、内存不够时打不出来也别换掉原来那个错，更别变成脚本 finally 吞得掉的 RuntimeException
        }
        throw new OutcomeUnknown(detail, standIn);
    }

    private static void logProviderAbort(String operation, String appId, ScriptAbort abort) {
        String detail;
        try {
            detail = LogText.filter(abort.getMessage());
        } catch (Throwable ignored) {
            detail = "(unreadable provider abort)";
        }
        com.november.mcphone.MCphone.LOGGER.error(
                "[MCphone] provider aborted operation={} app={} detail={}",
                operation, LogText.filter(appId), detail);
    }

    private static void translated(Runnable operation) {
        try {
            operation.run();
        } catch (StoreQuota.QuotaExceeded quota) {
            throw HostError.quota(quota.getMessage());
        }
    }

    /**
     * 字符串参数缺了（null / undefined）→ null，由调用方给返回码：常见是 {@code default()} 在没有默认货币时给的 null、
     * 或者玩家没填。别的类型照旧中断（脚本写错了）。
     */
    private static String strOrNull(Object[] args, int i, String where) {
        return HostFn.present(args, i) ? HostFn.str(args, i, where) : null;
    }

    static final String INVALID_AMOUNT = "mcphone.economy.invalid_amount";

    /**
     * 玩家、托管号是字符串，多半从别处传来：写歪了是返回码，不是脚本的错。
     * <b>只认规范写法</b>（读回来与原串一致，大小写不论）：{@code UUID.fromString} 很宽松，{@code "1-1-1-1-1"}、全角数字、
     * 超长的段都会被收成<b>另一个</b> UUID —— 钱就付进一个没有主人的账户里了。
     */
    private static java.util.UUID uuidOrNull(String s) {
        if (s == null) return null;
        try {
            java.util.UUID u = java.util.UUID.fromString(s);
            return u.toString().equalsIgnoreCase(s) ? u : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 金额。缺了（null / undefined，常见是 parse 给的 null）或是 BigInt 但超出 long（多半是玩家给的数）→ null，调用方给 INVALID；
     * 不是 BigInt 的别的类型是脚本写错了，照旧中断。
     */
    private static Long amountOrNull(Object[] args, int i, String where) {
        Object v = args.length > i ? args[i] : null;
        if (v == null || v instanceof org.mozilla.javascript.Undefined) return null;
        if (v instanceof java.math.BigInteger b && b.bitLength() > 63) return null;
        return Amounts.toLong(v, where);
    }

    /** ref 里有竖线、控制字符或太长（多半是把玩家输入当单号）：返回 null → INVALID。 */
    private static TxnReason reasonOrNull(Object[] args, int i, String kind) {
        try {
            return reason(args, i, kind);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** {@code reason} 里没有 appId 这一格 —— 由宿主盖章（§22.10）。 */
    private static TxnReason reason(Object[] args, int i, String kind) {
        String ref = HostFn.present(args, i) ? HostFn.str(args, i, "currency." + kind) : "";
        return new TxnReason(kind, ref);
    }

    /** 认不出的码一律 INTERNAL —— 不让脚本自己编一个码出来。 */
    private static ScriptErrorCode parse(String name) {
        for (ScriptErrorCode c : ScriptErrorCode.values()) {
            if (c.name().equals(name)) return c;
        }
        return ScriptErrorCode.INTERNAL;
    }

    /** 走沙箱里那个已经带了尺寸闸的 JSON.stringify。 */
    private static String json(Context cx, Scriptable scope, Object value) {
        Object out = org.mozilla.javascript.NativeJSON.stringify(cx, scope, value, null, "");
        if (out instanceof CharSequence cs) {
            SizeGate.check(cs, "ctx 的 data");
            return cs.toString();
        }
        return "";
    }
}
