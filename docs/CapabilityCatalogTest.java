package com.november.mcphone.core.script.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * S18 断言：能力目录（§18.8）的唯一查表。
 *
 * <p>先把口径钉死：<b>目录 33 条</b>（§18.8 三张表逐条展开 32 条 + 对抗 S18-A3 追加的
 * {@code predicate.test}），其中<b>首版开放 23 条</b>
 * （plain 18 + granted 6；减去本步不做的 {@code net.fetch}/{@code container.read}）。
 * step26 卡里的"22 个"指的就是这组开放项去掉 {@code predicate.test} 的那 22 条。
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
        eq(CapabilityCatalog.all().size(), 33, "目录共 33 条（§18.8 展开 + predicate.test）");
        eq(CapabilityCatalog.open().size(), 23, "首版开放 23 条（ctx 上会出现的就是这组）");
        eq(CapabilityCatalog.openIds().size(), 23, "openIds 与 open() 同数");
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
        eq(tier("predicate.test"), CapabilityTier.PLAIN, "predicate.test 是 plain（§32.6 恢复，可关）");
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

    /**
     * S18-A2：{@code open} 要分得清"有调用点 / 本步还没有"—— 前者 {@code disabled} 真的会拒，
     * 后者只影响目录展示。这份白名单是**故意写死**的：新增一条"没调用点"的开放项要有人来这里签字。
     *
     * <p>运行时一致性（门集合 == {@code enforcedIds()}）由 {@code ScriptEngineTest.enforcedGateProbe()}
     * 用记录门探针钉住（间接写法也看得见）；这里只管"开放项分类不超过这两类"。
     */
    static void enforced() {
        java.util.Set<String> notYet = java.util.Set.of(
                "read.self.position", "read.self.inventory", "read.self.stats",
                "read.world.time", "read.world.weather",
                "read.players.online_count", "read.players.list",
                "item.take.self", "trade.escrow", "message.self",
                "currency.mint", "item.give.other");
        for (String id : CapabilityCatalog.enforcedIds()) {
            check(CapabilityCatalog.openIds().contains(id), "enforced 的必须是开放项：" + id);
        }
        for (String id : CapabilityCatalog.openIds()) {
            check(CapabilityCatalog.enforced(id) || notYet.contains(id),
                    "开放项要么有调用点、要么在显式白名单里：" + id);
        }
        eq(CapabilityCatalog.enforcedIds().size() + notYet.size(), CapabilityCatalog.openIds().size(),
                "两类加起来正好是全部开放项（不多不少）");
        check(!CapabilityCatalog.enforced("net.fetch"), "不开放的项谈不上 enforced");
        check(!CapabilityCatalog.enforced("container.read"), "container.read 本步不开放也不设门");
    }

    /**
     * S18-C0 的源码腿：受门成员只许走 {@code CtxBuilder} 的 {@code gated()} / {@code gatedGetter()}
     * 两个帮助函数（挂载时登记 + 调用时拦截都在里面）。所以：
     * <ul>
     *   <li>{@code gate.require(} 在 CtxBuilder 里只许出现 2 次（两个帮助函数各一次）；</li>
     *   <li>不许出现 {@code gate.require("x")} 字面量（间接写法的漏洞就堵在这里：谁手写第三处，
     *       次数就不是 2）。</li>
     * </ul>
     * 挂载登记是否等于目录 enforced，由 {@code ScriptEngineTest.gatedMountRegistry()} 钉。
     */
    static void gatingOnlyInHelpers() throws Exception {
        java.nio.file.Path src = java.nio.file.Path.of("..", "..", "shared", "src", "main", "java",
                "com", "november", "mcphone", "core", "script", "engine", "CtxBuilder.java")
                .toAbsolutePath().normalize();
        check(java.nio.file.Files.isRegularFile(src), "CtxBuilder 源码在：" + src);
        if (!java.nio.file.Files.isRegularFile(src)) return;
        String text = java.nio.file.Files.readString(src, java.nio.charset.StandardCharsets.UTF_8);
        eq(count(text, "gate.require("), 2,
                "gate.require 只许出现在 gated()/gatedGetter() 里（新增帮助函数请同步这个数）");
        eq(count(text, "gate.require(\""), 0,
                "调用点不许写死能力 id 字面量；一律走帮助函数，挂载时登记");
    }

    static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) n++;
        return n;
    }

    /**
     * S18-E1：granted 档（凭空造物/改上限）的开放项要么有门、要么在**专门**的白名单里 ——
     * 这档漏一个比 plain 漏一个严重得多，所以单独签字，不混在通用 notYet 里。
     */
    static void grantedEnforcement() {
        java.util.Set<String> grantedNotYet = java.util.Set.of("currency.mint", "item.give.other");
        for (CapabilityCatalog.Entry e : CapabilityCatalog.open()) {
            if (e.tier() != CapabilityTier.GRANTED) continue;
            check(CapabilityCatalog.enforced(e.id()) || grantedNotYet.contains(e.id()),
                    "granted 开放项要么有门、要么在 grantedNotYet 白名单：" + e.id());
        }
        for (String id : grantedNotYet) {
            CapabilityCatalog.Entry e = CapabilityCatalog.of(id);
            check(e != null && e.tier() == CapabilityTier.GRANTED, id + " 必须在目录里且是 granted");
            check(!CapabilityCatalog.enforced(id), id + " 已在'无门'白名单里，不该同时又 enforced");
        }
    }

    public static void main(String[] args) throws Exception {
        catalogSize();
        tiers();
        declared();
        enforced();
        grantedEnforcement();
        gatingOnlyInHelpers();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
