package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.core.script.JsonScan;
import java.math.BigDecimal;
import java.util.*;

/** 已签名包中的声明式守卫。解析失败整包拒绝，绝不忽略坏守卫。 */
public record ActionGuards(List<Rule> rules) {
    public static final ActionGuards NONE = new ActionGuards(List.of());
    public static final int MAX_RULES = 16;
    public record Rule(String kind, String scope, String label, long value, long end, String predicate) { }
    public ActionGuards { rules = List.copyOf(rules); }

    public static Map<String, ActionGuards> parse(String json) {
        JsonScan.Problem problem = JsonScan.check(json, 12);
        if (problem != null) throw new IllegalArgumentException(problem.detail());
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<String, ActionGuards> out = new LinkedHashMap<>();
        if (!root.has("actions")) return Map.of();
        for (JsonElement el : root.getAsJsonArray("actions")) {
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                put(out, el.getAsString(), NONE);
                continue;
            }
            if (!el.isJsonObject()) throw new IllegalArgumentException("动作必须是字符串或对象");
            JsonObject action = el.getAsJsonObject();
            String id = text(action, "id", "");
            List<Rule> rules = new ArrayList<>();
            if (action.has("guards")) {
                JsonArray guards = action.getAsJsonArray("guards");
                if (guards.size() > MAX_RULES) throw new IllegalArgumentException("守卫超额");
                Set<String> kinds = new HashSet<>();
                for (JsonElement ge : guards) {
                    JsonObject g = ge.getAsJsonObject();
                    List<String> found = List.of("window", "predicate", "cooldown", "once", "limit", "cost")
                            .stream().filter(g::has).toList();
                    if (found.size() != 1) throw new IllegalArgumentException("每个守卫只允许一种类型");
                    String kind = found.get(0);
                    if (!kinds.add(kind)) throw new IllegalArgumentException("重复守卫 " + kind);
                    String scope = text(g, "scope", "player");
                    if (!scope.equals("global") && !scope.equals("player")) throw new IllegalArgumentException("守卫 scope 非法");
                    String label = text(g, "label", "default");
                    if (!label.matches("[A-Za-z0-9_.-]{1,64}")) throw new IllegalArgumentException("守卫 label 非法");
                    long value = 0, end = 0;
                    String predicate = "";
                    switch (kind) {
                        case "cost" -> throw new IllegalArgumentException("首版 cost 不支持（§20.8 策略 A）");
                        case "window" -> {
                            JsonObject w = g.getAsJsonObject("window");
                            value = integer(w.get("startAt")); end = integer(w.get("endAt"));
                            if (value < 0 || end < value) throw new IllegalArgumentException("活动窗口非法");
                        }
                        case "limit" -> {
                            value = integer(g.get("limit"));
                            if (value < 1 || value > Integer.MAX_VALUE) throw new IllegalArgumentException("限量必须为正整数");
                            if (!g.has("label")) throw new IllegalArgumentException("限量必须声明 label");
                        }
                        case "cooldown" -> value = duration(text(g, "cooldown", ""));
                        case "once" -> {
                            if (!g.get("once").isJsonPrimitive() || !g.get("once").getAsJsonPrimitive().isBoolean()
                                    || !g.get("once").getAsBoolean()) throw new IllegalArgumentException("once 必须为 true");
                            value = Long.MAX_VALUE;
                        }
                        case "predicate" -> {
                            predicate = text(g, "predicate", "");
                            if (!predicate.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("谓词 id 非法");
                        }
                    }
                    rules.add(new Rule(kind, scope, label, value, end, predicate));
                }
            }
            put(out, id, new ActionGuards(rules));
        }
        return Collections.unmodifiableMap(out);
    }
    private static void put(Map<String, ActionGuards> out, String id, ActionGuards guards) {
        if (!Deployment.validId(id) || id.contains("|") || id.startsWith("__mcphone_")
                || out.putIfAbsent(id, guards) != null) throw new IllegalArgumentException("动作 id 非法、保留或重复");
        if (out.size() > Deployment.MAX_ACTIONS) throw new IllegalArgumentException("动作超额");
    }
    private static String text(JsonObject o, String key, String fallback) {
        if (!o.has(key)) return fallback;
        JsonElement e = o.get(key);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + " 必须是字符串");
        return e.getAsString();
    }
    private static long integer(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException("必须为整数");
        try { return new BigDecimal(e.getAsString()).longValueExact(); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("整数溢出或含小数", ex); }
    }
    public static long duration(String value) {
        if (!value.matches("[1-9][0-9]{0,9}[smhd]")) throw new IllegalArgumentException("冷却时长非法");
        long multiplier = switch (value.charAt(value.length() - 1)) {
            case 's' -> 1000L; case 'm' -> 60_000L; case 'h' -> 3_600_000L; default -> 86_400_000L;
        };
        try { return Math.multiplyExact(Long.parseLong(value.substring(0, value.length() - 1)), multiplier); }
        catch (ArithmeticException ex) { throw new IllegalArgumentException("冷却时长溢出", ex); }
    }
}
