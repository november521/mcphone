package com.november.mcphone.core.script.sfc;

import com.november.mcphone.core.script.engine.ClientScript;
import com.november.mcphone.core.script.layout.ImageSizes;
import com.november.mcphone.core.script.layout.LayoutEngine;
import com.november.mcphone.core.script.layout.LayoutNode;
import com.november.mcphone.core.script.layout.Node;
import com.november.mcphone.core.script.layout.NodeType;
import com.november.mcphone.core.script.layout.TextMeasure;
import com.november.mcphone.core.script.layout.UiState;
import com.november.mcphone.core.script.server.store.LocalStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Actual event expressions -> bounded primitive arguments -> Rhino -> whole-click commit. */
public final class ClientFunctionArgsTest {
    private static int checks;
    private static final String INITIAL = "state = { draft: 'hello', result: '', count: 0, flag: false, rows: [{ id: 1, label: 'a' }, { id: 2, label: 'b' }] }\n";
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final TextMeasure FONT = new TextMeasure() {
        public int width(String text) { return text.codePointCount(0, text.length()) * 6; }
        public int lineHeight() { return 9; }
        public String truncate(String text, int width) { return text; }
    };

    private static void check(boolean ok, String reason) { checks++; if (!ok) throw new AssertionError(reason); }

    private static final class Fixture {
        final CompiledTemplate compiled;
        final UiState state;
        final TemplateInstance template;
        Fixture(String script, String action) { this(script, action, ""); }
        Fixture(String script, String action, String loop) {
            compiled = SfcCompiler.compilePage("args.vue", "<template>\n<column>\n<button " + loop + " @click=\""
                    + action.replace("&", "&amp;").replace("\"", "&quot;") + "\">run</button>\n</column>\n</template>\n<script>\n"
                    + script + "\n</script>\n").template();
            state = UiState.page(compiled.initialState());
            template = new TemplateInstance(compiled, "args.vue");
        }
        Statements.Outcome fire() { return fire(0, null); }
        Statements.Outcome fire(int index, Supplier<LocalStore> storage) {
            TemplateInstance.Tree tree = template.instantiate(state);
            return tree.click(tree.root().children().get(index), state, 0, storage);
        }
    }

    private static void reject(String script, String action, SfcError.Code code) {
        try { new Fixture(script, action); check(false, "expected compile rejection"); }
        catch (SfcError e) {
            check(e.code() == code, "compiler diagnostic for " + action + ": " + e);
            check("args.vue".equals(e.file()) && e.line() > 0, "diagnostic retains original file/line");
        }
    }

    private static void arguments() {
        Fixture four = new Fixture(INITIAL + "function run(text, number, enabled, suffix) { state.result = text + suffix; state.count = number; state.flag = enabled; }",
                "run('中文 😀', -2147483648, true, '!')");
        check(four.state.revision() == 0, "compile/render do not invoke author functions");
        check(four.fire().applied(), "four primitive arguments accepted");
        check(four.state.getString("result").equals("中文 😀!") && four.state.getInt("count") == Integer.MIN_VALUE && four.state.getBool("flag"), "primitive types and Unicode preserved");
        Fixture one = new Fixture(INITIAL + "function run(value) { state.result = value; }", "run(draft)");
        one.state.set("draft", "latest");
        check(one.fire().applied() && one.state.getString("result").equals("latest"), "state argument evaluated at click time");
        Fixture two = new Fixture(INITIAL + "function run(number, enabled) { state.count = number; state.flag = enabled; }", "run(count + 7, !flag)");
        check(two.fire().applied() && two.state.getInt("count") == 7 && two.state.getBool("flag"), "two expression arguments");
        Fixture three = new Fixture(INITIAL + "function run(text, number, enabled) { state.result = text; state.count = number; state.flag = enabled; }", "run(rows[1].label, 2147483647, false)");
        check(three.fire().applied() && three.state.getString("result").equals("b") && three.state.getInt("count") == Integer.MAX_VALUE && !three.state.getBool("flag"), "object primitive field and three arguments");
        Fixture zero = new Fixture(INITIAL + "function run() { state.count++; }", "run()");
        check(zero.fire().applied() && zero.state.getInt("count") == 1, "old zero-argument function unchanged");
        Fixture ordered = new Fixture(INITIAL + "function run(value) { state.count = value; }", "count = 2; run(count + 3); run(count + 4)");
        check(ordered.fire().applied() && ordered.state.getInt("count") == 9, "arguments see earlier assignments and function commits within one click");
        Fixture helper = new Fixture(INITIAL + "function helper(value) { return value.trim().toUpperCase(); } function run(value) { state.result = helper(value); }", "run(' hi ')");
        check(helper.fire().applied() && helper.state.getString("result").equals("HI"), "parameterized helper called from JavaScript");
        Fixture nested = new Fixture(INITIAL + "function run(index, label) { state.count = index; state.result = label; }", "run(index, row.label)", "v-for=\"(row, index) in rows\" :key=\"row.id\"");
        check(nested.fire(1, null).applied() && nested.state.getInt("count") == 1 && nested.state.getString("result").equals("b"), "bound loop index and primitive field come from clicked row");
        Fixture longText = new Fixture(INITIAL + "function run(value) { state.result = value; }", "run(draft)");
        longText.state.set("draft", "x".repeat(4096));
        check(longText.fire().applied() && longText.state.getString("result").length() == 4096, "argument respects page long-text limit");
        Fixture init = new Fixture(INITIAL + "function helper(value) { state.result = value; } function onInit() { helper('ready'); }", "helper(draft)");
        PageStates.Opened opened = new PageStates().open("", init.compiled, "args.vue", null);
        check(opened.warnings().isEmpty() && opened.state().getString("result").equals("ready"), "zero-argument initialization may call parameterized helpers");
        Fixture noJava = new Fixture(INITIAL + "function run(text, number, enabled) { if (typeof text.getClass !== 'undefined' || typeof number.getClass !== 'undefined' || typeof enabled.getClass !== 'undefined' || typeof Java !== 'undefined' || typeof Packages !== 'undefined') throw 'escape'; state.result = text; }", "run(draft, count, flag)");
        check(noJava.fire().applied() && noJava.state.getString("result").equals("hello"), "arguments are JS primitives with no Java handles");
    }

