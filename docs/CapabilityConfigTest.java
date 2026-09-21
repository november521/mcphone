package com.november.mcphone.core.script.server;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * S18 断言：能力与边界配置（{@code serverconfig/mcphone-capabilities.json}）。
 *
 * <p>口径照 E28：坏配置不崩服、只丢坏的那条、报出段名/字段名；文件只读不写（除首次生成模板）；
 * 预设是底稿、显式项覆盖预设。
 */
public class CapabilityConfigTest {

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

    static boolean warned(CapabilityConfig cfg, String fragment) {
        for (String w : cfg.warnings()) if (w.contains(fragment)) return true;
        return false;
    }

    static void defaultsAndPresets() {
        CapabilityConfig d = CapabilityConfig.parse("{}");
        eq(d.preset(), CapabilityConfig.Preset.STANDARD, "缺 preset 默认 standard");
        eq(d.disabled().size(), 0, "默认什么都不关");
        eq(d.boundary().resourceMove(), false, "默认边界全关（原版保守）");
        eq(d.boundary().remoteMachineRead(), false, "默认边界全关");
        check(d.warnings().isEmpty(), "空对象没有警告");

        CapabilityConfig open = CapabilityConfig.parse("{\"preset\":\"open\"}");
        eq(open.preset(), CapabilityConfig.Preset.OPEN, "open 预设认");
        eq(open.boundary().resourceMove(), true, "open 打开远程传电");
        eq(open.boundary().remoteMachineRead(), true, "open 打开远程读机器");
        eq(open.boundary().storeLivingEntities(), false, "open 不放存活体");

        CapabilityConfig hard = CapabilityConfig.parse("{\"preset\":\"hardcore\"}");
        eq(hard.preset(), CapabilityConfig.Preset.HARDCORE, "hardcore 预设认");
        check(hard.isDisabled("trade.escrow"), "hardcore 关掉全服市场");
        eq(hard.boundary().resourceMove(), false, "hardcore 边界全关");

        CapabilityConfig bad = CapabilityConfig.parse("{\"preset\":\"wild\"}");
        eq(bad.preset(), CapabilityConfig.Preset.STANDARD, "不认识的预设退回 standard");
        check(warned(bad, "preset"), "不认识的预设要警告");
    }

    static void explicitOverrides() {
        // 显式 disabled 覆盖预设底稿（哪怕写空数组）—— 覆盖本身是语义，但覆盖掉底稿要警告（S18-A4）
        CapabilityConfig c = CapabilityConfig.parse(
                "{\"preset\":\"hardcore\",\"disabled\":[\"item.give\"]}");
        check(c.isDisabled("item.give"), "显式 disabled 生效");
        check(!c.isDisabled("trade.escrow"), "显式 disabled 覆盖掉了预设底稿");
        check(warned(c, "trade.escrow"), "覆盖掉预设底稿要警告（免得静默放开）");

        CapabilityConfig empty = CapabilityConfig.parse("{\"preset\":\"hardcore\",\"disabled\":[]}");
        eq(empty.disabled().size(), 0, "写空数组 = 一个都不关（预设被覆盖）");
        check(warned(empty, "覆盖了预设"), "空数组清掉底稿同样要警告");

        CapabilityConfig kept = CapabilityConfig.parse(
                "{\"preset\":\"hardcore\",\"disabled\":[\"trade.escrow\",\"item.give\"]}");
        check(!warned(kept, "覆盖了预设"), "底稿原样保留：没有覆盖警告");

        CapabilityConfig standardEmpty = CapabilityConfig.parse("{\"preset\":\"standard\",\"disabled\":[]}");
        check(!warned(standardEmpty, "覆盖了预设"), "standard 底稿为空：没有覆盖警告");

        // 显式 boundary 逐项覆盖预设
        CapabilityConfig b = CapabilityConfig.parse(
                "{\"preset\":\"open\",\"boundary\":{\"resource_move\":false}}");
        eq(b.boundary().resourceMove(), false, "显式 false 覆盖 open 的 true");
        eq(b.boundary().remoteMachineRead(), true, "没写的项仍按预设");

        // plain 也可以被关（免审批 ≠ 服主管不了）
        CapabilityConfig plainOff = CapabilityConfig.parse("{\"disabled\":[\"trade.escrow\"]}");
        check(plainOff.isDisabled("trade.escrow"), "plain 档能力照样能关");
    }

