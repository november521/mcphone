package com.november.mcphone.core.script.pkg;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.november.mcphone.core.script.JsonScan;

import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * manifest.json（施工方案 §3.2）。字段校验全部硬失败，没有"尽力而为"的分支。
 *
 * <p>{@code id} 的两段与 {@code ui} 的两条路径都拆开存：调用点再去切一次字符串，就会有第二份切法。
 *
 * <p>两种来源：{@link #parse} 读包里的 manifest.json，icon 是包内路径；{@link #parseInline} 读 .vue 里的 {@code <manifest>}，
 * icon 是 data URI（或 null），uiTree / uiStyle / engine 为 null。拿 icon 当路径用之前先看是哪一种。
 *
 * <p>{@link #uiTree} 是前端入口，两种写法在这里收敛成一个字段：写了 {@code ui} 就是它指的那个文件
 * （§4.2 的 ui.json），没写就是 {@link #DEFAULT_ENTRY}（§11.2 的 app.vue）。{@code uiStyle} 只有前一种才有。
 */
public record Manifest(
        int format,
        String namespace,
        String path,
        String version,
        String name,
        String author,
        String description,
        String icon,
        String uiTree,
        String uiStyle,
        String engine,
        /** 这个 App 要用哪些 SDK、各自要几版（§23.4）。没写就是空表，<b>不是 null</b>。 */
        Map<String, Integer> sdk) {

    /** 本轮只认这一个包格式版本。 */
    public static final int FORMAT = 1;

    /** 本轮只认这一个引擎。 */
    public static final String ENGINE = "declarative-1";

    /** 内建 App 的命名空间。第三方占了它，PhoneScreenRegistry 的 id 去重会把内建 App 挡在外面。 */
    public static final String RESERVED_NAMESPACE = "mcphone";

    /**
     * 整条 {@code namespace:path} 的长度上限，与线格式的 {@code ScriptProtocol.ID_MAX}、
     * 部署表的 {@code Deployment.MAX_ID_LEN} <b>是同一个数</b>（三处各有一个 64，改一个就得三处一起改）。
     *
     * <p><b>注意 {@link #ID_SEGMENT} 那个 64 是「每段」的上限</b>：两段各 64 拼出来 129 字符，
     * 客户端装得下、`ScriptRpc` 却编码不出来（writeUtf 上限 64），服务端也永远批不了。
     * 所以在清单入口就按整条卡死（ADV-S2b-3）。
     */
    public static final int MAX_ID = 64;

    /** JSON 嵌套深度上限。清单是一层对象加一个 ui 子对象，给到 8 已经宽得没边。 */
    private static final int MAX_JSON_DEPTH = 8;

    private static final Pattern ID_SEGMENT = Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");
    private static final Pattern PLAIN_INT = Pattern.compile("\\d+");
    private static final Pattern SDK_KEY = Pattern.compile("[a-z][a-z0-9_]{0,31}");

    /**
     * {@code sdk} 段最多几项。A 档 6 个加 B 档 5 个是 11 个，给到 32 留足了将来加的余量 ——
     * 不封顶的话，一个几千项的 sdk 段会让商店每次列表都白算几千次比较。
     */
    public static final int MAX_SDK_ENTRIES = 32;

    private static final int MAX_NAME = 64;
    private static final int MAX_AUTHOR = 32;
    private static final int MAX_DESCRIPTION = 256;

    public Manifest {
        // 没写 sdk 的包占大多数，让它们与写了的走同一条读法：调用方不必每次判 null
        sdk = sdk == null ? Map.of() : Map.copyOf(sdk);
    }

    /** {@code namespace:path}，拼回去的那一个。 */
    public String id() {
        return namespace + ":" + path;
    }

    /** 不写 {@code ui} 时的前端入口（§11.2：zip 形态的前端入口必须是 app.vue）。 */
    public static final String DEFAULT_ENTRY = "app.vue";

    /** .vue 内联 manifest 的 icon 前缀（§11.2）。 */
    public static final String INLINE_ICON_PREFIX = "data:image/png;base64,";

    /** 内联 icon 的 base64 部分上限，§11.2 的 8 KiB。 */
    public static final int MAX_INLINE_ICON = 8 * 1024;

    /** 真解一遍：只看字符集的话 "="、"A" 这种解不开的串也能过，要等客户端画图标时才失败。 */
    private static boolean decodes(String base64) {
        try {
            return Base64.getDecoder().decode(base64).length > 0;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 解析并校验。任何一条不过就抛，不返回半个 Manifest。 */
    public static Manifest parse(String json) {
        JsonObject root = strictObject(json);
        Head h = head(root);
        String description = text(root, "description", MAX_DESCRIPTION);

        String icon = requirePath(root, "icon", "icon");

        // ui 可省（§11.2 的 zip 形态）：省了就是 app.vue 当入口，样式在那个文件的 <style> 块里。
        // §4.2 的 ui.json / ui.mss 那一对仍然认 —— 写了就按写的走，两种入口在这里收敛成一个 uiTree
        JsonObject ui = root.has("ui") ? requireObject(root, "ui") : null;
        String uiTree = ui == null ? DEFAULT_ENTRY : requirePath(ui, "tree", "ui.tree");
        String uiStyle = ui == null ? null : requirePath(ui, "style", "ui.style");

        String engine = requireString(root, "engine");
        if (!ENGINE.equals(engine)) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_ENGINE, engine);
        }

        return new Manifest(h.format, h.namespace, h.path, h.version, h.name, h.author, description,
                icon, uiTree, uiStyle, engine, sdk(root));
    }

    /**
     * .vue 里的内联 {@code <manifest>}（§9.2、§11.2）：界面就在同一个文件里，所以没有 ui 与 engine；description 可省；
     * icon 可省，写了就得是 data URI。返回值的 uiTree / uiStyle / engine 为 null，其余判据与 {@link #parse} 同一份。
     */
    public static Manifest parseInline(String json) {
        JsonObject root = strictObject(json);
        Head h = head(root);
        String description = root.has("description") ? text(root, "description", MAX_DESCRIPTION) : null;
        String icon = root.has("icon") ? inlineIcon(root) : null;
        return new Manifest(h.format, h.namespace, h.path, h.version, h.name, h.author, description,
                icon, null, null, null, sdk(root));
    }

    private record Head(int format, String namespace, String path, String version, String name, String author) {
    }

    private static JsonObject strictObject(String json) {
        strictScan(json);
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw PackageError.of(PackageError.Code.E_PKG_MANIFEST_NOT_OBJECT);
            }
            return parsed.getAsJsonObject();
        } catch (JsonParseException e) {
            throw PackageError.of(PackageError.Code.E_PKG_MANIFEST_SYNTAX, String.valueOf(e.getMessage()));
        }
    }

    /** 两种清单共有的那几条，顺序即报错顺序。 */
    private static Head head(JsonObject root) {
        int format = requireInt(root, "format");
        if (format != FORMAT) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_FORMAT, format);
        }

        String id = requireString(root, "id");
        if (id.length() > MAX_ID) {
            throw PackageError.of(PackageError.Code.E_PKG_ID_TOO_LONG, MAX_ID, id.length(), id);
        }
        int colon = id.indexOf(':');
        if (colon < 0 || id.indexOf(':', colon + 1) >= 0) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_ID, id);
        }
        String namespace = id.substring(0, colon);
        String path = id.substring(colon + 1);
        if (!ID_SEGMENT.matcher(namespace).matches() || !ID_SEGMENT.matcher(path).matches()) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_ID, id);
        }
        if (RESERVED_NAMESPACE.equals(namespace)) {
            throw PackageError.of(PackageError.Code.E_PKG_RESERVED_NAMESPACE);
        }

        String version = requireString(root, "version");
        if (!VERSION.matcher(version).matches()) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_VERSION, version);
        }

        String name = text(root, "name", MAX_NAME);
        String author = text(root, "author", MAX_AUTHOR);
        return new Head(format, namespace, path, version, name, author);
    }

    private static String inlineIcon(JsonObject root) {
        String v = requireString(root, "icon");
        if (!v.startsWith(INLINE_ICON_PREFIX)) {
            String head = v.length() > 32 ? v.substring(0, 32) + "…" : v;
            throw PackageError.of(PackageError.Code.E_PKG_BAD_ICON, MAX_INLINE_ICON, "'" + head + "'");
        }
        String payload = v.substring(INLINE_ICON_PREFIX.length());
        if (payload.length() > MAX_INLINE_ICON || !decodes(payload)) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_ICON, MAX_INLINE_ICON,
                    payload.length() + " 字符" + (payload.length() > MAX_INLINE_ICON ? "" : "，解不出字节"));
        }
        return v;
    }

    /** manifest 指到的三个文件都得真在包里，否则装上是个空壳。 */
    public void requireEntries(Collection<String> entryPaths) {
        // 内联清单的 icon 是 data URI、没有 ui：拿它对包查只会报出「'icon' 指向 'null'」这种误导的错
        if (uiTree == null) throw new IllegalStateException("内联 manifest 没有 ui 与包内 icon，不能对着包里的条目查");
        requireEntry(entryPaths, "icon", icon);
        requireEntry(entryPaths, "ui.tree", uiTree);
        // 没写 ui 的包样式在 app.vue 的 <style> 块里，没有单独的样式文件可查
        if (uiStyle != null) requireEntry(entryPaths, "ui.style", uiStyle);
    }

    private void requireEntry(Collection<String> entryPaths, String field, String value) {
        if (!entryPaths.contains(value)) {
            throw PackageError.of(PackageError.Code.E_PKG_MISSING_ENTRY, field, value);
        }
    }

    // ============================================================
    //  严格 JSON
    // ============================================================

    /**
     * 走一遍严格 JSON，顺手查重复键。
     *
     * <p>Gson 的 {@code JsonParser} 是宽容的：无引号的键、单引号、注释、{@code NaN} 全收，
     * 而重复键在 {@code JsonObject} 里是后者静默覆盖前者 —— 于是一份清单有两种读法，
     * 审核的人读到第一个 id，注册进去的是第二个。
     *
     * <p>S18-E4 起与能力配置共用 {@link JsonScan}；清单的嵌套上限比配置更严（8 层）。
     */
    private static void strictScan(String json) {
        JsonScan.Problem problem = JsonScan.check(json, MAX_JSON_DEPTH);
        if (problem == null) return;
        if (problem.kind() == JsonScan.Kind.DUP_KEY) {
            // args[0] 必须是键本身：SfcCompiler.manifestLine 靠它找"第二次出现的那一行"
            String detail = problem.detail();
            String prefix = "重复键：";
            String key = detail.startsWith(prefix) ? detail.substring(prefix.length()) : detail;
            throw PackageError.of(PackageError.Code.E_PKG_MANIFEST_DUP_KEY, key);
        }
        throw PackageError.of(PackageError.Code.E_PKG_MANIFEST_SYNTAX, problem.detail());
    }

    // ============================================================
    //  取值
    // ============================================================

    private static JsonElement require(JsonObject obj, String field, String label) {
        JsonElement e = obj.get(field);
        if (e == null || e.isJsonNull()) {
            throw PackageError.of(PackageError.Code.E_PKG_MISSING_FIELD, label);
        }
        return e;
    }

    private static int requireInt(JsonObject obj, String field) {
        JsonElement e = require(obj, field, field);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_TYPE, field, "整数", typeOf(e));
        }
        // 按字面量判，不按数值：1.0 与 1e0 数值上等于 1，但 §3.2 要的是整数 1。
        String raw = e.getAsJsonPrimitive().getAsString();
        if (!PLAIN_INT.matcher(raw).matches()) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_TYPE, field, "整数", "写成 " + raw + " 的数");
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_TYPE, field, "整数", "超出范围的数");
        }
    }

    private static String requireString(JsonObject obj, String field) {
        JsonElement e = require(obj, field, field);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_TYPE, field, "字符串", typeOf(e));
        }
        return e.getAsString();
    }

    /**
     * {@code "sdk": { "economy": 1, "mailbox": 1 }}（§23.4）。段可省，省了就是空表。
     *
     * <p><b>不认识的键照收不误</b>：那表示这个包要一个比本机新的 SDK，属于"需要更新 MCphone"，
     * 由商店门控去判（{@code SdkVersions.unsatisfied}）。在这里拒的话，将来每加一个 SDK，
     * 旧版 MCphone 就把新包报成"清单坏了"，而它没坏。
     */
    private static Map<String, Integer> sdk(JsonObject root) {
        if (!root.has("sdk")) return Map.of();
        JsonObject obj = requireObject(root, "sdk");
        if (obj.size() > MAX_SDK_ENTRIES) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_SDK,
                    "最多 " + MAX_SDK_ENTRIES + " 项，收到 " + obj.size());
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String key : obj.keySet()) {
            if (!SDK_KEY.matcher(key).matches()) {
                throw PackageError.of(PackageError.Code.E_PKG_BAD_SDK,
                        "键 '" + key + "' 要匹配 " + SDK_KEY.pattern());
            }
            int v = requireInt(obj, key);
            // 0 与负数没有意义：声明"我要第 0 版"既不是"不要"也不是任何一版
            if (v < 1) {
                throw PackageError.of(PackageError.Code.E_PKG_BAD_SDK,
                        "'" + key + "' 的版本要 ≥ 1，收到 " + v);
            }
            out.put(key, v);
        }
        return Map.copyOf(out);
    }

    private static JsonObject requireObject(JsonObject obj, String field) {
        JsonElement e = require(obj, field, field);
        if (!e.isJsonObject()) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_TYPE, field, "对象", typeOf(e));
        }
        return e.getAsJsonObject();
    }

    /** 会显示给人看的文本字段：长度按代码点数，内容不许有看不见的东西。 */
    private static String text(JsonObject obj, String field, int max) {
        String v = requireString(obj, field);
        int len = v.codePointCount(0, v.length());
        if (len > max) throw PackageError.of(PackageError.Code.E_PKG_TEXT_TOO_LONG, field, max, len);
        for (int i = 0; i < v.length(); ) {
            int cp = v.codePointAt(i);
            if (invisible(cp)) {
                throw PackageError.of(PackageError.Code.E_PKG_TEXT_CONTROL_CHAR, field);
            }
            i += Character.charCount(cp);
        }
        return v;
    }

    /**
     * 控制字符、Unicode 换行、以及方向覆盖那一类格式字符。
     *
     * <p>只判 ASCII 不够：U+202E 能让 UI 上显示出来的名字和实际的名字不一样，U+200B 能把
     * 别人的作者名一字不差地伪装出来。作者名旁边不许有对勾（§12.3），那这一关就得在入口挡。
     */
    private static boolean invisible(int cp) {
        int type = Character.getType(cp);
        return type == Character.CONTROL
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR
                || type == Character.SURROGATE
                || type == Character.UNASSIGNED;
    }

    private static String requirePath(JsonObject obj, String field, String label) {
        JsonElement e = require(obj, field, label);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_TYPE, label, "字符串", typeOf(e));
        }
        String v = e.getAsString();
        PackageError.PathRules.require(v);
        return v;
    }

    private static String typeOf(JsonElement e) {
        if (e.isJsonObject()) return "对象";
        if (e.isJsonArray()) return "数组";
        if (e.isJsonNull()) return "null";
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return "布尔";
        if (p.isNumber()) return "数字";
        return "字符串";
    }
}
