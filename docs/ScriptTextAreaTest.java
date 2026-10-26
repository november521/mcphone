package com.november.mcphone.core.script.client.render;

import com.november.mcphone.core.script.layout.*;
import com.november.mcphone.core.script.server.store.LocalStore;
import com.november.mcphone.core.script.sfc.*;
import net.minecraft.client.StringSplitter;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Real compiler, layout, input routing and local storage; only font metrics and OS clipboard are isolated. */
public final class ScriptTextAreaTest {
    private static int checks;
    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static final class Font extends com.november.mcphone.test.TestFontBase {
        private int glyphWidth = 6;
        private final StringSplitter splitter = new StringSplitter((cp, style) -> glyphWidth);
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
        String clipboard = "";
        final TextInputs inputs = new TextInputs(com.november.mcphone.test.TestEditBoxFactory::create,
                (box, x, y, button) -> {}, () -> clipboard, text -> clipboard = text);
        LayoutNode root;
        Fixture(String body, String initial, String style) {
            page = SfcCompiler.compilePage("area.vue", "<template>\n" + body + "\n</template>\n<script>\nstate="
                    + initial + "\n</script>\n<style>\n" + style + "\n</style>");
            template = new TemplateInstance(page.template(), "area.vue");
            state = UiState.page(page.template().initialState());
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
        void key(int key, int mods) { inputs.keyPressed(key, 0, mods); }
        void paste(String text) { clipboard = text; key(86, 2); }
        String text() { return state.getString("draft"); }
    }

    private static TextAreaEditor editor(TextInputs inputs) throws Exception {
        var focused = TextInputs.class.getDeclaredField("focused"); focused.setAccessible(true);
        Object entry = focused.get(inputs);
        var field = entry.getClass().getDeclaredField("area"); field.setAccessible(true);
        return (TextAreaEditor) field.get(entry);
    }

    private static net.minecraft.client.gui.components.EditBox focusDelegate(TextInputs inputs) throws Exception {
        var focused = TextInputs.class.getDeclaredField("focused"); focused.setAccessible(true);
        Object entry = focused.get(inputs);
        var field = entry.getClass().getDeclaredField("box"); field.setAccessible(true);
        return (net.minecraft.client.gui.components.EditBox) field.get(entry);
    }

    private static void reject(String body, String initial) {
        boolean bad = false;
        try { new Fixture(body, initial, ""); } catch (SfcError expected) { bad = true; }
        check(bad, "invalid textarea rejected: " + body);
    }

    private static void schema() {
        reject("<textarea/>", "{draft:''}");
        reject("<textarea bind=\"missing\"/>", "{draft:''}");
        reject("<textarea bind=\"draft\"/>", "{draft:1}");
        reject("<textarea bind=\"draft\" max-length=\"0\"/>", "{draft:''}");
        reject("<textarea bind=\"draft\" max-length=\"4097\"/>", "{draft:''}");
        reject("<textarea bind=\"draft\">child</textarea>", "{draft:''}");
        reject("<textarea bind=\"draft\" @click=\"close()\"/>", "{draft:''}");
        Fixture normal = new Fixture("<textarea bind=\"draft\"/>", "{draft:''}", "");
        check(normal.root.node.type() == NodeType.TEXTAREA && normal.root.w == 120 && normal.root.h == FONT.lineHeight * 4 + 6,
                "default textarea fills width and has four text rows plus padding");
        var ir = NodeParser.parse("""
                {"state":{"draft":""},"entry":"main","pages":{"main":{"type":"textarea","bind":"draft","max-length":64}}}
                """);
        check(ir.root().type() == NodeType.TEXTAREA, "legacy IR recognizes textarea with its existing short-string policy");
        boolean limited = false;
        try { NodeParser.parse("""
                {"state":{"draft":""},"entry":"main","pages":{"main":{"type":"textarea","bind":"draft","max-length":65}}}
                """); } catch (LayoutError expected) { limited = true; }
        check(limited, "legacy IR string policy is not widened");
    }

    private static void editing() throws Exception {
        Fixture f = new Fixture("<column><textarea bind=\"draft\"/><text>{{draft}}</text></column>", "{draft:''}", "");
        check(!f.inputs.focused() && !f.inputs.charTyped('x', 0), "opening does not capture keyboard");
        check(f.click(5, 5).node.type() == NodeType.TEXTAREA && f.inputs.focused(), "real hit routing focuses textarea");
        var delegate = focusDelegate(f.inputs);
        check(delegate.isFocused(), "textarea activates native text-input focus for the OS/IME");
        f.inputs.charTyped('中', 0); f.inputs.charTyped(0x1f600, 0); f.key(257, 0); f.inputs.charTyped('文', 0);
        check(f.text().equals("中😀\n文"), "submitted CJK, supplementary code points and Enter update state");
        TextAreaEditor original = editor(f.inputs);
        int cursor = original.cursor();
        f.sync();
        check(editor(f.inputs) == original && original.cursor() == cursor, "state reflow retains editor and caret");
        check(f.root.children.get(1).node.str("text", "").equals(f.text()), "textarea changes reach text interpolation");
        f.key(65, 2); f.paste("A\r\nB\rC\tD\u0000§E");
        check(f.text().equals("A\nB\nC DE"), "paste normalizes CRLF/CR, tabs and filtered controls");
        f.key(65, 2); f.key(67, 2);
        check(f.clipboard.equals(f.text()), "Ctrl-C copies selection including line breaks");
        f.key(88, 2);
        check(f.text().isEmpty() && f.clipboard.equals("A\nB\nC DE"), "Ctrl-X cuts the selected text");
        f.key(86, 8);
        check(f.text().equals("A\nB\nC DE"), "command modifier supports the same paste path");
        f.state.set("draft", "程序\n改值"); f.sync();
        check(editor(f.inputs).value().equals("程序\n改值"), "external function/state changes flow into editor");
        f.click(119, 170);
        check(!f.inputs.focused() && !f.inputs.charTyped('x', 0) && !delegate.isFocused(), "outside click releases keyboard and native input focus");
    }

    private static void unicodeAndLimits() throws Exception {
        Fixture f = new Fixture("<textarea bind=\"draft\" max-length=\"5\"/>", "{draft:''}", "");
        f.click(5, 5); f.paste("a😀bcZ");
        check(f.text().equals("a😀bc"), "length is UTF-16 and long paste is bounded");
        f.key(65, 2); f.paste("😀😀😀");
        check(f.text().equals("😀😀"), "replacing full selection uses the freed capacity and keeps complete pairs");
        f.key(259, 0);
        check(f.text().equals("😀"), "Backspace removes one complete supplementary code point");
        f.key(263, 1); f.key(88, 2);
        check(f.text().isEmpty() && f.clipboard.equals("😀"), "Shift-arrow selects an entire supplementary code point");
        f.paste("😀x"); f.key(268, 2); f.key(261, 0);
        check(f.text().equals("x"), "Delete removes complete supplementary code point");
        f.key(65, 2); f.paste("\ud800A\udc00");
        check(f.text().equals("A"), "unpaired surrogates never enter state");
        f.key(65, 2); f.paste("abcde"); f.key(263, 1); f.paste("😀");
        check(f.text().equals("abcde") && editor(f.inputs).cursor() != editor(f.inputs).anchor(),
                "unfittable code point does not delete the selected text");
        Fixture boundary = new Fixture("<textarea bind=\"draft\" :max-length=\"limit\"/>", "{draft:'abc😀',limit:5}", "");
        boundary.click(5, 5); boundary.state.set("limit", 4); boundary.sync();
        check(boundary.text().equals("abc"), "dynamic length normalization does not split an emoji");
        Fixture longText = new Fixture("<textarea bind=\"draft\"/>", "{draft:''}", "");
        longText.click(5, 5); longText.paste("中".repeat(5000));
        check(longText.text().length() == 4096, "default SFC textarea permits 4096 units");
        longText.key(65, 2); longText.paste("b".repeat(4096));
        check(longText.text().equals("b".repeat(4096)), "selection replacement also works at the hard maximum");
    }

    private static void navigationAndScroll() throws Exception {
        Fixture f = new Fixture("<textarea bind=\"draft\"/>", "{draft:''}", "");
        f.click(5, 5); f.paste("abc\ndef\nghi");
        f.key(268, 2); f.key(262, 0); f.key(264, 0);
        check(editor(f.inputs).cursor() == 5, "Down keeps horizontal caret position across lines");
        f.key(269, 0); f.inputs.charTyped('!', 0);
        check(f.text().equals("abc\ndef!\nghi"), "End and typing act on current visual line");
        f.key(268, 2); f.inputs.mouseDragged(X + 15, Y + 14, 0);
        f.key(67, 2);
        check(f.clipboard.equals("abc\nde"), "mouse drag selects across a line break");
        check(f.inputs.mouseReleased(0) && !f.inputs.mouseDragged(X + 4, Y + 4, 0), "release ends pointer capture");
        f.key(65, 2); f.paste("line\n".repeat(40));
        TextAreaEditor area = editor(f.inputs);
        check(area.scroll() > 0 && area.lines().size() == 41, "typing scrolls caret into view and preserves final empty line");
        check(f.inputs.mouseScrolled(f.root, 1000) && area.scroll() == 0, "wheel scrolls textarea independently");
        f.sync();
        check(editor(f.inputs) == area && area.scroll() == 0, "routine synchronization does not snap wheel scroll back to caret");
        f.inputs.mouseScrolled(f.root, -1000);
        check(area.scroll() > 0, "wheel reaches last visual line");
        f.inputs.mouseScrolled(f.root, Double.NaN);
        check(area.scroll() > 0, "non-finite wheel input does not corrupt scroll");
        Fixture narrow = new Fixture("<textarea bind=\"draft\" class=\"narrow\"/>", "{draft:'abcdef'}", ".narrow{width:24;}");
        narrow.click(5, 5);
        check(editor(narrow.inputs).lines().size() == 3, "narrow editor automatically wraps without editing source text");
        check(narrow.text().equals("abcdef"), "soft wraps do not add saved line breaks");
        narrow.key(268, 2); narrow.key(269, 0);
        check(editor(narrow.inputs).cursor() == 2 && editor(narrow.inputs).cursorLine() == 0,
                "End keeps the caret at the preceding visual line's soft-wrap boundary");
        narrow.key(264, 0);
        check(editor(narrow.inputs).cursor() == 4 && editor(narrow.inputs).cursorLine() == 1,
                "Down preserves the visual line-end column after a soft wrap");
        try {
            FONT.glyphWidth = 12;
            narrow.sync();
            check(editor(narrow.inputs).lines().size() == 6, "font metric changes rewrap the retained editor without requiring a new Font object");
        } finally { FONT.glyphWidth = 6; }
    }

    private static void lifecycle() throws Exception {
        Fixture f = new Fixture("<column><textarea v-if=\"shown\" bind=\"draft\" :enabled=\"on\"/><text-input bind=\"other\"/></column>",
                "{draft:'',other:'',shown:true,on:true}", "");
        f.click(5, 5); f.key(258, 0); f.inputs.charTyped('b', 0);
        check(f.state.getString("other").equals("b"), "Tab shares focus order with single-line inputs");
        f.key(258, 1); f.inputs.charTyped('a', 0);
        check(f.text().equals("a"), "Shift-Tab returns to textarea");
        f.state.set("on", false); f.sync();
        check(!f.inputs.focused() && !f.inputs.charTyped('x', 0), "disabling releases keyboard and drag");
        f.click(5, 5); check(!f.inputs.focused(), "disabled textarea consumes click without focusing");
        f.state.set("on", true); f.sync(); f.click(5, 5);
        f.state.set("shown", false); f.sync();
        check(!f.inputs.focused(), "hiding textarea does not transfer focus to shifted sibling");
        Fixture replace = new Fixture("<column><textarea v-if=\"first\" bind=\"draft\"/><textarea v-else bind=\"draft\"/></column>",
                "{draft:'',first:true}", "");
        replace.click(5, 5); replace.state.set("first", false); replace.sync();
        check(!replace.inputs.focused(), "same-path same-binding replacement loses focus by template site");
        Fixture clip = new Fixture("<scroll class=\"viewport\"><textarea bind=\"draft\"/><spacer size=\"120\"/></scroll>",
                "{draft:''}", ".viewport{height:50;}");
        clip.click(5, 5); clip.root.scrollY = 60; clip.reconcile();
        check(!clip.inputs.focused() && clip.click(5, 5) == null, "clipped editor releases focus and cannot receive pointer input");
        Fixture stack = new Fixture("<stack><textarea bind=\"draft\"/><button :enabled=\"false\" @click=\"close()\">cover</button></stack>", "{draft:''}", "");
        LayoutNode cover = stack.click(5, 5);
        check(cover.node.type() == NodeType.BUTTON && !stack.inputs.focused() && !stack.inputs.mouseScrolled(cover, 1),
                "top overlay blocks textarea click and wheel");
        replace.state.set("first", true); replace.sync(); replace.click(5, 5); replace.inputs.blur();
        check(!replace.inputs.mouseDragged(X + 10, Y + 10, 0), "input cancellation drops drag capture");
        replace.click(5, 5); replace.inputs.clear();
        check(!replace.inputs.focused() && !replace.inputs.keyPressed(258, 0, 0), "disposing page releases all editors");
    }

    private static void pageBudget() throws Exception {
        String full = "a".repeat(4096);
        Fixture f = new Fixture("<textarea bind=\"draft\"/>", "{draft:'',a:'" + full + "',b:'" + full + "',c:'" + full + "',d:'" + full + "'}", "");
        f.click(5, 5); f.paste("x");
        check(f.text().isEmpty() && editor(f.inputs).value().isEmpty(), "input rejects writes exceeding total page text budget");
        f.state.set("a", ""); f.sync(); f.paste("x");
        check(f.text().equals("x"), "freeing page budget permits typing again");
        f.state.set("a", full.substring(1)); f.sync(); f.key(65, 2); int before = editor(f.inputs).cursor();
        f.paste("yy");
        check(f.text().equals("x") && editor(f.inputs).cursor() == before && editor(f.inputs).anchor() == 0,
                "page budget rejection retains selection and cursor as well as saved state");
    }

    private static Node find(Node node, String id) {
        if (id.equals(node.id())) return node;
        for (Node child : node.children()) { Node found = find(child, id); if (found != null) return found; }
        return null;
    }

    private static void savedSample() throws Exception {
        var app = SfcCompiler.compile("textarea.vue", Files.readString(Path.of("../../docs/samples/textarea.vue")));
        check(app.manifest().id().equals("example:textarea"), "installable note sample compiles");
        Path root = Files.createTempDirectory("mcphone-textarea-");
        try {
            UUID account = UUID.fromString("00000000-0000-0000-0000-000000000001");
            Supplier<LocalStore> storage = () -> LocalStore.openClient(root, account, app.manifest().id());
            PageStates pages = new PageStates();
            UiState state = pages.open("", app.template(), "textarea.vue", storage).state();
            TextInputs inputs = new TextInputs(com.november.mcphone.test.TestEditBoxFactory::create,
                    (box, x, y, b) -> {}, () -> "第一段\n第二段😀", text -> {});
            TemplateInstance template = new TemplateInstance(app.template(), "textarea.vue");
            var tree = template.instantiate(state);
            LayoutNode layout = LayoutEngine.layout(tree.root(), app.stylesheet(), state, 120, 176, new FontMeasure(FONT), ImageSizes.NONE);
            inputs.sync(layout, state, FONT, X, Y, 120, 176);
            LayoutNode hit = HitTest.pick(layout, X, Y, 120, 176, X + 8, Y + 30, new FontMeasure(FONT));
            check(hit != null && hit.node.type() == NodeType.TEXTAREA, "note sample fills remaining page space with usable textarea");
            inputs.mouseClicked(hit, X + 8, Y + 30, 0); inputs.keyPressed(86, 0, 2);
            tree = template.instantiate(state);
            check(tree.click(find(tree.root(), "save"), state, 0, storage).applied(), "sample save commits real edited state through existing store API");
            pages.clear();
            UiState reopened = pages.open("", app.template(), "textarea.vue", storage).state();
            check(reopened.getString("note").equals("第一段\n第二段😀"), "closing and reopening restores newlines and emoji from disk");
            check(LocalStore.openClient(root, UUID.randomUUID(), app.manifest().id()).getString("note", "").isEmpty(),
                    "note remains isolated by account");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        schema(); editing(); unicodeAndLimits(); navigationAndScroll(); lifecycle(); pageBudget(); savedSample();
        System.out.println("全部通过：" + checks + " 条断言");
    }
}
