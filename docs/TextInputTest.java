package com.november.mcphone.core.script.layout;

import java.util.Map;
import com.november.mcphone.core.script.sfc.TemplateCompiler;
import com.november.mcphone.core.script.sfc.TemplateInstance;

/** 输入不会切坏代理对；选区、剪切、粘贴与模板契约一起检查。IME 预编辑由窗口处理，提交字符在此消费。 */
public final class TextInputTest {
    private static int checks;
    private static void eq(Object actual, Object expected) {
        checks++; if (!java.util.Objects.equals(actual, expected)) throw new AssertionError(actual + " != " + expected);
    }
    public static void main(String[] args) {
        TextInputBuffer b = new TextInputBuffer("甲😀乙", 8);
        b.move(-1, false); b.delete(true); eq(b.text(), "甲乙");
        b.type('\uD83D'); eq(b.text(), "甲乙"); b.type('\uDE00'); eq(b.text(), "甲😀乙");
        b.moveTo(2, false); eq(b.cursor(), 1);
        b.move(1, true); eq(b.selected(), "😀");
        b.replace("中"); eq(b.text(), "甲中乙");
        b.selectAll(); b.replace("hello\nworld\u0000"); eq(b.text(), "hello wo");
        b.moveTo(0, false); b.delete(true); eq(b.text(), "hello wo");
        b.moveTo(b.text().length(), false); b.delete(false); eq(b.text(), "hello wo");
        b.selectAll(); b.replace("😀".repeat(20)); eq(b.text(), "😀".repeat(8));
        eq(TextInputBuffer.clean("\uD800a\uDC00", 8), "a");
        UiState state = UiState.of(Map.of("draft", ""));
        var compiled = TemplateCompiler.compile("<text-input bind=\"draft\" max-length=\"4096\" placeholder=\"输入\"/>", state.values());
        Node input = new TemplateInstance(compiled, "input.vue").instantiate(state).root();
        eq(input.type(), NodeType.TEXT_INPUT); eq(input.num("max-length", 0), 4096);
        try { TemplateCompiler.compile("<text-input bind=\"n\"/>", Map.of("n", 1)); throw new AssertionError("整数被接受"); }
        catch (com.november.mcphone.core.script.sfc.SfcError expected) { checks++; }
        var parsed = NodeParser.parse("{\"state\":{\"draft\":\"\"},\"entry\":\"main\",\"pages\":{\"main\":{\"type\":\"text-input\",\"bind\":\"draft\",\"max-length\":4}}}");
        eq(parsed.root().type(), NodeType.TEXT_INPUT);
        System.out.println("TextInputTest: " + checks + " checks passed");
    }
}
