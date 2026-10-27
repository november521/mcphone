package com.november.mcphone.core.script.client;

import com.november.mcphone.core.script.http.HttpRequests;
import com.november.mcphone.core.script.layout.Node;
import com.november.mcphone.core.script.layout.PageParameters;
import com.november.mcphone.core.script.layout.UiState;
import com.november.mcphone.core.script.server.store.LocalStore;
import com.november.mcphone.core.script.sfc.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Real navigation statements, Rhino scopes, file transactions and ScriptPage host without a game window. */
public final class ClientNavigationTest {
    private static int checks;
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String INITIAL = "state={id:0,title:'',count:0,body:'',rows:[{id:1,title:'one'},{id:2,title:'two'}]}\n";

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static SfcCompiler.Page page(String script, String action) {
        return SfcCompiler.compilePage("nav.vue", "<template>\n<button id=\"run\" @click=\""
                + action.replace("&", "&amp;").replace("\"", "&quot;")
                + "\">run</button>\n</template>\n<script>\n" + script + "\n</script>");
    }

    private static Statements.Outcome fire(SfcCompiler.Page page, UiState state) {
        TemplateInstance.Tree tree = new TemplateInstance(page.template(), "nav.vue").instantiate(state);
        return tree.click(tree.root(), state, 0);
    }

    private static void reject(String action) {
        try { page(INITIAL, action); check(false, "expected compile rejection: " + action); }
        catch (SfcError expected) { check(expected.line() > 0 && "nav.vue".equals(expected.file()), "compile rejection retains source location"); }
    }

    private static void parameters() {
        var values = new LinkedHashMap<String, Object>(); values.put("id", 1); values.put("title", "中😀"); values.put("on", true);
        PageParameters parameters = PageParameters.from(values);
        values.put("id", 99);
        check(parameters.values().get("id").equals(1), "parameters snapshot caller values");
        try { parameters.values().put("id", 99); check(false, "immutable map"); }
        catch (UnsupportedOperationException expected) { check(true, "Java parameter map is immutable"); }
        check(PageParameters.from(Map.of()).equals(PageParameters.EMPTY), "empty object matches old argument-free navigation");
        check(new PageParameters(Map.of("a", Integer.MIN_VALUE, "b", Integer.MAX_VALUE, "c", false, "d", "x".repeat(256))).values().size() == 4,
                "four primitive parameters and numeric/string boundaries accepted");
        Object[] bad = {null, false, 1, "text", List.of(1), Map.of("a", List.of(1)), Map.of("a", Map.of("b", 1)),
                Map.of("a", 1.0), Map.of("a", 2147483648L), Map.of("bad-key", 1), Map.of("A", 1),
                Map.of("a", "x".repeat(257)), Map.of("a", "\ud800"), Map.of("a", "\udc00"),
                Map.of("a", 1, "b", 2, "c", 3, "d", 4, "e", 5), Map.of(1, "value")};
        for (Object value : bad) {
            try { PageParameters.from(value); check(false, "invalid parameters rejected"); }
            catch (IllegalArgumentException expected) { check(true, "invalid parameters rejected without truncation"); }
        }
        Map<String, Object> nullValue = new LinkedHashMap<>(); nullValue.put("id", null);
        try { PageParameters.from(nullValue); check(false, "null field rejected"); }
        catch (IllegalArgumentException expected) { check(true, "null field rejected"); }
    }

