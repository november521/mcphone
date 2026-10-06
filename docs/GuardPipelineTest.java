package com.november.mcphone.core.script.server;

import com.november.mcphone.core.script.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** 走真实 ScriptPipeline；守卫拒绝时 worker、账本、落地端都不应被调用。 */
public final class GuardPipelineTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static ActionGuards limit() {
        return ActionGuards.parse("{\"actions\":[{\"id\":\"claim\",\"guards\":[{\"limit\":20,\"scope\":\"global\",\"label\":\"week\"}]}]}").get("claim");
    }
    private static DeploymentView deployed(ActionGuards rules) {
        return new DeploymentView() {
            public boolean deployed(String app) { return "test:app".equals(app); }
            public boolean hasAction(String app, String action) { return "claim".equals(action); }
            public String deployRev(String app) { return "rev"; }
            public ActionGuards guards(String app, String action) { return rules; }
        };
    }
    private static PlayerSnapshot snapshot(UUID player) { return new PlayerSnapshot(player, "tester", "minecraft:overworld", "survival", 0); }
    private static ScriptRpc rpc(UUID player, long epoch, long request, String action) {
        return new ScriptRpc(ScriptProtocol.PROTOCOL, request, epoch, "test:app", "rev", action, new byte[0], "");
    }
    public static void main(String[] args) {
        AtomicLong now = new AtomicLong(1000); UUID server = new UUID(1, 2);
        IdempotencyLedger ledger = new IdempotencyLedger(now::get);
        GuardController guards = new GuardController(now::get, (p, u) -> true);
        int[] evaluations = {0}, journals = {0};
        ledger.onChanged(() -> {
            journals[0]++;
            long reserved = ledger.snapshot().values().stream().flatMap(m -> m.values().stream())
                    .filter(e -> e.state() != IdempotencyLedger.State.FAILED).count();
            long count = guards.counters().values().stream().mapToLong(Long::longValue).sum();
            check(count == reserved, "配额和账本在每次持久化边界相符");
        });
        ActionEvaluator evaluate = (request, done) -> {
            request.beginEffects().run(); evaluations[0]++;
            done.accept(ActionEvaluator.Outcome.ok(new byte[0], 0, List.of())); return true;
        };
        ScriptPipeline pipeline = new ScriptPipeline(server, ledger, new ScriptRateLimiter(now::get), deployed(limit()),
                (player, app, action) -> true, evaluate);
        pipeline.installGuards(guards, Runnable::run);
        List<ScriptRpcResult> replies = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            UUID player = new UUID(0, i + 1); long epoch = pipeline.newEpoch(player);
            pipeline.accept(rpc(player, epoch, 1, "claim"), snapshot(player), replies::add);
        }
        check(replies.size() == 500, "所有请求恰好回一条结果");
        check(replies.stream().filter(r -> r.code() == ScriptErrorCode.OK).count() == 20, "真实管线成功20");
        check(replies.stream().filter(r -> r.code() == ScriptErrorCode.EXHAUSTED).count() == 480, "真实管线拒绝480");
        check(evaluations[0] == 20 && journals[0] == 60, "失败的480次不进入脚本或写账本");
        // 取消授权：守卫从来没有占用配额。
        var deniedLedger = new IdempotencyLedger(now::get);
        var deniedGuards = new GuardController(now::get, (p, u) -> true);
        var denied = new ScriptPipeline(server, deniedLedger, new ScriptRateLimiter(now::get), deployed(limit()),
                (player, app, action) -> false, evaluate);
        denied.installGuards(deniedGuards, Runnable::run);
        UUID player = new UUID(2, 3); long epoch = denied.newEpoch(player);
        List<ScriptRpcResult> reject = new ArrayList<>();
        denied.accept(rpc(player, epoch, 1, "claim"), snapshot(player), reject::add);
        check(reject.get(0).code() == ScriptErrorCode.NOT_AUTHORIZED && deniedLedger.size(player) == 0
                && deniedGuards.counters().isEmpty(), "先查授权才做玩法守卫");
        // 求值队列拒收：预留回滚，不能永久卡在 RESERVED。
        var busyLedger = new IdempotencyLedger(now::get); var busyGuards = new GuardController(now::get, (p, u) -> true);
        var busy = new ScriptPipeline(server, busyLedger, new ScriptRateLimiter(now::get), deployed(limit()),
                (p, app, action) -> true, (request, done) -> false);
        busy.installGuards(busyGuards, Runnable::run); epoch = busy.newEpoch(player); reject.clear();
        busy.accept(rpc(player, epoch, 2, "claim"), snapshot(player), reject::add);
        check(reject.get(0).code() == ScriptErrorCode.RATE_LIMITED && busyGuards.counters().isEmpty(), "队列满回滚配额");
        check(busyLedger.snapshot().get(player).values().iterator().next().state() == IdempotencyLedger.State.FAILED,
                "队列满结清失败账本");
        for(boolean started:new boolean[]{false,true}) {
            var thrownLedger=new IdempotencyLedger(now::get);var thrownGuards=new GuardController(now::get,(p,u)->true);
            var thrown=new ScriptPipeline(server,thrownLedger,new ScriptRateLimiter(now::get),deployed(limit()),(p,app,action)->true,(request,done)->{if(started)request.beginEffects().run();throw new IllegalStateException("submission failed");});
            thrown.installGuards(thrownGuards,Runnable::run);epoch=thrown.newEpoch(player);reject.clear();thrown.accept(rpc(player,epoch,3,"claim"),snapshot(player),reject::add);
            check(reject.size()==1&&reject.get(0).code()==(started?ScriptErrorCode.UNKNOWN:ScriptErrorCode.INTERNAL),"提交异常恰好回包，开始副作用后禁止重试");
            check(started?!thrownGuards.counters().isEmpty():thrownGuards.counters().isEmpty(),"未开始副作用回滚、已经开始保持配额");
        }
        var twice=new ScriptPipeline(server,new IdempotencyLedger(now::get),new ScriptRateLimiter(now::get),deployed(ActionGuards.NONE),(p,app,action)->true,(request,done)->{var outcome=ActionEvaluator.Outcome.ok(new byte[0],0,List.of());done.accept(outcome);done.accept(outcome);return false;});
        epoch=twice.newEpoch(player);reject.clear();twice.accept(rpc(player,epoch,4,"claim"),snapshot(player),reject::add);check(reject.size()==1,"重复回调与拒收不能再发第二条结果");
        System.out.println("GuardPipelineTest: " + checks + " checks passed; OK=20, EXHAUSTED=480, evaluator calls=20");
    }
}
