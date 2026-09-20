package com.november.mcphone.core.script.sfc;

import com.november.mcphone.core.script.layout.Node;
import com.november.mcphone.core.script.layout.NodeParser;
import com.november.mcphone.core.script.layout.NodeType;
import com.november.mcphone.core.script.layout.UiState;
import com.november.mcphone.core.script.sfc.CompiledTemplate.BoundProp;
import com.november.mcphone.core.script.sfc.CompiledTemplate.Chain;
import com.november.mcphone.core.script.sfc.CompiledTemplate.Child;
import com.november.mcphone.core.script.sfc.CompiledTemplate.Element;
import com.november.mcphone.core.script.sfc.CompiledTemplate.Text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一页模板的实例化（施工方案 §9.9）。每次重排调一次 {@link #instantiate}：v-if 过滤、v-for 展开成真实的兄弟节点、
 * 绑定求值，产出一棵普通的 Node 树 —— 布局引擎永远看到静态树，不知道 v-for 存在，单遍布局才成立。
 */
public final class TemplateInstance {

    public static final int MAX_FOR_ITEMS = 2048;

    private static final PropRules.Spec TEXT = PropRules.of(NodeType.TEXT).get("text");

    private final CompiledTemplate compiled;
    private final String file;

    public TemplateInstance(CompiledTemplate compiled, String file) {
        this.compiled = Objects.requireNonNull(compiled, "compiled");
        this.file = Objects.requireNonNull(file, "file");
    }

    /**
     * 一次实例化的产物。点击动作不进 Node：{@code @click} 的值是语句，§4.5 的 onClick 装不下，所以挂在产出它的这棵树上，
     * 按节点对象查。拿哪棵树的 LayoutNode 去点，就用哪棵树的 Tree —— 两次实例化之间节点对象不通用。
     */
    public static final class Tree {
        private final Node root;
        private final List<String> warnings;
        private final boolean truncated;
        private final Map<Node, Statements.Bound> clicks;
        private final Set<Node> nodes;
        private final String file;

        private Tree(Node root, List<String> warnings, boolean truncated, Map<Node, Statements.Bound> clicks,
                     Set<Node> nodes, String file) {
            this.root = root;
            this.warnings = warnings;
            this.truncated = truncated;
            this.clicks = clicks;
            this.nodes = nodes;
            this.file = file;
        }

        public Node root() {
            return root;
        }

        /** 这次实例化的 warn，去重后普通的最多 {@value EvalContext#MAX_WARNINGS} 条，截断与预算用完的另算、不丢。 */
        public List<String> warnings() {
            return warnings;
        }

        public boolean truncated() {
            return truncated;
        }

        /** 这个节点的 @click，没有返回 null。 */
        public Statements.Bound clickOf(Node node) {
            return clicks.get(node);
        }

        /**
         * 点了 hit 之后改 state（§9.4.6）：toggle 把 bind 取反、tab-bar 把 bind 设成 segment，然后执行 @click。
         * 两步在一份副本上做，@click 任一条失败时 bind 也不写（§9.6 整组不执行）。可不可用由调用方先判（Renderer.isEnabled）。
         * 这个节点既没有 bind 也没有 @click、bind 不在 state 里、或 tab-bar 的 segment 越界时返回 null，什么都不改。
         * hit 不是这棵树产出的节点时抛 {@link IllegalArgumentException}：查不到 @click 却照写 bind，会悄悄拆开「bind 与 @click 同成败」。
         */
        public Statements.Outcome click(Node hit, UiState state, int segment) {
            if (!nodes.contains(hit)) {
                throw new IllegalArgumentException("这个节点不是这棵树产出的：点击要用同一次 instantiate 的树去查");
            }
            Statements.Bound bound = clicks.get(hit);
            String bind = hit.str("bind", null);
            boolean binds = bind != null && state.get(bind) != null
                    && (hit.type() == NodeType.TOGGLE || hit.type() == NodeType.TAB_BAR);
            if (hit.type() == NodeType.TAB_BAR && binds) {
                int count = hit.props().get("tabs") instanceof List<?> tabs ? tabs.size() : 0;
                if (segment < 0 || segment >= count) return null;
            }
            if (!binds && bound == null) return null;
            UiState trial = state.copy();
            if (binds) {
                if (hit.type() == NodeType.TOGGLE) {
                    trial.toggle(bind);
                } else {
                    trial.set(bind, segment);
                }
            }
            Statements.Outcome outcome = bound == null
                    ? new Statements.Outcome(true, false, false, null, List.of(), List.of())
                    : bound.run(trial, file);
            if (outcome.applied()) {
                for (Map.Entry<String, Object> e : trial.values().entrySet()) state.set(e.getKey(), e.getValue());
            }
            return outcome;
        }
    }

    /** 一次实例化的现场：节点预算、已用过的 id、点击表、宿主上下文（点击时也要用同一份）。 */
    private static final class Pass {
        int budget = NodeParser.MAX_NODES;
        boolean truncated;
        final Set<String> ids = new HashSet<>();
        final Map<Node, Statements.Bound> clicks = new IdentityHashMap<>();
        final Set<Node> nodes = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<String, Object> host;

        private Pass(Map<String, Object> host) {
            this.host = host;
        }
    }

    /** 按当前 state 产出一棵树。不抛：超限截断、求值出错降级，原因在 {@link Tree#warnings()}。 */
    public Tree instantiate(UiState state) {
        return instantiate(state, Map.of());
    }

    /**
     * 同上，另外把宿主注入的只读上下文（§13.8 的 {@code backend}）交给表达式。
     * 每次重排都要按当前握手状态传一次：换服之后同一个 App 的 {@code backend.available} 会变。
     */
    public Tree instantiate(UiState state, Map<String, Object> host) {
        EvalContext c = new EvalContext(state.values(), host, file);
        c.line = compiled.root().line();
        Pass pass = new Pass(host);
        Node root = node(compiled.root(), c, pass, null);
        if (pass.truncated) c.warnAlways("节点超过 " + NodeParser.MAX_NODES + " 个，多出来的没有进树");
        return new Tree(root, c.warnings(), pass.truncated, pass.clicks, pass.nodes, file);
    }

    public String file() {
        return file;
    }

    // ============================================================

    /**
     * 节点预算或求值预算用完时返回 null。siblingKeys 是同一个父节点下已经用过的 :key，根节点传 null。
     * 求值预算用完后不出节点：绑定属性求出来都是 null 会被丢掉，按默认值画（:enabled 丢了就是可点）正好与 state 相反。
     */
    private Node node(Element e, EvalContext c, Pass pass, Set<String> siblingKeys) {
        if (pass.budget <= 0) {
            pass.truncated = true;
            return null;
        }
        if (siblingKeys != null && c.exhausted()) return null;
        pass.budget--;
        String tag = e.type().json;

        Map<String, Object> props = new LinkedHashMap<>(e.staticProps());
        for (Map.Entry<String, BoundProp> b : e.boundProps().entrySet()) {
            Object v = PropRules.normalize(b.getValue().spec(), b.getValue().expr().run(c), c, tag);
            if (v != null) props.put(b.getKey(), v);
        }
        String id = e.id();
        if (id != null && !pass.ids.add(id)) {
            c.warn("id '" + id + "' 在树里出现了不止一次，后面的去掉 id");
            id = null;
        }
        if (e.key() != null) {
            String key = Values.text(e.key().run(c));
            // 空 key（null、数组、对象都文本化成空串）按下标找回；同一个父节点下重复的也按下标，不然两个列表共用滚动位置。
            // 有 id 的节点路径是 #id，不占 key 的名额
            if (key.isEmpty()) {
                c.warn("<" + tag + "> 的 :key 是空的，按下标找回滚动位置");
            } else if (id == null && siblingKeys != null && !siblingKeys.add(key)) {
                c.warn("<" + tag + "> 的 :key '" + key + "' 在同一个父节点下重复，这一项按下标找回滚动位置");
            } else {
                props.put("key", key);
            }
        }
        if (siblingKeys != null && c.exhausted()) return null;

        List<Node> kids = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        for (Child ch : e.children()) expand(ch, c, pass, kids, keys);

        Node out = new Node(e.type(), id, e.classes(), Collections.unmodifiableMap(props), List.copyOf(kids), null, null);
        pass.nodes.add(out);
        if (e.click() != null) pass.clicks.put(out,
                new Statements.Bound(e.click(), c.boundNames(), c.boundValues(), pass.host));
        return out;
    }

    private void expand(Child ch, EvalContext c, Pass pass, List<Node> out, Set<String> keys) {
        if (ch instanceof Chain chain) {
            // 求值预算用完后条件都得 null，按假走会翻到 v-else、画出与 state 相反的分支：整串都不进树
            if (c.exhausted()) return;
            for (int i = 0; i < chain.branches().size(); i++) {
                Expr.Compiled cond = chain.conditions().get(i);
                boolean taken = cond == null || Values.truthy(cond.run(c));
                if (c.exhausted()) return;
                if (taken) {
                    add(out, node(chain.branches().get(i), c, pass, keys));
                    return;
                }
            }
        } else if (ch instanceof Text t) {
            if (pass.budget <= 0) {
                pass.truncated = true;
                return;
            }
            if (c.exhausted()) return;
            pass.budget--;
            Object text = t.literal() != null ? t.literal() : PropRules.normalize(TEXT, t.expr().run(c), c, "text");
            if (c.exhausted()) return;
            Node node = new Node(NodeType.TEXT, null, List.of(), Map.of("text", text), List.of(), null, null);
            pass.nodes.add(node);
            out.add(node);
        } else {
            Element e = (Element) ch;
            if (e.vFor() == null) {
                add(out, node(e, c, pass, keys));
            } else {
                forEach(e, c, pass, out, keys);
            }
        }
    }

    /** v-for 先、v-if 后（对每一项判断，§9.4.4）。 */
    private void forEach(Element e, EvalContext c, Pass pass, List<Node> out, Set<String> keys) {
        CompiledTemplate.ForSpec spec = e.vFor();
        Object source = spec.source().run(c);
        List<?> items = null;
        int count;
        if (source instanceof List<?> list) {
            items = list;
            count = list.size();
        } else if (source instanceof Integer n) {
            count = Math.max(0, n);
        } else {
            // null 是数据还没到，不算错
            if (source != null) c.warn("v-for 的来源是 " + Values.kind(source) + "，要数组或整数，按空处理");
            count = 0;
        }
        if (count > MAX_FOR_ITEMS) {
            c.warnOnce("for-items", "v-for 有 " + count + " 项，截到 " + MAX_FOR_ITEMS + "（这次重排里别处同样截断的不再逐条记）");
            count = MAX_FOR_ITEMS;
        }
        for (int i = 0; i < count; i++) {
            if (c.exhausted()) return;
            if (pass.budget <= 0) {
                pass.truncated = true;
                return;
            }
            c.push(spec.item(), items != null ? items.get(i) : (Object) (i + 1));
            if (spec.index() != null) c.push(spec.index(), i);
            if (e.vIf() == null || Values.truthy(e.vIf().run(c))) add(out, node(e, c, pass, keys));
            if (spec.index() != null) c.pop();
            c.pop();
        }
    }

    private static void add(List<Node> out, Node n) {
        if (n != null) out.add(n);
    }
}
