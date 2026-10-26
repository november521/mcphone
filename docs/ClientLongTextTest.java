package com.november.mcphone.core.script.client.render;

import com.november.mcphone.core.script.layout.ImageSizes;
import com.november.mcphone.core.script.layout.LayoutEngine;
import com.november.mcphone.core.script.layout.LayoutNode;
import com.november.mcphone.core.script.layout.MssParser;
import com.november.mcphone.core.script.layout.Node;
import com.november.mcphone.core.script.layout.StateRules;
import com.november.mcphone.core.script.layout.TextMeasure;
import com.november.mcphone.core.script.layout.UiState;
import com.november.mcphone.core.script.server.store.LocalStore;
import com.november.mcphone.core.script.sfc.CompiledTemplate;
import com.november.mcphone.core.script.sfc.PageStates;
import com.november.mcphone.core.script.sfc.SfcCompiler;
import com.november.mcphone.core.script.sfc.SfcError;
import com.november.mcphone.core.script.sfc.Statements;
import com.november.mcphone.core.script.sfc.TemplateInstance;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Actual page policy, SFC, transactions, wrapping, scrolling and the renderer's visible-line selection. */
public final class ClientLongTextTest {
    private static int checks;
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final TextMeasure FONT = new TextMeasure() {
        public int width(String s) { return s.codePointCount(0, s.length()) * 6; }
        public int lineHeight() { return 9; }
        public String truncate(String s, int width) {
            int count = Math.max(0, width / 6);
            if (s.codePointCount(0, s.length()) <= count) return s;
            return count == 0 ? "" : s.substring(0, s.offsetByCodePoints(0, count - 1)) + "…";
        }
    };

    private static void check(boolean ok, String reason) { checks++; if (!ok) throw new AssertionError(reason); }
    private static CompiledTemplate page(String template, String script) {
        return SfcCompiler.compilePage("long.vue", "<template>\n" + template + "\n</template>\n<script>\n" + script + "\n</script>\n").template();
    }
    private static void reject(String template, String script, SfcError.Code code) {
        try { page(template, script); check(false, "expected rejection"); }
        catch (SfcError e) { check(e.code() == code, "proper compiler error: " + e); }
    }
    private static Node node(Node root, String id) {
        if (id.equals(root.id())) return root;
        for (Node child : root.children()) { Node found = node(child, id); if (found != null) return found; }
        return null;
    }
    private static LayoutNode layout(LayoutNode root, String id) {
        if (id.equals(root.node.id())) return root;
        for (LayoutNode child : root.children) { LayoutNode found = layout(child, id); if (found != null) return found; }
        return null;
    }
    private static int textUnits(Node root) {
        int count = root.type() == com.november.mcphone.core.script.layout.NodeType.TEXT ? root.str("text", "").length() : 0;
        for (Node child : root.children()) count += textUnits(child);
        return count;
    }

