package com.november.mcphone.core.script.server.economy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * S15d′ 断言：货币配置解析器（{@code serverconfig/mcphone-economy.json}）。
 *
 * <p>判据全在卡里：坏配置只丢一段、报错带行号+字段名+合法取值、段名写错要报出来、
 * 模板只写一次且不写示例货币、多条 default 取第一条、缺件档不挂空壳。
 * 这一份是纯函数 + 临时目录，三平台都能跑（经济线的 POSIX 用例在 {@code EconomyDataTest}，别混）。
 */
public class EconomyConfigTest {

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

    static boolean problemContains(EconomyConfig.Result r, String fragment) {
        for (String p : r.problems()) if (p.contains(fragment)) return true;
        return false;
    }

    static Path tmp(String name) throws Exception {
        Path dir = Files.createTempDirectory("mcphone-s15dp-" + name);
        dir.toFile().deleteOnExit();
        return dir;
    }

    /** 好配置：两条货币（builtin 默认 + scoreboard 上限压 int）。 */
    static void goodConfig() {
        EconomyConfig.Result r = EconomyConfig.parse("{\n"
                + "  \"currency\": [\n"
                + "    { \"id\": \"test:coin\", \"name\": \"金币\", \"symbol\": \"¢\", \"decimals\": 2, \"default\": true },\n"
                + "    { \"id\": \"test:gem\", \"provider\": \"scoreboard\", \"max\": 5000000000 }\n"
                + "  ]\n"
                + "}");
        check(r.clean(), "好配置没有 problem：" + r.problems());
        eq(r.specs().size(), 2, "两条货币");
        eq(r.specs().get(0).id(), "test:coin", "顺序 = 文件顺序（第一条）");
        eq(r.specs().get(0).provider(), "builtin", "provider 缺省 = builtin");
        eq(r.specs().get(0).isDefault(), true, "default 透传");
        eq(r.specs().get(1).provider(), "scoreboard", "scoreboard 认识");
        eq(r.specs().get(1).effectiveMax(), (long) Integer.MAX_VALUE, "scoreboard 上限压到 int（不许绕过）");
    }

    /** 模板：只写一次、只读不写、不写示例货币。 */
    static void templateIsWrittenOnceAndReadOnly() throws Exception {
        Path file = EconomyConfig.pathIn(tmp("template"));
        check(!Files.exists(file), "一开始没有文件");
        EconomyConfig.load(file);
        check(Files.isRegularFile(file), "首次装载生成模板");
        String template = Files.readString(file, StandardCharsets.UTF_8);
        check(template.contains("_comment"), "模板带说明键");
        check(template.contains("\"currency\": []"), "模板是空表（不写示例货币）");

        String custom = "{\n  \"_note\": \"mine\",\n  \"currency\": []\n}\n";
        Files.writeString(file, custom, StandardCharsets.UTF_8);
        EconomyConfig.load(file);
        eq(Files.readString(file, StandardCharsets.UTF_8), custom, "装载之后文件一个字节都不动");
    }

    /** 坏 JSON / 重复键 / 顶层不是对象 ⇒ 整份不可用（空表 + problem）。 */
    static void wholeFileRejected() {
        check(problemContains(EconomyConfig.parse("{oops"), "整份配置不可用"), "坏 JSON：整份不可用");
        check(problemContains(EconomyConfig.parse("[]"), "顶层必须是一个对象"), "顶层不是对象：整份不可用");
        check(problemContains(EconomyConfig.parse("{\"currency\":[],\"currency\":[]}"), "重复键"),
                "重复 JSON 键：整份不可用（两种读法）");
        eq(EconomyConfig.parse("{oops").specs().size(), 0, "整份不可用时没有货币");
    }

    /** 段名写错：明确报出来，不静默。 */
    static void unknownSection() {
        EconomyConfig.Result r = EconomyConfig.parse("{\n"
                + "  \"_comment\": [\"x\"],\n"
                + "  \"currencys\": []\n"
                + "}");
        check(problemContains(r, "currencys"), "点名报出不认识的段");
        check(problemContains(r, "不认识的段"), "说清是段名问题");
        check(problemContains(r, "第 3 行"), "段名错误带行号");
        eq(r.specs().size(), 0, "段名错不产出货币");
    }

