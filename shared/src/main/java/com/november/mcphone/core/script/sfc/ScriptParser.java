package com.november.mcphone.core.script.sfc;

import com.november.mcphone.core.script.layout.StateRules;
import com.november.mcphone.core.script.sfc.ExprParser.Tok;
import com.november.mcphone.core.script.sfc.SfcError.Code;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * P0 的 {@code <script>}（施工方案 §9.7）：只认一条 {@code state = { ... }}，值只收字面量。
 *
 * <p>注释、末尾的分号、键上的引号与尾逗号照 JS 的写法收下：P1 换成真引擎时作者的文件一个字都不用改。
 */
final class ScriptParser {

    private ScriptParser() {
    }

    /** 块内容的初始 state。行号从块内第 1 行起，由 {@link SfcCompiler} 加偏移。内容为空时是空表。 */
    static Map<String, Object> parse(String content) {
        try {
            return literalState(content);
        } catch (SfcError e) {
            // 小数、中文键名、不认识的转义也是「其他语法」（§9.7），不报成表达式语法错
            if (e.code() == Code.E_EXPR_SYNTAX) throw SfcError.at(Code.E_SCRIPT_P0_SUBSET, e.line(), e.col());
            throw e;
        }
    }

    private static Map<String, Object> literalState(String content) {
        ExprParser p = new ExprParser(content, 1, 1, true);
        if (p.peek().kind() == 'e') return Map.of();

        Tok t = p.next();
        if (t.kind() != 'n' || !t.text().equals("state")) throw subset(t);
        Tok eq = p.next();
        if (!ExprParser.is(eq, "=")) throw subset(eq);
        Tok open = p.next();
        if (!ExprParser.is(open, "{")) throw subset(open);

        Map<String, Object> out = new LinkedHashMap<>();
        while (!p.at("}")) {
            Tok k = p.next();
            if (k.kind() != 'n' && k.kind() != 's') throw subset(k);
            String key = k.kind() == 's' ? (String) k.value() : k.text();
            if (!StateRules.KEY.matcher(key).matches()) {
                throw SfcError.at(Code.E_SCRIPT_STATE, k.line(), k.col(), key, "键名要" + StateRules.KEY_RULE);
            }
            if (out.containsKey(key)) throw SfcError.at(Code.E_SCRIPT_STATE, k.line(), k.col(), key, "写了两次");
            if (ExprParser.Host.is(key)) {
                // 宿主注入的只读上下文（§13.8）不许被 state 顶掉：运行时它是宿主先查，写进来只会静默失效
                throw SfcError.at(Code.E_SCRIPT_STATE, k.line(), k.col(), key, "是宿主保留的名字，不能写进 state");
            }
            if (out.size() == StateRules.MAX_KEYS) {
                throw SfcError.at(Code.E_SCRIPT_STATE, k.line(), k.col(), key, "state 最多 " + StateRules.MAX_KEYS + " 个键");
            }
            Tok colon = p.next();
            if (!ExprParser.is(colon, ":")) throw subset(colon);
            Object value = p.literal(key, 1);
            String why = StateRules.check(value);
            if (why != null) throw SfcError.at(Code.E_SCRIPT_STATE, k.line(), k.col(), key, why);
            out.put(key, StateRules.freeze(value));
            if (!p.at(",")) break;
            p.next();
        }
        Tok close = p.next();
        if (!ExprParser.is(close, "}")) throw subset(close);
        if (p.at(";")) p.next();
        if (p.peek().kind() != 'e') throw subset(p.peek());
        return Collections.unmodifiableMap(out);
    }

    private static SfcError subset(Tok t) {
        return SfcError.at(Code.E_SCRIPT_P0_SUBSET, t.line(), t.col());
    }
}
