package com.november.mcphone.core.script.layout;

import com.november.mcphone.core.script.layout.LayoutError.Code;
import com.november.mcphone.core.script.layout.NodeParser.Ui;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Node IR 的断言测试（施工方案 §4），用 javac 单独编，不需要 Minecraft。
 *
 * <p>{@link #ui} / {@link #node} 那几个造树助手是 public 的：S4 的布局测试要吃同一批树，
 * 不该再造第二套写法。
 */
public class ScriptNodeTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();
    /** 哪些错误码被真正触发过。§4.9 全表逐条实现，靠它坐实。 */
    static final Set<Code> triggered = EnumSet.noneOf(Code.class);

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) {
            failures.add(what + "  期望 " + expected + "，实际 " + actual);
        }
    }

    static void check(boolean cond, String what) {
        checks++;
        if (!cond) failures.add(what);
    }

    static void report() {
        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部通过：" + checks + " 条断言");
        } else {
            System.out.println("失败 " + failures.size() + " / " + checks + " 条：");
            for (String f : failures) System.out.println("  ✗ " + f);
            System.exit(1);
        }
    }

    public static void main(String[] a) {
        typeTable();
        topLevel();
        validationOrder();
        nodeFields();
        showIfRules();
        onClickRules();
        componentProps();
        strictJson();
        pathsAndOffsets();
        templateTable();
        errorTableCovered();
        report();
    }

    // ============================================================
    //  造树助手（S4 复用）
    // ============================================================

    /** JSON 字符串字面量。控制字符按 JSON 规矩转义，不是原样塞进去。 */
    public static String q(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    /** {@code obj("a", "1", "b", q("x"))} → {@code {"a":1,"b":"x"}}，值必须已经是 JSON。 */
    public static String obj(String... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append(q(kv[i])).append(':').append(kv[i + 1]);
        }
        return sb.append('}').toString();
    }

    public static String arr(String... items) {
        return "[" + String.join(",", items) + "]";
    }

    /** 一个节点：{@code node("text", "text", q("hi"))}。 */
    public static String node(String type, String... kv) {
        String[] all = new String[kv.length + 2];
        all[0] = "type";
        all[1] = q(type);
        System.arraycopy(kv, 0, all, 2, kv.length);
        return obj(all);
    }

    /** 完整一份 IR。state 传 null 表示不写这个字段。 */
    public static String ui(String stateJson, String pagesJson, String entry) {
        return stateJson == null
                ? obj("pages", pagesJson, "entry", q(entry))
                : obj("state", stateJson, "pages", pagesJson, "entry", q(entry));
    }

    /** 只有一页 main 的最小 IR。 */
    public static String onePage(String rootNode) {
        return ui(null, obj("main", rootNode), "main");
    }

    /** 带一份常用 state 的单页 IR。 */
    public static String stateful(String rootNode) {
        return ui(obj("tab", "0", "expanded", "false", "title", q("x")), obj("main", rootNode), "main");
    }

    // ============================================================
    //  §4.7 / §5.4 类型表
    // ============================================================

    /** §5.4 速查表，逐行抄进来：json 名 | 容器 | 交互 | children | 专有字段。 */
    static final String[][] SPEC = {
            {"box", "1", "0", "1", ""},
            {"column", "1", "0", "1", ""},
            {"row", "1", "0", "1", ""},
            {"stack", "1", "0", "1", ""},
            {"scroll", "1", "0", "1", ""},
            {"grid", "1", "0", "1", "cols"},
            {"list", "1", "0", "1", "item-height"},
            {"spacer", "0", "0", "0", "size"},
            {"divider", "0", "0", "0", "vertical"},
            {"text", "0", "0", "0", "text i18n args"},
            {"image", "0", "0", "0", "src w h"},
            {"icon", "0", "0", "0", "name size"},
            {"item", "0", "0", "0", "item count size"},
            {"badge", "0", "0", "0", "count"},
            {"progress", "0", "0", "0", "value height"},
            {"button", "1", "1", "1", "text i18n args enabled enabledIf onClick"},
            {"toggle", "0", "1", "0", "bind label i18n enabled"},
            {"tab-bar", "0", "1", "0", "bind tabs"},
            {"text-input", "0", "1", "0", "bind placeholder max-length enabled"},
    };

    static void typeTable() {
        eq(NodeType.values().length, 19, "P0 的 18 个节点加 S27 text-input");
        eq(SPEC.length, 19, "每个节点都有一行完整契约");

        for (int i = 0; i < SPEC.length; i++) {
            String[] row = SPEC[i];
            NodeType t = NodeType.values()[i];
            eq(t.json, row[0], "第 " + (i + 1) + " 行的 json 名");
            eq(t.container, row[1].equals("1"), row[0] + " 的 container");
            eq(t.interactive, row[2].equals("1"), row[0] + " 的 interactive");
            eq(t.acceptsChildren, row[3].equals("1"), row[0] + " 的 acceptsChildren");
            eq(sorted(t.props()), sorted(split(row[4])), row[0] + " 的专有字段");
        }

        eq(NodeType.of("tab-bar"), NodeType.TAB_BAR, "带连字符的名字查得到");
        eq(NodeType.of("tabbar"), null, "查不到就是 null，不要猜");
        check(NodeType.BUTTON.container && NodeType.BUTTON.interactive,
              "BUTTON 既是容器又可交互，这是故意的");

        // 枚举名与 json 名必须能互推，否则改名时会漏
        for (NodeType t : NodeType.values()) {
            eq(t.json, t.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
               t.name() + " 的 json 名与枚举名对得上");
        }

        // acceptedFields：children 只有容器收，onClick 只有可交互的收
        check(NodeType.TEXT.acceptedFields().contains("children"),
              "children 是通用字段，每个类型都认识它");
        check(!NodeType.TEXT.acceptsChildren, "但 text 不接受 children，由 5e 拒");
        check(NodeType.TOGGLE.acceptedFields().contains("onClick"), "toggle 收 onClick");
        check(!NodeType.TEXT.acceptedFields().contains("onClick"), "text 不收 onClick");
    }

    // ============================================================
    //  §4.2.1 顶层三字段
    // ============================================================

    static void topLevel() {
        Ui good = NodeParser.parse(stateful(node("column")));
        eq(good.entry(), "main", "entry 读出来了");
        eq(good.pages().size(), 1, "一页");
        eq(good.state().get("tab"), 0, "state 的 int 值");
        eq(good.state().get("expanded"), false, "state 的 bool 值");
        eq(good.state().get("title"), "x", "state 的 string 值");
        eq(good.root().type(), NodeType.COLUMN, "entry 指到的根节点");

        // state 可缺省
        eq(NodeParser.parse(onePage(node("column"))).state().size(), 0, "没有 state 时是空表");

        trigger(Code.E_UNKNOWN_FIELD, obj("pages", obj("main", node("column")),
                "entry", q("main"), "states", "{}"), "顶层多一个 states");
        trigger(Code.E_MISSING_FIELD, obj("entry", q("main")), "缺 pages");
        trigger(Code.E_MISSING_FIELD, obj("pages", obj("main", node("column"))), "缺 entry");
        trigger(Code.E_BAD_TYPE, obj("pages", "[]", "entry", q("main")), "pages 不是对象");
        trigger(Code.E_UNKNOWN_PAGE, ui(null, obj("main", node("column")), "nope"),
                "entry 不在 pages 里");

        // state 的键名、数量、值类型
        trigger(Code.E_BAD_VALUE, ui(obj("Tab", "0"), obj("main", node("column")), "main"),
                "state 键名不许大写");
        trigger(Code.E_BAD_VALUE, ui(obj("_tab", "0"), obj("main", node("column")), "main"),
                "state 键名要字母开头");
        trigger(Code.E_BAD_TYPE, ui(obj("tab", "1.5"), obj("main", node("column")), "main"),
                "state 的值不收小数");
        // §9.7：数组（≤32、同构）与对象（≤16 键、一层）也是初值，容器最多套 2 层
        Ui containers = NodeParser.parse(ui(obj("items", arr(q("a"), q("b")), "rows", arr(obj("id", "1")),
                "tabIndex", "0"), obj("main", node("column")), "main"));
        eq(containers.state().get("items"), List.of("a", "b"), "state 收数组");
        eq(containers.state().get("rows"), List.of(java.util.Map.of("id", 1)), "state 收数组套对象");
        eq(containers.state().get("tabIndex"), 0, "state 键名首字母之后可以大写");
        trigger(Code.E_BAD_VALUE, ui(obj("tab", arr("1", q("x"))), obj("main", node("column")), "main"),
                "state 的数组要同构");
        trigger(Code.E_BAD_VALUE, ui(obj("tab", obj("a", obj("b", "1"))), obj("main", node("column")), "main"),
                "state 的对象只能一层");
        trigger(Code.E_BAD_VALUE, ui(obj("tab", arr(arr(arr("1")))), obj("main", node("column")), "main"),
                "state 的容器最多套 2 层");
        trigger(Code.E_BAD_TYPE, ui(obj("tab", arr("1.5")), obj("main", node("column")), "main"),
                "state 的数组里也不收小数");
        trigger(Code.E_BAD_TYPE, ui(obj("tab", "null"), obj("main", node("column")), "main"),
                "state 的值不收 null");
        trigger(Code.E_BAD_VALUE, ui(obj("tab", q("x".repeat(65))), obj("main", node("column")), "main"),
                "state 的字符串上限 64");

        String[] many = new String[NodeParser.MAX_STATE * 2 + 2];
        for (int i = 0; i <= NodeParser.MAX_STATE; i++) {
            many[i * 2] = "k" + i;
            many[i * 2 + 1] = "0";
        }
        trigger(Code.E_BAD_VALUE, ui(obj(many), obj("main", node("column")), "main"),
                "state 最多 16 项");

        // pages 的数量与键名
        String[] pages = new String[(NodeParser.MAX_PAGES + 1) * 2];
        for (int i = 0; i <= NodeParser.MAX_PAGES; i++) {
            pages[i * 2] = "p" + i;
            pages[i * 2 + 1] = node("column");
        }
        trigger(Code.E_BAD_VALUE, ui(null, obj(pages), "p0"), "pages 最多 8 页");
        trigger(Code.E_BAD_VALUE, ui(null, "{}", "main"), "pages 至少 1 页");
        trigger(Code.E_BAD_VALUE, ui(null, obj("Main", node("column")), "Main"), "页名不许大写");

        // 语法
        trigger(Code.E_JSON_SYNTAX, "{", "花括号没收尾");
        trigger(Code.E_JSON_SYNTAX, "{\"a\":1,}", "对象里多一个逗号");
        trigger(Code.E_JSON_SYNTAX, onePage(node("column")) + "x", "末尾有多余内容");
        eq(codeOf(() -> NodeParser.parse("[]")), Code.E_BAD_TYPE, "顶层不是对象");
    }

    // ============================================================
    //  §4.8 校验顺序
    // ============================================================

    static void validationOrder() {
        // 这一条是 §4.8 点名的：5b 必须先于 5f。写错 "texts" 的人该收到「不认识的字段」，
        // 而不是「缺少必填字段 text」
        LayoutError e = errorOf(onePage(node("text", "texts", q("hi"))));
        eq(e.code(), Code.E_UNKNOWN_FIELD, "写成 texts 报的是「不认识的字段」，不是「缺少 text」");
        check(e.getMessage().contains("texts"), "报错里点出 texts 这个名字");
        check(e.getMessage().contains("text"), "报错里列出这个类型接受的字段");

        // 5a 先于 5b：类型都不认识，就没有「这个类型接受什么」可言
        eq(codeOf(() -> NodeParser.parse(onePage(node("tekst", "nonsense", "1")))),
           Code.E_UNKNOWN_TYPE, "类型不认识时先报类型");

        // 5b 先于 5c：未知字段比 id 非法先报
        eq(codeOf(() -> NodeParser.parse(onePage(node("column", "id", q("BAD"), "nope", "1")))),
           Code.E_UNKNOWN_FIELD, "未知字段先于 id 校验");

        // 5c 先于 5d
        eq(codeOf(() -> NodeParser.parse(onePage(
                node("column", "id", q("BAD"), "class", arr(q("BAD")))))),
           Code.E_BAD_VALUE, "id 与 class 都坏时先报 id 那条");

        // 5e 先于 5f：divider 的 children 与 vertical 都坏，先报 children 那条
        eq(codeOf(() -> NodeParser.parse(onePage(
                node("divider", "children", arr(node("text", "text", q("x"))), "vertical", "1")))),
           Code.E_CHILDREN_NOT_ALLOWED, "children 不该收时先报它，不去查 vertical");

        // children 是通用字段：非容器写它报的是专用码，不是「不认识的字段」
        eq(codeOf(() -> NodeParser.parse(onePage(node("text", "text", q("x"), "children", "[]")))),
           Code.E_CHILDREN_NOT_ALLOWED, "§4.3 把 children 列为通用字段，所以有专用错误码");

        // 顶层顺序：pages / entry 的存在性与类型是第 1 步，state 的细节是第 2 步。
        // 反过来的话，整份缺 pages 的人收到的是一条 state 键名的抱怨
        eq(codeOf(() -> NodeParser.parse(ui(obj("BAD", "0"), "[]", "main"))),
           Code.E_BAD_TYPE, "pages 类型不对先于 state 键名报");
        eq(codeOf(() -> NodeParser.parse(ui(obj("BAD", "0"), obj("main", node("column")), "main"))),
           Code.E_BAD_VALUE, "pages 没问题时才轮到 state 的键名");
    }

    // ============================================================
    //  §4.3 节点通用字段
    // ============================================================

    static void nodeFields() {
        Ui ui = NodeParser.parse(onePage(node("column",
                "id", q("root"), "class", arr(q("page"), q("dark")),
                "children", arr(node("text", "text", q("hi"))))));
        Node root = ui.root();
        eq(root.id(), "root", "id 读出来了");
        eq(root.classes(), List.of("page", "dark"), "class 读出来了");
        eq(root.children().size(), 1, "children 读出来了");
        eq(root.children().get(0).str("text", ""), "hi", "子节点的 text");
        eq(root.showIf(), null, "没写 showIf 就是 null");
        eq(root.onClick(), null, "没写 onClick 就是 null");

        trigger(Code.E_UNKNOWN_TYPE, onePage(node("nope")), "不认识的类型");
        trigger(Code.E_MISSING_FIELD, onePage(obj("id", q("x"))), "节点缺 type");
        trigger(Code.E_BAD_VALUE, onePage(node("column", "id", q("Root"))), "id 不许大写");
        trigger(Code.E_BAD_VALUE, onePage(node("column", "id", q("1root"))), "id 要字母开头");
        trigger(Code.E_BAD_VALUE, onePage(node("column", "class", arr(q("Page")))), "class 不许大写");
        trigger(Code.E_BAD_TYPE, onePage(node("column", "class", q("page"))), "class 要数组");

        String[] classes = new String[NodeParser.MAX_CLASSES + 1];
        for (int i = 0; i < classes.length; i++) classes[i] = q("c" + i);
        trigger(Code.E_BAD_VALUE, onePage(node("column", "class", arr(classes))), "class 最多 8 个");

        // id 全树唯一
        trigger(Code.E_DUP_ID, onePage(node("column", "id", q("dup"), "children",
                arr(node("text", "id", q("dup"), "text", q("x"))))), "同一页里 id 撞了");
        trigger(Code.E_DUP_ID, ui(null, obj(
                "main", node("column", "id", q("dup")),
                "other", node("column", "id", q("dup"))), "main"), "跨页 id 也要唯一");

        // children 只有容器收
        trigger(Code.E_CHILDREN_NOT_ALLOWED, onePage(node("column", "children",
                arr(node("badge", "children", "[]")))), "非容器写 children");
        check(errorOf(onePage(node("badge", "children", "[]"))).getMessage().contains("column"),
              "报错里列出接受 children 的类型");
        trigger(Code.E_BAD_TYPE, onePage(node("column", "children", "{}")), "children 要数组");

        // 规模
        trigger(Code.E_TOO_DEEP, onePage(nest(NodeParser.MAX_DEPTH + 1)), "嵌套超过 32 层");
        trigger(Code.E_TOO_MANY_NODES, onePage(wide(NodeParser.MAX_NODES + 1)), "节点超过 512 个");
    }

    /** 套 n 层 column。 */
    static String nest(int n) {
        String inner = node("column");
        for (int i = 1; i < n; i++) inner = node("column", "children", arr(inner));
        return inner;
    }

    /** 一个 column 底下摊开 n-1 个 spacer。 */
    static String wide(int n) {
        String[] kids = new String[n - 1];
        for (int i = 0; i < kids.length; i++) kids[i] = node("spacer");
        return node("column", "children", arr(kids));
    }

    // ============================================================
    //  §4.4 showIf
    // ============================================================

    static void showIfRules() {
        Node root = NodeParser.parse(stateful(node("column", "showIf",
                obj("key", q("tab"), "eq", "1")))).root();
        eq(root.showIf().key(), "tab", "showIf 的 key");
        eq(root.showIf().kind(), Node.ShowIf.Kind.EQ, "eq 形式");
        eq(root.showIf().value(), 1, "比较值");

        eq(NodeParser.parse(stateful(node("column", "showIf",
                obj("key", q("tab"), "ne", "0")))).root().showIf().kind(),
           Node.ShowIf.Kind.NE, "ne 形式");
        eq(NodeParser.parse(stateful(node("column", "showIf",
                obj("key", q("expanded"), "truthy", "true")))).root().showIf().kind(),
           Node.ShowIf.Kind.TRUTHY, "truthy 形式");

        trigger(Code.E_UNKNOWN_STATE_KEY, stateful(node("column", "showIf",
                obj("key", q("nope"), "eq", "1"))), "showIf 的 key 不在 state 里");
        trigger(Code.E_STATE_TYPE, stateful(node("column", "showIf",
                obj("key", q("tab"), "eq", "true"))), "int 的 key 拿 bool 比");
        trigger(Code.E_STATE_TYPE, stateful(node("column", "showIf",
                obj("key", q("expanded"), "eq", "1"))), "bool 的 key 拿 int 比");
        trigger(Code.E_BAD_VALUE, stateful(node("column", "showIf",
                obj("key", q("tab"), "eq", "1", "ne", "2"))), "eq 与 ne 同时出现");
        trigger(Code.E_BAD_VALUE, stateful(node("column", "showIf", obj("key", q("tab")))),
                "eq / ne / truthy 一个都没有");
        trigger(Code.E_BAD_TYPE, stateful(node("column", "showIf",
                obj("key", q("expanded"), "truthy", "1"))), "truthy 要 bool");
        trigger(Code.E_UNKNOWN_FIELD, stateful(node("column", "showIf",
                obj("key", q("tab"), "eq", "1", "gt", "2"))), "showIf 里多一个 gt");
        trigger(Code.E_MISSING_FIELD, stateful(node("column", "showIf", obj("eq", "1"))),
                "showIf 缺 key");
    }

    // ============================================================
    //  §4.5 onClick
    // ============================================================

    static void onClickRules() {
        Node b = NodeParser.parse(stateful(button(obj("set", obj("tab", "1"),
                "nav", q("main"))))).root();
        eq(b.onClick().set().get("tab"), 1, "set 读出来了");
        eq(b.onClick().nav(), "main", "nav 读出来了");
        eq(b.onClick().builtin(), Node.Action.Builtin.NONE, "对象形式没有 builtin");

        eq(NodeParser.parse(stateful(button(q("close")))).root().onClick().builtin(),
           Node.Action.Builtin.CLOSE, "close");
        eq(NodeParser.parse(stateful(button(q("back")))).root().onClick().builtin(),
           Node.Action.Builtin.BACK, "back");
        eq(NodeParser.parse(stateful(button(obj("toggle", q("expanded"))))).root().onClick().toggle(),
           "expanded", "toggle");

        trigger(Code.E_BAD_VALUE, stateful(button(q("quit"))), "字符串形式只认 close / back");
        trigger(Code.E_NO_ACTION, stateful(button("{}")), "onClick 三者全省");
        trigger(Code.E_UNKNOWN_STATE_KEY, stateful(button(obj("set", obj("nope", "1")))),
                "set 的 key 不在 state 里");
        trigger(Code.E_STATE_TYPE, stateful(button(obj("set", obj("tab", "true")))),
                "set 的值类型对不上");
        trigger(Code.E_STATE_TYPE, stateful(button(obj("toggle", q("tab")))),
                "toggle 只能对 bool");
        trigger(Code.E_UNKNOWN_STATE_KEY, stateful(button(obj("toggle", q("nope")))),
                "toggle 的 key 不在 state 里");
        trigger(Code.E_UNKNOWN_PAGE, stateful(button(obj("nav", q("nope")))),
                "nav 的页不存在");
        trigger(Code.E_UNKNOWN_FIELD, stateful(button(obj("push", q("main")))),
                "onClick 里多一个 push");
        trigger(Code.E_BAD_TYPE, stateful(button("1")), "onClick 要字符串或对象");

        // onClick 只有可交互的类型收
        trigger(Code.E_UNKNOWN_FIELD, stateful(node("column", "onClick", q("close"))),
                "column 不收 onClick");
        eq(codeOf(() -> NodeParser.parse(stateful(node("toggle", "bind", q("expanded"),
                "onClick", q("close"))))), null, "toggle 收 onClick");
    }

    static String button(String onClick) {
        return node("button", "text", q("点我"), "onClick", onClick);
    }

    // ============================================================
    //  §5 组件专有字段
    // ============================================================

    static void componentProps() {
        // 容器
        eq(NodeParser.parse(onePage(node("grid", "cols", "4"))).root().num("cols", 0), 4, "grid.cols");
        trigger(Code.E_BAD_VALUE, onePage(node("grid", "cols", "7")), "grid.cols 上限 6");
        trigger(Code.E_BAD_VALUE, onePage(node("grid", "cols", "0")), "grid.cols 下限 1");
        trigger(Code.E_BAD_TYPE, onePage(node("grid", "cols", q("3"))), "grid.cols 要 int");
        eq(NodeParser.parse(onePage(node("list", "item-height", "20"))).root().num("item-height", 0),
           20, "list.item-height");

        // 内容
        eq(NodeParser.parse(onePage(node("text", "text", q("hi")))).root().str("text", ""), "hi", "text");
        trigger(Code.E_MISSING_FIELD, onePage(node("text")), "text 与 i18n 都没有");
        trigger(Code.E_BAD_VALUE, onePage(node("text", "text", q("x".repeat(513)))), "text 上限 512");
        trigger(Code.E_BAD_VALUE, onePage(node("text", "text", q("ab"))), "text 禁控制字符");
        check(codeOf(() -> NodeParser.parse(onePage(node("text", "text", q("a\nb"))))) == null,
              "text 允许换行");
        eq(NodeParser.parse(onePage(node("text", "i18n", q("k"), "args",
                arr(q("a"), "1")))).root().props().get("args"), List.of("a", 1), "i18n 的 args");
        trigger(Code.E_BAD_VALUE, onePage(node("text", "text", q("x"), "args", arr(q("a")))),
                "args 没有 i18n 就没意义");
        trigger(Code.E_BAD_VALUE, onePage(node("text", "i18n", q("k"), "args",
                arr(q("a"), q("b"), q("c"), q("d"), q("e")))), "args 最多 4 个");

        eq(NodeParser.parse(onePage(node("image", "src", q("assets/a.png")))).root().str("src", ""),
           "assets/a.png", "image.src");
        trigger(Code.E_BAD_VALUE, onePage(node("image", "src", q("a.jpg"))), "image.src 必须 .png");
        trigger(Code.E_BAD_VALUE, onePage(node("image", "src", q("../a.png"))), "image.src 不许穿越");
        trigger(Code.E_BAD_VALUE, onePage(node("image", "src", q("a.png"), "w", "200")), "image.w 上限 120");

        eq(NodeParser.parse(onePage(node("icon", "name", q("gear")))).root().str("name", ""),
           "gear", "icon.name");
        trigger(Code.E_BAD_VALUE, onePage(node("icon", "name", q("rocket"))), "icon 是固定 12 个");
        trigger(Code.E_BAD_VALUE, onePage(node("icon", "name", q("gear"), "size", "24")), "icon.size 上限 20");
        eq(NodeParser.ICON_NAMES.size(), 12, "icon 固定集合是 12 个");

        trigger(Code.E_MISSING_FIELD, onePage(node("item")), "item.item 必填");
        trigger(Code.E_BAD_VALUE, onePage(node("item", "item", q("minecraft:stone"), "count", "100")),
                "item.count 上限 99");
        trigger(Code.E_BAD_VALUE, onePage(node("progress", "value", "101")), "progress.value 上限 100");
        trigger(Code.E_BAD_VALUE, onePage(node("progress", "height", "1")), "progress.height 下限 2");
        eq(NodeParser.parse(onePage(node("badge", "count", "5"))).root().num("count", 0), 5, "badge.count");
        eq(NodeParser.parse(onePage(node("divider", "vertical", "true"))).root().flag("vertical", false),
           true, "divider.vertical");
        trigger(Code.E_BAD_TYPE, onePage(node("divider", "vertical", "1")), "divider.vertical 要 bool");

        // button：text / i18n / children 至少一个
        trigger(Code.E_MISSING_FIELD, stateful(node("button", "onClick", q("close"))),
                "button 三者都没有");
        check(codeOf(() -> NodeParser.parse(stateful(node("button", "onClick", q("close"),
                "children", aloneIcon())))) == null, "button 有 children 就不必写 text");
        trigger(Code.E_MISSING_FIELD, stateful(node("button", "text", q("x"))), "button.onClick 必填");
        eq(NodeParser.parse(stateful(node("button", "text", q("x"), "onClick", q("close"),
                "enabledIf", obj("key", q("expanded"), "truthy", "true")))).root()
                .props().get("enabledIf") instanceof Node.ShowIf, true, "enabledIf 与 showIf 同形");
        trigger(Code.E_UNKNOWN_STATE_KEY, stateful(node("button", "text", q("x"),
                "onClick", q("close"), "enabledIf", obj("key", q("nope"), "truthy", "true"))),
                "enabledIf 的 key 也要在 state 里");

        // toggle / tab-bar 的 bind
        eq(NodeParser.parse(stateful(node("toggle", "bind", q("expanded")))).root().str("bind", ""),
           "expanded", "toggle.bind");
        trigger(Code.E_STATE_TYPE, stateful(node("toggle", "bind", q("tab"))), "toggle.bind 要 bool");
        trigger(Code.E_MISSING_FIELD, stateful(node("toggle")), "toggle.bind 必填");
        trigger(Code.E_STATE_TYPE, stateful(node("tab-bar", "bind", q("expanded"),
                "tabs", twoTabs())), "tab-bar.bind 要 int");
        trigger(Code.E_BAD_VALUE, stateful(node("tab-bar", "bind", q("tab"),
                "tabs", arr(obj("text", q("a"))))), "tabs 最少 2 项");
        trigger(Code.E_BAD_VALUE, stateful(node("tab-bar", "bind", q("tab"), "tabs",
                arr(obj("text", q("a")), obj("text", q("b")), obj("text", q("c")),
                    obj("text", q("d")), obj("text", q("e"))))), "tabs 最多 4 项");
        trigger(Code.E_BAD_VALUE, stateful(node("tab-bar", "bind", q("tab"), "tabs",
                arr(obj("text", q("a"), "icon", q("gear")), obj("text", q("b"))))),
                "tabs 每项恰好一个 text / i18n / icon");
        trigger(Code.E_UNKNOWN_FIELD, stateful(node("tab-bar", "bind", q("tab"), "tabs",
                arr(obj("label", q("a")), obj("text", q("b"))))), "tabs 项里的字段名不认识");
        trigger(Code.E_BAD_VALUE, stateful(node("tab-bar", "bind", q("tab"), "tabs",
                arr(obj("icon", q("rocket")), obj("text", q("b"))))), "tabs 的 icon 也要在固定集合里");
    }

    static String aloneIcon() {
        return arr(node("icon", "name", q("check")));
    }

    static String twoTabs() {
        return arr(obj("text", q("a")), obj("text", q("b")));
    }

    // ============================================================
    //  路径与偏移
    // ============================================================

    static void pathsAndOffsets() {
        String json = ui(null, obj("main", node("column", "children",
                arr(node("text", "text", q("a")),
                    node("column", "children", arr(node("text", "texts", q("b"))))))), "main");
        LayoutError e = errorOf(json);
        eq(e.path(), "pages.main.children[1].children[0]", "路径指到出错的那个节点");
        check(e.offset() > 0, "带原文字符偏移");
        eq(json.startsWith("\"texts\"", e.offset()), true, "偏移正好落在出错的那个字段上");
        check(e.getMessage().contains("偏移 " + e.offset()), "报错文本里带着偏移");

        // 多行输入时语法错误报行列
        LayoutError syntax = errorOf("{\n  \"pages\": {\n    \"main\": {,\n");
        eq(syntax.code(), Code.E_JSON_SYNTAX, "语法错误");
        check(syntax.getMessage().contains("第 3 行"), "语法错误报到行");
    }


    // ============================================================
    //  严格 JSON：扫描器必须比 Gson 严，松一点两边就看见不同的树
    // ============================================================

    static void strictJson() {
        String bs = String.valueOf('\\');

        // 转义后面不是四位十六进制 —— 只查长度的话 parseInt 会连闭引号一起吃
        trigger(Code.E_JSON_SYNTAX, onePage("{\"type\":\"text\",\"text\":\"" + bs + "uZZZZ\"}"),
                "转义后面不是十六进制");
        trigger(Code.E_JSON_SYNTAX, onePage("{\"type\":\"text\",\"text\":\"" + bs + "u00\"}"),
                "转义后面不足四位");
        check(codeOf(() -> NodeParser.parse(onePage("{\"type\":\"text\",\"text\":\"" + bs + "u4e2d\"}")))
              == null, "合法的四位十六进制转义照收");

        // 深嵌套：扫描器是递归的，MAX_DEPTH 在它之后才生效的话就是一个纯爆栈的洞
        trigger(Code.E_TOO_DEEP, "[".repeat(200000), "扫描器自己带深度上限，不靠后面的节点判据");

        // JSON 的空白只有四个。中文作者输入法手滑打出全角空格是日常
        trigger(Code.E_JSON_SYNTAX, "{\"pages\":\u3000{\"main\":{\"type\":\"column\"}},\"entry\":\"main\"}",
                "结构里的全角空格");
        trigger(Code.E_JSON_SYNTAX, "{\"pages\":\u000b{\"main\":{\"type\":\"column\"}},\"entry\":\"main\"}",
                "结构里的垂直制表符");
        check(codeOf(() -> NodeParser.parse(onePage(node("text", "text", q("全角\u3000空格"))))) == null,
              "字符串里面的全角空格照收");

        // 不带引号的记号：Gson 宽容模式会当字符串收下，于是拼错的字面量静默生效
        trigger(Code.E_JSON_SYNTAX, "{\"pages\":{\"main\":{\"type\":badge}},\"entry\":\"main\"}",
                "类型名没写引号");
        trigger(Code.E_JSON_SYNTAX, ui("{\"k\":tru}", obj("main", node("column")), "main"),
                "true 少打一个字母");
        for (String bad : new String[]{"01", "+1", "1.", ".5", "-", "0x1", "1e", "deadbeef"}) {
            trigger(Code.E_JSON_SYNTAX, ui("{\"k\":" + bad + "}", obj("main", node("column")), "main"),
                    "不合 JSON 数字文法：" + bad);
        }
        check(codeOf(() -> NodeParser.parse(ui("{\"k\":-0}", obj("main", node("column")), "main"))) == null,
              "-0 是合法的 JSON 数字");

        // 重复键：Gson 里后者静默覆盖前者，人读到第一个、跑的是第二个
        trigger(Code.E_DUP_KEY, onePage("{\"type\":\"text\",\"text\":\"只是一行字\","
                + "\"type\":\"button\",\"onClick\":\"close\"}"), "节点里 type 写了两次");
        trigger(Code.E_DUP_KEY, "{\"pages\":{\"main\":{\"type\":\"column\"}},"
                + "\"entry\":\"main\",\"entry\":\"other\"}", "顶层 entry 写了两次");

        // 可选字段写成 null，该说类型不对，不是「缺少必填字段」
        eq(codeOf(() -> NodeParser.parse(onePage(node("column", "id", "null")))), Code.E_BAD_TYPE,
           "id 写成 null 报的是类型，不是缺字段");

        // §4.8 第 1 步：整份缺 pages 是结构性错误，先于 state 的细节报
        eq(codeOf(() -> NodeParser.parse("{\"state\":{\"BAD\":1},\"entry\":\"main\"}")),
           Code.E_MISSING_FIELD, "缺 pages 先于 state 键名报");
        eq(codeOf(() -> NodeParser.parse(ui("{\"BAD\":1}", "[]", "main"))), Code.E_BAD_TYPE,
           "pages 类型不对先于 state 键名报");

        // 报错文字必须可复现：Set.of 的迭代顺序逐进程随机
        String first = errorOf(onePage(node("text", "text", q("x"), "zz", "1"))).getMessage();
        for (int i = 0; i < 20; i++) {
            eq(errorOf(onePage(node("text", "text", q("x"), "zz", "1"))).getMessage(), first,
               "同一条报错每次的文字都一样");
        }
        check(first.indexOf("args") < first.indexOf("children"), "「允许的取值」是排过序的");

        // text 与 i18n 二选一
        trigger(Code.E_BAD_VALUE, onePage(node("text", "text", q("a"), "i18n", q("k"))),
                "text 与 i18n 不许都写");

        // 会进渲染的文字三处同一条判据
        trigger(Code.E_BAD_VALUE, stateful(node("toggle", "bind", q("expanded"),
                "label", q("x".repeat(513)))), "toggle.label 也有长度上限");
        trigger(Code.E_BAD_VALUE, stateful(node("tab-bar", "bind", q("tab"), "tabs",
                arr(obj("text", q("a\u0001")), obj("text", q("b"))))), "tab 的分段名也禁控制字符");
    }

    // ============================================================
    //  §4.9 的模板逐字对照
    // ============================================================

    /** 施工方案 §4.9 的原文，逐字抄进来。改实现的文案而不动方案，这里会红。 */
    static final String[][] SPEC_TEMPLATES = {
            {"E_JSON_SYNTAX", "ui.json 第 %d 行第 %d 列：%s"},
            {"E_UNKNOWN_FIELD", "%s：不认识的字段 '%s'。这个类型接受：%s"},
            {"E_MISSING_FIELD", "%s：缺少必填字段 '%s'"},
            {"E_BAD_TYPE", "%s：字段 '%s' 要 %s，给的是 %s"},
            {"E_BAD_VALUE", "%s：字段 '%s' 的值 '%s' 不合法。允许：%s"},
            {"E_DUP_ID", "%s：id '%s' 和 %s 撞了，id 必须全树唯一"},
            {"E_TOO_MANY_NODES", "节点总数 %d 超过上限 512"},
            {"E_TOO_DEEP", "%s：嵌套深度 %d 超过上限 32"},
            {"E_UNKNOWN_STATE_KEY", "%s：state 里没有 '%s'。已声明的有：%s"},
            {"E_STATE_TYPE", "%s：state['%s'] 是 %s，不能和 %s 比较"},
            {"E_UNKNOWN_PAGE", "%s：pages 里没有 '%s'。已声明的有：%s"},
            {"E_NO_ACTION", "%s：onClick 至少要有 set / toggle / nav 之一"},
    };

    /** 与 §4.9 有出入的三条，每条都得有理由。多出一条没登记的，下面会红。 */
    static final Set<String> DEVIATIONS = Set.of(
            "E_UNKNOWN_TYPE",           // 加了「允许：%s」—— §4.9 要求每条都列出允许的取值，原模板没有这个槽
            "E_CHILDREN_NOT_ALLOWED",   // 同上，列出接受 children 的类型
            "E_DUP_KEY");               // §4.9 没有这条；重复键的威胁与 S1 的 E_PKG_MANIFEST_DUP_KEY 同源

    static void templateTable() {
        for (String[] row : SPEC_TEMPLATES) {
            eq(Code.valueOf(row[0]).text(), row[1], row[0] + " 的文案与 §4.9 逐字相同");
        }
        Set<String> covered = new LinkedHashSet<>();
        for (String[] row : SPEC_TEMPLATES) covered.add(row[0]);
        covered.addAll(DEVIATIONS);
        for (Code c : Code.values()) {
            check(covered.contains(c.name()), c.name() + " 要么与 §4.9 逐字相同，要么登记成有理由的出入");
        }
        eq(covered.size(), Code.values().length, "没有登记了却不存在的码");

        // 实参个数对不上时当场炸，而不是等这条分支被走到
        boolean threw = false;
        try {
            LayoutError.of(Code.E_MISSING_FIELD, "p", 0, "只给一个");
        } catch (IllegalStateException e) {
            threw = true;
        }
        check(threw, "模板要两个参数、只给一个时当场抛");
    }

    // ============================================================
    //  §4.9 全表
    // ============================================================

    static void errorTableCovered() {
        System.out.println("§4.9 错误文案对照表：");
        for (Code c : Code.values()) {
            System.out.printf("  %-24s %s%n", c.name(), triggered.contains(c) ? "已实现，有触发样例" : "没有触发样例");
        }
        Set<Code> missing = EnumSet.allOf(Code.class);
        missing.removeAll(triggered);
        check(missing.isEmpty(), "§4.9 每个错误码都有触发样例，缺的是：" + missing);
    }

    // ============================================================
    //  断言辅助
    // ============================================================

    /** 断言这份 IR 被拒，且拒的是这个码；顺手记进全表覆盖。 */
    static void trigger(Code expected, String json, String what) {
        LayoutError e = null;
        try {
            NodeParser.parse(json);
        } catch (LayoutError caught) {
            e = caught;
        }
        checks++;
        if (e == null) {
            failures.add(what + "  期望 " + expected + "，实际没有报错");
            return;
        }
        if (e.code() != expected) {
            failures.add(what + "  期望 " + expected + "，实际 " + e.code() + "：" + e.getMessage());
            return;
        }
        triggered.add(expected);
    }

    static Code codeOf(Runnable body) {
        try {
            body.run();
            return null;
        } catch (LayoutError e) {
            return e.code();
        }
    }

    static LayoutError errorOf(String json) {
        try {
            NodeParser.parse(json);
        } catch (LayoutError e) {
            return e;
        }
        throw new IllegalStateException("这份 IR 本该被拒：" + json);
    }

    static List<String> sorted(java.util.Collection<String> values) {
        List<String> out = new ArrayList<>(values);
        out.sort(null);
        return out;
    }

    static Set<String> split(String spaceSeparated) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : spaceSeparated.split(" ")) {
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }
}
