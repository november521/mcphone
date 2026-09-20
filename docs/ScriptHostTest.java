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

    static ExecutorService newMain() {
        return Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "fake-main");
            thread.setDaemon(true);
            return thread;
        });
    }

    public static void main(String[] args) throws Exception {
        denyAllViews();
        endToEndReachesEvaluator();
        manyInFlightExactlyOnce();
        messageKeysAreLocalizationKeys();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