    /** 一条坏、其余照常；报错带行号 + 字段名 + 合法取值。 */
    static void badEntryDropsOnlyItself() {
        EconomyConfig.Result r = EconomyConfig.parse("{\n"
                + "  \"_comment\": [\"c\"],\n"
                + "  \"currency\": [\n"
                + "    {\n"
                + "      \"id\": \"test:coin\",\n"
                + "      \"decimals\": 9\n"
                + "    },\n"
                + "    {\n"
                + "      \"id\": \"test:gem\",\n"
                + "      \"provider\": \"scoreboard\"\n"
                + "    }\n"
                + "  ]\n"
                + "}");
        eq(r.specs().size(), 1, "坏的那条丢掉，好的那条留着");
        eq(r.specs().get(0).id(), "test:gem", "留下的正是第二条");
        check(problemContains(r, "第 4 行"), "报错指向条目起始行");
        check(problemContains(r, "decimals"), "报错含字段名");
        check(problemContains(r, "0..4"), "报错含合法取值");
    }

    /** 未知字段：丢这一条，报行号 + 认得的字段。 */
    static void unknownFieldDropsEntry() {
        EconomyConfig.Result r = EconomyConfig.parse("{\n"
                + "  \"currency\": [\n"
                + "    {\n"
                + "      \"id\": \"test:coin\",\n"
                + "      \"colour\": \"red\"\n"
                + "    }\n"
                + "  ]\n"
                + "}");
        eq(r.specs().size(), 0, "有未知字段的条目被丢");
        check(problemContains(r, "不认识的字段 'colour'"), "点名未知字段：" + r.problems());
        check(problemContains(r, "第 5 行"), "未知字段带自己的行号");
        check(problemContains(r, "id/name/symbol/decimals/provider/default/max"), "列出认得的字段");
    }

    /** id 重复：第二条被拒并报行号（唯一实例纪律）。 */
    static void duplicateIdRejected() {
        EconomyConfig.Result r = EconomyConfig.parse("{\n"
                + "  \"currency\": [\n"
                + "    { \"id\": \"test:coin\" },\n"
                + "    { \"id\": \"test:coin\" }\n"
                + "  ]\n"
                + "}");
        eq(r.specs().size(), 1, "重复 id 只留第一条");
        check(problemContains(r, "id 'test:coin' 重复"), "点名重复的 id：" + r.problems());
        check(problemContains(r, "第 4 行"), "重复那条带行号");
    }

    /** 说明键：顶层与条目内的 `_` 都跳过且不报警。 */
    static void underscoreKeysSkipped() {
        EconomyConfig.Result r = EconomyConfig.parse("{\n"
                + "  \"_comment\": [\"x\"],\n"
                + "  \"currency\": [ { \"id\": \"test:coin\", \"_note\": \"mine\" } ]\n"
                + "}");
        check(r.clean(), "`_` 键不产生 problem：" + r.problems());
        eq(r.specs().size(), 1, "条目照常");
    }

    /** 多条 default：取第一条 + warning；没有 default 就没有默认货币。 */
    static void multipleDefaultsTakeFirst() {
        EconomyConfig.Result r = EconomyConfig.parse("{\n"
                + "  \"currency\": [\n"
                + "    { \"id\": \"test:a\", \"default\": true },\n"
                + "    { \"id\": \"test:b\", \"default\": true }\n"
                + "  ]\n"
                + "}");
        EconomyProviders.Plan plan = EconomyProviders.plan(r.specs(), false);
        eq(plan.entries().size(), 2, "两条都在计划里");
        eq(plan.entries().get(0).isDefault(), true, "默认取第一条");
        eq(plan.entries().get(1).isDefault(), false, "第二条降为非默认");
        eq(plan.warnings().size(), 1, "来一条 warning");
        check(plan.warnings().get(0).contains("test:b"), "warning 点名被忽略的那条");

        EconomyConfig.Result none = EconomyConfig.parse("{\"currency\": [ { \"id\": \"test:a\" } ]}");
        check(EconomyProviders.plan(none.specs(), false).entries().stream().noneMatch(EconomyProviders.Planned::isDefault),
                "没有 default 就没有默认货币");
    }

