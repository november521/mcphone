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
                           KvBackend store, SealedBackend sealed, CurrencyRegistry currencies) {

        /** 只有 S13 那几样的旧写法。 */
        public Backends(SharedState shared, ItemView item, Cycle cycle) {
            this(shared, item, cycle, null, null, null);
        }

        /** S14 那一版。 */
        public Backends(SharedState shared, ItemView item, Cycle cycle,
                        KvBackend store, SealedBackend sealed) {
            this(shared, item, cycle, store, sealed, null);
        }
    }

    /** 脚本调 {@code ctx.ok} / {@code ctx.fail} 之后落在这里。 */
    public static final class Result {
        public ScriptErrorCode code;
        public String messageKey = "";
        public List<String> messageArgs = List.of();
        public String dataJson = "";
        public final java.util.List<String> logs = new java.util.ArrayList<>();
    }

    private static final AtomicLong SEQ = new AtomicLong();

    /** 建一个 {@code ctx}。{@code result} 由调用方持有，求值结束后读它。 */
    public static ScriptableObject build(Context cx, Scriptable scope, String appId,
                                         PlayerSnapshot player, Backends backends, Result result) {
        return build(cx, scope, appId, player, backends, result, new MoneyLedger());
    }

    /**
     * 建一个 {@code ctx}，并带上这一次求值的"钱动过没有"的账。
     *
     * <p>{@code ledger} 由 {@link RhinoEvaluator} 建、求值结束后读它 ——
     * <b>必须是调用方传进来的那一个</b>，不能在这儿现建：provider 回主线程跑，
     * 账得跨线程统一（理由见 {@link MoneyLedger}）。
     */
    public static ScriptableObject build(Context cx, Scriptable scope, String appId,
                                         PlayerSnapshot player, Backends backends, Result result,
                                         MoneyLedger ledger) {
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
                // 认不出的粒度是"输入不合法"：脚本接得住、不记过失（S15h/S15i 任务 7）
                if (k == null) throw HostError.unknownValue("cycle.label 只认 daily/weekly/monthly，收到别的");
                return CycleLabels.label(k, Instant.now(), cfg.zone(), cfg.dailyAt());
            });
            HostFn.put(cycle, scope, "nextBoundary", 1, (c, s, a) -> {
                CycleKind k = CycleKind.of(HostFn.str(a, 0, "cycle.nextBoundary"));
                if (k == null) throw HostError.unknownValue("cycle.nextBoundary 只认 daily/weekly/monthly，收到别的");
                // 十进制字符串：毫秒时间戳超过 2^53，用数字会静默丢精度（§23.3）
                return String.valueOf(CycleLabels.nextBoundary(k, Instant.now(), cfg.zone(), cfg.dailyAt()));
            });
            cycle.sealObject();
            ScriptableObject.putProperty(ctx, "cycle", cycle);
        }

        // ---- ctx.shared（§32.7 的 plain 档，限量竞争的唯一原语）
        //
        // 【两套上限，先后关系写在这里】SharedState.check 先拦：单值 SizeGate.MAX_STRING（64 KiB）
        // 与每 App MAX_KEYS_PER_APP（4096）键；SharedState.MAX_VALUE 就是 SizeGate.MAX_STRING。
        // StoreQuota 那套（SHARED_PER_VALUE 2 KiB / SHARED_KEYS 64 / SHARED_PER_PLAYER_APP 8 KiB）
        // 是 ScriptKv 落盘时的上限，走 KvBackend 实现，本步还没有实现类。
        // 两层拒绝现在都会被 translated() 翻成脚本接得住的 HostError，不记过失、不产生部分写入。
        if (backends.shared() != null) {
            SharedState st = backends.shared();
            ScriptableObject shared = HostFn.obj(cx, scope);
            HostFn.put(shared, scope, "get", 1, (c, s, a) -> {
                String v = st.get(appId, HostFn.str(a, 0, "shared.get"));
                return v == null ? null : v;
            });
            HostFn.put(shared, scope, "set", 2, (c, s, a) -> {
                String key = HostFn.str(a, 0, "shared.set");
                String value = HostFn.str(a, 1, "shared.set");
                translated(() -> st.set(appId, key, value));
                return Boolean.TRUE;
            });
            HostFn.put(shared, scope, "compareAndSet", 3, (c, s, a) -> {
                String key = HostFn.str(a, 0, "shared.compareAndSet");
                String expected = HostFn.present(a, 1) ? HostFn.str(a, 1, "shared.compareAndSet") : null;
                String next = HostFn.str(a, 2, "shared.compareAndSet");
                return translated(() -> st.compareAndSet(appId, key, expected, next));
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
        //
        // 【配额的两套上限，先后关系】KvBackend 的实现自己按 StoreQuota 判（shared 档：
        // 单值 2 KiB、键数 64、每玩家每 App 8 KiB），超了抛 StoreQuota.QuotaExceeded；
        // 同一次写入如果先撞上 ctx 表面的尺寸闸（HostFn 的 SizeGate，64 KiB），
        // 那一条在进后端之前就被 HostError 拒了。所以顺序是：表面尺寸闸 → 后端配额。
        // 两条路都经 translated() 翻成脚本接得住的 HostError，不记过失、不产生部分写入。
        if (backends.store() != null) {
            KvBackend kv = backends.store();
            ScriptableObject store = HostFn.obj(cx, scope);
            HostFn.put(store, scope, "getString", 2, (c, s, a) -> {
                String v = kv.getString(appId, HostFn.str(a, 0, "store.getString"));
                return v != null ? v : (HostFn.present(a, 1) ? a[1] : null);
            });
            HostFn.put(store, scope, "setString", 2, (c, s, a) -> {
                String key = HostFn.str(a, 0, "store.setString");
                String value = HostFn.str(a, 1, "store.setString");
                translated(() -> kv.setString(appId, key, value));
                return Boolean.TRUE;
            });
            // 数值一律按十进制字符串过：毫秒时间戳与计数会超过 2^53（§23.3 同一条理由）
            HostFn.put(store, scope, "getLong", 2, (c, s, a) -> {
                String v = kv.getString(appId, HostFn.str(a, 0, "store.getLong"));
                return v != null ? v : (HostFn.present(a, 1) ? a[1] : "0");
            });
            HostFn.put(store, scope, "setLong", 2, (c, s, a) -> {
                String key = HostFn.str(a, 0, "store.setLong");
                // 【精确转换，越界一律拒】ExactLong 在写入之前抛，所以失败时一个字节都没落盘。
                // 换成 Number.longValue() 就是静默截断：1.9 写成 1、NaN 写成 0、2^63 写成 Long.MAX_VALUE
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
                String key = HostFn.str(a, 0, "store.remove");
                translated(() -> kv.remove(appId, key));
                return Boolean.TRUE;
            });
            HostFn.put(store, scope, "keys", 0, (c, s, a) ->
                    c.newArray(s, kv.keys(appId).toArray()));
            store.sealObject();
            ScriptableObject.putProperty(ctx, "store", store);
        }

        // ---- ctx.sealed（§17.4.5）：只有 get，服务端只搬字节、解不开
        //
        // 【put 不挂了】（S15h/S15i 任务 7）：它原先是一个无条件抛 ScriptAbort(HOST) 的空壳 ——
        // 脚本看得到属性、按定义永远失败，而 SealedBackend 至今没有任何实现类，
        // 也就没有任何真实消费方。没有消费方就不许挂一个"有属性但永远失败"的假 API，
        // 也不许改成静默 no-op（那会让作者以为写进去了）。写回那条路等客户端把封好的记录
        // 递进来时再连同真实消费方一起挂（§16.5）。
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

            // balance 只能读自己（§22.5）。读别人是 currency.read.other，granted 档，本步不给。
            // 【不动钱】：它不受"本次求值已经动过钱"的限制 —— 那一条只管动钱的调用，
            // 而查余额没有任何副作用（读盘那一步由网关自己把关）。
            HostFn.put(cur, scope, "balance", 1, (c, s, a) -> {
                ICurrencyProvider prov = requireOrError(reg, strOrNull(a, 0, "currency.balance"));
                try {
                    return Amounts.toScript(prov.balance(player.uuid()));
                } catch (com.november.mcphone.core.script.server.economy.CurrencyUnavailableException e) {
                    // 抛脚本接得住的 Error，App 在 catch 里 ctx.fail('UNAVAILABLE')。
                    // 不返回 0 或 null：比大小时 null 也当 0，App 会告诉玩家他没钱。
                    // 不抛 ScriptAbort：那个接不住、还记过失，连着几次就把整个 App 熔断
                    throw org.mozilla.javascript.ScriptRuntime.constructError("Error", "UNAVAILABLE: " + e.reasonKey());
                } catch (ScriptAbort e) {
                    throw e;
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
                // 金额缺了或超出 long：和 pay 一样算"数不对"，抛接得住的 Error，不中断
                Long v = amountOrNull(a, 1, "currency.format");
                if (v == null) throw org.mozilla.javascript.ScriptRuntime.constructError("Error", "INVALID: " + INVALID_AMOUNT);
                return Balances.format(v, prov.currency().decimals()) + " " + prov.currency().symbol();
            });

            // parse 收字符串，回 BigInt。
            //
            // 【失败抛脚本接得住的错误，不返回 null】（S15h/S15i 任务 4，勘误 E35②）：
            // 原先 `catch (NumberFormatException) { return null; }`，而 null 会继续往下流 ——
            // `ctx.currency.pay(c, to, ctx.currency.parse(...))` 只是变成 INVALID（还好），
            // 但 `parse(...) > 0`、`parse(...) + 1` 这类表达式会把 null 当 0 用，
            // 于是"玩家输错了"变成"金额是 0"，静默地做了一件玩家没要求的事。
            // 现在它与 balance 的 UNAVAILABLE、format 的 INVALID 同一形态：Error + 码 + 说明。
            HostFn.put(cur, scope, "parse", 2, (c, s, a) -> {
                ICurrencyProvider prov = requireOrError(reg, strOrNull(a, 0, "currency.parse"));
                if (!HostFn.present(a, 1)) {
                    // 没给文本（null / undefined）：缺参数，不是"解析失败"，但同样不该给哨兵值
                    throw org.mozilla.javascript.ScriptRuntime.constructError("Error", "INVALID: " + INVALID_AMOUNT);
                }
                try {
                    return Amounts.toScript(Balances.parse(HostFn.str(a, 1, "currency.parse"), prov.currency().decimals()));
                } catch (NumberFormatException e) {
                    // 解析的多半是玩家输入：明确拒，App 在 catch 里 ctx.fail('INVALID_ARGUMENT')
                    throw org.mozilla.javascript.ScriptRuntime.constructError("Error", "INVALID: " + INVALID_AMOUNT);
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
            // 【不静默降级】：没批就明说没批，别让 App 以为成功了。
            // 它们同样算"动钱"：真接上能力表那天，标志那一条要一起生效，所以先把门放上
            for (String granted : new String[]{"mint", "burn"}) {
                HostFn.put(cur, scope, granted, 3, (c, s, a) -> {
                    ledger.rejectFurther("currency." + granted);
                    return TxnResult.NOT_AUTHORIZED.name();
                });
            }

            cur.sealObject();
            ScriptableObject.putProperty(ctx, "currency", cur);
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
            if (result.logs.size() < 32) result.logs.add(HostFn.str(a, 0, "ctx.log"));
            return Boolean.TRUE;
        });

        ctx.sealObject();
        return ctx;
    }

    /** 服务器上没有这种货币（多半是服主改了配置）：抛脚本接得住的 Error，不中断。 */
    private static ICurrencyProvider requireOrError(CurrencyRegistry reg, String id) {
        ICurrencyProvider p = reg.get(id);
        if (p == null) {
            throw org.mozilla.javascript.ScriptRuntime.constructError("Error", "UNAVAILABLE: " + NO_SUCH_CURRENCY);
        }
        return p;
    }

    /** 有结果的活儿。 */
    @FunctionalInterface
    private interface Call<T> {
        T get();
    }

    /** 没结果的活儿。 */
    @FunctionalInterface
    private interface Run {
        void run();
    }

    /**
     * 把后端抛出来的<b>宿主校验类</b>失败翻成脚本接得住的 {@link HostError}（S15h/S15i 任务 6）。
     *
     * <p>两条来源：
     * <ul>
     *   <li>{@link StoreQuota.QuotaExceeded} —— {@code KvBackend} 实现按配额拒（超长键、超大值、键数上限）。
     *       原先它一路裸着冒到 {@code RhinoEvaluator}，被当成"脚本内部错误"记一次过失 ——
     *       而玩家把背包塞满键是<b>正常玩法</b>，不是脚本写坏了。</li>
     *   <li>{@link ScriptAbort}(HOST/SIZE) —— {@link SharedState#check} 拒绝的那几条。</li>
     * </ul>
     *
     * <p>硬停止（预算、重入…）原样往外抛，不许在这里被吃掉。
     */
    private static HostError translate(Throwable t) {
        if (t instanceof StoreQuota.QuotaExceeded q) {
            return HostError.quota(q.getMessage());
        }
        if (t instanceof ScriptAbort abort) {
            HostError h = HostError.fromAbort(abort);
            if (h != null) return h;
        }
        return null;
    }

    private static <T> T translated(Call<T> body) {
        try {
            return body.get();
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            HostError h = translate(t);
            if (h != null) throw h;
            throw sneaky(t);
        }
    }

    private static void translated(Run body) {
        translated(() -> {
            body.run();
            return Boolean.TRUE;
        });
    }

    /**
     * 原样再抛一个不是宿主校验类的异常。
     *
     * <p>受检异常也能过：这些异常都是后端实现抛的，没有任何"调用方该处理它"的语义，
     * 而把它们包一层会破坏 {@link RhinoEvaluator} 对 {@link OutcomeUnknown} /
     * {@link ProviderError} 的 instanceof 判断。
     */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneaky(Throwable t) throws E {
        throw (E) t;
    }

    static final String NO_SUCH_CURRENCY = "mcphone.economy.no_such_currency";

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
                // 【provider 真的给了结果 = 这笔钱动过了】（E35③）：置上标志，
                // 于是本次求值之后不许再动第二笔，且最终结论不许被降级成 INTERNAL
                ledger.movedMoney();
                return r;
            }
            failure = null;
        } catch (ScriptAbort e) {
            // 【provider 自己抛的 ScriptAbort 不记过失】（E35⑤）：那是外部实现的行为，不是脚本自身行为。
            // 归因靠宿主这一层包出来的 {@link ProviderAbort}，不靠 message、也不靠"谁抛的"去猜；
            // 它仍然保持硬停止（RhinoException/Error 的血统），脚本 finally { return } 吞不掉它
            throw new ProviderAbort(what, appId, e);
        } catch (HostError e) {
            // 宿主自己的拒绝（比如"同一次求值不许再动第二笔"）不是结果不明：原样交给脚本
            throw e;
        } catch (Throwable e) {
            failure = e;
        }
        // provider 抛来的那个不可信（getMessage / getStackTrace 自己可能会炸）：日志与 cause 都只用替身
        Throwable standIn = null;
        String detail = "货币调用结果不明";
        try {
            standIn = failure == null ? null : ProviderFailure.of(failure);
            // 说明里每一段都会被 LogText.filter 过一遍才交给日志（E35⑤ 的日志过滤那一条）：
            // app / currencyId / other 都可能是第三方或脚本给的字符串
            detail = "货币调用结果不明：app=" + LogText.filter(appId)
                    + " 玩家=" + player
                    + " " + what + " " + LogText.filter(currencyId)
                    + " 对方或托管号=" + other + " 金额=" + (amount == null ? "-" : amount + "（最小单位）")
                    + " —— " + (failure == null ? "provider 没给结果（返回了 null）"
                            : "provider 抛了 " + LogText.filter(standIn.getMessage()))
                    + "，钱可能已经动了一半，请核对";
            com.november.mcphone.MCphone.LOGGER.error("[MCphone] ⚠ {}", detail, standIn);
        } catch (Throwable ignored) {
            // 栈溢出、内存不够时打不出来也别换掉原来那个错，更别变成脚本 finally 吞得掉的 RuntimeException
        }
        throw new OutcomeUnknown(detail, standIn);
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
