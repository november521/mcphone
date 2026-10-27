package com.november.mcphone.core.script.layout;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Small immutable navigation values, independent of page state and the script engine. */
public record PageParameters(Map<String, Object> values) {
    public static final int MAX_KEYS = 4;
    public static final int MAX_STRING = 256;
    public static final PageParameters EMPTY = new PageParameters(Map.of());

    public PageParameters {
        if (values == null || values.size() > MAX_KEYS) throw new IllegalArgumentException("导航参数最多 4 个字段");
        Map<String, Object> copy = new LinkedHashMap<>();
        for (var entry : values.entrySet()) {
            String key = entry.getKey();
            if (key == null || !StateRules.KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("导航参数字段名要" + StateRules.KEY_RULE);
            }
            Object value = entry.getValue();
            if (!(value instanceof String || value instanceof Integer || value instanceof Boolean)) {
                throw new IllegalArgumentException("导航参数只能是字符串、32 位整数或布尔值");
            }
            if (value instanceof String text) {
                if (text.length() > MAX_STRING) throw new IllegalArgumentException("导航参数字符串最多 256 个 UTF-16 单元");
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (Character.isHighSurrogate(c)) {
                        if (++i < text.length() && Character.isLowSurrogate(text.charAt(i))) continue;
                        throw new IllegalArgumentException("导航参数包含不完整的 Unicode 字符");
                    }
                    if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("导航参数包含不完整的 Unicode 字符");
                }
            }
            copy.put(key, value);
        }
        values = Collections.unmodifiableMap(copy);
    }

    public static PageParameters from(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("nav 的第二个参数必须是普通对象");
        if (map.size() > MAX_KEYS) throw new IllegalArgumentException("导航参数最多 4 个字段");
        Map<String, Object> fields = new LinkedHashMap<>();
        for (var entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("导航参数字段名必须是字符串");
            fields.put(key, entry.getValue());
        }
        return fields.isEmpty() ? EMPTY : new PageParameters(fields);
    }
}
