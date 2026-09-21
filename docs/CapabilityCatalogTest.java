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
     * S18-B2 的一半：{@code CtxBuilder} 里的**字面量** {@code gate.require("x")} 必须都在
     * {@code enforced} 里 —— 新增一条门却没登记，这里当场红（哪怕那个成员没被探针脚本调到）。
     * 反向不查（目录有、代码里是间接写法时不该误报）；间接写法与"登记了没门"由
     * {@code ScriptEngineTest.enforcedGateProbe()} 的运行时探针兜。
     */
    static void gateLiteralsKnown() throws Exception {
        java.nio.file.Path src = java.nio.file.Path.of("..", "..", "shared", "src", "main", "java",
                "com", "november", "mcphone", "core", "script", "engine", "CtxBuilder.java")
                .toAbsolutePath().normalize();
        check(java.nio.file.Files.isRegularFile(src), "CtxBuilder 源码在：" + src);
        if (!java.nio.file.Files.isRegularFile(src)) return;
        String text = java.nio.file.Files.readString(src, java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("gate\\.require\\(\"([^\"]+)\"\\)").matcher(text);
        java.util.Set<String> found = new java.util.TreeSet<>();
        while (m.find()) {
            found.add(m.group(1));
            check(CapabilityCatalog.enforced(m.group(1)),
                    "CtxBuilder 里的字面量门必须在 enforced 里：" + m.group(1));
        }
        eq(found.size(), CapabilityCatalog.enforcedIds().size(),
                "字面量门（去重）条数 = enforced 条数（间接写法会让这条红，请改成字面量或更新探针）");
    }

    public static void main(String[] args) throws Exception {
        catalogSize();
        tiers();
        declared();
        enforced();
        gateLiteralsKnown();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