    static void badInput() {
        CapabilityConfig unknownCap = CapabilityConfig.parse("{\"disabled\":[\"item.give\",\"economy.pay\"]}");
        eq(unknownCap.disabled().size(), 1, "目录外能力 id 被丢");
        check(unknownCap.isDisabled("item.give"), "认得的那条留着");
        check(warned(unknownCap, "economy.pay"), "丢掉的要报出名字");

        CapabilityConfig typo = CapabilityConfig.parse("{\"currencys\":[]}");
        check(warned(typo, "currencys"), "段名写错要明确报出来");

        CapabilityConfig boundaryTypo = CapabilityConfig.parse(
                "{\"boundary\":{\"resource_mve\":true}}");
        check(warned(boundaryTypo, "resource_mve"), "boundary 里不认识的开关要报出来");

        CapabilityConfig badType = CapabilityConfig.parse("{\"disabled\":\"item.give\"}");
        check(warned(badType, "disabled"), "类型不对要警告");
        eq(badType.disabled().size(), 0, "类型不对时按预设（空）");

        CapabilityConfig notObject = CapabilityConfig.parse("[]");
        eq(notObject.preset(), CapabilityConfig.Preset.STANDARD, "顶层不是对象 → 默认");
        check(warned(notObject, "对象"), "顶层不是对象要警告");

        CapabilityConfig broken = CapabilityConfig.parse("{oops");
        eq(broken.preset(), CapabilityConfig.Preset.STANDARD, "坏 JSON → 默认（不抛）");
        check(!broken.warnings().isEmpty(), "坏 JSON 要留警告");

        // 下划线键是说明键：忽略且不警告
        CapabilityConfig under = CapabilityConfig.parse("{\"_comment\":[\"hi\"],\"preset\":\"standard\"}");
        check(under.warnings().isEmpty(), "下划线说明键不产生警告");
    }

    static void fileIsReadOnly() throws Exception {
        Path dir = Files.createTempDirectory("mcphone-cap-test");
        Path file = CapabilityConfig.pathIn(dir);
        check(!Files.exists(file), "一开始没有文件");

        CapabilityConfig first = CapabilityConfig.load(file);
        check(Files.isRegularFile(file), "首次装载生成模板");
        eq(first.preset(), CapabilityConfig.Preset.STANDARD, "模板即默认配置");
        String template = Files.readString(file, StandardCharsets.UTF_8);
        check(template.contains("_comment"), "模板带下划线说明键");
        check(template.contains("\"disabled\""), "模板带 disabled");

        // 服主改过之后：装载不重写（对抗④）
        String custom = "{\"_note\":\"mine\",\"preset\":\"hardcore\",\"disabled\":[\"trade.escrow\"]}\n";
        Files.writeString(file, custom, StandardCharsets.UTF_8);
        CapabilityConfig second = CapabilityConfig.load(file);
        eq(second.preset(), CapabilityConfig.Preset.HARDCORE, "读到服主写的值");
        eq(Files.readString(file, StandardCharsets.UTF_8), custom, "装载之后文件一个字节都不动");
    }

