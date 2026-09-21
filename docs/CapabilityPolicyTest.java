package com.november.mcphone.core.script.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * S18 断言：能力门（{@link CapabilityPolicy}）。
 *
 * <p>四件事：档位只认目录（App 自称无效）、plain 免审批、granted 要在这个 App 的批准集合里、
 * 服主开关（disabled）能压过一切（含 plain）。
 */
public class CapabilityPolicyTest {

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

    static CapabilityPolicy policy(String json) {
        return new CapabilityPolicy(CapabilityConfig.parse(json));
    }

    static void tiers() {
        CapabilityPolicy p = policy("{}");
        eq(p.check("trade.escrow", Set.of()), CapabilityPolicy.Verdict.OK, "plain 免审批（空批准集合也 OK）");
        eq(p.check("storage.global.write", Set.of()), CapabilityPolicy.Verdict.OK, "storage.global.write 是 plain");
        eq(p.check("item.give", Set.of()), CapabilityPolicy.Verdict.NOT_APPROVED, "granted 没批 → NOT_APPROVED");
        eq(p.check("item.give", Set.of("item.give")), CapabilityPolicy.Verdict.OK, "granted 批了 → OK");
        eq(p.check("ability.fly", Set.of("ability.fly")), CapabilityPolicy.Verdict.NOT_OPEN,
                "restricted 即使批了也不开放");
        eq(p.check("container.write", Set.of("container.write")), CapabilityPolicy.Verdict.NOT_OPEN,
                "container.write 首版不开放");
        eq(p.check("net.fetch", Set.of("net.fetch")), CapabilityPolicy.Verdict.NOT_OPEN,
                "本版没有路径的能力不开放");
        eq(p.check("command.template:daily", Set.of()), CapabilityPolicy.Verdict.NOT_OPEN,
                "参数化模板族在目录里但不开放");
        eq(p.check("economy.pay", Set.of()), CapabilityPolicy.Verdict.UNKNOWN, "目录外 → UNKNOWN");
    }

    static void disabledWins() {
        CapabilityPolicy p = policy("{\"disabled\":[\"trade.escrow\",\"item.give\"]}");
        eq(p.check("trade.escrow", Set.of()), CapabilityPolicy.Verdict.DISABLED, "plain 也能被服主关掉");
        eq(p.check("item.give", Set.of("item.give")), CapabilityPolicy.Verdict.DISABLED,
                "disabled 压过批准集合");
        eq(p.check("storage.self", Set.of()), CapabilityPolicy.Verdict.OK, "没关的照常");
    }

    static void messageKeys() {
        check(!CapabilityPolicy.messageKey(CapabilityPolicy.Verdict.DISABLED).isEmpty(), "DISABLED 有文案键");
        check(!CapabilityPolicy.messageKey(CapabilityPolicy.Verdict.NOT_APPROVED).isEmpty(), "NOT_APPROVED 有文案键");
        check(!CapabilityPolicy.messageKey(CapabilityPolicy.Verdict.NOT_OPEN).isEmpty(), "NOT_OPEN 有文案键");
        check(!CapabilityPolicy.messageKey(CapabilityPolicy.Verdict.UNKNOWN).isEmpty(), "UNKNOWN 有文案键");
        eq(CapabilityPolicy.messageKey(CapabilityPolicy.Verdict.OK), "", "OK 没有拒绝文案");
    }

    static void reloadSwapsSnapshot() {
        CapabilityPolicy p = policy("{}");
        eq(p.check("trade.escrow", Set.of()), CapabilityPolicy.Verdict.OK, "重载前开着");
        p.reload(CapabilityConfig.parse("{\"disabled\":[\"trade.escrow\"]}"));
        eq(p.check("trade.escrow", Set.of()), CapabilityPolicy.Verdict.DISABLED,
                "重载后 worker 上的判定立刻看到新配置（切换预设后行为随之改变）");
    }

    public static void main(String[] args) {
        tiers();
        disabledWins();
        messageKeys();
        reloadSwapsSnapshot();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
