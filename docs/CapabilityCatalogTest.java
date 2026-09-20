package com.november.mcphone.core.script.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * S18 断言：能力目录（§18.8）的唯一查表。
 *
 * <p>先把口径钉死：<b>目录 32 条</b>（§18.8 三张表逐条展开），其中<b>首版开放 22 条</b>
 * （plain 16 + granted 6；减去本步不做的 {@code net.fetch}/{@code container.read}）。
 * step26 卡里的"22 个"指的就是这组开放项。
 */
public class CapabilityCatalogTest {

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

    static void catalogSize() {
        eq(CapabilityCatalog.all().size(), 32, "目录共 32 条（§18.8 逐条展开）");
        eq(CapabilityCatalog.open().size(), 22, "首版开放 22 条（ctx 上会出现的就是这组）");
        eq(CapabilityCatalog.openIds().size(), 22, "openIds 与 open() 同数");
        for (CapabilityCatalog.Entry e : CapabilityCatalog.open()) {
            check(e.open(), e.id() + " 在 open() 里必须标 open");
            check(e.tier() != CapabilityTier.RESTRICTED, e.id() + " 开放项不许是 restricted（首版全不开放）");
        }
        for (CapabilityCatalog.Entry e : CapabilityCatalog.all()) {
            if (e.tier() == CapabilityTier.RESTRICTED) {
                check(!e.open(), "restricted 一档全部不开放：" + e.id());
            }
        }
    }

    static void tiers() {
        eq(tier("item.give"), CapabilityTier.GRANTED, "item.give 是 granted（Q1 凭空造物）");
        eq(tier("loot.roll"), CapabilityTier.GRANTED, "loot.roll 是 granted");
        eq(tier("item.give.other"), CapabilityTier.GRANTED, "item.give.other 是 granted（Q2）");
        eq(tier("attr.grant"), CapabilityTier.GRANTED, "attr.grant 是 granted");
        eq(tier("trade.escrow"), CapabilityTier.PLAIN, "trade.escrow 是 plain（守恒的等价交换）");
        eq(tier("score.rw"), CapabilityTier.PLAIN, "score.rw 是 plain（限 myapp_* 前缀）");
        eq(tier("item.take.self"), CapabilityTier.PLAIN, "item.take.self 是 plain");
        eq(tier("ability.fly"), CapabilityTier.RESTRICTED, "ability.fly 是 restricted");
        eq(tier("block.set"), CapabilityTier.RESTRICTED, "block.set 是 restricted");
        eq(tier("read.nearby.entities"), CapabilityTier.RESTRICTED, "read.nearby.entities 是 restricted");

        check(CapabilityCatalog.of("item.give").open(), "item.give 首版开放");
        check(!CapabilityCatalog.of("container.read").open(), "container.read 本步不做 ctx 路径（不开放）");
        check(!CapabilityCatalog.of("net.fetch").open(), "net.fetch 本步不做（不开放）");
        check(!CapabilityCatalog.of("container.write").open(), "container.write 首版不开放");
        check(!CapabilityCatalog.of("command.template").open(), "command.template 首版不开放");
        eq(CapabilityCatalog.of("item.give").note().isEmpty(), false, "每条都写清了档位理由");
    }

    static CapabilityTier tier(String id) {
        CapabilityCatalog.Entry e = CapabilityCatalog.of(id);
        check(e != null, "目录里有 " + id);
        return e == null ? null : e.tier();
    }

    static void declared() {
        check(CapabilityCatalog.knownDeclared("item.give"), "精确 id 认");
        check(CapabilityCatalog.knownDeclared("command.template:daily_gift"), "参数化模板名认");
        check(!CapabilityCatalog.knownDeclared("command.template:"), "空模板 id 不认");
        check(!CapabilityCatalog.knownDeclared("command.template:a\nb"), "模板 id 不许控制字符");
        check(!CapabilityCatalog.knownDeclared("economy.pay"), "旧的自造名字不认（目录外一律拒）");
        check(!CapabilityCatalog.knownDeclared("Item.Give"), "大小写不近似：精确匹配");
        check(!CapabilityCatalog.knownDeclared(null), "null 不认");

        String tooLong = "command.template:" + "x".repeat(65);
        check(!CapabilityCatalog.knownDeclared(tooLong), "模板 id 超过 64 不认");
    }

    public static void main(String[] args) {
        catalogSize();
        tiers();
        declared();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
