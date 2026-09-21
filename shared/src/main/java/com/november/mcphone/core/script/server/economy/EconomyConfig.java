package com.november.mcphone.core.script.server.economy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.JsonScan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 货币配置（施工方案 §22.7/§22.8，S15d′）：读
 * {@code <世界目录>/serverconfig/mcphone-economy.json}，产出 {@link CurrencySpec} 表。
 *
 * <h2>语义只有一份</h2>
 *
 * 七字段的名字/默认值/校验全部住在 {@link CurrencySpec#from(Map)}；这里只做"读文件 + 定位行号 +
 * 收集坏条目"，<b>不复制任何校验规则</b>。坏字段把 {@code CurrencySpec.from} 抛出的那句话
 * 原样带上，前面补"第 N 行"。
 *
 * <h2>坏配置的处置（E28，全条沿用）</h2>
 *
 * <ul>
 *   <li>未知顶层段（如 {@code currencys}）→ 明确报出来，不静默；</li>
 *   <li>一条货币里未知字段 / 字段非法 / id 重复 → <b>只丢这一条</b>，其余照常；</li>
 *   <li>{@code _} 开头的键（顶层与条目内）直接跳过，是说明键；</li>
 *   <li>重复 JSON 键（整份有两种读法）→ 整份不可用（共用 {@link JsonScan}，与能力配置同一套）；
 *       文件不是 JSON / 读不出来 → 空表 + 一条 problem，服务器照常起。</li>
 * </ul>
 *
 * <h2>为什么不是纯流式</h2>
 *
 * 卡里写的是"流式 {@code JsonReader} + 顺手记行号"，但仓库里的 Gson 2.10 <b>不暴露行号</b>
 * （{@code javap} 核过：公开面只有 {@code getPath()}）。所以换成：{@link JsonScan} 过严格形状 →
 * {@code JsonParser} 成树 → 一遍轻量扫描定位行号（与 {@code SfcCompiler.keyLines} 同思路）。
 * 行为等价，且行号反而更准（能指到具体字段那一行）。
 *
 * <p><b>只读不写</b>：只有"文件不存在"时生成一次模板；跑过之后文件一个字节都不动。
 */
public final class EconomyConfig {

    /** 相对世界根的文件名（与 {@code EconomyRuntime} 现有流水落点同一写法）。 */
    public static final String FILE = "serverconfig/mcphone-economy.json";

    /** 顶层唯一认得的段。 */
    public static final String SECTION = "currency";

    /** 一条货币认得的七个字段（顺序照 §22.7 的表）。 */
    public static final List<String> FIELDS =
            List.of("id", "name", "symbol", "decimals", "provider", "default", "max");

    /** 配置文件大小闸（对抗 D′3，与能力配置同一档）：正常模板 1 KiB 量级，64 KiB 已经宽得没边。 */
    public static final int MAX_FILE_BYTES = 64 * 1024;

    /** 货币条数上限（对抗 D′3）：一台服远用不到这么多；超出的只取前 N 条 + 一条汇总 problem。 */
    public static final int MAX_CURRENCIES = 64;

    /** 解析结果：可用的表（顺序 = 文件顺序）+ 逐条 problem（人类可读，带行号）。 */
    public record Result(List<CurrencySpec> specs, List<String> problems) {
        public Result {
            specs = List.copyOf(specs);
            problems = List.copyOf(problems);
        }

        public boolean clean() {
            return problems.isEmpty();
        }
    }

    private EconomyConfig() {
    }

    /** 世界根下的配置路径。 */
    public static Path pathIn(Path worldRoot) {
        return worldRoot.resolve(FILE);
    }

    /** 从文件读；文件不存在就生成模板（只此一次）。任何失败都不抛。 */
    public static Result load(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                try {
                    Files.createDirectories(file.getParent());
                    Files.writeString(file, template(), StandardCharsets.UTF_8);
                    MCphone.LOGGER.info("[MCphone] 货币配置不存在，已生成模板：{}（空表；配了才有钱）", file);
                } catch (IOException e) {
                    MCphone.LOGGER.error("[MCphone] 生成货币配置模板失败（{}），按空表继续", file, e);
                }
                return new Result(List.of(), List.of());
            }
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                MCphone.LOGGER.error("[MCphone] 货币配置太大（{} 字节 > {}），按空表继续", size, MAX_FILE_BYTES);
                return new Result(List.of(), List.of("配置文件太大（" + size + " 字节 > "
                        + MAX_FILE_BYTES + "），整份不加载"));
            }
            String json = Files.readString(file, StandardCharsets.UTF_8);
            return parse(json);
        } catch (IOException e) {
            MCphone.LOGGER.error("[MCphone] 货币配置读不出来（{}），按空表继续", file, e);
            return new Result(List.of(), List.of("配置文件读不出来：" + e.getMessage()));
        }
    }

    /** 解析一段 JSON；不抛。问题都进 {@link Result#problems()}。 */
    public static Result parse(String json) {
        List<String> problems = new ArrayList<>();
        JsonScan.Problem shape = JsonScan.check(json, 32);
        if (shape != null) {
            problems.add(shape.detail() + "（整份配置不可用）");
            return new Result(List.of(), problems);
        }

        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            problems.add("不是合法 JSON（" + e.getMessage() + "）（整份配置不可用）");
            return new Result(List.of(), problems);
        }
        if (!parsed.isJsonObject()) {
            problems.add("顶层必须是一个对象（整份配置不可用）");
            return new Result(List.of(), problems);
        }

        Lines lines = scanLines(json);
        JsonObject root = parsed.getAsJsonObject();
        List<CurrencySpec> specs = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            String key = e.getKey();
            if (key.startsWith("_")) continue;
            int keyLine = lines.topKeys().getOrDefault(key, 1);
            if (!SECTION.equals(key)) {
                problems.add("第 " + keyLine + " 行：不认识的段 '" + key
                        + "'（只认 " + SECTION + "；`_` 开头的说明键会被忽略）");
                continue;
            }
            if (!e.getValue().isJsonArray()) {
                problems.add("第 " + keyLine + " 行：'" + SECTION + "' 必须是一个数组（[ {...}, {...} ]）");
                continue;
            }
            JsonArray arr = e.getValue().getAsJsonArray();
            int take = Math.min(arr.size(), MAX_CURRENCIES);
            if (arr.size() > take) {
                problems.add("'currency' 有 " + arr.size() + " 条，超过上限 " + MAX_CURRENCIES
                        + "，只取前 " + take + " 条");
            }
            for (int i = 0; i < take; i++) {
                int entryLine = i < lines.entryLines().size() ? lines.entryLines().get(i) : 1;
                if (!arr.get(i).isJsonObject()) {
                    problems.add("第 " + entryLine + " 行：这一条不是对象（已丢弃）");
                    continue;
                }
                readEntry(arr.get(i).getAsJsonObject(), i, entryLine, lines, specs, seenIds, problems);
            }
        }
        return new Result(specs, problems);
    }

    private static void readEntry(JsonObject obj, int index, int entryLine, Lines lines,
                                  List<CurrencySpec> specs, Set<String> seenIds, List<String> problems) {
        Map<String, Integer> fieldLines =
                index < lines.entryKeyLines().size() ? lines.entryKeyLines().get(index) : Map.of();
        Map<String, Object> table = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> f : obj.entrySet()) {
            String field = f.getKey();
            if (field.startsWith("_")) continue;
            int fieldLine = fieldLines.getOrDefault(field, entryLine);
            if (!FIELDS.contains(field)) {
                problems.add("第 " + entryLine + " 行这一条已丢弃：不认识的字段 '" + field + "'（第 "
                        + fieldLine + " 行）（认得的字段：" + String.join("/", FIELDS) + "）");
                return;
            }
            JsonElement value = f.getValue();
            if (value.isJsonObject() || value.isJsonArray()) {
                problems.add("第 " + entryLine + " 行这一条已丢弃：字段 '" + field + "'（第 " + fieldLine
                        + " 行）不接受对象/数组");
                return;
            }
            table.put(field, primitive(value));
        }
        CurrencySpec spec;
        try {
            spec = CurrencySpec.from(table);
        } catch (IllegalArgumentException e) {
            problems.add("第 " + entryLine + " 行这一条已丢弃：" + e.getMessage()
                    + "（认得的字段：" + String.join("/", FIELDS) + "）");
            return;
        }
        if (!seenIds.add(spec.id())) {
            problems.add("第 " + entryLine + " 行这一条已丢弃：id '" + spec.id() + "' 重复（唯一实例纪律）");
            return;
        }
        specs.add(spec);
    }

    /** {@code JsonElement} → 原语（字符串/数字/布尔/null），交给 {@code CurrencySpec.from} 按同一口径解释。 */
    private static Object primitive(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        JsonPrimitive p = el.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) {
            String raw = p.getAsString();
            try {
                return Long.valueOf(raw);
            } catch (NumberFormatException e) {
                return p.getAsDouble();   // 小数/科学计数：让 CurrencySpec 的 num 按"要整数"拒
            }
        }
        return p.getAsString();
    }

    // ---------------------------------------------------------------- 行号

    /** 顶层键的行、currency 每个元素的行、元素内字段的行。 */
    record Lines(Map<String, Integer> topKeys, List<Integer> entryLines,
                 List<Map<String, Integer>> entryKeyLines) {
    }

    /**
     * 轻量行号扫描：只找"顶层键"与 "currency 数组每个元素的行 + 元素内字段的行"。
     * 字符串/转义感知地走一遍原文；不判合法（{@link JsonScan} 已经判过），够给错误定位就行。
     *
     * <p><b>行号按"元素序号"对齐</b>（对抗 D′2）：数组里混非对象（`[{}, 5, {}]`）或嵌套数组时，
     * 行表与 `parse` 的下标也要一一对应；非对象元素同样占一行（空字段表），不许按 `{` 数量错位。
     */
    static Lines scanLines(String json) {
        Map<String, Integer> topKeys = new LinkedHashMap<>();
        List<Integer> entryLines = new ArrayList<>();
        List<Map<String, Integer>> entryKeyLines = new ArrayList<>();
        int line = 1;
        int depth = 0;
        boolean inCurrency = false;
        int currencyDepth = -1;
        boolean expectElement = false;
        String lastTopKey = "";
        int i = 0;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\n') {
                line++;
                i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '"') {
                int start = i + 1;
                i++;
                while (i < json.length()) {
                    char d = json.charAt(i);
                    if (d == '\\') {
                        i += 2;
                        continue;
                    }
                    if (d == '"') break;
                    i++;
                }
                String text = unescape(json.substring(start, Math.min(i, json.length())));
                i = Math.min(i + 1, json.length());
                if (inCurrency && depth == currencyDepth && expectElement) {
                    entryLines.add(line);
                    entryKeyLines.add(new LinkedHashMap<>());
                    expectElement = false;
                }
                int j = i;
                while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
                boolean isKey = j < json.length() && json.charAt(j) == ':';
                if (isKey) {
                    if (depth == 1) {
                        topKeys.putIfAbsent(text, line);
                        lastTopKey = text;
                    } else if (inCurrency && depth == currencyDepth + 1 && !entryKeyLines.isEmpty()) {
                        entryKeyLines.get(entryKeyLines.size() - 1).putIfAbsent(text, line);
                    }
                }
                continue;
            }
            if (c == '{' || c == '[') {
                if (inCurrency && depth == currencyDepth && expectElement) {
                    entryLines.add(line);
                    entryKeyLines.add(new LinkedHashMap<>());
                    expectElement = false;
                }
                depth++;
                if (c == '[' && depth == 2 && SECTION.equals(lastTopKey)) {
                    inCurrency = true;
                    currencyDepth = depth;
                    expectElement = true;
                }
                i++;
                continue;
            }
            if (c == ',') {
                if (inCurrency && depth == currencyDepth) expectElement = true;
                i++;
                continue;
            }
            if (c == ']' && inCurrency && depth == currencyDepth) {
                inCurrency = false;
                expectElement = false;
                depth--;
                i++;
                continue;
            }
            if (c == '}' || c == ']') {
                depth--;
                if (inCurrency && depth < currencyDepth) inCurrency = false;
                i++;
                continue;
            }
            // 数字 / true / false / null 这类裸元素：也是数组的一员，同样占一行
            if (inCurrency && depth == currencyDepth && expectElement) {
                entryLines.add(line);
                entryKeyLines.add(new LinkedHashMap<>());
                expectElement = false;
            }
            i++;
        }
        return new Lines(Map.copyOf(topKeys), List.copyOf(entryLines), List.copyOf(entryKeyLines));
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                b.append(c);
                continue;
            }
            char e = s.charAt(++i);
            switch (e) {
                case 'n' -> b.append('\n');
                case 't' -> b.append('\t');
                case 'r' -> b.append('\r');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'u' -> {
                    if (i + 4 < s.length()) {
                        try {
                            b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                            i += 4;
                        } catch (NumberFormatException ex) {
                            b.append(e);
                        }
                    } else {
                        b.append(e);
                    }
                }
                default -> b.append(e);
            }
        }
        return b.toString();
    }

    /** 首次生成用的模板（只在文件不存在时写一次；<b>不写示例货币</b>）。 */
    public static String template() {
        return "{\n"
                + "  \"_comment\": [\n"
                + "    \"MCphone 货币配置。文件不存在时自动生成；此后只读，不会被改写。\",\n"
                + "    \"每条货币七字段：id / name / symbol / decimals / provider / default / max；\",\n"
                + "    \"provider 认 builtin | scoreboard | adapter | emc_legacy；不认识就整条丢弃并报行号。\",\n"
                + "    \"多条 default=true 时取第一条；没有 default 就没有默认货币（ctx.currency.default() 为 null）。\"\n"
                + "  ],\n"
                + "  \"currency\": []\n"
                + "}\n";
    }
}