    private static void statements() {
        for (String action : List.of("nav('detail')", "nav('detail', {})", "count=2; nav('detail', {id:count+1,title:'中文 😀',on:true})")) {
            var page = page(INITIAL, action);
            UiState state = UiState.page(page.template().initialState());
            Statements.Outcome result = fire(page, state);
            check(result.applied() && "detail".equals(result.nav()), "old and parameterized navigation accepted");
            if (action.startsWith("count")) check(result.navParameters().values().equals(Map.of("id", 3, "title", "中文 😀", "on", true)),
                    "parameters evaluate after earlier statements and retain primitive types");
            else check(result.navParameters().equals(PageParameters.EMPTY), "omitted/empty parameters remain empty");
        }
        for (String action : List.of("nav('detail', 1)", "nav('detail', false)", "nav('detail', null)",
                "nav('detail', [])", "nav('detail', 'x')", "nav('detail', {}, {})", "nav('detail', {id:1,id:2})")) reject(action);
        for (String action : List.of("count++; nav('detail',{a:[]})", "count++; nav('detail',{a:{id:1}})",
                "count++; nav('detail',{a:null})", "count++; nav('detail',{a:1,b:2,c:3,d:4,e:5})")) {
            var page = page(INITIAL, action);
            UiState state = UiState.page(page.template().initialState());
            var failed = fire(page, state);
            check(!failed.applied() && failed.nav() == null && state.getInt("count") == 0, "bad payload rolls back the entire click");
            check(failed.navParameters().equals(PageParameters.EMPTY) && !failed.warnings().isEmpty(), "rejected navigation has no residual payload");
        }
        var longPage = page(INITIAL, "nav('detail',{title:body})");
        UiState longState = UiState.page(longPage.template().initialState());
        longState.set("body", "x".repeat(257));
        check(!fire(longPage, longState).applied(), "dynamic long strings are rejected before page entry");
        longState.set("body", "\ud800");
        check(!fire(longPage, longState).applied(), "dynamic incomplete Unicode is rejected without host exceptions");
        var laterFailure = page(INITIAL, "count=1; nav('detail',{id:1}); count='bad'");
        UiState before = UiState.page(laterFailure.template().initialState());
        var rejected = fire(laterFailure, before);
        check(!rejected.applied() && rejected.nav() == null && rejected.navParameters().equals(PageParameters.EMPTY)
                && before.getInt("count") == 0, "a later statement failure also discards an earlier valid navigation");
        var last = page(INITIAL, "nav('first',{id:1}); nav('second')");
        var result = fire(last, UiState.page(last.template().initialState()));
        check(result.nav().equals("second") && result.navParameters().equals(PageParameters.EMPTY), "last nav replaces destination and parameters together");
        var object = page(INITIAL, "nav('detail',rows[0])");
        check(fire(object, UiState.page(object.template().initialState())).navParameters().values().equals(Map.of("id", 1, "title", "one")),
                "plain object expressions can supply bounded primitive fields");
        var loop = SfcCompiler.compilePage("loop.vue", "<template>\n<column><button v-for=\"row in rows\" :key=\"row.id\" @click=\"nav('detail',{id:row.id,title:row.title})\">{{row.title}}</button></column>\n</template>\n<script>\n" + INITIAL + "\n</script>");
        var tree = new TemplateInstance(loop.template(), "loop.vue").instantiate(UiState.page(loop.template().initialState()));
        result = tree.click(tree.root().children().get(1), UiState.page(loop.template().initialState()), 0);
        check(result.navParameters().values().equals(Map.of("id", 2, "title", "two")), "clicked loop item's primitive fields reach navigation");
    }