    /** 缺件档不挂空壳：emc_legacy 无钱包跳过、adapter 一律跳过。 */
    static void plannedSkips() {
        EconomyConfig.Result r = EconomyConfig.parse("{\n"
                + "  \"currency\": [\n"
                + "    { \"id\": \"test:coin\", \"provider\": \"builtin\", \"default\": true },\n"
                + "    { \"id\": \"test:emc\", \"provider\": \"emc_legacy\" },\n"
                + "    { \"id\": \"test:adapter\", \"provider\": \"adapter\" }\n"
                + "  ]\n"
                + "}");
        EconomyProviders.Plan without = EconomyProviders.plan(r.specs(), false);
        eq(without.entries().get(0).register(), true, "builtin 注册");
        eq(without.entries().get(1).register(), false, "没有真钱包：emc_legacy 不注册");
        eq(without.entries().get(1).skipReasonKey(), EconomyProviders.SKIP_EMC_NO_WALLET, "emc 跳过原因键");
        eq(without.entries().get(2).register(), false, "adapter 不注册");
        eq(without.entries().get(2).skipReasonKey(), EconomyProviders.SKIP_ADAPTER_NO_BRIDGE, "adapter 跳过原因键");

        EconomyProviders.Plan with = EconomyProviders.plan(r.specs(), true);
        eq(with.entries().get(1).register(), true, "有真钱包：emc_legacy 注册");
    }

    /** 字段给对象/数组：丢这一条，不静默当缺省。 */
    static void nonPrimitiveFieldRejected() {
        EconomyConfig.Result r = EconomyConfig.parse("{\n"
                + "  \"currency\": [ { \"id\": \"test:coin\", \"name\": {\"zh\":\"金币\"} } ]\n"
                + "}");
        eq(r.specs().size(), 0, "对象值不静默当缺省");
        check(problemContains(r, "不接受对象/数组"), "说清字段类型不收：" + r.problems());
    }

    /** 同一条配置解析两次 ⇒ 相等的表（换台机器/换次开服不该变）。 */
    static void parseIsDeterministic() {
        String json = "{\"currency\":[{\"id\":\"test:coin\",\"decimals\":2},{\"id\":\"test:gem\",\"provider\":\"scoreboard\"}]}";
        eq(EconomyConfig.parse(json).specs(), EconomyConfig.parse(json).specs(), "同一条配置 → 同一个 List<CurrencySpec>");
    }

    /** S15d′ 接线：按配置注册（含默认货币与 scoreboard 档）+ 存档兜底不许漏。 */
    static void wiringRegistersConfigured() throws Exception {
        Path dir = tmp("wire");
        EconomyData data = EconomyData.createFor(dir.resolve("world"), dir.resolve("snapshot"),
                System::currentTimeMillis);
        TxnLog log = new TxnLog(dir.resolve("economy"), java.time.ZoneId.systemDefault());
        CurrencyGateway gateway = new CurrencyGateway(Runnable::run, () -> true);
        data.set(java.util.UUID.randomUUID(), "test:legacy", 5L);   // 存档里已有、配置里没有

        List<CurrencySpec> specs = EconomyConfig.parse("{\"currency\":["
                + "{\"id\":\"test:coin\",\"default\":true},"
                + "{\"id\":\"test:gem\",\"provider\":\"scoreboard\"}]}").specs();
        EconomyRuntime rt = EconomyRuntime.wire(data, log, gateway, 1L, specs, () -> null);

        check(rt.registry().get("test:coin") != null, "配置里的 builtin 注册了");
        check(rt.registry().get("test:gem") != null, "scoreboard 档注册了");
        check(rt.registry().get("test:legacy") != null, "存档里已有的、配置里没有的：兜底注册");
        eq(rt.registry().defaultCurrency(), "test:coin", "默认货币 = 配置里第一条 default=true");
    }

    /** D′1：带引号的布尔/数字不静默解释（宽松面只留给无歧义的形态）。 */
    static void quotedTypesRejected() {
        EconomyConfig.Result def = EconomyConfig.parse(
                "{\"currency\":[{\"id\":\"test:coin\",\"default\":\"yes\"}]}");
        eq(def.specs().size(), 0, "带引号的 default 不静默吞成 false");
        check(problemContains(def, "default") && problemContains(def, "true/false"),
                "报字段名与合法取值：" + def.problems());

        EconomyConfig.Result dec = EconomyConfig.parse(
                "{\"currency\":[{\"id\":\"test:coin\",\"decimals\":\"2\"}]}");
        eq(dec.specs().size(), 0, "带引号的 decimals 不静默收下");
        check(problemContains(dec, "decimals"), "报字段名：" + dec.problems());

        EconomyConfig.Result frac = EconomyConfig.parse(
                "{\"currency\":[{\"id\":\"test:coin\",\"decimals\":2.5}]}");
        eq(frac.specs().size(), 0, "小数 decimals 不截断");
        check(problemContains(frac, "decimals"), "报字段名：" + frac.problems());
    }

