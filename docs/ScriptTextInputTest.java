package com.november.mcphone.core.script.client.render;

import com.november.mcphone.core.script.layout.*;
import com.november.mcphone.core.script.sfc.SfcCompiler;
import com.november.mcphone.core.script.sfc.SfcError;
import com.november.mcphone.core.script.sfc.TemplateInstance;
import net.minecraft.client.StringSplitter;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.Map;

/** Real vanilla editors and actual SFC/layout/hit paths; no window, system clipboard, or network. */
public final class ScriptTextInputTest {
    private static int checks;
    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static final class Font extends com.november.mcphone.test.TestFontBase {
        private final StringSplitter splitter = new StringSplitter((cp, style) -> 6);
        @Override public StringSplitter getSplitter() { return splitter; }
        @Override public int width(String text) { return (int) Math.ceil(splitter.stringWidth(text)); }
        @Override public int width(FormattedText text) { return (int) Math.ceil(splitter.stringWidth(text)); }
        @Override public int width(FormattedCharSequence text) { return (int) Math.ceil(splitter.stringWidth(text)); }
        @Override public String plainSubstrByWidth(String text, int width) { return splitter.plainHeadByWidth(text, width, Style.EMPTY); }
        @Override public String plainSubstrByWidth(String text, int width, boolean tail) {
            return tail ? splitter.plainTailByWidth(text, width, Style.EMPTY) : plainSubstrByWidth(text, width);
        }
    }

    private static final Font FONT = new Font();
    private static final int X = 17, Y = 23;

    private static final class Fixture {
        final SfcCompiler.Page page;
        final TemplateInstance template;
        final UiState state;
        // Native mouseClicked consults the live sound/window services. Isolate that OS boundary;
        // the controller's hit/focus logic and the editor's input/selection/filtering remain real.
        final TextInputs inputs = new TextInputs(com.november.mcphone.test.TestEditBoxFactory::create,
                (box, x, y, button) -> {});
        LayoutNode root;
        Fixture(String body, String state, String styles) {
            page = SfcCompiler.compilePage("input.vue", "<template>\n" + body + "\n</template>\n<script>\nstate = "
                    + state + "\n</script>\n<style>\n" + styles + "\n</style>\n");
            template = new TemplateInstance(page.template(), "input.vue");
            this.state = UiState.of(page.template().initialState());
            sync();
        }
        void sync() {
            root = LayoutEngine.layout(template.instantiate(state).root(), page.stylesheet(), state,
                    120, 176, new FontMeasure(FONT), ImageSizes.NONE);
            reconcile();
        }
        void reconcile() { inputs.sync(root, state, FONT, X, Y, 120, 176); }
        LayoutNode click(int x, int y) {
            LayoutNode hit = HitTest.pick(root, X, Y, 120, 176, X + x, Y + y, new FontMeasure(FONT));
            inputs.mouseClicked(hit, X + x, Y + y, 0);
            return hit;
        }
    }

    private static EditBox editor(TextInputs inputs) throws Exception {
        var f = TextInputs.class.getDeclaredField("focused"); f.setAccessible(true);
        Object entry = f.get(inputs);
        var box = entry.getClass().getDeclaredField("box"); box.setAccessible(true);
        return (EditBox) box.get(entry);
    }

    private static void reject(String body, String initial) {
        boolean rejected = false;
        try { new Fixture(body, initial, ""); } catch (SfcError expected) {
            check(expected.code() != SfcError.Code.E_SFC_BLOCK_FORMAT, "fixture must use the real SFC block format");
            rejected = true;
        }
        check(rejected, "must reject invalid template: " + body);
    }

    private static void schema() {
        reject("<text-input/>", "{draft:''}");
        reject("<text-input bind=\"missing\"/>", "{draft:''}");
        reject("<text-input bind=\"draft\"/>", "{draft:1}");
        reject("<text-input bind=\"draft\" max-length=\"0\"/>", "{draft:''}");
        reject("<text-input bind=\"draft\" max-length=\"65\"/>", "{draft:''}");
        reject("<text-input bind=\"draft\">children</text-input>", "{draft:''}");
        reject("<text-input bind=\"draft\" @click=\"draft = 'x'\"/>", "{draft:''}");
        NodeParser.Ui ui = NodeParser.parse("""
            {"state":{"draft":""},"entry":"main","pages":{"main":
              {"type":"text-input","bind":"draft","placeholder":"输入","max-length":8,"enabled":false}}}
            """);
        check(ui.root().type() == NodeType.TEXT_INPUT, "JSON IR recognizes text-input");
        boolean bad = false;
        try { NodeParser.parse("""
            {"state":{"draft":false},"entry":"main","pages":{"main":{"type":"text-input","bind":"draft"}}}
            """); } catch (LayoutError expected) { bad = true; }
        check(bad, "JSON IR also requires a string binding");
    }

    private static void sampleAndUnicode() throws Exception {
        var sample = SfcCompiler.compile("text-input.vue", java.nio.file.Files.readString(
                java.nio.file.Path.of("../../docs/samples/text-input.vue")));
        check(sample.manifest().id().equals("example:text_input"), "installable example compiles with its manifest");
        Fixture f = new Fixture("<text-input bind=\"draft\" max-length=\"3\"/>", "{draft:''}", "");
        f.click(5, 5); f.inputs.charTyped(0x1f600, 0); f.sync();
        check(f.state.getString("draft").equals("😀"), "code-point event preserves supplementary characters across rebuilds");
        editor(f.inputs).insertText("😀");
        check(f.state.getString("draft").equals("😀"), "length boundary never leaves half a surrogate pair");
    }

