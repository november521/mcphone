package com.november.mcphone.core.script.sfc;

import com.november.mcphone.core.script.engine.ClientScript;
import com.november.mcphone.core.script.layout.Node;
import com.november.mcphone.core.script.layout.UiState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Actual SFC -> button transaction -> hardened Rhino -> validated state -> template refresh. */
public final class ClientScriptTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static final String INITIAL = "state = { draft: '  hello  ', result: '', count: 0, flag: false, rows: [{ n: 1, label: 'a' }] }\n";

    private static final class Fixture {
        final SfcCompiler.Page page;
        final UiState state;
        final TemplateInstance template;
        Fixture(String script, String action) {
            page = SfcCompiler.compilePage("functions.vue", "<template>\n<column>\n<button id=\"run\" @click=\""
                    + action.replace("&", "&amp;").replace("\"", "&quot;") + "\">run</button>\n<text>{{ result }}</text>\n</column>\n</template>\n<script>\n"
                    + script + "\n</script>\n");
            state = UiState.of(page.template().initialState());
            template = new TemplateInstance(page.template(), "functions.vue");
        }
        Statements.Outcome fire() {
            TemplateInstance.Tree tree = template.instantiate(state);
            return tree.click(tree.root().children().get(0), state, 0);
        }
        String echo() { return template.instantiate(state).root().children().get(1).str("text", ""); }
    }

    private static Fixture fixture(String body) { return new Fixture(INITIAL + "function run() { " + body + " }", "run()"); }

    private static void rejected(String script, String action, SfcError.Code code) {
        try {
            new Fixture(script, action);
            check(false, "should reject " + script);
        } catch (SfcError e) {
            check(e.code() == code, "expected " + code + ", got " + e);
            check("functions.vue".equals(e.file()) && e.line() > 0, "original file and script/template line");
        }
    }

    private static void rollback(String body) {
        Fixture f = fixture("state.count = 7; " + body);
        Map<String, Object> before = Map.copyOf(f.state.values());
        int revision = f.state.revision();
        Statements.Outcome result = f.fire();
        check(!result.applied() && !result.warnings().isEmpty(), "failure reported: " + body);
        check(f.state.values().equals(before) && f.state.revision() == revision, "all state rolled back: " + body);
    }

    public static void main(String[] args) throws Exception {
        Fixture text = fixture("state.result = state.draft.trim().toUpperCase(); state.count++; state.flag = true;");
        check(text.state.getInt("count") == 0, "compilation and initial render execute no author code");
        check(text.fire().applied(), "button executes named no-argument function");
        check(text.state.getString("result").equals("HELLO") && text.echo().equals("HELLO"), "state commit and actual interpolation");
        check(text.state.getInt("count") == 1 && text.state.getBool("flag"), "number and boolean conversion");
        text.state.set("draft", " changed ");
        check(text.fire().applied() && text.echo().equals("CHANGED"), "each click reads latest state");
        check(text.state.getInt("count") == 2, "state persists across calls");

        Fixture helper = new Fixture(INITIAL + "function helper() { state.result = 'helper'; }\nfunction run() { helper(); }", "run()");
        check(helper.fire().applied() && helper.echo().equals("helper"), "page-local functions can call one another");
        Fixture loop = fixture("var total = 0; for (var i = 0; i < 5; i++) { total += i; } if (total === 10) state.count = total;");
        check(loop.fire().applied() && loop.state.getInt("count") == 10, "local variables, loops and branches");
        Fixture array = fixture("state.rows[0].n = 2; state.rows.push({ n: 3, label: 'b' }); state.rows.reverse();");
        check(array.fire().applied(), "nested plain object and array mutations");
        check(array.state.get("rows").equals(List.of(Map.of("n", 3, "label", "b"), Map.of("n", 2, "label", "a"))), "arrays converted without Java wrappers");
        Fixture fresh = fixture("if (typeof remembered !== 'undefined') throw 'retained'; remembered = 1; state.count++;");
        check(fresh.fire().applied() && fresh.fire().applied(), "temporary globals do not persist across calls");
        Fixture isolated = fixture("state.result = state.draft;");
        check(isolated.fire().applied() && isolated.echo().equals("  hello  "), "another page instance has independent state");
        Fixture unchanged = fixture("return 123;");
        check(unchanged.fire().applied() && unchanged.state.revision() == 0, "return ignored and unchanged state causes no refresh");
        Fixture mixed = new Fixture(INITIAL + "function run() { state.count += 3; }", "count = 2; run(); count++");
        check(mixed.fire().applied() && mixed.state.getInt("count") == 6, "function participates in existing ordered transaction");
        Fixture atomic = new Fixture(INITIAL + "function run() { state.count = 5; }", "run(); count = 'bad'; call('test')");
        Statements.Outcome failed = atomic.fire();
        check(!failed.applied() && failed.calls().isEmpty() && atomic.state.getInt("count") == 0, "later failure rolls back function and pending RPC");
        Fixture legacy = new Fixture(INITIAL, "count++; result = 'old'; nav('detail')");
        Statements.Outcome old = legacy.fire();
        check(old.applied() && old.nav().equals("detail") && legacy.echo().equals("old"), "old syntax and navigation remain usable");

        rejected(INITIAL + "function run(a, b, c, d, e) {}", "run()", SfcError.Code.E_SCRIPT_FUNCTION);
        rejected(INITIAL + "function* run() {}", "run()", SfcError.Code.E_SCRIPT_FUNCTION);
        rejected(INITIAL + "function run() {} function run() {}", "run()", SfcError.Code.E_SCRIPT_FUNCTION);
        rejected(INITIAL + "function state() {}", "run()", SfcError.Code.E_SCRIPT_FUNCTION);
        rejected(INITIAL + "function call() {}", "run()", SfcError.Code.E_SCRIPT_FUNCTION);
        rejected(INITIAL + "function parseInt() {}", "run()", SfcError.Code.E_SCRIPT_FUNCTION);
        rejected(INITIAL + "function run( {}", "run()", SfcError.Code.E_SCRIPT_FUNCTION);
        rejected(INITIAL + "function run() { state.flag = " + "!".repeat(5000) + "true; }", "run()", SfcError.Code.E_SCRIPT_FUNCTION);
        rejected(INITIAL + "function run() {}\nrun()", "run()", SfcError.Code.E_SCRIPT_P0_SUBSET);
        rejected(INITIAL + "var x = 1; function run() {}", "run()", SfcError.Code.E_SCRIPT_P0_SUBSET);
        rejected("state = { result: 1 + 1 }; function run() {}", "run()", SfcError.Code.E_SCRIPT_P0_SUBSET);
        rejected(INITIAL + "function run() {}", "run(1)", SfcError.Code.E_EXPR_SYNTAX);
        rejected(INITIAL + "function run() {}", "missing()", SfcError.Code.E_EXPR_NO_CALLS);
        rejected(INITIAL + "function run() {}\n//" + "x".repeat(ClientScript.MAX_SOURCE), "run()", SfcError.Code.E_SCRIPT_FUNCTION);
        StringBuilder many = new StringBuilder(INITIAL);
        for (int i = 0; i <= ClientScript.MAX_FUNCTIONS; i++) many.append("function f").append(i).append("() {}\n");
        rejected(many.toString(), "f0()", SfcError.Code.E_SCRIPT_FUNCTION);

        rollback("throw new Error('bad');");
        rollback("state.result = 'x'.repeat(65);");
        rollback("state.count = 1.5;");
        rollback("state.count = NaN;");
        rollback("state.count = Infinity;");
        rollback("state.count = 2147483648;");
        rollback("state.count = 'wrong type';");
        rollback("state.extra = 1;");
        rollback("delete state.result;");
        rollback("state = {};");
        rollback("delete state;");
        rollback("state.rows = [1];");
        rollback("state.rows = new Array(33).fill({ n: 1, label: 'a' });");
        rollback("state.rows = new Array(1000000000);");
        rollback("state.rows[0].n = function() {};");
        rollback("state.rows[0].label = null;");
        rollback("state.rows[0].label = undefined;");
        rollback("state.rows[0].n = state;");
        rollback("delete state.rows[0];");
        rollback("state.rows.extra = 1;");
        rollback("state[Symbol('hidden')] = 1;");
        rollback("Object.defineProperty(state, 'result', { get: function() { while(true) {} } });");
        rollback("Object.defineProperty(state.rows, '0', { get: function() { while(true) {} } });");
        rollback("Object.defineProperty(state, 'result', { set: function(x) {} });");
        rollback("Object.defineProperty(this, 'state', { get: function() { while(true) {} } });");
        rollback("state.result = 'x'.repeat(1000000000);");
        rollback("/(a+)+$/.test('a'.repeat(40) + '!');");
        rollback("while (true) {};");
        rollback("try { while (true) {} } catch(e) { state.result = 'caught'; } finally { return; }");
        rollback("run();");

        Fixture recovery = fixture("if (!state.flag) { state.count = 9; throw 'bad'; } state.result = 'recovered';");
        check(!recovery.fire().applied() && recovery.state.getInt("count") == 0, "error leaves page unchanged");
        recovery.state.set("flag", true);
        check(recovery.fire().applied() && recovery.echo().equals("recovered"), "next call works after error");
        Fixture afterAbort = fixture("if (!state.flag) while(true) {} state.result = 'recovered';");
        check(!afterAbort.fire().applied(), "infinite execution aborted");
        afterAbort.state.set("flag", true);
        check(afterAbort.fire().applied() && afterAbort.echo().equals("recovered"), "budget and host depth reset after abort");
        Fixture sourceLine = fixture("\nthrow 'bad';");
        Statements.Outcome atLine = sourceLine.fire();
        check(atLine.warnings().stream().anyMatch(w -> w.contains("script 第 10 行")), "runtime error uses original SFC script line: " + atLine.warnings());

        Fixture globals = fixture("state.result = [typeof Packages, typeof java, typeof phone, typeof fetch, typeof require, typeof eval].join(',');");
        check(globals.fire().applied() && globals.echo().equals("undefined,undefined,undefined,undefined,undefined,undefined"), "no Java, network, storage, RPC or dynamic compilation globals");
        rollback("state.result = state.getClass().getName();");
        rollback("state.result = (function(){}).constructor('return 1')();");
        Fixture comments = fixture("// } function fake() {\nstate.result = '} function fake() {'; /* { } */");
        check(comments.fire().applied() && comments.echo().equals("} function fake() {"), "AST extraction respects strings and comments");

        Path sample = Path.of("../../docs/samples/client-functions.vue");
        if (!Files.isRegularFile(sample)) sample = Path.of("docs/samples/client-functions.vue");
        SfcCompiler.App app = SfcCompiler.compile("client-functions.vue", Files.readString(sample, StandardCharsets.UTF_8));
        UiState sampleState = UiState.of(app.template().initialState());
        sampleState.set("draft", "  Alice  ");
        TemplateInstance sampleTemplate = new TemplateInstance(app.template(), "client-functions.vue");
        TemplateInstance.Tree sampleTree = sampleTemplate.instantiate(sampleState);
        Node button = sampleTree.root().children().stream().filter(n -> "check".equals(n.id())).findFirst().orElseThrow();
        check(sampleTree.click(button, sampleState, 0).applied() && sampleState.getString("result").equals("你好，Alice"), "installable sample executes through real button transaction");
        sampleState.set("draft", "  ");
        check(sampleTree.click(button, sampleState, 0).applied() && sampleState.getString("result").equals("请输入名字"), "sample validation branch");
        System.out.println("ClientScriptTest: " + checks + " checks passed");
    }
}