    private static void policy() {
        check(UiState.page(Map.of("body", "x".repeat(4096))).stringLimit() == 4096, "page accepts the exact long-string limit");
        check(StateRules.check("x".repeat(65)) != null && UiState.of(Map.of("body", "")).stringLimit() == 64, "legacy IR policy remains 64");
        try { UiState.of(Map.of("body", "x".repeat(65))); check(false, "legacy state"); }
        catch (IllegalArgumentException expected) { check(true, "legacy initial state still rejects 65"); }
        try { UiState.page(Map.of("body", "x".repeat(4097))); check(false, "page overflow"); }
        catch (IllegalArgumentException expected) { check(true, "page rejects string overflow"); }
        UiState exact = UiState.page(Map.of("a", "x".repeat(4096), "b", "x".repeat(4096), "c", "x".repeat(4096), "d", "x".repeat(4096)));
        check(exact.copy().writeProblems(exact.values()) == null, "exact total budget and policy survive copying");
        try { UiState.page(Map.of("a", "x".repeat(4096), "b", "x".repeat(4096), "c", "x".repeat(4096), "d", "x".repeat(4096), "e", "x")); check(false, "total overflow"); }
        catch (IllegalArgumentException expected) { check(true, "total budget counts all fields"); }
        try { UiState.page(Map.of("rows", java.util.Collections.nCopies(5, Map.of("body", "x".repeat(4096))))); check(false, "nested total overflow"); }
        catch (IllegalArgumentException expected) { check(true, "nested string leaves count toward total budget"); }
        CompiledTemplate literal = page("<text :text=\"body\"/>", "state = { body: '" + "x".repeat(4096) + "' }");
        UiState initial = new PageStates().open("", literal, "long.vue", null).state();
        check(initial.getString("body").length() == 4096, "SFC literal and production page creation accept full length");
        check(new TemplateInstance(literal, "long.vue").instantiate(initial).root().str("text", "").length() == 4096, "bound text is no longer cut at 512");
        reject("<text/>", "state = { body: '" + "x".repeat(4097) + "' }", SfcError.Code.E_SCRIPT_STATE);
        String many = "state = {a:'" + "x".repeat(4096) + "', b:'" + "x".repeat(4096) + "', c:'" + "x".repeat(4096) + "', d:'" + "x".repeat(4096) + "', e:'x'}";
        reject("<box/>", many, SfcError.Code.E_SCRIPT_STATE);
        check(page("<text>" + "文".repeat(4096) + "</text>", "").initialState().isEmpty(), "static text body accepts 4096");
        reject("<text>" + "x".repeat(4097) + "</text>", "", SfcError.Code.E_TPL_BAD_VALUE);
        reject("<text text=\"" + "x".repeat(4097) + "\"/>", "", SfcError.Code.E_TPL_BAD_VALUE);
        reject("<button @click=\"close()\" text=\"" + "x".repeat(513) + "\"/>", "", SfcError.Code.E_TPL_BAD_VALUE);
        reject("<button @click=\"close()\">" + "x".repeat(513) + "</button>", "", SfcError.Code.E_TPL_BAD_VALUE);
        check(page("<button @click=\"close()\">" + "x".repeat(512) + "</button>", "").initialState().isEmpty(), "button inline caption keeps exact 512 boundary");
        reject("<text-input bind=\"body\" max-length=\"65\"/>", "state={body:''}", SfcError.Code.E_TPL_BAD_VALUE);
        CompiledTemplate init = page("<text :text=\"body\"/>", "state={body:''}\nfunction onInit(){ state.body='文🦎'.repeat(1000); }");
        PageStates.Opened opened = new PageStates().open("", init, "init.vue", null);
        check(opened.warnings().isEmpty() && opened.state().getString("body").length() == 3000, "onInit receives the long-string page policy");
    }

