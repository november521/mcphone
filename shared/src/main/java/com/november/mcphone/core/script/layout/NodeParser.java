package com.november.mcphone.core.script.layout;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.november.mcphone.core.script.layout.LayoutError.Code;
import com.november.mcphone.core.script.pkg.PackageError;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把 IR 的 JSON 形态校验成 {@link Node} 树（施工方案 §4.8 的顺序、§4.9 的文案）。
 *
 * <p>作者写的不是这个 JSON —— 他写的是 S7 的单文件格式，由模板编译器产出这棵树。JSON 形态
 * 是 IR 的等价写法，调试与单测用（§4.2）。
 *
 * <p>未知字段一律拒整个包。忽略会让「我明明写了 colour」这种错误静默生效，第三方要调一整天。
 */
public final class NodeParser {

    /** 节点总数上限。 */
    public static final int MAX_NODES = 512;
    /** 嵌套深度上限。 */
    public static final int MAX_DEPTH = 32;
    /** state 的条数上限。 */
    public static final int MAX_STATE = StateRules.MAX_KEYS;
    /** pages 的页数上限。 */
    public static final int MAX_PAGES = 8;
    /** 单个节点的 class 个数上限。 */
    public static final int MAX_CLASSES = 8;
    /** text 的长度上限。 */
    public static final int MAX_TEXT = 512;
    /** i18n 占位参数个数上限。 */
    public static final int MAX_ARGS = 4;
    /** state 里字符串值的长度上限。 */
    public static final int MAX_STATE_STRING = StateRules.MAX_STRING;

    /** icon 的固定集合，P0 共 12 个，不许扩展（§5.2）。 */
    public static final Set<String> ICON_NAMES = Set.of(
            "back", "forward", "up", "down", "check", "cross",
            "plus", "minus", "gear", "search", "info", "warn");

    private static final Pattern PAGE_KEY = Pattern.compile("[a-z][a-z0-9_]{0,31}");
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{0,31}");
    private static final Pattern CLASS_NAME = Pattern.compile("[a-z][a-z0-9_-]{0,31}");
    private static final Pattern PLAIN_INT = Pattern.compile("-?\\d+");

    private static final Set<String> TOP_FIELDS = Set.of("state", "pages", "entry");
    private static final Set<String> SHOW_IF_FIELDS = Set.of("key", "eq", "ne", "truthy");
    private static final Set<String> ACTION_FIELDS = Set.of("set", "toggle", "nav");
    private static final Set<String> TAB_FIELDS = Set.of("text", "i18n", "icon");

    /** 一份界面：初始状态、各页的根节点、入口页（§4.2.1）。 */
    public record Ui(Map<String, Object> state, Map<String, Node> pages, String entry) {

        /** 入口页的根节点。 */
        public Node root() {
            return pages.get(entry);
        }
    }

    private final Map<String, Integer> offsets;
    private final Map<String, Class<?>> stateTypes = new LinkedHashMap<>();
    private final Map<String, String> ids = new HashMap<>();
    private Set<String> pageKeys = Set.of();
    private int nodeCount;

    private NodeParser(Map<String, Integer> offsets) {
        this.offsets = offsets;
    }

    /** 解析并校验。任何一条不过就抛，不返回半棵树。 */
    public static Ui parse(String json) {
        Map<String, Integer> offsets = JsonScan.index(json);
        return new NodeParser(offsets).run(json);
    }

    private Ui run(String json) {
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            throw fail(Code.E_BAD_TYPE, "", "顶层", "顶层", "ui.json", "对象", typeOf(parsed));
        }
        JsonObject root = parsed.getAsJsonObject();

        // 1. 顶层三字段存在性与类型
        for (String key : root.keySet()) {
            if (!TOP_FIELDS.contains(key)) {
                throw fail(Code.E_UNKNOWN_FIELD, key, "顶层", "顶层", key, joined(TOP_FIELDS));
            }
        }

        // 1. 顶层三字段的存在性与类型 —— 整份少了 pages 是结构性错误，必须先于 state 的细节报
        JsonObject pages = requireObject(root, "pages", "pages", "pages");
        String entry = requireString(root, "entry", "entry", "entry");

        // 2. state → 建立 stateTypes 表
        readState(root);

        // 3. pages 的键名与数量
        if (pages.size() == 0) {
            throw fail(Code.E_BAD_VALUE, "pages", "pages", "pages", "pages", "{}", "至少 1 页");
        }
        if (pages.size() > MAX_PAGES) {
            throw fail(Code.E_BAD_VALUE, "pages", "pages", "pages", "pages",
                    pages.size() + " 页", "最多 " + MAX_PAGES + " 页");
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String key : pages.keySet()) {
            if (!PAGE_KEY.matcher(key).matches()) {
                throw fail(Code.E_BAD_VALUE, "pages." + key, "pages", "pages", "页名", key,
                        "匹配 [a-z][a-z0-9_]{0,31}");
            }
            keys.add(key);
        }
        pageKeys = keys;

