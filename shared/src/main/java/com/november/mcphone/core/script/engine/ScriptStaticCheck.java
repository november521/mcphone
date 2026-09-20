package com.november.mcphone.core.script.engine;

import org.mozilla.javascript.Parser;
import org.mozilla.javascript.ast.AstNode;
import org.mozilla.javascript.ast.AstRoot;
import org.mozilla.javascript.ast.FunctionCall;
import org.mozilla.javascript.ast.Name;
import org.mozilla.javascript.ast.NodeVisitor;
import org.mozilla.javascript.ast.StringLiteral;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 装配期的静态核对（S18 约束 1：<b>零副作用</b>）：<b>只解析、只编译，绝不执行</b>。
 *
 * <p>允许做的事（卡里逐字列举）：{@code server.js} 的语法、模块解析与 {@code require} 的
 * 静态解析。允许的断言是"这份源码在语法上成立、它 require 的模块都在包内、依赖没有环"——
 * <b>不</b>包括"顶层代码跑得起来"。顶层写着 {@code throw} 的包在这里<b>照样通过</b>，
 * 它会在第一次请求时按正常运行期错误处理（INTERNAL/STRIKE）。
 *
 * <h2>为什么用 AST 而不是正则</h2>
 *
 * 注释里的 {@code require('./x')}、字符串里的 {@code "require('./x')"} 都不能算依赖；
 * 而 Rhino 自己就有解析器，用它扫出来的 {@link FunctionCall} 才是真的调用点。
 *
 * <h2>{@code require} 的参数必须是字符串字面量</h2>
 *
 * 动态拼出来的（{@code require('./x' + n + '.js')}）在装配期无法静态核对，
 * 一律判预检不过。这条写进作者指南：模块路径要写成字面量。
 */
public final class ScriptStaticCheck {

    private ScriptStaticCheck() {
    }

    /**
     * 静态核对一份模块表。<b>不抛</b>：失败返回一条可读原因，成功返回 null。
     *
     * @param sources 规范名 → 源码（与 {@link ScriptModules} 的构造入参同一份）
     */
    public static String check(Map<String, String> sources) {
        if (sources == null || sources.isEmpty()) return "模块表是空的";
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : sources.entrySet()) {
            String module = e.getKey();
            AstRoot root;
            try {
                root = new Parser().parse(e.getValue(), module, 1);
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable t) {
                return "语法错误 " + module + "：" + oneLine(t.getMessage());
            }
            if (root == null) return "解析不出 " + module;
            RequireCollector collector = new RequireCollector(module, sources, edges);
            root.visit(collector);
            if (collector.error != null) return collector.error;
        }
        String cycle = findCycle(edges);
        if (cycle != null) return "循环依赖：" + cycle;
        return null;
    }

    /** 收集一个模块里所有 {@code require('字面量')} 的边，并顺手做每一处的合法性判定。 */
    private static final class RequireCollector implements NodeVisitor {
        private final String module;
        private final Map<String, String> sources;
        private final Map<String, List<String>> edges;
        String error;

        RequireCollector(String module, Map<String, String> sources, Map<String, List<String>> edges) {
            this.module = module;
            this.sources = sources;
            this.edges = edges;
        }

        @Override
        public boolean visit(AstNode node) {
            if (error != null) return false;
            if (!(node instanceof FunctionCall call)) return true;
            AstNode target = call.getTarget();
            if (!(target instanceof Name name) || !"require".equals(name.getIdentifier())) return true;
            List<AstNode> args = call.getArguments();
            if (args == null || args.size() != 1) {
                error = module + " 第 " + call.getLineno() + " 行：require 只收一个参数";
                return false;
            }
            if (!(args.get(0) instanceof StringLiteral literal)) {
                error = module + " 第 " + call.getLineno()
                        + " 行：require 的参数必须是字符串字面量（预检要静态核对模块，动态拼接一律不收）";
                return false;
            }
            String spec = literal.getValue();
            if (spec == null || (!spec.startsWith("./") && !spec.startsWith("../"))) {
                error = module + "：" + spec + " —— require 只许包内相对路径（./ 或 ../ 开头）";
                return false;
            }
            String key = ScriptModules.normalize(ScriptModules.parentOf(module), spec);
            if (key == null || !sources.containsKey(key)) {
                error = module + "：" + spec + " —— require 找不到这个模块";
                return false;
            }
            edges.computeIfAbsent(module, k -> new ArrayList<>()).add(key);
            return true;
        }
    }

    /** 依赖图里有没有环。有就返回一条路径，没有返回 null。 */
    private static String findCycle(Map<String, List<String>> edges) {
        Set<String> done = new LinkedHashSet<>();
        for (String start : edges.keySet()) {
            List<String> path = new ArrayList<>();
            if (dfs(start, edges, new LinkedHashSet<>(), done, path)) {
                return String.join(" -> ", path);
            }
        }
        return null;
    }

    private static boolean dfs(String node, Map<String, List<String>> edges,
                               Set<String> onPath, Set<String> done, List<String> path) {
        if (done.contains(node)) return false;
        if (!onPath.add(node)) {
            path.add(node);
            return true;
        }
        path.add(node);
        List<String> next = edges.getOrDefault(node, List.of());
        for (String n : next) {
            if (dfs(n, edges, onPath, done, path)) return true;
        }
        path.remove(path.size() - 1);
        onPath.remove(node);
        done.add(node);
        return false;
    }

    private static String oneLine(String message) {
        if (message == null) return "（没有消息）";
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }
}