    private static void editing() throws Exception {
        Fixture f = new Fixture("<column><text-input bind=\"draft\" max-length=\"8\"/><text>{{draft}}</text></column>", "{draft:''}", "");
        check(!f.inputs.focused() && !f.inputs.charTyped('e', 0), "opening does not steal keyboard focus");
        LayoutNode hit = f.click(5, 5);
        check(hit != null && hit.node.type() == NodeType.TEXT_INPUT && f.inputs.focused(), "hit test focuses input at nonzero canvas origin");
        check(hit.h == FONT.lineHeight + 6 && hit.w == 120, "default input fills width and reserves padding");
        f.inputs.charTyped('e', 0); f.inputs.charTyped('中', 0);
        check(f.state.getString("draft").equals("e中"), "Latin and submitted Chinese input write state");
        EditBox box = editor(f.inputs);
        box.setCursorPosition(1);
        f.sync();
        check(editor(f.inputs) == box && box.getCursorPosition() == 1, "rebuild retains native editor and caret");
        check(f.root.children.get(1).node.str("text", "").equals("e中"), "interpolation reflects edited state");
        box.setHighlightPos(2);
        f.inputs.charTyped('文', 0);
        check(f.state.getString("draft").equals("e文"), "typing replaces native selection");
        box.insertText("123456789");
        check(f.state.getString("draft").equals("e文123456"), "paste insertion enforces total max-length");
        box.setCursorPosition(2); box.setHighlightPos(8); box.insertText("A\nB\t");
        check(f.state.getString("draft").equals("e文AB"), "paste filters line breaks and controls using vanilla rules");
        box.setCursorPosition(4); box.deleteChars(-1);
        check(f.state.getString("draft").equals("e文A"), "native deletion updates binding");
        f.state.set("draft", "按钮改值"); f.sync();
        check(editor(f.inputs).getValue().equals("按钮改值"), "external state updates flow back into editor");
        f.click(119, 170);
        check(!f.inputs.focused() && !f.inputs.charTyped('x', 0), "clicking outside releases keyboard");
        f.click(5, 5); f.inputs.blur();
        check(!f.inputs.focused() && f.state.getString("draft").equals("按钮改值"), "input cancellation loses focus without deleting text");
        f.click(5, 5); f.inputs.clear();
        check(!f.inputs.focused() && !f.inputs.keyPressed(258, 0, 0), "page disposal releases editors");
    }

    private static void lifecycle() throws Exception {
        Fixture f = new Fixture("<column><text-input v-if=\"shown\" bind=\"draft\" :enabled=\"on\"/><text-input bind=\"other\"/></column>",
                "{draft:'',other:'',shown:true,on:true}", "");
        f.click(5, 5); f.inputs.keyPressed(258, 0, 0); f.inputs.charTyped('b', 0);
        check(f.state.getString("other").equals("b"), "Tab advances to the next visible editor");
        f.inputs.keyPressed(258, 0, 1); f.inputs.charTyped('a', 0);
        check(f.state.getString("draft").equals("a"), "Shift-Tab moves backwards");
        f.state.set("on", false); f.sync();
        check(!f.inputs.focused() && !f.inputs.charTyped('x', 0), "disabling a focused input releases it");
        f.click(5, 5); check(!f.inputs.focused(), "disabled input cannot regain focus");
        f.state.set("on", true); f.sync(); f.click(5, 5);
        f.state.set("shown", false); f.sync();
        check(!f.inputs.focused(), "hidden field does not transfer focus to a shifted sibling");
        Fixture branch = new Fixture("<column><text-input v-if=\"first\" bind=\"draft\"/><text-input v-else bind=\"draft\"/></column>",
                "{draft:'',first:true}", "");
        branch.click(5, 5); branch.state.set("first", false); branch.sync();
        check(!branch.inputs.focused(), "conditional replacement with identical path and binding still loses focus");
        Fixture limit = new Fixture("<text-input bind=\"draft\" :max-length=\"limit\"/>", "{draft:'abcdef',limit:6}", "");
        limit.click(5, 5); limit.state.set("limit", 3); limit.sync();
        check(limit.state.getString("draft").equals("abc") && editor(limit.inputs).getValue().equals("abc"), "length changes normalize both state and editor");
    }

    private static void clipping() {
        Fixture f = new Fixture("<scroll class=\"viewport\"><text-input bind=\"draft\"/><spacer size=\"100\"/></scroll>", "{draft:''}", ".viewport{height:20;}");
        f.click(5, 5); check(f.inputs.focused(), "visible scrolled input focuses");
        f.root.scrollY = 30; f.reconcile();
        check(!f.inputs.focused(), "scrolling the field completely out of view releases focus");
        check(f.click(5, 5) == null, "clipped field cannot be clicked at its old position");
        Fixture stack = new Fixture("<stack><text-input bind=\"draft\"/><button :enabled=\"false\" @click=\"close()\">盖住</button></stack>", "{draft:''}", "");
        check(stack.click(5, 5).node.type() == NodeType.BUTTON && !stack.inputs.focused(), "disabled overlay blocks the input underneath");
    }

    public static void main(String[] args) throws Exception {
        schema(); editing(); lifecycle(); clipping(); sampleAndUnicode();
        System.out.println("全部通过：" + checks + " 条断言");
    }
}
