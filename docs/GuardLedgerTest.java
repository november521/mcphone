package com.november.mcphone.core.script.server;

import com.november.mcphone.core.script.net.ScriptErrorCode;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** §20 的抢占、固定顺序与真实持久化恢复。真服 TPS/强杀仍见测试任务。 */
public final class GuardLedgerTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++; if (!ok) throw new AssertionError(message);
    }
    private static void denied(Runnable task, String message) {
        boolean rejected = false;
        try { task.run(); } catch (IllegalArgumentException | IllegalStateException expected) { rejected = true; }
        check(rejected, message);
    }
    private static ActionGuards parse(String guards) {
        return ActionGuards.parse("{\"actions\":[{\"id\":\"claim\",\"guards\":" + guards + "}]}").get("claim");
    }
    private static byte[] key(UUID player, long id) {
        return IdempotencyKey.of(new UUID(0, 1), player, "test:gift", "rev", "claim", id);
    }
    private static void orderAndCooldown() {
        AtomicLong now = new AtomicLong(999); int[] predicateCalls = {0};
        GuardController controller = new GuardController(now::get, (predicate, player) -> { predicateCalls[0]++; return false; });
        ActionGuards guards = parse("[{\"window\":{\"startAt\":1000,\"endAt\":2000}},{\"predicate\":\"test:vip\"},"
                + "{\"limit\":20,\"scope\":\"global\",\"label\":\"week\"}]");
        UUID player = new UUID(0, 2);
        for (int i = 0; i < 1000; i++) check(controller.reserve("before" + i, player, "app", "claim", guards).code()
                == ScriptErrorCode.NOT_STARTED, "抢跑应拒绝");
        check(predicateCalls[0] == 0 && controller.counters().isEmpty(), "窗口失败不得查谓词或占份额");
        now.set(1000); check(controller.reserve("predicate", player, "app", "claim", guards).code()
                == ScriptErrorCode.NOT_AUTHORIZED, "谓词失败应拒绝");
        check(controller.counters().isEmpty(), "谓词失败不消耗配额");
        controller = new GuardController(now::get, (predicate, uuid) -> true);
        ActionGuards once = parse("[{\"once\":true},{\"limit\":20,\"scope\":\"global\",\"label\":\"week\"}]");
        check(controller.reserve("once", player, "app", "claim", once).allowed(), "首次领取");
        check(controller.reserve("again", player, "app", "claim", once).code() == ScriptErrorCode.IN_PROGRESS, "在飞重复点击不占份额");
        controller.finish("once", ScriptErrorCode.OK);
        for (int i = 0; i < 50; i++) check(controller.reserve("repeat" + i, player, "app", "claim", once).code()
                == ScriptErrorCode.COOLDOWN, "once 不因新请求号而重领");
        now.set(0); check(controller.reserve("rollback-clock", player, "app", "claim", once).code()
                == ScriptErrorCode.COOLDOWN, "服务器时间回拨不能重领");
    }
    private static void concurrent500() throws Exception {
        GuardController controller = new GuardController(() -> 1000L, (predicate, player) -> true);
        ActionGuards guards = parse("[{\"limit\":20,\"scope\":\"global\",\"label\":\"week\"}]");
        ExecutorService arrivals = Executors.newFixedThreadPool(16);
        ExecutorService mainThread = Executors.newSingleThreadExecutor();
        List<Future<ScriptErrorCode>> requests = new ArrayList<>();
        try {
            for (int i = 0; i < 500; i++) {
                final int id = i;
                requests.add(arrivals.submit(() -> mainThread.submit(() -> {
                    var decision = controller.reserve("request" + id, new UUID(0, id), "app", "claim", guards);
                    if (decision.allowed()) controller.finish("request" + id, ScriptErrorCode.OK);
                    return decision.code();
                }).get()));
            }
            int ok = 0, exhausted = 0;
            for (Future<ScriptErrorCode> request : requests) {
                ScriptErrorCode code = request.get(10, TimeUnit.SECONDS);
                if (code == ScriptErrorCode.OK) ok++; if (code == ScriptErrorCode.EXHAUSTED) exhausted++;
            }
            check(ok == 20 && exhausted == 480, "500 抢 20：成功数与拒绝数");
            check(controller.counters().values().stream().mapToLong(Long::longValue).sum() == 20, "最终计数 = 20");
            System.out.println("500 requests: OK=" + ok + ", EXHAUSTED=" + exhausted + ", count=20");
            ActionGuards nextWeek = parse("[{\"limit\":20,\"scope\":\"global\",\"label\":\"nextweek\"}]");
            check(controller.reserve("nextweek", new UUID(1, 1), "app", "claim", nextWeek).allowed(), "换 label 开新一轮");
        } finally { arrivals.shutdownNow(); mainThread.shutdownNow(); }
    }
    private static void recovery() throws Exception {
        Path dir = Files.createTempDirectory("mcphone-guard-ledger-"); Path file = dir.resolve("global.dat");
        AtomicLong now = new AtomicLong(1000); UUID player = new UUID(0, 12);
        ActionGuards guards = parse("[{\"once\":true},{\"limit\":2,\"scope\":\"global\",\"label\":\"week\"}]");
        byte[] digest = IdempotencyKey.digestOf(new byte[0]), key = key(player, 1);
        var ledger = new IdempotencyLedger(now::get); var controller = new GuardController(now::get, (p, u) -> true);
        var data = ScriptStateData.open(file); ledger.onChanged(() -> data.commit(ledger, controller));
        check(controller.reserve(IdempotencyKey.hex(key), player, "app", "claim", guards).allowed(), "预留成功");
        ledger.reserve(player, key, digest);
        var afterReserved = new IdempotencyLedger(now::get); var guardsReserved = new GuardController(now::get, (p, u) -> true);
        ScriptStateData.open(file).restore(afterReserved, guardsReserved);
        check(guardsReserved.counters().isEmpty(), "重启 RESERVED 回滚份额");
        check(afterReserved.snapshot().get(player).get(IdempotencyKey.hex(key)).state() == IdempotencyLedger.State.FAILED, "RESERVED 恢复为 FAILED");
        ledger.effectStarted(player, key);
        var afterStarted = new IdempotencyLedger(now::get); var guardsStarted = new GuardController(now::get, (p, u) -> true);
        ScriptStateData.open(file).restore(afterStarted, guardsStarted);
        check(guardsStarted.counters().values().iterator().next() == 1, "开始执行的份额不回滚");
        check(afterStarted.check(player, key, digest) instanceof IdempotencyLedger.Verdict.Replay r && r.code() == ScriptErrorCode.UNKNOWN,
                "EFFECT_STARTED 重启回 UNKNOWN");
        check(guardsStarted.reserve("new-request", player, "app", "claim", guards).code() == ScriptErrorCode.IN_PROGRESS, "换请求号也不能绕过待核对资格");
        now.addAndGet(IdempotencyLedger.TTL_MS * 10); afterStarted.sweep();
        check(afterStarted.size(player) == 1, "UNKNOWN 不按 TTL 自动删除");
        controller.finish(IdempotencyKey.hex(key), ScriptErrorCode.OK); ledger.settle(player, key, ScriptErrorCode.OK, new byte[]{3}, 0, 1);
        var afterSuccess = new IdempotencyLedger(now::get); var guardsSuccess = new GuardController(now::get, (p, u) -> true);
        ScriptStateData.open(file).restore(afterSuccess, guardsSuccess);
        check(afterSuccess.check(player, key, digest) instanceof IdempotencyLedger.Verdict.Replay r && r.code() == ScriptErrorCode.OK, "成功结果跨重启重放");
        check(guardsSuccess.reserve("reinstall", player, "app", "claim", guards).code() == ScriptErrorCode.COOLDOWN, "重装/重连不清 once");
        Files.writeString(file, "{\"version\":1,\"broken\":");
        denied(() -> ScriptStateData.open(file), "损坏快照不能自动空账");
        Files.delete(file); Files.delete(dir);
    }
    public static void main(String[] args) throws Exception {
        denied(() -> parse("[{\"cost\":{\"item\":\"minecraft:diamond\",\"count\":1}}]"), "cost 首版必须拒绝");
        denied(() -> parse("[{\"limit\":1.5,\"label\":\"week\"}]"), "限量不接受小数");
        denied(() -> parse("[{\"window\":{\"startAt\":3,\"endAt\":2}}]"), "坏窗口拒绝");
        denied(() -> ActionGuards.parse("{\"actions\":[\"a\",\"a\"]}"), "重复动作拒绝");
        orderAndCooldown(); concurrent500(); recovery();
        System.out.println("GuardLedgerTest: " + checks + " checks passed");
    }
}