        // 4. entry ∈ pageKeys
        if (!pageKeys.contains(entry)) {
            throw fail(Code.E_UNKNOWN_PAGE, "entry", "entry", "entry", entry, joined(pageKeys));
        }

        // 5. 逐页深度优先遍历
        Map<String, Node> built = new LinkedHashMap<>();
        for (String key : pageKeys) {
            String path = "pages." + key;
            built.put(key, node(element(pages, key, path), path, 1));
        }
        return new Ui(Collections.unmodifiableMap(stateValues), Collections.unmodifiableMap(built), entry);
    }

    // ============================================================
    //  2. state
    // ============================================================

    private final Map<String, Object> stateValues = new LinkedHashMap<>();

    private void readState(JsonObject root) {
        JsonElement raw = root.get("state");
        if (raw == null || raw.isJsonNull()) return;
        if (!raw.isJsonObject()) {
            throw fail(Code.E_BAD_TYPE, "state", "state", "顶层", "state", "对象", typeOf(raw));
        }
        JsonObject state = raw.getAsJsonObject();
        if (state.size() > MAX_STATE) {
            throw fail(Code.E_BAD_VALUE, "state", "state", "state", "state",
                    state.size() + " 项", "最多 " + MAX_STATE + " 项");
        }
        for (Map.Entry<String, JsonElement> e : state.entrySet()) {
            String key = e.getKey();
            String path = "state." + key;
            if (!StateRules.KEY.matcher(key).matches()) {
                throw fail(Code.E_BAD_VALUE, path, "state", "state", "键名", key, StateRules.KEY_RULE);
            }
            Object value = stateValue(e.getValue(), path, key);
            String why = StateRules.check(value);
            if (why != null) {
                throw fail(Code.E_BAD_VALUE, path, "state", path, key, clip(e.getValue().toString()), why);
            }
            value = StateRules.freeze(value);
            stateValues.put(key, value);
            stateTypes.put(key, value.getClass());
        }
    }

    /** JSON → state 值的 Java 形态。个数、长度、同构这些由 {@link StateRules#check} 判。 */
    private Object stateValue(JsonElement e, String path, String key) {
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return intOf(p, path, key);
            if (p.isString()) return p.getAsString();
        }
        if (e.isJsonArray()) {
            List<Object> out = new ArrayList<>();
            for (JsonElement item : e.getAsJsonArray()) out.add(stateValue(item, path, key));
            return out;
        }
        if (e.isJsonObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> item : e.getAsJsonObject().entrySet()) {
                out.put(item.getKey(), stateValue(item.getValue(), path, key));
            }
            return out;
        }
        throw fail(Code.E_BAD_TYPE, path, "state", path, key, "int / bool / string / array / object", typeOf(e));
    }

    // ============================================================
    //  5. 一个节点
    // ============================================================

    private Node node(JsonElement raw, String path, int depth) {
        if (!raw.isJsonObject()) {
            throw fail(Code.E_BAD_TYPE, path, path, path, "节点", "对象", typeOf(raw));
        }
        JsonObject obj = raw.getAsJsonObject();

        // 5a. type 认识吗
        JsonElement typeRaw = obj.get("type");
        if (typeRaw == null || typeRaw.isJsonNull()) {
            throw fail(Code.E_MISSING_FIELD, path + ".type", path, path, "type");
        }
        if (!isString(typeRaw)) {
            throw fail(Code.E_BAD_TYPE, path + ".type", path, path, "type", "字符串", typeOf(typeRaw));
        }
        String typeName = typeRaw.getAsString();
        NodeType type = NodeType.of(typeName);
        if (type == null) {
            throw fail(Code.E_UNKNOWN_TYPE, path + ".type", path, path, typeName, NodeType.allNames());
        }

        // 5b. 未知字段 —— 必须在 5f 之前：写错 "texts" 的人该收到「不认识的字段」，
        // 而不是「缺少必填字段 text」
        Set<String> accepted = type.acceptedFields();
        for (String key : obj.keySet()) {
            if (!accepted.contains(key)) {
                throw fail(Code.E_UNKNOWN_FIELD, path + "." + key, path, path, key, joined(accepted));
            }
        }

        // 5c. id 合法且全树唯一
        String id = null;
        if (obj.has("id")) {
            id = requireString(obj, "id", path + ".id", path);
            if (!ID.matcher(id).matches()) {
                throw fail(Code.E_BAD_VALUE, path + ".id", path, path, "id", id,
                        "匹配 [a-z][a-z0-9_-]{0,31}");
            }
            String previous = ids.putIfAbsent(id, path);
            if (previous != null) {
                throw fail(Code.E_DUP_ID, path + ".id", path, path, id, previous);
            }
        }

        // 5d. class 合法
        List<String> classes = readClasses(obj, path);

        // 5e. children 只出现在 acceptsChildren 的类型上
        JsonArray childArray = null;
        if (obj.has("children")) {
            if (!type.acceptsChildren) {
                throw fail(Code.E_CHILDREN_NOT_ALLOWED, path + ".children", path, path, type.json,
                        NodeType.childrenAcceptors());
            }
            JsonElement raws = obj.get("children");
            if (!raws.isJsonArray()) {
                throw fail(Code.E_BAD_TYPE, path + ".children", path, path, "children", "数组", typeOf(raws));
            }
            childArray = raws.getAsJsonArray();
        }

        // 5f. 组件专有字段
        Map<String, Object> props = props(obj, type, path, childArray);

        // 5g. showIf
        Node.ShowIf showIf = obj.has("showIf")
                ? condition(obj.get("showIf"), path + ".showIf", path, "showIf")
                : null;

        // 5h. onClick
        Node.Action onClick = obj.has("onClick")
                ? action(obj.get("onClick"), path + ".onClick", path)
                : null;
        if (onClick == null && type == NodeType.BUTTON) {
            throw fail(Code.E_MISSING_FIELD, path + ".onClick", path, path, "onClick");
        }

        // 5i. 规模
        nodeCount++;
        if (nodeCount > MAX_NODES) {
            throw fail(Code.E_TOO_MANY_NODES, path, path, nodeCount);
        }
        if (depth > MAX_DEPTH) {
            throw fail(Code.E_TOO_DEEP, path, path, path, depth);
        }

        List<Node> children = new ArrayList<>();
        if (childArray != null) {
            for (int i = 0; i < childArray.size(); i++) {
                children.add(node(childArray.get(i), path + ".children[" + i + "]", depth + 1));
            }
        }

        return new Node(type, id, List.copyOf(classes), Collections.unmodifiableMap(props),
                List.copyOf(children), showIf, onClick);
    }

    private List<String> readClasses(JsonObject obj, String path) {
        List<String> out = new ArrayList<>();
        if (!obj.has("class")) return out;
        JsonElement raw = obj.get("class");
        if (!raw.isJsonArray()) {
            throw fail(Code.E_BAD_TYPE, path + ".class", path, path, "class", "字符串数组", typeOf(raw));
        }
        JsonArray array = raw.getAsJsonArray();
        if (array.size() > MAX_CLASSES) {
            throw fail(Code.E_BAD_VALUE, path + ".class", path, path, "class",
                    array.size() + " 个", "最多 " + MAX_CLASSES + " 个");
        }
        for (JsonElement e : array) {
            if (!isString(e)) {
                throw fail(Code.E_BAD_TYPE, path + ".class", path, path, "class", "字符串数组", typeOf(e));
            }
            String name = e.getAsString();
            if (!CLASS_NAME.matcher(name).matches()) {
                throw fail(Code.E_BAD_VALUE, path + ".class", path, path, "class", name,
                        "匹配 [a-z][a-z0-9_-]{0,31}");
            }
            out.add(name);
        }
        return out;
    }

    // ============================================================
    //  5f. 组件专有字段（§5.4 那张表）
    // ============================================================

    private Map<String, Object> props(JsonObject obj, NodeType type, String path, JsonArray children) {
        Map<String, Object> out = new LinkedHashMap<>();
        switch (type) {
            case GRID -> intProp(obj, out, "cols", path, 1, 6);
            // §5.1 给 list 的 children 写了 2048，而全树上限是 512，那条够不到：513 个子节点
            // 先撞 E_TOO_MANY_NODES。要让长列表成立得把 list 的子节点单独计数，那是改全树语义。
            case LIST -> intProp(obj, out, "item-height", path, 0, Integer.MAX_VALUE);
            case SPACER -> intProp(obj, out, "size", path, 0, Integer.MAX_VALUE);
            case DIVIDER -> boolProp(obj, out, "vertical", path);
            case TEXT -> {
                label(obj, out, path, true);
                args(obj, out, path);
            }
            case IMAGE -> {
                out.put("src", imageSrc(obj, path));
                intProp(obj, out, "w", path, 1, 120);
                intProp(obj, out, "h", path, 1, 176);
            }
            case ICON -> {
                String name = requireString(obj, "name", path + ".name", path);
                if (!ICON_NAMES.contains(name)) {
                    throw fail(Code.E_BAD_VALUE, path + ".name", path, path, "name", name,
                            joined(ICON_NAMES));
                }
                out.put("name", name);
                intProp(obj, out, "size", path, 6, 20);
            }
            case ITEM -> {
                out.put("item", requireString(obj, "item", path + ".item", path));
                intProp(obj, out, "count", path, 1, 99);
                intProp(obj, out, "size", path, 8, 20);
            }
            case BADGE -> intProp(obj, out, "count", path, 0, Integer.MAX_VALUE);
            case PROGRESS -> {
                intProp(obj, out, "value", path, 0, 100);
                intProp(obj, out, "height", path, 2, 12);
            }
            case BUTTON -> {
                boolean hasChildren = children != null && children.size() > 0;
                label(obj, out, path, !hasChildren);
                args(obj, out, path);
                boolProp(obj, out, "enabled", path);
                if (obj.has("enabledIf")) {
                    out.put("enabledIf", condition(obj.get("enabledIf"), path + ".enabledIf",
                            path, "enabledIf"));
                }
            }
            case TOGGLE -> {
                out.put("bind", bind(obj, path, Boolean.class, "bool"));
                if (obj.has("label")) out.put("label", visibleText(obj, "label", path, MAX_TEXT));
                stringProp(obj, out, "i18n", path);
                boolProp(obj, out, "enabled", path);
            }
            case TAB_BAR -> {
                out.put("bind", bind(obj, path, Integer.class, "int"));
                out.put("tabs", tabs(obj, path));
            }
            case TEXT_INPUT -> {
                out.put("bind", bind(obj, path, String.class, "string"));
                if (obj.has("placeholder")) out.put("placeholder", visibleText(obj, "placeholder", path, MAX_TEXT));
                intProp(obj, out, "max-length", path, 1, 4096);
                boolProp(obj, out, "enabled", path);
            }
            default -> {
                // box / column / row / stack / scroll 没有专有字段
            }
        }
        return out;
    }

    /** text 与 i18n 二选一。button 有 children 时两个都可省。 */
    private void label(JsonObject obj, Map<String, Object> out, String path, boolean required) {
        boolean hasText = obj.has("text");
        boolean hasI18n = obj.has("i18n");
        if (!hasText && !hasI18n) {
            if (required) throw fail(Code.E_MISSING_FIELD, path + ".text", path, path, "text 或 i18n");
            return;
        }
        if (hasText && hasI18n) {
            throw fail(Code.E_BAD_VALUE, path + ".i18n", path, path, "i18n", "text 与 i18n 都写了",
                    "二选一");
        }
        if (hasText) out.put("text", visibleText(obj, "text", path, MAX_TEXT));
        if (hasI18n) out.put("i18n", requireString(obj, "i18n", path + ".i18n", path));
    }

    /**
     * 会进字体渲染的文字：长度有上限，除换行外不许有控制字符。
     *
     * <p>§5.2 只给 text 写了这条，但理由（控制字符进渲染、长度失控）对 toggle 的 label 与
     * tab 的分段名逐字成立，所以三处共用。
     */
    private String visibleText(JsonObject obj, String field, String path, int max) {
        String value = requireString(obj, field, path + "." + field, path);
        if (value.length() > max) {
            throw fail(Code.E_BAD_VALUE, path + "." + field, path, path, field,
                    value.length() + " 字", "最多 " + max + " 字");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\n' && (c < 0x20 || c == 0x7F)) {
                throw fail(Code.E_BAD_VALUE, path + "." + field, path, path, field,
                        "U+" + String.format(Locale.ROOT, "%04X", (int) c), "可见字符与换行");
            }
        }
        return value;
    }

    /** args 只在 i18n 时有意义，最多 4 个，元素是 string 或 int。 */
    private void args(JsonObject obj, Map<String, Object> out, String path) {
        if (!obj.has("args")) return;
        if (!obj.has("i18n")) {
            throw fail(Code.E_BAD_VALUE, path + ".args", path, path, "args", "有 args 没有 i18n",
                    "args 仅在 i18n 时有效");
        }
        JsonElement raw = obj.get("args");
        if (!raw.isJsonArray()) {
            throw fail(Code.E_BAD_TYPE, path + ".args", path, path, "args", "数组", typeOf(raw));
        }
        JsonArray array = raw.getAsJsonArray();
        if (array.size() > MAX_ARGS) {
            throw fail(Code.E_BAD_VALUE, path + ".args", path, path, "args",
                    array.size() + " 个", "最多 " + MAX_ARGS + " 个");
        }
        List<Object> values = new ArrayList<>();
        for (JsonElement e : array) {
            if (isString(e)) {
                values.add(e.getAsString());
            } else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) {
                values.add(intOf(e.getAsJsonPrimitive(), path + ".args", "args"));
            } else {
                throw fail(Code.E_BAD_TYPE, path + ".args", path, path, "args",
                        "string 或 int 的数组", typeOf(e));
            }
        }
        out.put("args", List.copyOf(values));
    }

    /**
     * 图片路径走 S1 的 {@link PackageError.PathRules}，不另写一份 —— 抄一份必然分叉。
     *
     * <p>「这张图在不在包里」这一步查不了：{@code parse} 只收一棵树，手里没有包。那条判据要等树
     * 与包碰面时才成立，拿 {@code AppPackage.paths()} 对，与 {@code Manifest.requireEntries} 同一个形状。
     */
    private String imageSrc(JsonObject obj, String path) {
        String src = requireString(obj, "src", path + ".src", path);
        if (!src.endsWith(".png") || !PackageError.PathRules.accept(src)) {
            throw fail(Code.E_BAD_VALUE, path + ".src", path, path, "src", src,
                    "包内相对路径，以 .png 结尾");
        }
        return src;
    }

    /** toggle / tab-bar 的 bind：必须是 state 里的 key，且类型对得上。 */
    private String bind(JsonObject obj, String path, Class<?> want, String wantName) {
        String key = requireString(obj, "bind", path + ".bind", path);
        Class<?> actual = stateTypes.get(key);
        if (actual == null) {
            throw fail(Code.E_UNKNOWN_STATE_KEY, path + ".bind", path, path, key, joined(stateTypes.keySet()));
        }
        if (actual != want) {
            throw fail(Code.E_STATE_TYPE, path + ".bind", path, path, key, typeName(actual), wantName);
        }
        return key;
    }

    /** tab-bar 的分段：2–4 项，每项恰好一个 text / i18n / icon。 */
    private List<Map<String, Object>> tabs(JsonObject obj, String path) {
        JsonElement raw = obj.get("tabs");
        if (raw == null || raw.isJsonNull()) {
            throw fail(Code.E_MISSING_FIELD, path + ".tabs", path, path, "tabs");
        }
        if (!raw.isJsonArray()) {
            throw fail(Code.E_BAD_TYPE, path + ".tabs", path, path, "tabs", "数组", typeOf(raw));
        }
        JsonArray array = raw.getAsJsonArray();
        if (array.size() < 2 || array.size() > 4) {
            throw fail(Code.E_BAD_VALUE, path + ".tabs", path, path, "tabs",
                    array.size() + " 项", "tabs 最多 4 项（内容区只有 120px 宽），最少 2 项");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            String tabPath = path + ".tabs[" + i + "]";
            JsonElement e = array.get(i);
            if (!e.isJsonObject()) {
                throw fail(Code.E_BAD_TYPE, tabPath, path, tabPath, "tabs", "对象", typeOf(e));
            }
            JsonObject tab = e.getAsJsonObject();
            for (String key : tab.keySet()) {
                if (!TAB_FIELDS.contains(key)) {
                    throw fail(Code.E_UNKNOWN_FIELD, tabPath + "." + key, path, tabPath, key,
                            joined(TAB_FIELDS));
                }
            }
            if (tab.size() != 1) {
                throw fail(Code.E_BAD_VALUE, tabPath, path, tabPath, "tabs",
                        joined(tab.keySet()), "每项恰好一个：" + joined(TAB_FIELDS));
            }
            String key = tab.keySet().iterator().next();
            String value = key.equals("i18n")
                    ? requireString(tab, key, tabPath + "." + key, tabPath)
                    : visibleText(tab, key, tabPath, MAX_TEXT);
            if (key.equals("icon") && !ICON_NAMES.contains(value)) {
                throw fail(Code.E_BAD_VALUE, tabPath + ".icon", path, tabPath, "icon", value,
                        joined(ICON_NAMES));
            }
            out.add(Map.of(key, value));
        }
        return List.copyOf(out);
    }

    // ============================================================
    //  5g / 5h. showIf 与 onClick
    // ============================================================

    /** showIf 与 button 的 enabledIf 同形：eq / ne / truthy 恰好一个。 */
    private Node.ShowIf condition(JsonElement raw, String path, String nodePath, String field) {
        if (!raw.isJsonObject()) {
            throw fail(Code.E_BAD_TYPE, path, nodePath, nodePath, field, "对象", typeOf(raw));
        }
        JsonObject obj = raw.getAsJsonObject();
        for (String key : obj.keySet()) {
            if (!SHOW_IF_FIELDS.contains(key)) {
                throw fail(Code.E_UNKNOWN_FIELD, path + "." + key, nodePath, path, key,
                        joined(SHOW_IF_FIELDS));
            }
        }
        String key = requireString(obj, "key", path + ".key", path);
        Class<?> stateType = stateTypes.get(key);
        if (stateType == null) {
            throw fail(Code.E_UNKNOWN_STATE_KEY, path + ".key", nodePath, path, key,
                    joined(stateTypes.keySet()));
        }

        int forms = (obj.has("eq") ? 1 : 0) + (obj.has("ne") ? 1 : 0) + (obj.has("truthy") ? 1 : 0);
        if (forms != 1) {
            throw fail(Code.E_BAD_VALUE, path, nodePath, path, field,
                    forms + " 种形式", "eq / ne / truthy 恰好一个");
        }

        if (obj.has("truthy")) {
            JsonElement t = obj.get("truthy");
            if (!isBoolean(t)) {
                throw fail(Code.E_BAD_TYPE, path + ".truthy", nodePath, path, "truthy", "bool", typeOf(t));
            }
            return new Node.ShowIf(key, Node.ShowIf.Kind.TRUTHY, t.getAsBoolean());
        }

        boolean isEq = obj.has("eq");
        String which = isEq ? "eq" : "ne";
        Object value = compared(obj.get(which), path + "." + which, nodePath, which);
        if (value.getClass() != stateType) {
            throw fail(Code.E_STATE_TYPE, path + "." + which, nodePath, path, key,
                    typeName(stateType), typeName(value.getClass()));
        }
        return new Node.ShowIf(key, isEq ? Node.ShowIf.Kind.EQ : Node.ShowIf.Kind.NE, value);
    }

    private Object compared(JsonElement raw, String path, String nodePath, String field) {
        if (raw.isJsonPrimitive()) {
            JsonPrimitive p = raw.getAsJsonPrimitive();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return intOf(p, path, field);
            if (p.isString()) return p.getAsString();
        }
        throw fail(Code.E_BAD_TYPE, path, nodePath, nodePath, field, "int / bool / string", typeOf(raw));
    }

    private Node.Action action(JsonElement raw, String path, String nodePath) {
        if (isString(raw)) {
            String builtin = raw.getAsString();
            return switch (builtin) {
                case "close" -> new Node.Action(Map.of(), null, null, Node.Action.Builtin.CLOSE);
                case "back" -> new Node.Action(Map.of(), null, null, Node.Action.Builtin.BACK);
                default -> throw fail(Code.E_BAD_VALUE, path, nodePath, nodePath, "onClick",
                        builtin, "close back，或者 set / toggle / nav 的对象形式");
            };
        }
        if (!raw.isJsonObject()) {
            throw fail(Code.E_BAD_TYPE, path, nodePath, nodePath, "onClick", "字符串或对象", typeOf(raw));
        }
        JsonObject obj = raw.getAsJsonObject();
        for (String key : obj.keySet()) {
            if (!ACTION_FIELDS.contains(key)) {
                throw fail(Code.E_UNKNOWN_FIELD, path + "." + key, nodePath, path, key, joined(ACTION_FIELDS));
            }
        }
        Map<String, Object> set = new LinkedHashMap<>();
        if (obj.has("set")) {
            JsonElement rawSet = obj.get("set");
            if (!rawSet.isJsonObject()) {
                throw fail(Code.E_BAD_TYPE, path + ".set", nodePath, path, "set", "对象", typeOf(rawSet));
            }
            for (Map.Entry<String, JsonElement> e : rawSet.getAsJsonObject().entrySet()) {
                String key = e.getKey();
                Class<?> stateType = stateTypes.get(key);
                if (stateType == null) {
                    throw fail(Code.E_UNKNOWN_STATE_KEY, path + ".set." + key, nodePath, path + ".set",
                            key, joined(stateTypes.keySet()));
                }
                Object value = compared(e.getValue(), path + ".set." + key, nodePath, key);
                if (value.getClass() != stateType) {
                    throw fail(Code.E_STATE_TYPE, path + ".set." + key, nodePath, path + ".set",
                            key, typeName(stateType), typeName(value.getClass()));
                }
                // 初值的规则对点击写入同样成立：放过的话校验通过的包在点击时从 UiState.set 抛出来
                String why = StateRules.check(value);
                if (why != null) {
                    throw fail(Code.E_BAD_VALUE, path + ".set." + key, nodePath, path + ".set", key,
                            clip(String.valueOf(value)), why);
                }
                set.put(key, value);
            }
        }

        String toggle = null;
        if (obj.has("toggle")) {
            toggle = requireString(obj, "toggle", path + ".toggle", path);
            Class<?> stateType = stateTypes.get(toggle);
            if (stateType == null) {
                throw fail(Code.E_UNKNOWN_STATE_KEY, path + ".toggle", nodePath, path, toggle,
                        joined(stateTypes.keySet()));
            }
            if (stateType != Boolean.class) {
                throw fail(Code.E_STATE_TYPE, path + ".toggle", nodePath, path, toggle,
                        typeName(stateType), "bool");
            }
        }

        String nav = null;
        if (obj.has("nav")) {
            nav = requireString(obj, "nav", path + ".nav", path);
            if (!pageKeys.contains(nav)) {
                throw fail(Code.E_UNKNOWN_PAGE, path + ".nav", nodePath, path, nav, joined(pageKeys));
            }
        }

        if (set.isEmpty() && toggle == null && nav == null) {
            throw fail(Code.E_NO_ACTION, path, nodePath, path);
        }
        return new Node.Action(Collections.unmodifiableMap(set), toggle, nav, Node.Action.Builtin.NONE);
    }

    // ============================================================
    //  取值与报错
    // ============================================================

    private JsonObject requireObject(JsonObject obj, String field, String path, String nodePath) {
        JsonElement e = present(obj, field, path, nodePath, "对象");
        if (!e.isJsonObject()) {
            throw fail(Code.E_BAD_TYPE, path, nodePath, nodePath, field, "对象", typeOf(e));
        }
        return e.getAsJsonObject();
    }

    private String requireString(JsonObject obj, String field, String path, String nodePath) {
        JsonElement e = present(obj, field, path, nodePath, "字符串");
        if (!isString(e)) {
            throw fail(Code.E_BAD_TYPE, path, nodePath, nodePath, field, "字符串", typeOf(e));
        }
        return e.getAsString();
    }

    private JsonElement element(JsonObject obj, String field, String path) {
        return present(obj, field, path, path, "节点");
    }

    /** 缺字段与写了 null 是两回事：id 是可选的，写成 null 该说类型不对，不是「缺少必填字段」。 */
    private JsonElement present(JsonObject obj, String field, String path, String nodePath, String want) {
        JsonElement e = obj.get(field);
        if (e == null) {
            throw fail(Code.E_MISSING_FIELD, path, nodePath, nodePath, field);
        }
        if (e.isJsonNull()) {
            throw fail(Code.E_BAD_TYPE, path, nodePath, nodePath, field, want, "null");
        }
        return e;
    }

    private void intProp(JsonObject obj, Map<String, Object> out, String field, String path,
                         int min, int max) {
        if (!obj.has(field)) return;
        JsonElement e = obj.get(field);
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            throw fail(Code.E_BAD_TYPE, path + "." + field, path, path, field, "int", typeOf(e));
        }
        int v = intOf(e.getAsJsonPrimitive(), path + "." + field, field);
        if (v < min || v > max) {
            String allowed = max == Integer.MAX_VALUE ? "≥ " + min : min + "–" + max;
            throw fail(Code.E_BAD_VALUE, path + "." + field, path, path, field, String.valueOf(v), allowed);
        }
        out.put(field, v);
    }

    private void boolProp(JsonObject obj, Map<String, Object> out, String field, String path) {
        if (!obj.has(field)) return;
        JsonElement e = obj.get(field);
        if (!isBoolean(e)) {
            throw fail(Code.E_BAD_TYPE, path + "." + field, path, path, field, "bool", typeOf(e));
        }
        out.put(field, e.getAsBoolean());
    }

    private void stringProp(JsonObject obj, Map<String, Object> out, String field, String path) {
        if (!obj.has(field)) return;
        out.put(field, requireString(obj, field, path + "." + field, path));
    }

    /** 按字面量判，不按数值：1.0 数值上等于 1，但 IR 里那不是一个 int。 */
    private int intOf(JsonPrimitive p, String path, String field) {
        String raw = p.getAsString();
        if (!PLAIN_INT.matcher(raw).matches()) {
            throw fail(Code.E_BAD_TYPE, path, path, path, field, "int", "写成 " + raw + " 的数");
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw fail(Code.E_BAD_VALUE, path, path, path, field, raw, "int 的取值范围");
        }
    }

    /**
     * 成一条错误。
     *
     * @param offsetPath 拿去查字符偏移的路径，尽量指到出问题的那个字段
     * @param nodePath   报给人看的节点路径
     * @param args       {@link Code} 模板的参数，顺序与模板一致
     */
    private LayoutError fail(Code code, String offsetPath, String nodePath, Object... args) {
        int offset = offsets.getOrDefault(offsetPath, offsets.getOrDefault(nodePath, -1));
        return LayoutError.of(code, nodePath, offset, args);
    }

    private static boolean isString(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    private static boolean isBoolean(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean();
    }

    private static String typeOf(JsonElement e) {
        if (e.isJsonObject()) return "对象";
        if (e.isJsonArray()) return "数组";
        if (e.isJsonNull()) return "null";
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) return "bool";
        if (p.isNumber()) return "int";
        return "string";
    }

    private static String typeName(Class<?> c) {
        if (c == Integer.class) return "int";
        if (c == Boolean.class) return "bool";
        if (List.class.isAssignableFrom(c)) return "array";
        if (Map.class.isAssignableFrom(c)) return "object";
        return "string";
    }

    /** 放进报错文案的值截到 32 个码点：整段数组塞进去有几千字，按码元截会切断代理对。 */
    private static String clip(String s) {
        return s.codePointCount(0, s.length()) > 32 ? s.substring(0, s.offsetByCodePoints(0, 32)) + "…" : s;
    }

    /** 排序不是为了好看：Set.of 的迭代顺序逐进程随机，不排的话同一条报错两次跑文字不同。 */
    private static String joined(Collection<String> values) {
        if (values.isEmpty()) return "（空）";
        List<String> out = new ArrayList<>(values);
        out.sort(null);
        return String.join(" ", out);
    }

    // ============================================================
    //  字符偏移
    // ============================================================

    /**
     * 走一遍原文：记下每条路径的字符偏移，同时把语法判据全部钉死。
     *
     * <p>值仍由 Gson 解析（§4.1 第一条），但<b>宽严必须由这里说了算</b>。Gson 默认是宽容的，
     * 一旦这里比它松，同一份字节两边会解出不同的结构 —— 偏移表描述的树和被校验的树对不上。
     * 所以这里只认严格 JSON：严格 JSON 只有一种解读，分叉就不存在了。
     */
    static final class JsonScan {

        private final String src;
        private final Map<String, Integer> offsets = new HashMap<>();
        private int pos;

        private JsonScan(String src) {
            this.src = src;
        }

        static Map<String, Integer> index(String json) {
            JsonScan scan = new JsonScan(json);
            scan.value("", 0);
            scan.ws();
            if (scan.pos != json.length()) scan.syntax("末尾还有多余的内容");
            return scan.offsets;
        }

        private void value(String path, int depth) {
            if (depth > MAX_DEPTH) {
                throw LayoutError.of(Code.E_TOO_DEEP, path, pos, path, depth);
            }
            ws();
            if (pos >= src.length()) syntax("提前结束");
            char c = src.charAt(pos);
            switch (c) {
                case '{' -> object(path, depth);
                case '[' -> array(path, depth);
                case '"' -> string();
                case 't' -> keyword("true");
                case 'f' -> keyword("false");
                case 'n' -> keyword("null");
                default -> number();
            }
        }

        private void object(String path, int depth) {
            expect('{');
            ws();
            if (peek() == '}') {
                pos++;
                return;
            }
            Set<String> seen = new java.util.HashSet<>();
            while (true) {
                ws();
                int keyStart = pos;
                String key = string();
                // 重复键在 Gson 里是后者静默覆盖前者：审核的人读到第一个，跑起来的是第二个
                if (!seen.add(key)) {
                    throw LayoutError.of(Code.E_DUP_KEY, path.isEmpty() ? "顶层" : path, keyStart,
                            path.isEmpty() ? "顶层" : path, key);
                }
                String child = path.isEmpty() ? key : path + "." + key;
                offsets.put(child, keyStart);
                ws();
                expect(':');
                value(child, depth + 1);
                ws();
                char c = next();
                if (c == '}') return;
                if (c != ',') syntax("对象里缺少 , 或 }");
            }
        }

        private void array(String path, int depth) {
            expect('[');
            ws();
            if (peek() == ']') {
                pos++;
                return;
            }
            int i = 0;
            while (true) {
                ws();
                offsets.put(path + "[" + i + "]", pos);
                value(path + "[" + i + "]", depth + 1);
                i++;
                ws();
                char c = next();
                if (c == ']') return;
                if (c != ',') syntax("数组里缺少 , 或 ]");
            }
        }

        private String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (pos >= src.length()) syntax("字符串没有收尾的引号");
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= src.length()) syntax("转义没有写完");
                    char e = src.charAt(pos++);
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> sb.append(unicodeEscape());
                        default -> syntax("不认识的转义");
                    }
                } else if (c < 0x20) {
                    syntax("字符串里有未转义的控制字符");
                } else {
                    sb.append(c);
                }
            }
        }

        /** 四位十六进制，逐位查。只查长度的话 parseInt 会连闭引号一起吃，还抛非 LayoutError。 */
        private char unicodeEscape() {
            if (pos + 4 > src.length()) syntax("转义后面不足四位");
            int v = 0;
            for (int i = 0; i < 4; i++) {
                int d = Character.digit(src.charAt(pos + i), 16);
                if (d < 0) syntax("转义后面不是四位十六进制");
                v = v * 16 + d;
            }
            pos += 4;
            return (char) v;
        }

        private void keyword(String word) {
            if (!src.startsWith(word, pos)) syntax("不认识的记号");
            pos += word.length();
        }

        /** JSON 的数字文法。放宽一点，01 / +1 / .5 / 1. 就会被 Gson 当字符串静默收下。 */
        private void number() {
            int start = pos;
            if (pos < src.length() && src.charAt(pos) == '-') pos++;
            if (pos < src.length() && src.charAt(pos) == '0') {
                pos++;
            } else {
                int d = pos;
                while (pos < src.length() && isDigit(src.charAt(pos))) pos++;
                if (pos == d) syntax("不认识的记号");
            }
            if (pos < src.length() && src.charAt(pos) == '.') {
                pos++;
                int d = pos;
                while (pos < src.length() && isDigit(src.charAt(pos))) pos++;
                if (pos == d) syntax("小数点后面要有数字");
            }
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
                int d = pos;
                while (pos < src.length() && isDigit(src.charAt(pos))) pos++;
                if (pos == d) syntax("指数后面要有数字");
            }
            if (pos == start) syntax("不认识的记号");
        }

        private static boolean isDigit(char c) {
            return c >= '0' && c <= '9';
        }

        /** JSON 的空白只有这四个。用 Character.isWhitespace 会放进全角空格，而 Gson 不认它。 */
        private void ws() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\r' || c == '\n') pos++;
                else break;
            }
        }

        private char peek() {
            if (pos >= src.length()) syntax("提前结束");
            return src.charAt(pos);
        }

        private char next() {
            char c = peek();
            pos++;
            return c;
        }

        private void expect(char want) {
            if (peek() != want) syntax("这里要 '" + want + "'");
            pos++;
        }

        private void syntax(String reason) {
            int line = 1;
            int col = 1;
            for (int i = 0; i < Math.min(pos, src.length()); i++) {
                if (src.charAt(i) == '\n') {
                    line++;
                    col = 1;
                } else {
                    col++;
                }
            }
            throw LayoutError.of(Code.E_JSON_SYNTAX, "", pos, line, col, reason);
        }
    }
}