    /**
     * S18-B1：{@code boundary} 六个开关本步没有执行面消费者（所以 preset 现在没有运行期差别）。
     * 白名单就是**接线签字处**：哪张卡把某个开关接进执行面，就把方法名加进来 —— 否则这里当场红。
     */
    static void boundaryConsumers() throws Exception {
        java.util.Set<String> wired = java.util.Set.of();
        java.util.Set<String> observed = new java.util.TreeSet<>();
        Path root = Path.of("..", "..").toAbsolutePath().normalize();
        try (var stream = Files.walk(root)) {
            for (Path f : stream
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
                    .toList()) {
                if (f.getFileName().toString().equals("CapabilityConfig.java")) continue;
                // 宽口径（S18-C3）：只认 `.boundary()` 这个取用点，不要求后面紧跟链式调用 ——
                // `Boundary b = cfg.boundary();` 这种两步读取也要进闸。类内部的局部变量
                // `boundary.resourceMove()` 前面没有点，不会误报。
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("\\.boundary\\(\\)")
                        .matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) observed.add(f.getFileName().toString());
            }
        }
        eq(observed, new java.util.TreeSet<>(wired),
                "boundary 开关的消费者集合 = 接线白名单（本步为空；接了线就来这里签字）");
    }

    /**
     * S18-E3：坏 JSON / 顶层不是对象 ⇒ {@code loadError} 非空（"这份不可用"），
     * {@code load(file, previous)} 保留上一份生效的关停项，不许静默全开。
     */
    static void badJsonKeepsPrevious() throws Exception {
        check(!CapabilityConfig.parse("[]").loadError().isEmpty(), "顶层不是对象：loadError 非空");
        check(!CapabilityConfig.parse("{oops").loadError().isEmpty(), "坏 JSON：loadError 非空");

        Path dir = Files.createTempDirectory("mcphone-cap-previous");
        Path file = CapabilityConfig.pathIn(dir);
        CapabilityConfig previous = CapabilityConfig.parse("{\"disabled\":[\"storage.self\"]}");
        check(previous.loadError().isEmpty(), "好配置没有 loadError");
        Files.createDirectories(file.getParent());

        Files.writeString(file, "{oops", StandardCharsets.UTF_8);
        CapabilityConfig kept = CapabilityConfig.load(file, previous);
        check(!kept.loadError().isEmpty(), "坏文件：loadError 非空");
        check(kept.isDisabled("storage.self"), "坏文件没有把上一份的关停清掉");

        Files.writeString(file, "{\"disabled\":[\"loot.roll\"]}", StandardCharsets.UTF_8);
        CapabilityConfig fresh = CapabilityConfig.load(file, previous);
        check(fresh.loadError().isEmpty(), "好文件：loadError 空");
        check(fresh.isDisabled("loot.roll") && !fresh.isDisabled("storage.self"), "好文件正常替换快照");
    }

    /** S18-E4：重复键 = 两种读法 ⇒ 整份拒（和 manifest 同一套严格扫描）。 */
    static void duplicateKeysRejected() {
        CapabilityConfig dup = CapabilityConfig.parse(
                "{\"disabled\":[\"storage.global.read\"],\"disabled\":[]}");
        check(!dup.loadError().isEmpty(), "重复键：loadError 非空");
        check(warned(dup, "重复键"), "重复键要报出来");
        check(!dup.isDisabled("storage.global.read"), "重复键的结果不生效（整份被拒）");
    }

    /** S18-E5：disabled 超限截断 + 一条汇总警告；超大文件整份拒（保留上一份）。 */
    static void sizeGuards() throws Exception {
        StringBuilder big = new StringBuilder("{\"disabled\":[");
        for (int i = 0; i < CapabilityConfig.MAX_DISABLED + 20; i++) {
            if (i > 0) big.append(',');
            big.append("\"command.template:t").append(i).append('"');
        }
        big.append("]}");
        CapabilityConfig capped = CapabilityConfig.parse(big.toString());
        check(capped.loadError().isEmpty(), "超限不是不可用，只是截断");
        eq(capped.disabled().size(), CapabilityConfig.MAX_DISABLED, "截断到上限条");
        check(capped.isDisabled("command.template:t0"), "前 256 条留着");
        check(!capped.isDisabled("command.template:t" + CapabilityConfig.MAX_DISABLED), "第 257 条没进来");
        check(warned(capped, "超过上限"), "要有一条汇总警告");

        Path dir = Files.createTempDirectory("mcphone-cap-big");
        Path file = CapabilityConfig.pathIn(dir);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"disabled\":[]}" + " ".repeat(CapabilityConfig.MAX_FILE_BYTES),
                StandardCharsets.UTF_8);
        CapabilityConfig over = CapabilityConfig.load(file);
        check(!over.loadError().isEmpty(), "超大文件：loadError 非空");
        check(over.loadError().contains("太大"), "报出'太大'：" + over.loadError());
    }

    public static void main(String[] args) throws Exception {
        defaultsAndPresets();
        explicitOverrides();
        badInput();
        fileIsReadOnly();
        badJsonKeepsPrevious();
        duplicateKeysRejected();
        sizeGuards();
        boundaryConsumers();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