    /** D′2：行号按元素序号对齐 —— 混非对象/嵌套数组时，"不是对象"要指到元素自己的行。 */
    static void lineNumbersAlignAcrossNonObjects() {
        EconomyConfig.Result mix = EconomyConfig.parse("{\n"
                + "  \"currency\": [\n"
                + "    {},\n"
                + "    5,\n"
                + "    { \"id\": \"test:coin\" }\n"
                + "  ]\n"
                + "}");
        eq(mix.specs().size(), 1, "混着非对象：对象照常解析");
        check(problemContains(mix, "第 4 行：这一条不是对象"), "非对象元素的行号 = 元素自己的行：" + mix.problems());

        EconomyConfig.Result nested = EconomyConfig.parse("{\n"
                + "  \"currency\": [\n"
                + "    [ { \"id\": \"test:coin\" } ]\n"
                + "  ]\n"
                + "}");
        eq(nested.specs().size(), 0, "嵌套数组不算一条");
        check(problemContains(nested, "第 3 行：这一条不是对象"), "嵌套数组指向元素行：" + nested.problems());
    }

    /** D′3：条数上限截断 + 文件大小闸（与能力配置同一档）。 */
    static void sizeGuards() throws Exception {
        StringBuilder many = new StringBuilder("{\"currency\":[");
        for (int i = 0; i < EconomyConfig.MAX_CURRENCIES + 5; i++) {
            if (i > 0) many.append(',');
            many.append("{\"id\":\"test:c").append(i).append("\"}");
        }
        many.append("]}");
        EconomyConfig.Result capped = EconomyConfig.parse(many.toString());
        eq(capped.specs().size(), EconomyConfig.MAX_CURRENCIES, "超限截断到上限");
        check(capped.specs().get(0).id().equals("test:c0"), "保留的从第一条起");
        check(problemContains(capped, "超过上限"), "一条汇总 problem");

        Path file = EconomyConfig.pathIn(tmp("big"));
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"currency\":[]}" + " ".repeat(EconomyConfig.MAX_FILE_BYTES),
                StandardCharsets.UTF_8);
        EconomyConfig.Result big = EconomyConfig.load(file);
        check(big.specs().isEmpty() && problemContains(big, "太大"),
                "超大文件整份不加载：" + big.problems());
    }

    /** 对抗 #4：被跳过的 default 不许吃掉默认位 —— 默认货币必须落到能注册的那一条。 */
    static void skippedDefaultFallsThrough() throws Exception {
        EconomyConfig.Result r = EconomyConfig.parse("{\"currency\":["
                + "{\"id\":\"test:emc\",\"provider\":\"emc_legacy\",\"default\":true},"
                + "{\"id\":\"test:coin\",\"default\":true}]}");
        EconomyProviders.Plan plan = EconomyProviders.plan(r.specs(), false);
        eq(plan.entries().get(0).register(), false, "没有钱包：第一条跳过");
        eq(plan.entries().get(0).isDefault(), false, "被跳过的那条不许占默认位");
        eq(plan.entries().get(1).isDefault(), true, "默认顺延给能注册的第二条");
        check(plan.warnings().stream().anyMatch(w -> w.contains("顺延")),
                "要说清为什么顺延：" + plan.warnings());
        eq(plan.entries().stream().filter(EconomyProviders.Planned::isDefault).count(), 1L,
                "一个计划里最多一条默认");

        // 接线层也要真的落到第二条
        Path dir = tmp("skipped-default");
        EconomyData data = EconomyData.createFor(dir.resolve("world"), dir.resolve("snapshot"),
                System::currentTimeMillis);
        TxnLog log = new TxnLog(dir.resolve("economy"), java.time.ZoneId.systemDefault());
        CurrencyGateway gateway = new CurrencyGateway(Runnable::run, () -> true);
        EconomyRuntime rt = EconomyRuntime.wire(data, log, gateway, 1L, r.specs(), () -> null);
        eq(rt.registry().defaultCurrency(), "test:coin", "registry 的默认货币 = 能注册的那条");
        check(rt.registry().get("test:emc") == null, "被跳过的档没有注册（不挂空壳）");
    }

    public static void main(String[] args) throws Exception {
        goodConfig();
        templateIsWrittenOnceAndReadOnly();
        wholeFileRejected();
        unknownSection();
        badEntryDropsOnlyItself();
        unknownFieldDropsEntry();
        duplicateIdRejected();
        underscoreKeysSkipped();
        multipleDefaultsTakeFirst();
        plannedSkips();
        nonPrimitiveFieldRejected();
        parseIsDeterministic();
        wiringRegistersConfigured();
        quotedTypesRejected();
        lineNumbersAlignAcrossNonObjects();
        sizeGuards();
        skippedDefaultFallsThrough();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
