package com.november.mcphone.core.script.sfc;

import com.november.mcphone.core.script.engine.ScriptSandbox;
import com.november.mcphone.core.script.layout.StateRules;
import org.mozilla.javascript.*;
import org.mozilla.javascript.ast.*;

import java.util.*;

/** 前端源码只在装包时解析。顶层只允许字面量 state 和具名函数，初始化不执行作者的业务。 */
public record FrontendProgram(String source, int firstLine, Set<String> handlers,
                              Map<String, Object> initialState, Script compiled) {
    public static final FrontendProgram EMPTY = new FrontendProgram("", 1, Set.of(), Map.of());
    private static final Set<String> HOST_NAMES = Set.of("state", "backend", "phone", "toast", "nav", "back", "close");

    public FrontendProgram(String source, int firstLine, Set<String> handlers, Map<String, Object> initialState) {
        this(source, firstLine, handlers, initialState, compile(source, firstLine));
    }

    private static Script compile(String source, int firstLine) {
        if (source.isBlank()) return null;
        var budget = com.november.mcphone.core.script.engine.ScriptBudget.client();
        try (Context cx = budget.enterContext()) { return cx.compileString(source, "<script>", firstLine, null); }
        catch (EvaluatorException error) {
            throw SfcError.at(SfcError.Code.E_SCRIPT_SYNTAX, Math.max(1, error.lineNumber() - firstLine + 1),
                    Math.max(1, error.columnNumber()), error.details());
        }
    }

    public FrontendProgram {
        handlers = Set.copyOf(handlers);
        initialState = Collections.unmodifiableMap(new LinkedHashMap<>(initialState));
    }

    public static FrontendProgram parse(String content, int firstLine) {
        // 保留既有字面量解析器的精确拒绝项；有函数声明才进入扩展语法。
        try { return new FrontendProgram("", firstLine, Set.of(), ScriptParser.parse(content)); }
        catch (SfcError old) {
            if (!content.matches("(?s).*\\bfunction\\s+.*")) throw old;
        }
        if (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
            throw SfcError.at(SfcError.Code.E_SCRIPT_SYNTAX, 1, 1, "前端脚本超过 64 KiB");
        CompilerEnvirons env = new CompilerEnvirons();
        env.setLanguageVersion(Context.VERSION_ES6);
        env.setRecoverFromErrors(false);
        AstRoot ast;
        try { ast = new Parser(env).parse(content, "<script>", 1); }
        catch (EvaluatorException error) {
            throw SfcError.at(SfcError.Code.E_SCRIPT_SYNTAX, Math.max(1, error.lineNumber()),
                    Math.max(1, error.columnNumber()), error.details());
        }
        char[] executable = content.toCharArray();
        Map<String, Object> state = Map.of();
        Set<String> names = new LinkedHashSet<>();
        boolean hasState = false;
        for (org.mozilla.javascript.Node node : ast) {
            AstNode current = (AstNode) node;
            if (current instanceof FunctionNode fn && fn.getFunctionType() == FunctionNode.FUNCTION_STATEMENT) {
                String name = fn.getName();
                if (!StateRules.KEY.matcher(name).matches() || HOST_NAMES.contains(name)
                        || ScriptSandbox.ALLOWED_GLOBALS.contains(name) || !names.add(name) || names.size() > 32
                        || fn.isGenerator() || fn.hasRestParameter() || fn.getParams().size() > 8
                        || fn.getParams().stream().anyMatch(p -> !(p instanceof Name))) {
                    throw SfcError.at(SfcError.Code.E_SCRIPT_SYNTAX, fn.getLineno(), 1,
                            "函数名须唯一且不能覆盖宿主；最多 32 个函数，每个最多 8 个普通参数，不支持生成器");
                }
            } else if (current instanceof ExpressionStatement statement
                    && statement.getExpression() instanceof Assignment assignment
                    && assignment.getOperator() == Token.ASSIGN
                    && assignment.getLeft() instanceof Name name && name.getIdentifier().equals("state") && !hasState) {
                int begin = current.getAbsolutePosition(), end = begin + current.getLength();
                String literal = content.substring(begin, end);
                try { state = ScriptParser.parse(literal); }
                catch (SfcError error) { throw error.shift(current.getLineno() - 1); }
                hasState = true;
                for (int i = begin; i < end; i++) if (executable[i] != '\n' && executable[i] != '\r') executable[i] = ' ';
            } else if (!(current instanceof EmptyStatement)) {
                throw SfcError.at(SfcError.Code.E_SCRIPT_P0_SUBSET, current.getLineno(), 1);
            }
        }
        return new FrontendProgram(new String(executable), firstLine, names, state);
    }
}