    private static void display() {
        String body = "第一段：中文与🦎。\n\n" + "abcdefghij".repeat(380) + "\n末尾标记";
        CompiledTemplate template = page("<scroll id=\"viewport\"><text id=\"body\" :text=\"body\"/></scroll>", "state={body:''}");
        UiState state = UiState.page(template.initialState());
        state.set("body", body);
        TemplateInstance.Tree tree = new TemplateInstance(template, "long.vue").instantiate(state);
        check(tree.warnings().isEmpty() && node(tree.root(), "body").str("text", "").equals(body), "full Unicode, blank lines and end marker survive template binding");
        LayoutNode root = LayoutEngine.layout(tree.root(), MssParser.parse("#viewport{height:54;}"), state, 120, 150, FONT, ImageSizes.NONE);
        LayoutNode text = layout(root, "body");
        check(text.lines().size() > 150 && text.lines().get(text.lines().size() - 1).contains("末尾标记"), "wrapping includes the end of a multi-screen article");
        check(text.lines().contains(""), "explicit empty paragraphs remain visible");
        check(root.scrollMax() > 1000, "long article creates real scroll extent");
        root.scrollY = Integer.MAX_VALUE;
        check(Renderer.clampScroll(root) == root.scrollMax(), "scroll reaches the final line and clamps safely");
        int[] last = Renderer.lineRange(text.lines().size(), -root.scrollY, 9, 0, 54);
        check(last[1] == text.lines().size() && last[1] - last[0] <= 9, "end viewport draws a bounded set including the last line");
        root.scrollY = -10;
        check(Renderer.clampScroll(root) == 0, "negative scroll resets to top");
        int[] top = Renderer.lineRange(4096, 0, 9, 0, 90);
        check(top[0] == 0 && top[1] <= 12, "top viewport does not paint thousands of hidden lines");
        int[] middle = Renderer.lineRange(4096, -9000, 9, 0, 90);
        check(middle[0] >= 999 && middle[1] <= 1011, "middle viewport selects only nearby lines");
        check(Arrays.equals(Renderer.lineRange(4096, 100, 9, 0, 90), new int[]{0, 0}), "fully below viewport draws no lines");
        check(Arrays.equals(Renderer.lineRange(4096, 0, 9, 10, 10), new int[]{0, 0}), "empty nested clip draws nothing");
        int[] extreme = Renderer.lineRange(4096, Integer.MIN_VALUE, 9, 0, 90);
        check(extreme[0] == extreme[1], "extreme coordinates do not overflow visible-line arithmetic");

        CompiledTemplate repeated = page("<column><text v-for=\"i in 5\" :text=\"body\"/></column>", "state={body:''}");
        UiState bounded = UiState.page(repeated.initialState()); bounded.set("body", "x".repeat(4096));
        TemplateInstance.Tree limited = new TemplateInstance(repeated, "repeat.vue").instantiate(bounded);
        check(textUnits(limited.root()) == 16384 && limited.truncated(), "repeated long texts have a separate rendered-page budget");
        check(limited.warnings().stream().filter(s -> s.contains("16384")).count() == 1, "rendered-page truncation reports one clear warning");
        CompiledTemplate unicode = page("<column><text v-for=\"i in 3\" :text=\"body\"/><text :text=\"tail\"/><text id=\"emoji\" :text=\"emoji\"/></column>", "state={body:'',tail:'',emoji:'🦎'}");
        UiState chars = UiState.page(unicode.initialState()); chars.set("body", "x".repeat(4096)); chars.set("tail", "x".repeat(4095));
        TemplateInstance.Tree safe = new TemplateInstance(unicode, "unicode.vue").instantiate(chars);
        check(safe.truncated() && node(safe.root(), "emoji").str("text", "bad").isEmpty(), "rendered budget never cuts a surrogate pair");
    }

