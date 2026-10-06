package com.november.mcphone.core.script.sfc;

import com.november.mcphone.core.script.layout.NodeType;

import java.util.List;
import java.util.Map;

/**
 * 编译好的模板（施工方案 §9.9）：静态骨架 + 每个动态位置的 AST。装包时编一次、之后常驻；
 * 每次重排由 {@link TemplateInstance} 遍历它、对动态位置求值，产出一棵普通的 Node 树。
 */
public final class CompiledTemplate {

    /** 容器里的一项：元素、一段文字、或一串 v-if / v-else-if / v-else。 */
    sealed interface Child permits Element, Text, Chain {
    }

    /**
     * 一个元素。vIf 只在带 v-for 时有值（对每一项判断）；不带 v-for 的 v-if 编进 {@link Chain}。
     * staticProps 已按 §5.4 校验过；boundProps 的值要到实例化时才知道。
     */
    record Element(NodeType type, int line, String id, List<String> classes, Map<String, Object> staticProps,
                   Map<String, BoundProp> boundProps, ForSpec vFor, Expr.Compiled vIf, Expr.Compiled key,
                   Statements click, List<Child> children) implements Child {
    }

    record BoundProp(PropRules.Spec spec, Expr.Compiled expr) {
    }

    /** {@code (item, index) in source}；index 可为 null。 */
    record ForSpec(String item, String index, Expr.Compiled source) {
    }

    /** 容器里的一段文字，实例化成一个 text 节点。literal 与 expr 恰好一个不为 null。 */
    record Text(int line, String literal, Expr.Compiled expr) implements Child {
    }

    /** 第一个条件成立的那支进树；conditions 里最后一个为 null 表示 v-else。 */
    record Chain(List<Expr.Compiled> conditions, List<Element> branches) implements Child {
    }

    private final Element root;
    private final Map<String, Object> state;
    private final FrontendProgram program;

    CompiledTemplate(Element root, Map<String, Object> state) {
        this(root, state, FrontendProgram.EMPTY);
    }

    CompiledTemplate(Element root, Map<String, Object> state, FrontendProgram program) {
        this.root = root;
        this.state = state;
        this.program = program;
    }

    public FrontendProgram program() { return program; }

    Element root() {
        return root;
    }

    /** 编译时依据的 state 初值，也是页面打开时的初值。 */
    public Map<String, Object> initialState() {
        return state;
    }
}