    private static void lifecycleAndReadonly() {
        var template = page(INITIAL + "function onInit(){state.id=ctx.params.id || 0;state.title=ctx.params.title || '';state.count++;}\n"
                + "function verify(){try{ctx.params.id=99;}catch(e){}try{delete ctx.params.title;}catch(e){}try{ctx.params.extra=1;}catch(e){}try{Object.defineProperty(ctx.params,'id',{value:88});}catch(e){}"
                + "if(Object.getPrototypeOf(ctx.params)!==null || typeof ctx.params.getClass!=='undefined' || typeof ctx.params.extra!=='undefined')throw 'escape';"
                + "state.id=ctx.params.id;state.title=ctx.params.title;}", "verify()").template();
        PageStates states = new PageStates();
        PageParameters a = new PageParameters(Map.of("id", 1, "title", "one")), b = new PageParameters(Map.of("id", 2, "title", "two"));
        UiState first = states.open("detail", template, "detail.vue", null, null, a).state();
        check(first.getInt("id") == 1 && first.getInt("count") == 1, "onInit reads parameters before first render");
        first.set("body", "draft A");
        check(states.open("detail", template, "detail.vue", null, null, a).state() == first, "same parameters retain state and skip initialization");
        var reordered = new LinkedHashMap<String, Object>(); reordered.put("title", "one"); reordered.put("id", 1);
        check(states.open("detail", template, "detail.vue", null, null, new PageParameters(reordered)).state() == first,
                "parameter equality ignores object identity and field order");
        UiState second = states.open("detail", template, "detail.vue", null, null, b).state();
        check(second != first && second.getInt("id") == 2 && second.getString("body").isEmpty(), "new parameters create fresh state");
        var tree = new TemplateInstance(template, "detail.vue").instantiate(second);
        check(tree.click(tree.root(), second, 0).applied() && second.getInt("id") == 2 && second.getString("title").equals("two"),
                "parameter values/properties cannot be changed or removed by script");
        check(second.copy().parameters().equals(b), "transaction copies preserve immutable parameters");
        check(states.restore("detail", first).state() == first && first.getString("body").equals("draft A"), "back restores exact prior parameter variant");
        check(states.open("detail", template, "detail.vue", null, null, a).state() == first, "restored variant becomes the retained page");
        UiState empty = states.open("detail", template, "detail.vue", null).state();
        check(empty.getInt("id") == 0 && empty.parameters().equals(PageParameters.EMPTY), "argument-free entry clears prior parameters");
        check(states.open("detail", template, "detail.vue", null).state() == empty, "legacy parameter-free retention remains unchanged");
        states.clear();
        check(states.open("detail", template, "detail.vue", null, null, a).state() != first, "App close releases all parameter variants");
        var failure = page(INITIAL + "function onInit(){state.id=ctx.params.id;throw 'failed';}", "nav('detail')").template();
        var failed = new PageStates().open("detail", failure, "failed.vue", null, null, a);
        check(!failed.warnings().isEmpty() && failed.state().getInt("id") == 0 && failed.state().parameters().equals(a),
                "failed initialization keeps defaults and the entry's immutable parameters");
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static Object invoke(Object object, String name, Class<?>[] types, Object... values) throws Exception {
        Method method = object.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(object, values);
    }
    private static Node node(Node root, String id) {
        if (id.equals(root.id())) return root;
        for (Node child : root.children()) { Node found = node(child, id); if (found != null) return found; }
        return null;
    }
    private static UiState state(ScriptPage host) throws Exception { return (UiState) field(host, "state"); }
    private static Statements.Outcome click(ScriptPage host, ScriptApp app, String id) throws Exception {
        String name = (String) field(host, "current");
        var tree = new TemplateInstance(app.page(name).template(), name).instantiate(state(host));
        @SuppressWarnings("unchecked") Supplier<LocalStore> storage = (Supplier<LocalStore>) field(host, "storage");
        var outcome = tree.click(node(tree.root(), id), state(host), 0, storage);
        invoke(host, "run", new Class<?>[]{Statements.Outcome.class}, outcome);
        return outcome;
    }

    private static void host() throws Exception {
        String entry = "<manifest>\n{\"format\":1,\"id\":\"test:nav\",\"version\":\"1.0.0\",\"name\":\"nav\",\"author\":\"test\"}\n</manifest>\n"
                + "<template>\n<button id=\"run\" @click=\"nav('detail',{id:count+1})\">open</button>\n</template>\n<script>\n" + INITIAL + "\n</script>";
        ScriptApp base = ScriptAppFolder.read("nav.vue", entry.getBytes(StandardCharsets.UTF_8));
        var detail = page(INITIAL + "function onInit(){state.id=ctx.params.id;state.count++;}", "back()");
        ScriptApp app = ScriptApp.of(base.id(), base.manifest(), null, base.entry(), Map.of("detail", detail), null, "nav.vue");
        ScriptPage host = new ScriptPage(app);
        invoke(host, "openPage", new Class<?>[]{String.class}, "");
        UiState main = state(host); main.set("body", "main draft");
        @SuppressWarnings("unchecked") Map<String, Integer> scroll = (Map<String, Integer>) field(host, "scroll");
        scroll.put("list", 37);
        click(host, app, "run"); UiState first = state(host); first.set("body", "detail draft");
        check(first.getInt("id") == 1 && first.parameters().values().get("id").equals(1), "real host opens parameterized detail");
        var two = new PageParameters(Map.of("id", 2));
        invoke(host, "navTo", new Class<?>[]{String.class, PageParameters.class}, "detail", two);
        UiState second = state(host);
        check(second != first && second.getInt("id") == 2, "same named page can navigate with a different id");
        int depth = ((Deque<?>) field(host, "back")).size();
        invoke(host, "navTo", new Class<?>[]{String.class, PageParameters.class}, "detail", two);
        check(state(host) == second && ((Deque<?>) field(host, "back")).size() == depth, "identical current destination is a no-op");
        click(host, app, "run");
        check(state(host) == first && state(host).getString("body").equals("detail draft"), "back restores previous same-page instance and draft");
        click(host, app, "run");
        check(state(host) == main && state(host).getString("body").equals("main draft"), "back restores list's exact state");
        check(scroll.get("list").equals(37), "back restores list scroll snapshot");
        invoke(host, "navTo", new Class<?>[]{String.class, PageParameters.class}, "missing", two);
        check(state(host) == main, "unknown destination leaves host on current page");
        invoke(host, "navTo", new Class<?>[]{String.class, PageParameters.class}, "", two);
        check(state(host) != main && state(host).parameters().equals(two), "empty destination name accepts parameters for the entry page");
        check(host.onBack() && state(host) == main, "system back restores the previous entry-page instance");
        for (int i = 0; i < 30; i++) invoke(host, "navTo", new Class<?>[]{String.class, PageParameters.class}, "detail", new PageParameters(Map.of("id", i)));
        check(((Deque<?>) field(host, "back")).size() == ScriptPage.MAX_NAV, "parameterized back stack stays bounded");
        check(((Map<?, ?>) field(field(host, "states"), "states")).size() == 2, "cache grows by page name, not parameter combinations");
        host.onClose();
        check(((Deque<?>) field(host, "back")).isEmpty() && ((Map<?, ?>) field(field(host, "states"), "states")).isEmpty(),
                "closing clears retained state and back references");
    }

    private static void transactions(Path root) {
        Supplier<LocalStore> storage = () -> LocalStore.openClient(root, ACCOUNT, "test:navigation");
        String stage = "function stage(){state.count++;ctx.store.setString('key','changed');ctx.http.request({url:'https://example.invalid/'},'received');}"
                + "function received(status,body,error){state.body=String(ctx.params.id);}";
        var page = page(INITIAL + stage, "stage(); nav('detail',{id:[]})");
        UiState state = UiState.page(page.template().initialState());
        AtomicInteger sent = new AtomicInteger();
        var tree = new TemplateInstance(page.template(), "nav.vue").instantiate(state);
        var failed = tree.click(tree.root(), state, 0, storage, new HttpRequests(4, list -> sent.addAndGet(list.size())));
        check(!failed.applied() && state.getInt("count") == 0 && sent.get() == 0, "invalid navigation rolls back function state and staged HTTP");
        check(LocalStore.openClient(root, ACCOUNT, "test:navigation").getString("key", "missing").equals("missing"), "invalid navigation discards staged storage");
        var template = page(INITIAL + stage, "nav('detail')").template();
        PageStates states = new PageStates();
        UiState old = states.open("detail", template, "detail.vue", storage, null, new PageParameters(Map.of("id", 1))).state();
        UiState current = states.open("detail", template, "detail.vue", storage, null, new PageParameters(Map.of("id", 2))).state();
        var request = new HttpRequests.Request("https://example.invalid/", "GET", Map.of(), "", 1000, "received");
        check(PageStates.respond(template.script(), request, new HttpRequests.Result(200,"ok",""), old, "detail.vue", storage, null).isEmpty(),
                "old HTTP callback still executes in its originating page context");
        check(old.getString("body").equals("1") && current.getString("body").isEmpty(), "old result cannot overwrite the new parameter variant");
    }

    private static void sample(Path root) throws Exception {
        Path source = Path.of("../../docs/samples/navigation-params");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out); var paths = Files.walk(source)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().equals("README.md")).sorted().toList()) {
                zip.putNextEntry(new ZipEntry(source.relativize(path).toString().replace('\\','/')));
                zip.write(Files.readAllBytes(path)); zip.closeEntry();
            }
        }
        ScriptApp app = ScriptAppFolder.read("navigation-params.zip", out.toByteArray());
        check(app.pages().size() == 1 && app.page("detail") != null, "real installable example loads both pages");
        ScriptPage host = new ScriptPage(app);
        Supplier<LocalStore> storage = () -> LocalStore.openClient(root, ACCOUNT, app.manifest().id());
        set(host, "storage", storage); invoke(host, "openPage", new Class<?>[]{String.class}, "");
        UiState list = state(host); list.set("draft", "list draft");
        var tree = new TemplateInstance(app.entry().template(), "app.vue").instantiate(list);
        var outcome = tree.click(tree.root().children().get(3), list, 0, storage);
        invoke(host, "run", new Class<?>[]{Statements.Outcome.class}, outcome);
        UiState first = state(host);
        check(first.getInt("id") == 1 && first.getString("title").equals("便签 1"), "clicked example row supplies its id and title");
        first.set("note", "saved A\n中文😀"); click(host, app, "save");
        check(storage.get().getString("note_1", "").equals("saved A\n中文😀"), "sample saves under parameter-selected key");
        first.set("note", "unsaved A"); click(host, app, "next");
        check(state(host).getInt("id") == 2 && !state(host).getString("note").equals("unsaved A"), "next note starts with its own content");
        click(host, app, "back");
        check(state(host) == first && state(host).getString("note").equals("unsaved A"), "back from next note preserves unsaved prior note");
        click(host, app, "back");
        check(state(host) == list && list.getString("draft").equals("list draft") && list.getInt("opened") == 1, "return to list preserves draft and click count");
        host.onClose();
    }

    public static void main(String[] args) throws Exception {
        parameters(); statements(); lifecycleAndReadonly(); host();
        Path root = Files.createTempDirectory("mcphone-nav-");
        try { transactions(root); sample(root); }
        finally { try (var paths = Files.walk(root)) { for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path); } }
        System.out.println("ClientNavigationTest: " + checks + " checks passed");
    }
}
