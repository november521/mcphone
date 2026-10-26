package com.november.mcphone.core.script.server.store;

import com.november.mcphone.core.PhonePaths;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.november.mcphone.MCphone;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code local} 档：玩家自己机器上的文件（施工方案 §17.2）。<b>默认档。</b>
 *
 * <pre>mcphone/data/appdata/&lt;serverId&gt;/&lt;appId&gt;/local.json</pre>
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
    public static final String ROOT = PhonePaths.APP_DATA.relative();

    private final Path file;
    private JsonObject values;

    private LocalStore(Path file, JsonObject values) {
        this.file = file;
        this.values = values;
    }

    /** 这个 App 在这台服务器上的那一份。读不出来就从空的开始。 */
    public static LocalStore open(Path gameDir, String serverId, String appId) {
        Path p = PhonePaths.APP_DATA.in(gameDir).resolve(safe(serverId)).resolve(safe(appId)).resolve("local.json");
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

    /** Frontend-only scope: launcher account and canonical App id, independent of any server handshake. */
    public static Path clientPath(Path gameDir, UUID account, String appId) {
        if (account == null || appId == null || appId.isBlank()) throw new IllegalArgumentException("本地存储身份不可用");
        try {
            String app = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(appId.getBytes(StandardCharsets.UTF_8)));
            return PhonePaths.APP_DATA.in(gameDir).resolve("client").resolve(account.toString()).resolve(app + ".json");
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    /** A damaged or oversized frontend file is kept intact and reported; never replace it with an empty store. */
    public static LocalStore openClient(Path gameDir, UUID account, String appId) {
        Path file = clientPath(gameDir, account, appId);
        JsonObject values = new JsonObject();
        try {
            if (Files.exists(file)) {
                if (!Files.isRegularFile(file)) {
                    throw new IllegalStateException("本地存储文件不合法或超出读取上限");
                }
                byte[] content;
                try (var input = Files.newInputStream(file)) { content = input.readNBytes(StoreQuota.LOCAL_PER_APP * 4 + 1); }
                if (content.length > StoreQuota.LOCAL_PER_APP * 4) throw new IllegalStateException("本地存储文件超出读取上限");
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(content)).toString();
                values = readClientValues(text);
            }
            LocalStore store = new LocalStore(file, new JsonObject());
            for (var entry : values.entrySet()) {
                checkClientKey(entry.getKey());
                if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                    throw new IllegalStateException("本地存储文件包含非字符串值");
                }
                store.setString(entry.getKey(), entry.getValue().getAsString());
            }
            return store;
        } catch (IOException | RuntimeException e) {
            MCphone.LOGGER.warn("[MCphone] 客户端 local 档读取失败 {}: {}", file, e.getClass().getSimpleName());
            throw new IllegalStateException("无法读取本地存储，原文件已保留", e);
        }
    }

    private static JsonObject readClientValues(String text) throws IOException {
        JsonObject values = new JsonObject();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setLenient(false);
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (values.has(key) || reader.peek() != JsonToken.STRING) {
                    throw new IllegalStateException("本地存储字段重复或值不是字符串");
                }
                values.addProperty(key, reader.nextString());
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalStateException("本地存储文件存在多余内容");
        }
        return values;
    }

    /** Captures one account at page-open time; an account switch cannot reuse the previous page's storage. */
    public static Supplier<LocalStore> clientProvider(Path gameDir, UUID account, String appId, Supplier<UUID> activeAccount) {
        return () -> {
            if (gameDir == null || account == null || !account.equals(activeAccount.get())) {
                throw new IllegalStateException("账号已改变或不可用，请重新打开 App");
            }
            return openClient(gameDir, account, appId);
        };
    }

    private static void checkClientKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9_.:-]{1,64}")) {
            throw new IllegalArgumentException("存储键名需为 1–64 个字母、数字或 _ . : -");
        }
    }

    /** One lazy snapshot for an entire click, including multiple function calls; no I/O until first use. */
    public static final class Transaction implements AutoCloseable {
        private final Supplier<LocalStore> provider;
        private LocalStore staged;
        private boolean dirty;
        private boolean closed;

        public Transaction(Supplier<LocalStore> provider) { this.provider = provider; }

        private LocalStore current() {
            if (closed) throw new IllegalStateException("本次按钮操作已经结束，存储接口已失效");
            if (staged == null) {
                if (provider == null) throw new IllegalStateException("本地存储尚未连接");
                LocalStore source = provider.get();
                staged = new LocalStore(source.file, source.values.deepCopy());
            }
            return staged;
        }

        public String getString(String key, String fallback) {
            checkClientKey(key);
            checkText(fallback);
            StoreQuota.checkValue(fallback, StoreQuota.LOCAL_PER_VALUE, "local");
            return current().getString(key, fallback);
        }

        public void setString(String key, String value) {
            checkClientKey(key);
            LocalStore store = current();
            var before = store.values.get(key);
            store.setString(key, value);
            dirty |= !store.values.get(key).equals(before);
        }

        /** Called only after the complete click and its UI state have been validated. */
        public boolean commit() {
            if (closed) throw new IllegalStateException("本次按钮操作已经结束，存储接口已失效");
            return !dirty || staged.save();
        }

        @Override public void close() { closed = true; staged = null; }
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
        checkText(value);
        StoreQuota.checkValue(value, StoreQuota.LOCAL_PER_VALUE, "local");
        put(key, new JsonPrimitive(value));
    }

    public void setLong(String key, long value) {
        // 十进制字符串，不是数字：毫秒时间戳与计数会超过 2^53（§23.3 同一条理由）
        setString(key, Long.toString(value));
    }

    public void setBool(String key, boolean value) {
        put(key, new JsonPrimitive(value));
    }

    private void put(String key, JsonPrimitive value) {
        long replaced = values.has(key) ? entryBytes(key, values.get(key)) : 0;
        StoreQuota.checkAdd(values.size(), bytes() - replaced, !values.has(key), Math.toIntExact(entryBytes(key, value)),
                StoreQuota.LOCAL_KEYS, StoreQuota.LOCAL_PER_APP, "local");
        values.add(key, value);
    }

    private static void checkText(String value) {
        if (value == null) throw new IllegalArgumentException("本地存储只接受字符串");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) {
                    throw new IllegalArgumentException("字符串包含不完整的 Unicode 字符");
                }
            } else if (Character.isLowSurrogate(c)) throw new IllegalArgumentException("字符串包含不完整的 Unicode 字符");
        }
    }

    private static long entryBytes(String key, com.google.gson.JsonElement value) {
        return key.getBytes(StandardCharsets.UTF_8).length + value.toString().getBytes(StandardCharsets.UTF_8).length;
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
            n += entryBytes(e.getKey(), e.getValue());
        }
        return n;
    }

    /** 落盘；返回 false 让调用方能感知失败，而不是只在日志里留下线索。 */
    public boolean save() {
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "local-", ".tmp");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer content = ByteBuffer.wrap(values.toString().getBytes(StandardCharsets.UTF_8));
                while (content.hasRemaining()) channel.write(content);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            temporary = null;
            return true;
        } catch (IOException | SecurityException e) {
            MCphone.LOGGER.warn("[MCphone] local 档写不了 {}: {}", file, e.getClass().getSimpleName());
            return false;
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException | SecurityException e) { MCphone.LOGGER.warn("[MCphone] local 临时文件清理失败: {}", temporary); }
            }
        }
    }

    /** 给测试用：不落盘，直接看内容。 */
    public JsonObject snapshot() {
        return values.deepCopy();
    }
}