    private static void declarations() {
        for (String parameters : List.of("a,b,c,d,e", "value,value", "state", "ctx", "backend", "nav", "Math", "parseInt", "arguments", "[value]", "{value}", "value = 1", "...value", "Upper", "_value")) {
            reject(INITIAL + "function run(" + parameters + ") {}", "run()", SfcError.Code.E_SCRIPT_FUNCTION);
        }
        reject(INITIAL + "function onInit(value) {}", "onInit()", SfcError.Code.E_SCRIPT_FUNCTION);
        for (String action : List.of("run()", "run(1, 2)", "run(1, 2, 3, 4, 5)", "run(1,)", "run(rows)", "run({ a: 1 })", "run([1])", "run(null)", "run(backend)")) {
            reject(INITIAL + "function run(value) {}", action, SfcError.Code.E_EXPR_SYNTAX);
        }
        reject(INITIAL + "function run(value) {}", "run(run())", SfcError.Code.E_EXPR_NO_CALLS);
        reject(INITIAL + "function run(value) {}", "run(draft = 'bad')", SfcError.Code.E_EXPR_SYNTAX);
        reject(INITIAL + "function run(value) {}", "run('" + "x".repeat(257) + "')", SfcError.Code.E_EXPR_TOO_LONG);
        reject(INITIAL + "function run(a,b) {}", "run(" + "1+".repeat(16) + "1," + "1+".repeat(16) + "1)", SfcError.Code.E_EXPR_TOO_COMPLEX);
        reject(INITIAL + "function run(value) {}", "run(1);run(1);run(1);run(1);run(1)", SfcError.Code.E_EXPR_SYNTAX);
    }

