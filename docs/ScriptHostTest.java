package com.november.mcphone.core.script.server;

import com.november.mcphone.core.script.engine.AppScope;
import com.november.mcphone.core.script.engine.CtxBuilder;
import com.november.mcphone.core.script.engine.RhinoEvaluator;
import com.november.mcphone.core.script.engine.ScriptBudget;
import com.november.mcphone.core.script.engine.SharedState;
import com.november.mcphone.core.script.engine.StrikeTracker;
import com.november.mcphone.core.script.net.ScriptErrorCode;
import com.november.mcphone.core.script.net.ScriptProtocol;
import com.november.mcphone.core.script.net.ScriptRpc;
import com.november.mcphone.core.script.net.ScriptRpcResult;
import org.mozilla.javascript.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * S15g 的断言：脚本宿主接线（施工方案 §15.5）。
 *
 * <p>起不了服务器，所以 {@link ScriptHost#start(net.minecraft.server.MinecraftServer)} 本身不在
 * {@code docs/} 里跑（平台侧由三平台编译与生命周期接线覆盖）；这里装配的是与它逐件相同的一串——
 * 真 {@link AppScope} + 真 {@link RhinoEvaluator} + 真 {@link ScriptPipeline} + 真 {@code ScriptWorkers}，
 * 主线程用一条命名的假主线程充当。
 */
public class ScriptHostTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!Objects.equals(actual, expected)) failures.add(what + "  期望 " + expected + "，实际 " + actual);
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures.add(what);
    }

    static final UUID SERVER = UUID.nameUUIDFromBytes("s15g-server".getBytes());
    static final UUID P1 = UUID.nameUUIDFromBytes("s15g-p1".getBytes());

    static PlayerSnapshot snap(UUID id) {
        return new PlayerSnapshot(id, "tester", "minecraft:overworld", "survival", 0L);
    }

    static ScriptRpc rpc(long requestId, long epoch, String action) {
        return new ScriptRpc(ScriptProtocol.PROTOCOL, requestId, epoch, "example:app", "rev1", action, new byte[0], "d");
    }

    /** 什么都批准的部署表（只给测试用；生产是 {@link DenyAllDeployments}）。 */
    static DeploymentView allDeployed(String rev) {
        return new DeploymentView() {
            public boolean deployed(String appId) {
                return true;
            }

            public boolean hasAction(String appId, String actionId) {
                return true;
            }

            public String deployRev(String appId) {
                return rev;
            }
        };
    }

    static AppScope appWith(String js) {
        AppScope app = new AppScope("example:app", ScriptBudget.server(), Map.of());
        Context cx = app.budget().enterContext();
        try {
            cx.evaluateString(app.scope(cx), js, "app", 1, null);
        } finally {
            Context.exit();
        }
        return app;
    }

    // ================================================================ 两个视图：只做"空表即拒"

    static void denyAllViews() {
        DeploymentView d = new DenyAllDeployments();
        check(!d.deployed("example:app"), "空部署表：deployed 一律 false");
        check(!d.hasAction("example:app", "act"), "空部署表：hasAction 一律 false");
        eq(d.deployRev("example:app"), null, "空部署表：deployRev 为 null");

        AtomicInteger ran = new AtomicInteger();
        ActionEvaluator runner = (req, onDone) -> {
            ran.incrementAndGet();
            onDone.accept(ActionEvaluator.Outcome.ok(new byte[0], 0, List.of()));
            return true;
        };
        List<ScriptRpcResult> out = new ArrayList<>();
        ScriptPipeline p = new ScriptPipeline(SERVER, new IdempotencyLedger(System::currentTimeMillis),
                new ScriptRateLimiter(System::currentTimeMillis), new DenyAllDeployments(), new DenyAllAuthority(), runner);
        p.accept(rpc(1, p.newEpoch(P1), "act"), snap(P1), out::add);
        eq(out.get(0).code(), ScriptErrorCode.NOT_DEPLOYED, "空部署表：请求一律 NOT_DEPLOYED");
        eq(ran.get(), 0, "空部署表：求值器一次都没跑（两轴都不在就不建桶）");

        // 【对抗组 P1】生产里 newEpoch / forget 还没有调用点，epochs 表恒空 ⇒ 真实请求在 epoch
        // 这一档就被拒，根本走不到部署判定。这里故意【不喂 epoch】，钉住生产当前的真实返回码 ——
        // 别让"测试自己补上生产缺失的那一环"再无声发生。
        out.clear();
        p.accept(rpc(3, 12345L, "act"), snap(P1), out::add);
        eq(out.get(0).code(), ScriptErrorCode.INVALID_ARGUMENT, "没喂过 epoch：真实生产在 epoch 一档被拒");
        eq(out.get(0).messageKey(), ScriptPipeline.KEY_STALE_CONNECTION, "原因键是「过期连接」");
        eq(ran.get(), 0, "更走不到求值器");

        // 部署表放行、授权表空 → 求值跑了，但落地前重查没过，效果没发生
        out.clear();
        ScriptPipeline p2 = new ScriptPipeline(SERVER, new IdempotencyLedger(System::currentTimeMillis),
                new ScriptRateLimiter(System::currentTimeMillis), allDeployed("rev1"), new DenyAllAuthority(),
                (req, onDone) -> {
                    onDone.accept(ActionEvaluator.Outcome.ok(new byte[0], 1, List.of()));
                    return true;
                });
        p2.accept(rpc(2, p2.newEpoch(P1), "act"), snap(P1), out::add);
        eq(out.get(0).code(), ScriptErrorCode.NOT_AUTHORIZED, "空授权表：落地前重查没过 → NOT_AUTHORIZED");
    }

    // ================================================================ 端到端：真求值器

    /** 真 AppScope + 真 RhinoEvaluator + 真 ScriptPipeline + 真 worker：请求走到求值器并回 OK。 */
    static void endToEndReachesEvaluator() throws Exception {
        ScriptWorkers.start();
        ExecutorService main = newMain();
        try {
            StrikeTracker strikes = main.submit(() -> new StrikeTracker(System::currentTimeMillis)).get();
            AppScope app = appWith("var actions = { act: function (ctx) { ctx.ok({}) } }");
            RhinoEvaluator ev = new RhinoEvaluator(Map.of("example:app", app), strikes,
                    new CtxBuilder.Backends(new SharedState(), null, null, null, null, null), main::execute);
            ScriptPipeline p = new ScriptPipeline(SERVER, new IdempotencyLedger(System::currentTimeMillis),
                    new ScriptRateLimiter(System::currentTimeMillis), allDeployed("rev1"),
                    (player, appId, actionId) -> true, ev);

            CompletableFuture<ScriptRpcResult> done = new CompletableFuture<>();
            // accept 必须在拥有 StrikeTracker 的主线程上调（生产里就是服务端主线程）
            main.submit(() -> {
                p.accept(rpc(10, p.newEpoch(P1), "act"), snap(P1), done::complete);
                return null;
            }).get(20, TimeUnit.SECONDS);
            ScriptRpcResult r = done.get(20, TimeUnit.SECONDS);
            eq(r.code(), ScriptErrorCode.OK, "端到端：请求真的走到求值器并回 OK");

            app.discard();
        } finally {
            main.shutdownNow();
            ScriptWorkers.stop();
        }
    }

    /** 多条在飞：onDone 恰好一次、全部在主线程落地、逐条 OK。 */
    static void manyInFlightExactlyOnce() throws Exception {
        ScriptWorkers.start();
        ExecutorService main = newMain();
        try {
            StrikeTracker strikes = main.submit(() -> new StrikeTracker(System::currentTimeMillis)).get();
            AppScope app = appWith("var actions = { act: function (ctx) { ctx.ok({}) } }");
            RhinoEvaluator ev = new RhinoEvaluator(Map.of("example:app", app), strikes,
                    new CtxBuilder.Backends(new SharedState(), null, null, null, null, null), main::execute);
            AtomicLong t = new AtomicLong(1_000);
            ScriptPipeline p = new ScriptPipeline(SERVER, new IdempotencyLedger(t::get),
                    new ScriptRateLimiter(t::get), allDeployed("rev1"),
                    (player, appId, actionId) -> true, ev);

            final int n = 32;
            ConcurrentLinkedQueue<ScriptRpcResult> out = new ConcurrentLinkedQueue<>();
            AtomicInteger onMain = new AtomicInteger();
            // 有条 worker、管线只许主线程用：整段提交在假主线程上顺序做
            main.submit(() -> {
                long epoch = p.newEpoch(P1);
                for (int i = 0; i < n; i++) {
                    t.addAndGet(1000);           // 每条隔一秒，避开动作桶的突发上限（5）
                    p.accept(rpc(i + 1, epoch, "act"), snap(P1), res -> {
                        if (Thread.currentThread().getName().equals("fake-main")) onMain.incrementAndGet();
                        out.add(res);
                    });
                }
                return null;
            }).get(30, TimeUnit.SECONDS);

            long deadline = System.nanoTime() + 30_000_000_000L;
            while (out.size() < n && System.nanoTime() < deadline) Thread.sleep(5);
            eq(out.size(), n, n + " 条在飞全部拿到 onDone（实际 " + out.size() + "，一条都不许丢）");
            eq(onMain.get(), n, "全部在主线程（fake-main）上落地");
            for (ScriptRpcResult r : out) eq(r.code(), ScriptErrorCode.OK, "每一条都 OK");

            app.discard();
        } finally {
            main.shutdownNow();
            ScriptWorkers.stop();
        }
    }

    /** E30③：脚本侧能拿到的原因是本地化键，不是自由文本。 */
    static void messageKeysAreLocalizationKeys() {
        for (ScriptErrorCode c : ScriptErrorCode.values()) {
            check(c.defaultMessageKey().startsWith("mcphone."), c + " 的默认文案是本地化键：" + c.defaultMessageKey());
        }
    }

    /** S17 Stage 1：服务器身份存【存档】、往返不变；旧存档缺这一段时生成新的并标脏。 */
    static void serverIdentityRoundTrip() {
        ServerIdentity a = new ServerIdentity();
        net.minecraft.nbt.CompoundTag tag = a.write(new net.minecraft.nbt.CompoundTag());
        eq(ServerIdentity.load(tag).id(), a.id(), "身份从存档往返不变");
        check(ServerIdentity.FILE_NAME.contains("server_identity"), "文件名落在存档数据里：" + ServerIdentity.FILE_NAME);

        ServerIdentity fresh = ServerIdentity.load(new net.minecraft.nbt.CompoundTag());
        check(fresh.id() != null, "旧存档缺这一段时生成新身份");
        check(fresh.isDirty(), "新身份标脏，下次世界保存时落盘");
    }

    static ExecutorService newMain() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "fake-main");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** S17：AppScope 的入口求值 + 宿主实现的 {@code require}（模块化 server.js）。 */
    static void appScopeEntryAndRequire() {
        Map<String, String> modules = new java.util.LinkedHashMap<>();
        modules.put("lib.js", "42");
        modules.put("pkg/tool.js", "require('../lib.js') + 1");
        modules.put(AppScope.ENTRY,
                "var a = require('./lib.js');"
                        + "var b = require('./pkg/tool.js');"
                        + "var esc = 0; try { require('../evil.js') } catch (e) { esc = 1 }"
                        + "var actions = { act: function (ctx) { ctx.ok({}) } };");
        AppScope app = new AppScope("example:app", ScriptBudget.server(), modules);
        Context cx = app.budget().enterContext();
        try {
            var scope = app.scope(cx);
            eq(number(scope, "a"), 42.0, "入口 require 到模块导出");
            eq(number(scope, "b"), 43.0, "模块里再 require（相对当前模块解析）");
            eq(number(scope, "esc"), 1.0, "require('../…') 弹出包根被拒，且脚本接得住（HostError）");
            check(app.actions(cx) != null, "入口求值定义了 actions 表");
            app.discard();
        } finally {
            Context.exit();
        }
    }

    /** S17：落地前重查被拒 —— 没动钱回 NOT_AUTHORIZED，动过钱回 UNKNOWN（E35③）。 */
    static void landingDeniedAfterMoneyMovedIsUnknown() {
        AtomicLong t = new AtomicLong(1_000);
        List<ScriptRpcResult> moved = new ArrayList<>();
        ScriptPipeline p = new ScriptPipeline(SERVER, new IdempotencyLedger(t::get), new ScriptRateLimiter(t::get),
                allDeployed("rev1"), (player, appId, actionId) -> false,
                (req, onDone) -> {
                    onDone.accept(ActionEvaluator.Outcome.ok(new byte[0], 1, List.of()).withMoneyMoved());
                    return true;
                });
        p.accept(rpc(9, p.newEpoch(P1), "act"), snap(P1), moved::add);
        eq(moved.get(0).code(), ScriptErrorCode.UNKNOWN, "钱已动 + 落地前被拒 → UNKNOWN，不是 NOT_AUTHORIZED");

        List<ScriptRpcResult> untouched = new ArrayList<>();
        ScriptPipeline p2 = new ScriptPipeline(SERVER, new IdempotencyLedger(t::get), new ScriptRateLimiter(t::get),
                allDeployed("rev1"), (player, appId, actionId) -> false,
                (req, onDone) -> {
                    onDone.accept(ActionEvaluator.Outcome.ok(new byte[0], 1, List.of()));
                    return true;
                });
        p2.accept(rpc(10, p2.newEpoch(P1), "act"), snap(P1), untouched::add);
        eq(untouched.get(0).code(), ScriptErrorCode.NOT_AUTHORIZED, "没动钱 + 落地前被拒 → NOT_AUTHORIZED（对照）");
    }

    /** S17 约束 3：主线程执行器在停服时拒绝投递 —— onDone 不许丢、账本那条 RESERVED 必须结清。 */
    static void dispatchFailureStillCompletes() throws Exception {
        ScriptWorkers.start();
        try {
            StrikeTracker strikes = new StrikeTracker(System::currentTimeMillis);   // 本线程就是 owner
            AppScope app = appWith("var actions = { act: function (ctx) { ctx.ok({}) } }");
            RhinoEvaluator ev = new RhinoEvaluator(Map.of("example:app", app), strikes,
                    new CtxBuilder.Backends(new SharedState(), null, null, null, null, null),
                    r -> {
                        throw new java.util.concurrent.RejectedExecutionException("服务器正在停");
                    });
            AtomicLong t = new AtomicLong(1_000);
            IdempotencyLedger ledger = new IdempotencyLedger(t::get);
            ScriptPipeline p = new ScriptPipeline(SERVER, ledger, new ScriptRateLimiter(t::get), allDeployed("rev1"),
                    (player, appId, actionId) -> true, ev);
            CompletableFuture<ScriptRpcResult> done = new CompletableFuture<>();
            p.accept(rpc(77, p.newEpoch(P1), "act"), snap(P1), done::complete);
            ScriptRpcResult r = done.get(20, TimeUnit.SECONDS);
            eq(r.code(), ScriptErrorCode.OK, "投递失败时用真实结论降级落地，onDone 不许丢（不是 INTERNAL）");

            byte[] key = IdempotencyKey.of(SERVER, P1, "example:app", "rev1", "act", 77);
            check(ledger.check(P1, key, IdempotencyKey.digestOf(new byte[0]))
                    instanceof IdempotencyLedger.Verdict.Replay, "那条 RESERVED 已结清，不本局永久挂着");
            check(ScriptWorkers.running(), "worker 没被打死");
            app.discard();
        } finally {
            ScriptWorkers.stop();
        }
    }

    /** M2/C7/C12：后端模块谓词 —— 前端 js 与非 .js 都不进服务端模块表。 */
    static void backendModulePredicate() {
        check(ServerAppAssembler.isBackendModule("server.js"), "server.js 是后端");
        check(ServerAppAssembler.isBackendModule("server/util.js"), "server/** 的 js 是后端");
        check(!ServerAppAssembler.isBackendModule("server/readme.txt"), "server/** 下的非 .js 不是模块（C12）");
        check(!ServerAppAssembler.isBackendModule("ui/app.js"), "前端 js 不是后端（M2）");
        check(!ServerAppAssembler.isBackendModule("app.js"), "顶层前端 js 不是后端");
        check(!ServerAppAssembler.isBackendModule(null), "null 不是");
    }

    /** require 绑定只读：脚本给自己赋值改不动它（否则一个 App 能把自己弄坏）。 */
    static void requireIsReadOnly() {
        Map<String, String> modules = new java.util.LinkedHashMap<>();
        modules.put("lib.js", "7");
        modules.put(AppScope.ENTRY,
                "var before = require('./lib.js');"
                        + "require = 1;"
                        + "var after = require('./lib.js');"
                        + "var actions = { act: function (ctx) { ctx.ok({}) } };");
        AppScope app = new AppScope("example:app", ScriptBudget.server(), modules);
        Context cx = app.budget().enterContext();
        try {
            var scope = app.scope(cx);
            eq(number(scope, "before"), 7.0, "改之前 require 能用");
            eq(number(scope, "after"), 7.0, "给 require 赋值改不动绑定（只读）");
            app.discard();
        } finally {
            Context.exit();
        }
    }

    static double number(org.mozilla.javascript.ScriptableObject scope, String name) {
        Object v = org.mozilla.javascript.ScriptableObject.getProperty(scope, name);
        return v instanceof Number n ? n.doubleValue() : Double.NaN;
    }

    public static void main(String[] args) throws Exception {
        denyAllViews();
        endToEndReachesEvaluator();
        manyInFlightExactlyOnce();
        messageKeysAreLocalizationKeys();
        serverIdentityRoundTrip();
        appScopeEntryAndRequire();
        landingDeniedAfterMoneyMovedIsUnknown();
        dispatchFailureStillCompletes();
        backendModulePredicate();
        requireIsReadOnly();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
