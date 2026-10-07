package com.november.mcphone.core.script;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** RPC 与页面共用的有界值域：int/string/bool/null/array/object。大数与小数必须用十进制字符串。 */
public final class JsonValues {
    public static final int MAX_BYTES = 4096;
    private static final Gson GSON = new Gson();
    private JsonValues() { }

    public static Object decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return Map.of();
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("RPC 数据超过 4 KiB");
        try {
            String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            JsonScan.Problem p = JsonScan.check(json, 8);
            if (p != null) throw new IllegalArgumentException("RPC JSON：" + p.detail());
            return value(JsonParser.parseString(json));
        } catch (java.nio.charset.CharacterCodingException e) {
            throw new IllegalArgumentException("RPC 数据不是 UTF-8", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(byte[] bytes) {
        Object v = decode(bytes);
        if (!(v instanceof Map<?, ?>)) throw new IllegalArgumentException("RPC 参数必须是 JSON 对象");
        return (Map<String, Object>) v;
    }

    public static String encode(Map<String, Object> values) {
        String json = GSON.toJson(values);
        object(json.getBytes(StandardCharsets.UTF_8));
        return json;
    }

    private static Object value(JsonElement e) {
        if (e.isJsonNull()) return null;
        if (e.isJsonObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (var entry : e.getAsJsonObject().entrySet()) {
                String k = entry.getKey();
                if (k.length() > 64 || k.chars().anyMatch(Character::isISOControl)
                        || k.equals("__proto__") || k.equals("constructor") || k.equals("prototype"))
                    throw new IllegalArgumentException("RPC 对象键非法");
                map.put(k, value(entry.getValue()));
            }
            return Collections.unmodifiableMap(map);
        }
        if (e.isJsonArray()) {
            if (e.getAsJsonArray().size() > 64) throw new IllegalArgumentException("RPC 数组超过 64 项");
            var list = new ArrayList<>();
            for (JsonElement item : e.getAsJsonArray()) list.add(value(item));
            return Collections.unmodifiableList(list);
        }
        var p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) {
            try { return p.getAsBigDecimal().intValueExact(); }
            catch (ArithmeticException ex) { throw new IllegalArgumentException("RPC 大数或小数请使用字符串", ex); }
        }
        String s = p.getAsString();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= s.length() || !Character.isLowSurrogate(s.charAt(i)))
                    throw new IllegalArgumentException("RPC 字符串含孤立代理字符");
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("RPC 字符串含孤立代理字符");
        }
        return s;
    }
}
