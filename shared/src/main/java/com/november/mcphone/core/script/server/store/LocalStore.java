package com.november.mcphone.core.script.server.store;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.november.mcphone.MCphone;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code local} 档：玩家自己机器上的文件（施工方案 §17.2）。<b>默认档。</b>
 *
 * <pre>config/mcphone/appdata/&lt;serverId&gt;/&lt;appId&gt;/local.json</pre>
 *
 * <ul>
 *   <li><b>按 serverId 分桶</b>（§13.5 那个随机 UUID），<b>不是按世界键</b>。
 *       用世界键的话，同一台服务器换个世界名，玩家的数据就找不回来了（§27.3 CMP-03）</li>
 *   <li><b>不加密</b> —— 这是玩家自己的机器，他本来就能看</li>
 *   <li><b>永不上传</b>：任何网络包里都不许出现 local 档的值</li>
 *   <li>配额 {@link StoreQuota#LOCAL_PER_APP} / {@link StoreQuota#LOCAL_PER_VALUE} / {@link StoreQuota#LOCAL_KEYS}</li>
 * </ul>
 *
 * <p>作者指南里那一句：<b>除非真的需要跨设备，token 一律用 local。</b>
 *
 * <h2>脚本面的名字</h2>
 *
 * 方案的 §16.5 表里没写 local 档的脚本面 API。本步定的口径是：
 * <b>客户端侧沿用 {@code ctx.store.*} 的同名族</b>（{@code getString/setString/getLong/setLong/
 * getBool/setBool/remove/keys}），<b>不新造 {@code ctx.localStore} 这类第三套命名</b> ——
 * 作者不该为了"存在哪一档"去记两套方法名，档位是写入时选的，不是名字里带的。
 */
public final class LocalStore {

    private static final Set<String> WINDOWS_DEVICES = Set.of(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    /** 相对游戏目录的根。 */
    public static final String ROOT = "config/mcphone/appdata";

    private final Path file;
    private JsonObject values;

    private LocalStore(Path file, JsonObject values) {
        this.file = file;
        this.values = values;
    }

    /** 这个 App 在这台服务器上的那一份。读不出来就从空的开始。 */
    public static LocalStore open(Path gameDir, String serverId, String appId) {
        Path p = gameDir.resolve(ROOT).resolve(safe(serverId)).resolve(safe(appId)).resolve("local.json");
        JsonObject obj = new JsonObject();
        if (Files.isRegularFile(p)) {
            try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
                var parsed = JsonParser.parseReader(r);
                if (parsed.isJsonObject()) obj = parsed.getAsJsonObject();
            } catch (IOException | RuntimeException e) {
                // 坏文件不该让 App 打不开。原样留着，下次写入时覆盖
                MCphone.LOGGER.warn("[MCphone] local 档读不了 {}: {}", p, e.toString());
            }
        }
        return new LocalStore(p, obj);
    }

    /** 路径里的一段。serverId 与 appId 都是我们自己给的，这一道是防手改配置。 */
    static String safe(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.';
            sb.append(ok ? c : '_');
        }
        String result = sb.isEmpty() ? "_" : sb.toString();
        if (result.equals(".") || result.equals("..") || result.chars().allMatch(c -> c == '.')) {
            return "_";
        }
        String base = result;
        int dot = base.indexOf('.');
        if (dot >= 0) base = base.substring(0, dot);
        if (WINDOWS_DEVICES.contains(base.toUpperCase(Locale.ROOT))) return "_" + result;
        return result;
    }

    public String getString(String key, String fallback) {
        var v = values.get(key);
        return v != null && v.isJsonPrimitive() ? v.getAsString() : fallback;
    }

    public long getLong(String key, long fallback) {
        var v = values.get(key);
        try {
            return v != null && v.isJsonPrimitive() ? Long.parseLong(v.getAsString()) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean getBool(String key, boolean fallback) {
        var v = values.get(key);
        return v != null && v.isJsonPrimitive() ? v.getAsBoolean() : fallback;
    }

    /** 写一个值。<b>超配额抛，不静默截断。</b> */
    public void setString(String key, String value) {
        StoreQuota.checkValue(value, StoreQuota.LOCAL_PER_VALUE, "local");
        StoreQuota.checkAdd(values.size(), bytes(), !values.has(key),
                key.length() + value.getBytes(StandardCharsets.UTF_8).length,
                StoreQuota.LOCAL_KEYS, StoreQuota.LOCAL_PER_APP, "local");
        values.add(key, new JsonPrimitive(value));
    }

    public void setLong(String key, long value) {
        // 十进制字符串，不是数字：毫秒时间戳与计数会超过 2^53（§23.3 同一条理由）
        setString(key, Long.toString(value));
    }

    public void setBool(String key, boolean value) {
        values.add(key, new JsonPrimitive(value));
    }

    public void remove(String key) {
        values.remove(key);
    }

    public List<String> keys() {
        return new ArrayList<>(values.keySet());
    }

    /** 现在占多少字节。 */
    public long bytes() {
        long n = 0;
        for (var e : values.entrySet()) {
            n += e.getKey().length();
            n += e.getValue().toString().getBytes(StandardCharsets.UTF_8).length;
        }
        return n;
    }

    /** 落盘；返回 false 让调用方能感知失败，而不是只在日志里留下线索。 */
    public boolean save() {
        try {
            Files.createDirectories(file.getParent());
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write(values.toString());
            }
            return true;
        } catch (IOException e) {
            MCphone.LOGGER.warn("[MCphone] local 档写不了 {}: {}", file, e.toString());
            return false;
        }
    }

    /** 给测试用：不落盘，直接看内容。 */
    public JsonObject snapshot() {
        return values.deepCopy();
    }
}