    private static void transactions(Path root) throws Exception {
        String app = "test:long_text";
        Supplier<LocalStore> storage = LocalStore.clientProvider(root, ACCOUNT, app, () -> ACCOUNT);
        LocalStore seed = storage.get(); seed.setString("saved", "old"); check(seed.save(), "seed existing storage");
        Path file = LocalStore.clientPath(root, ACCOUNT, app);
        byte[] before = Files.readAllBytes(file);
        String template = "<column><button id=\"run\" @click=\"run()\">run</button><text :text=\"body\"/></column>";
        for (String tail : new String[]{"state.body='x'.repeat(4097);", "state.body=7;", "throw 'stop';", "ctx.store.setString('saved','文'.repeat(2000));"}) {
            CompiledTemplate compiled = page(template, "state={body:''}\nfunction run(){ctx.store.setString('saved','new');state.body='x'.repeat(2000);" + tail + "}");
            UiState state = UiState.page(compiled.initialState());
            TemplateInstance.Tree tree = new TemplateInstance(compiled, "rollback.vue").instantiate(state);
            Statements.Outcome result = tree.click(node(tree.root(), "run"), state, 0, storage);
            check(!result.applied() && state.getString("body").isEmpty() && state.revision() == 0, "long text operation rolls back: " + tail);
            check(Arrays.equals(before, Files.readAllBytes(file)), "failed long text operation preserves saved bytes");
        }
        String initial = "state={a:'',b:'',c:'',d:'',e:''}\n";
        String fill = "function run(){ctx.store.setString('saved','new');state.a='x'.repeat(4000);state.b=state.a;state.c=state.a;state.d=state.a;state.e=state.a;}";
        CompiledTemplate total = page("<button @click=\"run()\">run</button>", initial + fill);
        UiState defaults = UiState.page(total.initialState());
        TemplateInstance.Tree tree = new TemplateInstance(total, "total.vue").instantiate(defaults);
        check(!tree.click(tree.root(), defaults, 0, storage).applied() && StateRules.textUnits(defaults.values()) == 0, "combined function writes enforce total budget");
        check(Arrays.equals(before, Files.readAllBytes(file)), "total overflow rolls back storage too");
        String four = "state={a:'" + "x".repeat(4000) + "',b:'" + "x".repeat(4000) + "',c:'" + "x".repeat(4000) + "',d:'" + "x".repeat(4000) + "',e:''}\n";
        CompiledTemplate group = page("<button @click=\"run(); e=e+'" + "x".repeat(100) + "'; call('noop')\">run</button>", four
                + "function run(){ctx.store.setString('saved','new');state.e='x'.repeat(300);}");
        UiState grouped = UiState.page(group.initialState());
        TemplateInstance.Tree again = new TemplateInstance(group, "group.vue").instantiate(grouped);
        Statements.Outcome failed = again.click(again.root(), grouped, 0, storage);
        check(!failed.applied() && failed.calls().isEmpty() && grouped.getString("e").isEmpty(), "later legacy statement cannot bypass whole-click text budget");
        check(Arrays.equals(before, Files.readAllBytes(file)), "whole-click budget failure preserves original file");
    }

    private static void sample(Path root) throws Exception {
        Path source = Path.of("../../docs/samples/long-text.vue");
        if (!Files.isRegularFile(source)) source = Path.of("docs/samples/long-text.vue");
        SfcCompiler.App app = SfcCompiler.compile("long-text.vue", Files.readString(source, StandardCharsets.UTF_8));
        Supplier<LocalStore> storage = LocalStore.clientProvider(root, ACCOUNT, app.manifest().id(), () -> ACCOUNT);
        PageStates states = new PageStates();
        PageStates.Opened opened = states.open("", app.template(), "long-text.vue", storage);
        String article = opened.state().getString("body");
        check(opened.warnings().isEmpty() && article.length() > 512 && article.getBytes(StandardCharsets.UTF_8).length <= 4096, "sample opens with a genuinely long but storable article");
        TemplateInstance renderer = new TemplateInstance(app.template(), "long-text.vue");
        TemplateInstance.Tree tree = renderer.instantiate(opened.state());
        check(node(tree.root(), "article").str("text", "").equals(article), "sample displays the complete article");
        for (int[] size : new int[][]{{120,176},{226,158}}) {
            LayoutNode screen = LayoutEngine.layout(tree.root(), app.stylesheet(), opened.state(), size[0], size[1], FONT, ImageSizes.NONE);
            check(screen.h <= size[1] && layout(screen,"viewport").scrollMax() > 0,
                    "sample controls fit phone/tablet content area while article remains scrollable");
        }
        check(tree.click(node(tree.root(), "save"), opened.state(), 0, storage).applied(), "sample saves real long article");
        opened.state().set("body", "short"); states.clear();
        PageStates.Opened restored = states.open("", app.template(), "long-text.vue", storage);
        check(restored.warnings().isEmpty() && restored.state().getString("body").equals(article), "sample restores full article after App close/reopen");
    }

    public static void main(String[] args) throws Exception {
        policy(); display();
        Path root = Files.createTempDirectory("mcphone-long-text-test-").toAbsolutePath().normalize();
        try { transactions(root); sample(root); }
        finally {
            if (!root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())) throw new AssertionError("cleanup outside owned temporary root");
            try (var paths = Files.walk(root)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
        System.out.println("ClientLongTextTest: " + checks + " checks passed");
    }
}
