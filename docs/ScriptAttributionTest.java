package com.november.mcphone.core.script.engine;

import com.november.mcphone.core.script.net.ScriptErrorCode;
import com.november.mcphone.core.script.server.ActionEvaluator;
import com.november.mcphone.core.script.server.ScriptWorkers;
import com.november.mcphone.core.script.server.store.KvBackend;
import com.november.mcphone.core.script.server.store.StoreQuota;
import org.mozilla.javascript.Context;

import java.math.BigInteger;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/** Focused S15h/S15i attribution, thread ownership, and completion-path regressions. */
public final class ScriptAttributionTest {

    private static int checks;
    private static final List<String> failures = new ArrayList<>();

    private static void check(boolean value, String message) {
        checks++;
        if (!value) failures.add(message);
    }

    private static void eq(Object actual, Object expected, String message) {
        check(java.util.Objects.equals(actual, expected), message + " expected=" + expected + " actual=" + actual);
    }

    private static CtxBuilder.Backends backends(KvBackend store) {
        return new CtxBuilder.Backends(new SharedState(), ScriptEngineTest.fakeItems(),
                new CtxBuilder.Cycle(ZoneId.of("Asia/Shanghai"), LocalTime.of(4, 0)), store, null, null);
    }

    private static void exactLongAndNoWrite() {
        eq(ExactLong.of(BigInteger.valueOf(Long.MIN_VALUE), "t"), Long.MIN_VALUE, "Long.MIN_VALUE");
        eq(ExactLong.of(BigInteger.valueOf(Long.MAX_VALUE), "t"), Long.MAX_VALUE, "Long.MAX_VALUE");
        eq(ExactLong.of(ExactLong.JS_SAFE_INT_MAX, "t"), ExactLong.JS_SAFE_INT_MAX, "safe integer max");
        for (Object value : List.of(
                BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
                BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE),
                1.5d, Double.NaN, Double.POSITIVE_INFINITY,
                (double) ExactLong.JS_SAFE_INT_MAX + 1d, "1")) {
            try {
                ExactLong.of(value, "store.setLong");
                check(false, "invalid exact-long value was accepted: " + value);
            } catch (HostError expected) {
                eq(HostError.classify(expected), HostError.INVALID, "exact-long host attribution");
            }
        }

