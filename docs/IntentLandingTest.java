package com.november.mcphone.core.script.server;

import com.november.mcphone.core.script.net.ScriptErrorCode;
import com.november.mcphone.core.script.net.ScriptProtocol;
import com.november.mcphone.core.script.net.ScriptRpc;
import com.november.mcphone.core.script.net.ScriptRpcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * S18 断言：意图在 {@link ScriptPipeline#land} 的落地判定。
 *
 * <p>覆盖：部署版本重查、能力逐条重查（未批/被服主关）、落地端未接通、四种失败码
 * （INVENTORY_FULL / UNKNOWN / UNAVAILABLE / VERSION_MISMATCH），以及"钱已动过 ⇒ 失败也回 UNKNOWN"。
 */
public class IntentLandingTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) failures.add(what + "  期望 " + expected + "，实际 " + actual);
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures.add(what);
    }

    static final UUID SERVER = UUID.nameUUIDFromBytes("server".getBytes());
    static final UUID P1 = UUID.nameUUIDFromBytes("p1".getBytes());

    static PlayerSnapshot snap() {
        return new PlayerSnapshot(P1, "tester", "minecraft:overworld", "survival", 0L);
    }

    /** 一个已部署、动作放行、版本 rev 的替身；能力集合由参数给。 */
    static DeploymentView deployed(String rev, Set<String> caps) {
        return new DeploymentView() {
            @Override
            public boolean deployed(String appId) {
                return true;
            }

            @Override
            public boolean hasAction(String appId, String actionId) {
                return true;
            }

            @Override
            public String deployRev(String appId) {
                return rev;
            }

            @Override
            public Set<String> approvedCapabilities(String appId) {
                return caps;
            }
        };
    }

    static ScriptRpc rpc(String rev, long epoch) {
        return new ScriptRpc(ScriptProtocol.PROTOCOL, 7L, epoch, "example:app", rev, "act",
                new byte[0], "d");
    }

    static ActionEvaluator evaluating(List<ActionIntent> intents, boolean moneyMoved) {
        return (req, onDone) -> {
            ActionEvaluator.Outcome outcome = moneyMoved
                    ? new ActionEvaluator.Outcome(ScriptErrorCode.OK, new byte[0], "", List.of(), 0, 0, intents, true)
                    : ActionEvaluator.Outcome.ok(new byte[0], 0, intents);
            onDone.accept(outcome);
            return true;
        };
    }

    /** 记录调用、给出指定结果的落地端替身。 */
    static final class FakeApplier implements IntentApplier {
        final IntentApplier.Landed result;
        int calls;
        UUID lastPlayer;

        FakeApplier(IntentApplier.Landed result) {
            this.result = result;
        }

        @Override
        public Landed apply(UUID player, List<ActionIntent> intents) {
            calls++;
            lastPlayer = player;
            return result;
        }
    }

    static ScriptRpcResult run(DeploymentView dep, CapabilityPolicy policy, ActionEvaluator evaluator,
                               IntentApplier applier, String rev) {
        IdempotencyLedger ledger = new IdempotencyLedger(System::currentTimeMillis);
        ScriptPipeline p = new ScriptPipeline(SERVER, ledger, new ScriptRateLimiter(System::currentTimeMillis),
                dep, (player, appId, actionId) -> true, evaluator, applier, policy);
        long epoch = p.newEpoch(P1);
        List<ScriptRpcResult> out = new ArrayList<>();
        p.accept(rpc(rev, epoch), snap(), out::add);
        return out.get(0);
    }

    static List<ActionIntent> oneGive() {
        return List.of(ActionIntent.itemGive("minecraft:diamond", 3, ""));
    }

    static void happyPath() {
        FakeApplier applier = new FakeApplier(IntentApplier.Landed.ok());
        ScriptRpcResult r = run(deployed("rev1", Set.of("item.give")),
                new CapabilityPolicy(CapabilityConfig.parse("{}")),
                evaluating(oneGive(), false), applier, "rev1");
        eq(r.code(), ScriptErrorCode.OK, "已部署 + 已批准 + 落地端成功 → OK");
        eq(applier.calls, 1, "落地端被调了一次");
        eq(applier.lastPlayer, P1, "落地端拿到的是发起者");
    }

    static void versionRecheck() {
        FakeApplier applier = new FakeApplier(IntentApplier.Landed.ok());
        ScriptRpcResult r = run(deployed("rev2", Set.of("item.give")),
                new CapabilityPolicy(CapabilityConfig.parse("{}")),
                evaluating(oneGive(), false), applier, "rev1");
        eq(r.code(), ScriptErrorCode.VERSION_MISMATCH, "排队期间换了包 → 不落地");
        eq(applier.calls, 0, "意图一条都没落地");
    }

    static void notApprovedAndDisabled() {
        FakeApplier applier = new FakeApplier(IntentApplier.Landed.ok());
        ScriptRpcResult notApproved = run(deployed("rev1", Set.of()),
                new CapabilityPolicy(CapabilityConfig.parse("{}")),
                evaluating(oneGive(), false), applier, "rev1");
        eq(notApproved.code(), ScriptErrorCode.NOT_AUTHORIZED, "capability 没批 → NOT_AUTHORIZED");
        eq(notApproved.messageKey(), CapabilityPolicy.messageKey(CapabilityPolicy.Verdict.NOT_APPROVED),
                "带的是能力的文案键");
        eq(applier.calls, 0, "没批就不落地");

        FakeApplier applier2 = new FakeApplier(IntentApplier.Landed.ok());
        ScriptRpcResult disabled = run(deployed("rev1", Set.of("item.give")),
                new CapabilityPolicy(CapabilityConfig.parse("{\"disabled\":[\"item.give\"]}")),
                evaluating(oneGive(), false), applier2, "rev1");
        eq(disabled.code(), ScriptErrorCode.UNAVAILABLE, "服主关掉 → UNAVAILABLE");
        eq(disabled.messageKey(), CapabilityPolicy.messageKey(CapabilityPolicy.Verdict.DISABLED), "带的是 disabled 键");
        eq(applier2.calls, 0, "被关掉就不落地");
    }

    static void unwiredApplier() {
        ScriptRpcResult r = run(deployed("rev1", Set.of("item.give")),
                new CapabilityPolicy(CapabilityConfig.parse("{}")),
                evaluating(oneGive(), false), IntentApplier.UNWIRED, "rev1");
        eq(r.code(), ScriptErrorCode.UNAVAILABLE, "落地端没接上 → UNAVAILABLE，不谎报成功");
        eq(r.messageKey(), "mcphone.script.intent_unavailable", "有专门的文案键");
    }

    static void inventoryFull() {
        FakeApplier applier = new FakeApplier(new IntentApplier.Landed(ScriptErrorCode.INVENTORY_FULL,
                ScriptErrorCode.INVENTORY_FULL.defaultMessageKey()));
        ScriptRpcResult r = run(deployed("rev1", Set.of("item.give")),
                new CapabilityPolicy(CapabilityConfig.parse("{}")),
                evaluating(oneGive(), false), applier, "rev1");
        eq(r.code(), ScriptErrorCode.INVENTORY_FULL, "背包满 → INVENTORY_FULL");
    }

    static void moneyMovedWins() {
        FakeApplier applier = new FakeApplier(new IntentApplier.Landed(ScriptErrorCode.INVENTORY_FULL,
                ScriptErrorCode.INVENTORY_FULL.defaultMessageKey()));
        ScriptRpcResult r = run(deployed("rev1", Set.of("item.give")),
                new CapabilityPolicy(CapabilityConfig.parse("{}")),
                evaluating(oneGive(), true), applier, "rev1");
        eq(r.code(), ScriptErrorCode.UNKNOWN, "钱已动过 + 发放失败 → UNKNOWN（不谎报没动）");
    }

    public static void main(String[] args) {
        happyPath();
        versionRecheck();
        notApprovedAndDisabled();
        unwiredApplier();
        inventoryFull();
        moneyMovedWins();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