    private static void rollback(Path root) throws Exception {
        String app = "test:function_args";
        Supplier<LocalStore> storage = LocalStore.clientProvider(root, ACCOUNT, app, () -> ACCOUNT);
        Fixture good = new Fixture(INITIAL + "function save(key, value) { ctx.store.setString(key, value); state.result = value; }", "save('value', draft)");
        check(good.fire(0, storage).applied(), "parameters integrate with local storage");
        Path file = LocalStore.clientPath(root, ACCOUNT, app);
        byte[] before = Files.readAllBytes(file);
        for (String invalid : List.of("rows[9]", "rows[0]", "rows[9].label", "true ? rows : 1")) {
            Fixture bad = new Fixture(INITIAL + "function save(value) { ctx.store.setString('value', value); state.count = 8; } function run(value) { state.result = 'should not run'; }",
                    "save('changed'); call('pending'); run(" + invalid + ")");
            Map<String, Object> values = Map.copyOf(bad.state.values());
            Statements.Outcome outcome = bad.fire(0, storage);
            check(!outcome.applied() && outcome.calls().isEmpty() && outcome.nav() == null && !outcome.close(), "invalid dynamic argument cancels click and pending RPC");
            check(bad.state.values().equals(values) && bad.state.revision() == 0, "earlier function state rolled back");
            check(Arrays.equals(before, Files.readAllBytes(file)), "earlier function write never reaches disk");
            check(outcome.warnings().stream().anyMatch(w -> w.contains("args.vue:3 ")), "argument warning uses original template line");
        }
        Fixture thrower = new Fixture(INITIAL + "function run(value) { ctx.store.setString('value', value); state.count = 9; throw new Error('fail'); }", "call('pending'); run('changed')");
        check(!thrower.fire(0, storage).applied() && thrower.state.revision() == 0 && Arrays.equals(before, Files.readAllBytes(file)), "throw in parameterized function rolls back both state and storage");
        Fixture budget = new Fixture(INITIAL + "function run(value) { state.result = value; while (true) {} }", "run(draft)");
        check(!budget.fire().applied() && budget.state.revision() == 0, "parameterized functions retain execution budget");
        ClientScript script = ClientScript.compile(INITIAL + "function run(value) {}", 0, ScriptParser::parse);
        UiState state = UiState.page(script.initialState());
        List<List<?>> invalidArgs = List.of(List.of(new Object()), List.of(Map.of("a", 1)), List.of(List.of(1)), Arrays.asList((Object) null), List.of(1.5), List.of(1L), List.of("x".repeat(4097)), List.of(), List.of(1, 2));
        for (List<?> args : invalidArgs) {
            try (LocalStore.Transaction transaction = new LocalStore.Transaction(null)) {
                try { script.invoke("run", args, state.values(), state, transaction); check(false, "host boundary must reject invalid arguments"); }
                catch (ClientScript.Failure expected) { check(true, "host boundary rejects without coercion or Java wrapping"); }
            }
        }
    }

    private static void buttons(Node root, List<Node> into) {
        if (root.type() == NodeType.BUTTON) into.add(root);
        for (Node child : root.children()) buttons(child, into);
    }

    private static void sample() throws Exception {
        Path path = Path.of("../../docs/samples/function-args.vue");
        if (!Files.isRegularFile(path)) path = Path.of("docs/samples/function-args.vue");
        SfcCompiler.App app = SfcCompiler.compile("function-args.vue", Files.readString(path, StandardCharsets.UTF_8));
        UiState state = UiState.page(app.template().initialState());
        TemplateInstance template = new TemplateInstance(app.template(), "function-args.vue");
        for (int step : new int[]{1, 1, 2, 0, 3}) {
            TemplateInstance.Tree tree = template.instantiate(state);
            check(tree.warnings().isEmpty(), "sample instantiates without warnings");
            List<Node> buttons = new ArrayList<>(); buttons(tree.root(), buttons);
            check(buttons.size() == 4 && tree.click(buttons.get(step), state, 0).applied(), "actual sample row/reset click succeeds");
            if (step == 1 && state.getInt("done") == 1) {
                List<?> rows = (List<?>) state.get("tasks");
                check(!((Map<?, ?>) rows.get(0)).get("done").equals(true) && ((Map<?, ?>) rows.get(1)).get("done").equals(true) && !((Map<?, ?>) rows.get(2)).get("done").equals(true), "second row changes only second task");
            }
            for (int[] size : new int[][]{{120,176},{226,158}}) {
                LayoutNode layout = LayoutEngine.layout(template.instantiate(state).root(), app.stylesheet(), state, size[0], size[1], FONT, ImageSizes.NONE);
                check(layout.h <= size[1] && layout.w <= size[0], "sample fits phone and tablet content areas");
            }
        }
        check(state.getInt("done") == 0 && ((List<?>) state.get("tasks")).stream().allMatch(row -> Boolean.FALSE.equals(((Map<?, ?>) row).get("done"))), "false literal resets every task through shared function");
    }

    public static void main(String[] args) throws Exception {
        arguments(); declarations(); sample();
        Path root = Files.createTempDirectory("mcphone-function-args-test-").toAbsolutePath().normalize();
        try { rollback(root); }
        finally {
            if (!root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())) throw new AssertionError("cleanup outside owned temporary root");
            try (var paths = Files.walk(root)) { for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
        System.out.println("ClientFunctionArgsTest: " + checks + " checks passed");
    }
}