        Map<String, String> writes = new java.util.concurrent.ConcurrentHashMap<>();
        KvBackend store = new KvBackend() {
            public String getString(String appId, String key) { return writes.get(key); }
            public void setString(String appId, String key, String value) { writes.put(key, value); }
            public void remove(String appId, String key) { writes.remove(key); }
            public List<String> keys(String appId) { return List.copyOf(writes.keySet()); }
        };
        eq(ScriptEngineTest.withCtx("ctx.store.setLong('ok', 9223372036854775807n)", backends(store)),
                "true", "valid BigInt setLong");
        eq(writes.get("ok"), Long.toString(Long.MAX_VALUE), "exact value persisted");
        eq(ScriptEngineTest.withCtx("try { ctx.store.setLong('bad', 1.25) } catch (e) { e.message.split(':')[0] }",
                backends(store)), "INVALID", "fraction is catchable");
        check(!writes.containsKey("bad"), "rejected setLong must not write");
    }

    private static void catchableHostFailures() {
        CtxBuilder.Backends basic = backends(null);
        eq(ScriptEngineTest.withCtx("try { ctx.cycle.label('yearly') } catch(e) { e.message.split(':')[0] }", basic),
                "UNKNOWN_VALUE", "unknown cycle is catchable");
        eq(ScriptEngineTest.withCtx("var out='before'; try { ctx.cycle.label('yearly') }"
                        + " catch(e) { out='continued' } out", basic),
                "continued", "catchable host failure may continue safely");
        eq(HostError.classify(new IllegalArgumentException("INVALID: forged")), null,
                "same-message Java exception cannot forge attribution");
        eq(ScriptEngineTest.withCtx("var e=new Error('INVALID: forged'); String(e.message)", basic),
                "INVALID: forged", "script may copy text without obtaining host attribution");

        Map<String, String> writes = new java.util.concurrent.ConcurrentHashMap<>();
        KvBackend quotaStore = new KvBackend() {
            public String getString(String appId, String key) { return writes.get(key); }
            public void setString(String appId, String key, String value) {
                throw new StoreQuota.QuotaExceeded("full");
            }
            public void remove(String appId, String key) { writes.remove(key); }
            public List<String> keys(String appId) { return List.copyOf(writes.keySet()); }
        };
        eq(ScriptEngineTest.withCtx("try { ctx.store.setString('k','v') } catch(e) { e.message.split(':')[0] }",
                backends(quotaStore)), "QUOTA", "store quota is catchable");
        check(writes.isEmpty(), "quota rejection has no partial write");

        SharedState shared = new SharedState();
        for (int i = 0; i < SharedState.MAX_KEYS_PER_APP; i++) {
            shared.set("t:quota", "k" + i, "v");
        }
        try {
            shared.set("t:quota", "overflow", "v");
            check(false, "shared key quota should reject");
        } catch (HostError quota) {
            eq(HostError.classify(quota), HostError.QUOTA, "shared key quota attribution");
        }
        eq(shared.get("t:quota", "overflow"), null, "shared quota rejection has no partial write");

        var sealed = new com.november.mcphone.core.script.server.store.SealedBackend() {
            public void put(String appId, String key,
                            com.november.mcphone.core.script.server.store.SealedRecord record) { }
            public com.november.mcphone.core.script.server.store.SealedRecord get(String appId, String key) {
                return null;
            }
        };
        CtxBuilder.Backends sealedBackends = new CtxBuilder.Backends(new SharedState(),
                ScriptEngineTest.fakeItems(), new CtxBuilder.Cycle(ZoneId.of("Asia/Shanghai"),
                LocalTime.of(4, 0)), null, sealed, null);
        eq(ScriptEngineTest.withCtx("typeof ctx.sealed.put", sealedBackends), "undefined",
                "sealed.put is not exposed without a real consumer");
        eq(ScriptEngineTest.withCtx("typeof ctx.sealed.get", sealedBackends), "function",
                "sealed.get remains available");

        String filtered = LogText.filter("a\u2028b\u2029c\u202Ed§e\nf");
        check(!filtered.contains("\u2028") && !filtered.contains("\u2029")
                        && !filtered.contains("\u202E") && !filtered.contains("§")
                        && !filtered.contains("\n"),
                "untrusted log text has no raw line, bidi, or Minecraft formatting controls");
        check(filtered.contains("\\u2028") && filtered.contains("\\u2029")
                        && filtered.contains("\\u202E") && filtered.contains("\\u00A7"),
                "dangerous log characters remain visible as escapes");

        String formats = "\u061C\u200E\u200F\u202A\u202B\u202C\u202D\u2066\u2067\u2068\u2069"
                + "\u200B\u200C\u200D\u2060\uFEFF\u00AD\u180E";
        String formatFiltered = LogText.filter(formats);
        check(formats.chars().noneMatch(c -> formatFiltered.indexOf(c) >= 0),
                "all bidi and invisible format controls are escaped");

        Throwable hugeStack = new RuntimeException("bad\u202Dmessage") {
            @Override public StackTraceElement[] getStackTrace() {
                StackTraceElement[] frames = new StackTraceElement[1000];
                for (int i = 0; i < frames.length; i++) {
                    frames[i] = new StackTraceElement("Provider", "call", "Provider.java", i + 1);
                }
                return frames;
            }
        };
        var bounded = com.november.mcphone.core.script.server.economy.ProviderFailure.of(hugeStack);
        eq(bounded.getStackTrace().length,
                com.november.mcphone.core.script.server.economy.ProviderFailure.MAX_STACK_FRAMES,
                "provider stack trace is bounded before logging");
        check(!bounded.getMessage().contains("\u202D") && bounded.getMessage().contains("\\u202D"),
                "provider message uses the shared log sanitizer");
    }

    private static void concurrencyRegressions() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            AppScope app = new AppScope("t:race", ScriptBudget.server(), Map.of());
            CountDownLatch start = new CountDownLatch(1);
            java.util.concurrent.Callable<org.mozilla.javascript.ScriptableObject> getScope = () -> {
                start.await();
                Context cx = app.budget().enterContext();
                try {
                    return app.scope(cx);
                } finally {
                    Context.exit();
                }
            };
            var scopeA = pool.submit(getScope);
            var scopeB = pool.submit(getScope);
            start.countDown();
            check(scopeA.get(10, TimeUnit.SECONDS) == scopeB.get(10, TimeUnit.SECONDS),
                    "concurrent first use publishes exactly one App scope");

            ScriptModules modules = new ScriptModules(Map.of("util.js", ""));
            CountDownLatch loaderEntered = new CountDownLatch(1);
            CountDownLatch loaderRelease = new CountDownLatch(1);
            AtomicInteger loads = new AtomicInteger();
            var first = pool.submit(() -> modules.require("./util.js", "server.js", (name, source) -> {
                loads.incrementAndGet();
                loaderEntered.countDown();
                try {
                    if (!loaderRelease.await(10, TimeUnit.SECONDS)) throw new AssertionError("loader wait timed out");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
                return "exports";
            }));
            check(loaderEntered.await(10, TimeUnit.SECONDS), "first module load entered");
            var second = pool.submit(() -> modules.require("./util.js", "server.js", (name, source) -> {
                loads.incrementAndGet();
                return "duplicate";
            }));
            loaderRelease.countDown();
            eq(first.get(10, TimeUnit.SECONDS), "exports", "first module result");
            eq(second.get(10, TimeUnit.SECONDS), "exports", "concurrent require shares cache");
            eq(loads.get(), 1, "module is loaded once under concurrency");
            eq(modules.depth(), 0, "module stack balances after concurrent load");

            SharedState shared = new SharedState();
            java.util.Set<String> seen = java.util.concurrent.ConcurrentHashMap.newKeySet();
            AtomicBoolean writing = new AtomicBoolean(true);
            var writer = pool.submit(() -> {
                for (int i = 0; i < 3000; i++) shared.set("t:dirty", "k" + i, "v");
                writing.set(false);
            });
            while (writing.get()) {
                shared.drainDirty().values().forEach(seen::addAll);
            }
            writer.get(10, TimeUnit.SECONDS);
            shared.drainDirty().values().forEach(seen::addAll);
            eq(seen.size(), 3000, "concurrent drainDirty loses no committed key");
        } finally {
            pool.shutdownNow();
        }
    }

    private static void trackerOwnershipAndStress() throws Exception {
        ExecutorService main = Executors.newSingleThreadExecutor(r -> new Thread(r, "fake-main"));
        ExecutorService producers = Executors.newFixedThreadPool(4);
        try {
            StrikeTracker tracker = main.submit(() -> new StrikeTracker(System::currentTimeMillis)).get();
            CompletableFuture<Boolean> wrongThread = new CompletableFuture<>();
            Thread probe = new Thread(() -> {
                try {
                    tracker.allowed("t:app", UUID.randomUUID());
                    wrongThread.complete(false);
                } catch (IllegalStateException expected) {
                    wrongThread.complete(true);
                }
            }, "probe-worker");
            probe.start();
            check(wrongThread.get(5, TimeUnit.SECONDS), "worker access to StrikeTracker is rejected");

            int total = 10_000;
            CountDownLatch landed = new CountDownLatch(total);
            AtomicInteger seen = new AtomicInteger();
            for (int i = 0; i < total; i++) {
                int n = i;
                producers.execute(() -> main.execute(() -> {
                    UUID player = new UUID(0, n % 25 + 1L);
                    String app = "t:app" + (n % 4);
                    if ((n & 3) == 0) tracker.recordAbort(app, player);
                    else tracker.recordOk(app, player);
                    seen.incrementAndGet();
                    landed.countDown();
                }));
            }
            check(landed.await(30, TimeUnit.SECONDS), "10k interleaved events completed");
            eq(seen.get(), total, "10k main-thread dispositions had no lost update");
        } finally {
            producers.shutdownNow();
            main.shutdownNow();
        }
    }

    private static AppScope app(String id, String source) {
        AppScope app = new AppScope(id, ScriptBudget.server(), Map.of());
        Context cx = app.budget().enterContext();
        try {
            cx.evaluateString(app.scope(cx), source, id, 1, null);
            return app;
        } finally {
            Context.exit();
        }
    }

    private static ActionEvaluator.Outcome call(ExecutorService main, RhinoEvaluator evaluator,
                                                 String appId, String action, AtomicInteger callbacks) throws Exception {
        CompletableFuture<ActionEvaluator.Outcome> done = new CompletableFuture<>();
        boolean accepted = main.submit(() -> evaluator.submit(new ActionEvaluator.Request(
                appId, action, new byte[0], ScriptEngineTest.player(), "r", 1), outcome -> {
            callbacks.incrementAndGet();
            done.complete(outcome);
        })).get();
        check(accepted, "evaluation accepted");
        return done.get(15, TimeUnit.SECONDS);
    }

    private static void retainedSweepCannotKillWorker() throws Exception {
        ExecutorService main = Executors.newSingleThreadExecutor(r -> new Thread(r, "fake-main"));
        ScriptWorkers.start();
        try {
            AppScope bomb = app("t:bomb", "var actions={go:function(ctx){ctx.ok({})}};"
                    + "Object.defineProperty(this,'bomb',{enumerable:true,get:function(){throw new Error('getter bomb')}})");
            AppScope healthy = app("t:healthy", "var actions={go:function(ctx){ctx.ok({})}}");
            AppScope invalid = app("t:invalid", "var actions={go:function(ctx){ctx.cycle.label('yearly')}}");
            AppScope forged = app("t:forged", "var actions={go:function(ctx){throw new Error('INVALID: forged')}}");
            StrikeTracker tracker = main.submit(() -> new StrikeTracker(System::currentTimeMillis)).get();
            RhinoEvaluator evaluator = new RhinoEvaluator(Map.of("t:bomb", bomb, "t:healthy", healthy,
                    "t:invalid", invalid, "t:forged", forged), tracker,
                    backends(null), main::execute);
            AtomicInteger callbacks = new AtomicInteger();
            eq(call(main, evaluator, "t:bomb", "go", callbacks).code(), ScriptErrorCode.OK,
                    "getter-bomb request still completes");
            eq(call(main, evaluator, "t:healthy", "go", callbacks).code(), ScriptErrorCode.OK,
                    "worker continues serving another app");
            for (int i = 0; i < 3; i++) {
                eq(call(main, evaluator, "t:invalid", "go", callbacks).code(), ScriptErrorCode.INTERNAL,
                        "uncaught host validation is rejected");
            }
            check(main.submit(() -> tracker.allowed("t:invalid", ScriptEngineTest.player().uuid())).get(),
                    "uncaught host validation does not strike");
            for (int i = 0; i < 3; i++) {
                eq(call(main, evaluator, "t:forged", "go", callbacks).code(), ScriptErrorCode.INTERNAL,
                        "forged same-message script error stays a script error");
            }
            check(!main.submit(() -> tracker.allowed("t:forged", ScriptEngineTest.player().uuid())).get(),
                    "third forged script error is already banned before onDone completes");
            eq(callbacks.get(), 8, "onDone exactly once per accepted request");
        } finally {
            ScriptWorkers.stop();
            main.shutdownNow();
        }
    }

    public static void main(String[] args) throws Exception {
        exactLongAndNoWrite();
        catchableHostFailures();
        concurrencyRegressions();
        trackerOwnershipAndStress();
        retainedSweepCannotKillWorker();
        System.out.println("S15h/S15i assertions: " + checks);
        if (!failures.isEmpty()) {
            failures.forEach(f -> System.out.println(" - " + f));
            throw new AssertionError(failures.size() + " assertions failed");
        }
    }
}
