package com.november.mcphone.core.script.client;

import com.november.mcphone.core.script.http.HttpRequests;
import com.november.mcphone.core.script.http.HttpSession;
import com.november.mcphone.core.script.layout.*;
import com.november.mcphone.core.script.server.store.LocalStore;
import com.november.mcphone.core.script.sfc.*;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Actual page activation, Rhino transactions and ScriptPage back entries, without a game window. */
public final class ClientResumeTest {
    private static int checks;
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String INITIAL = "state={created:0,resumed:0,id:0,draft:'',value:'',requestId:0}\n";
    private static final String INIT = "function onInit(){state.created++;state.id=ctx.params.id || 0;}\n";
    private static final String RESUME = "function onResume(){state.resumed++;state.id=ctx.params.id || 0;state.value='resumed '+state.id;}\n";
    private static final String REQUEST = "ctx.http.request({url:'https://example.invalid/'},'received')";

    private static void check(boolean ok, String message) {checks++; if (!ok) throw new AssertionError(message);}
    private static String source(String body, String script) {
        return "<template>\n" + body + "\n</template>\n<script>\n" + script + "\n</script>\n";
    }
    private static CompiledTemplate template(String script) {
        return SfcCompiler.compilePage("resume.vue", source("<text>{{ draft }}</text>", script)).template();
    }
    private static Supplier<LocalStore> storage(Path root, String app) {
        return LocalStore.clientProvider(root, ACCOUNT, app, () -> ACCOUNT);
    }
    private static void seed(Supplier<LocalStore> storage) {
        LocalStore store = storage.get(); store.setString("keep", "original"); check(store.save(), "seed original file");
    }

    private static void lifecycle() {
        AtomicInteger io = new AtomicInteger();
        Supplier<LocalStore> unused = () -> {io.incrementAndGet(); throw new AssertionError("unexpected I/O");};
        CompiledTemplate page = template(INITIAL + INIT + RESUME); PageStates states = new PageStates();
        check(page.initialState().get("resumed").equals(0), "compiling does not run hooks");
        UiState first = states.open("", page, "main.vue", unused).state(); first.set("draft", "unsaved");
        check(first.getInt("created") == 1 && first.getInt("resumed") == 0, "first activation only initializes");
        for (int i = 0; i < 5; i++) new TemplateInstance(page, "main.vue").instantiate(first);
        check(first.getInt("resumed") == 0, "rendering never resumes");
        UiState child = states.open("detail", page, "detail.vue", unused, null, new PageParameters(Map.of("id", 1))).state();
        check(child != first && child.getInt("created") == 1 && child.getInt("resumed") == 0, "new child only initializes");
        check(states.open("", page, "main.vue", unused).state() == first && first.getInt("resumed") == 1, "cached activation resumes same state once");
        check(first.getString("draft").equals("unsaved") && first.getInt("created") == 1, "resume does not reset draft or initialization count");
        var secondParams = new PageParameters(Map.of("id", 2));
        UiState second = states.open("detail", page, "detail.vue", unused, null, secondParams).state();
        check(second != child && second.getInt("id") == 2 && second.getInt("resumed") == 0, "changed parameters create a fresh instance");
        child.set("draft", "first detail draft");
        check(states.restore("detail", child, page, "detail.vue", unused, null).state() == child, "context-aware back restores exact old instance");
        check(child.getInt("id") == 1 && child.getInt("resumed") == 1 && child.getString("draft").equals("first detail draft"), "restored hook reads its own parameters and keeps unrelated edits");
        check(states.restore("detail", child).state() == child && child.getInt("resumed") == 1, "legacy context-free restore retains its original signature and behavior");
        check(io.get() == 0, "state-only hooks never load storage");
        states.clear(); UiState reopened = states.open("", page, "main.vue", unused).state();
        check(reopened != first && reopened.getInt("created") == 1 && reopened.getInt("resumed") == 0, "App reopen initializes a new instance");
        CompiledTemplate onlyResume = template(INITIAL + RESUME); PageStates only = new PageStates();
        UiState plain = only.open("", onlyResume, "plain.vue", null).state();
        check(plain.getInt("resumed") == 0 && only.open("", onlyResume, "plain.vue", null).state().getInt("resumed") == 1, "onResume works without an onInit declaration");
        CompiledTemplate legacy = template(INITIAL + INIT); PageStates old = new PageStates();
        UiState retained = old.open("", legacy, "old.vue", unused).state(); retained.set("draft", "old draft");
        check(old.open("", legacy, "old.vue", unused).state() == retained && retained.getInt("created") == 1, "apps without onResume retain old activation behavior");
        for (String declaration : List.of("function onResume(value){}", "function onResume(){} function onResume(){}")) {
            try {template(INITIAL + declaration); check(false, "invalid resume rejected");}
            catch (SfcError expected) {check(expected.code() == SfcError.Code.E_SCRIPT_FUNCTION, "resume follows zero-argument and unique-function rules");}
        }
    }

    private static void transactions(Path root) throws Exception {
        int index = 0;
        for (String tail : List.of("", "throw 'stop';", "state.resumed='bad';", "while(true){}", "ctx.store.setString('big','x'.repeat(4097));")) {
            String app = "test:resume_" + index++; var storage = storage(root, app); seed(storage);
            Path file = LocalStore.clientPath(root, ACCOUNT, app); byte[] before = Files.readAllBytes(file);
            var page = template(INITIAL + INIT + "function onResume(){state.resumed++;ctx.store.setString('keep','updated');"
                    + REQUEST + ";state.value=ctx.store.getString('keep');" + tail + "}\nfunction received(s,b,e){state.value=b;}");
            PageStates states = new PageStates(); UiState state = states.open("", page, "resume.vue", storage).state();
            state.set("draft", "keep draft"); Map<String, Object> snapshot = Map.copyOf(state.values());
            AtomicInteger sent = new AtomicInteger();
            Function<UiState, HttpRequests> network = target -> new HttpRequests(4, batch -> sent.addAndGet(batch.size()));
            var result = states.open("", page, "resume.vue", storage, network);
            check(result.state() == state, "resume always retains instance identity");
            if (tail.isEmpty()) {
                check(result.warnings().isEmpty() && sent.get() == 1 && state.getString("value").equals("updated"), "successful resume publishes state, storage and network together");
                check(storage.get().getString("keep", "").equals("updated") && state.getString("draft").equals("keep draft"), "successful refresh preserves unrelated draft");
            } else {
                check(!result.warnings().isEmpty() && result.warnings().get(0).contains("resume.vue onResume()"), "failure identifies the resume hook");
                check(state.values().equals(snapshot) && sent.get() == 0 && Arrays.equals(before, Files.readAllBytes(file)), "script/type/budget/quota failure discards every staged effect");
                for (int i = 0; i < 3; i++) new TemplateInstance(page, "resume.vue").instantiate(state);
                check(state.values().equals(snapshot), "failed resume is not retried during rendering");
                check(!states.open("", page, "resume.vue", storage, network).warnings().isEmpty() && state.getInt("created") == 1, "another activation retries resume, never initialization");
            }
        }
        String app = "test:resume_io"; LocalStore loaded = storage(root, app).get(); loaded.setString("keep", "original");
        Path conflict = LocalStore.clientPath(root, ACCOUNT, app); Files.createDirectories(conflict); Files.writeString(conflict.resolve("keep.txt"), "keep");
        var page = template(INITIAL + INIT + "function onResume(){ctx.store.remove('keep');state.resumed++;" + REQUEST + ";}\nfunction received(s,b,e){}");
        PageStates states = new PageStates(); UiState state = states.open("", page, "io.vue", () -> loaded).state();
        AtomicInteger sent = new AtomicInteger(); var failed = states.open("", page, "io.vue", () -> loaded, target -> new HttpRequests(4, batch -> sent.addAndGet(batch.size())));
        check(!failed.warnings().isEmpty() && state.getInt("resumed") == 0 && sent.get() == 0, "failed disk commit preserves state and prevents network");
        check(loaded.getString("keep", "").equals("original") && Files.readString(conflict.resolve("keep.txt")).equals("keep"), "failed resume deletion preserves original provider and target");
        var active = new AtomicReference<>(ACCOUNT); app = "test:resume_account"; seed(storage(root, app));
        var guarded = LocalStore.clientProvider(root, ACCOUNT, app, active::get);
        states = new PageStates(); state = states.open("", page, "account.vue", guarded).state(); active.set(UUID.randomUUID());
        check(!states.open("", page, "account.vue", guarded).warnings().isEmpty() && state.getInt("resumed") == 0, "old page cannot refresh another account's storage");
        check(storage(root, app).get().getString("keep", "").equals("original"), "account failure preserves original stored key");
    }

    private static HttpRequests network(HttpSession session, CompiledTemplate page, UiState state) {
        return new HttpRequests(session, batch -> {
            for (var request : batch) session.submit(request, response -> check(PageStates.respond(page.script(), request, response,
                    state, "network.vue", null, network(session, page, state)).isEmpty(), "resumed request callback commits"));
        });
    }

    private static void requests() {
        for (boolean fail : List.of(false, true)) {
            var futures = new java.util.ArrayList<CompletableFuture<HttpRequests.Result>>();
            var page = template(INITIAL + "function onInit(){state.created++;state.requestId=" + REQUEST + ";}\n"
                    + "function onResume(){ctx.http.cancel(state.requestId);state.requestId=" + REQUEST + ";state.resumed++;" + (fail ? "throw 'stop';" : "") + "}\n"
                    + "function received(s,b,e){state.value=String(ctx.params.id)+':'+b;}");
            try (HttpSession session = new HttpSession(() -> true, request -> {var future = new CompletableFuture<HttpRequests.Result>(); futures.add(future); return future;})) {
                PageStates states = new PageStates(); var params = new PageParameters(Map.of("id", 7));
                Function<UiState, HttpRequests> factory = target -> network(session, page, target);
                UiState state = states.open("detail", page, "network.vue", null, factory, params).state(); int firstId = state.getInt("requestId");
                check(futures.size() == 1 && firstId > 0, "initial request setup");
                var result = states.open("detail", page, "network.vue", null, factory, params);
                check(result.warnings().isEmpty() != fail && futures.get(0).isCancelled() != fail, "resume request cancellation shares the transaction");
                check(futures.size() == (fail ? 1 : 2) && (state.getInt("requestId") == firstId) == fail, "failed resume retains old request ID and sends no replacement");
                futures.get(fail ? 0 : 1).complete(new HttpRequests.Result(200, "reply", "")); session.poll();
                check(state.getString("value").equals("7:reply") && session.available() == 4, "reply uses retained parameters and UI state");
            }
        }
    }

    private static Object field(Object object, String name) throws Exception {Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);}
    private static void set(Object object, String name, Object value) throws Exception {Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);}
    private static Object invoke(Object object, String name, Class<?>[] types, Object... values) throws Exception {Method method = object.getClass().getDeclaredMethod(name, types); method.setAccessible(true); return method.invoke(object, values);}
    private static UiState state(ScriptPage host) throws Exception {return (UiState) field(host, "state");}
    private static void nav(ScriptPage host, String name, int id) throws Exception {invoke(host, "navTo", new Class<?>[]{String.class, PageParameters.class}, name, new PageParameters(Map.of("id", id)));}
    private static Node node(Node root, String id) {if (id.equals(root.id())) return root; for (Node child : root.children()) {Node found = node(child, id); if (found != null) return found;} return null;}
    private static List<String> noteTitles(Node root) {
        var titles = new java.util.ArrayList<String>();
        if (root.type() == NodeType.BUTTON && root.str("text", "").startsWith("便签 ")) titles.add(root.str("text", ""));
        for (Node child : root.children()) titles.addAll(noteTitles(child));
        return titles;
    }
    private static LayoutNode layoutNode(LayoutNode root, String id) {
        if (id.equals(root.node.id())) return root;
        for (LayoutNode child : root.children) {LayoutNode found = layoutNode(child, id); if (found != null) return found;}
        return null;
    }
    private static void click(ScriptPage host, ScriptApp app, String id) throws Exception {
        String name = (String) field(host, "current"); var tree = new TemplateInstance(app.page(name).template(), name).instantiate(state(host));
        @SuppressWarnings("unchecked") Supplier<LocalStore> storage = (Supplier<LocalStore>) field(host, "storage");
        var result = tree.click(node(tree.root(), id), state(host), 0, storage);
        check(result.applied(), "real host click: " + id); invoke(host, "run", new Class<?>[]{Statements.Outcome.class}, result);
    }
    private static ScriptApp app(String entryScript) {
        String manifest = "<manifest>\n{\"format\":1,\"id\":\"test:resume_host\",\"version\":\"1.0.0\",\"name\":\"resume\",\"author\":\"test\"}\n</manifest>\n";
        ScriptApp base = ScriptAppFolder.read("resume.vue", (manifest + source("<button id=\"open\" @click=\"nav('detail',{id:1})\">open</button>", entryScript)).getBytes(StandardCharsets.UTF_8));
        var detail = SfcCompiler.compilePage("detail.vue", source("<button id=\"back\" @click=\"back()\">back</button>", INITIAL + INIT + RESUME));
        return ScriptApp.of(base.id(), base.manifest(), null, base.entry(), Map.of("detail", detail), null, "resume.vue");
    }

    private static void host() throws Exception {
        ScriptApp app = app(INITIAL + INIT + RESUME); ScriptPage host = new ScriptPage(app);
        invoke(host, "openPage", new Class<?>[]{String.class}, ""); UiState main = state(host); main.set("draft", "main draft");
        @SuppressWarnings("unchecked") Map<String, Integer> scroll = (Map<String, Integer>) field(host, "scroll"); scroll.put("list", 37);
        click(host, app, "open"); UiState first = state(host); first.set("draft", "detail draft");
        check(first.getInt("created") == 1 && first.getInt("resumed") == 0, "host first entry only initializes");
        int depth = ((Deque<?>) field(host, "back")).size(); nav(host, "detail", 1);
        check(state(host) == first && first.getInt("resumed") == 0 && ((Deque<?>) field(host, "back")).size() == depth, "same current page and parameters do not resume or push history");
        nav(host, "detail", 2); UiState second = state(host);
        check(second != first && second.getInt("created") == 1 && second.getInt("resumed") == 0, "parameter changes initialize new host instance");
        check(host.onBack() && state(host) == first && first.getInt("resumed") == 1 && first.getInt("id") == 1, "system back resumes exact old parameter variant");
        check(first.getString("draft").equals("detail draft"), "detail draft survives resume");
        click(host, app, "back");
        check(state(host) == main && main.getInt("resumed") == 1 && main.getString("draft").equals("main draft"), "script back resumes main without resetting draft");
        check(scroll.get("list").equals(37), "back restores scroll before drawing refreshed state");
        nav(host, "detail", 1); check(state(host) == first && first.getInt("created") == 1 && first.getInt("resumed") == 2, "forward navigation to cached page resumes once");
        for (int i = 0; i < 5; i++) {host.tick(); new TemplateInstance(app.page("detail").template(), "detail.vue").instantiate(first);}
        check(first.getInt("resumed") == 2, "host ticks and layout do not run resume hooks");
        host.onClose(); check(((Deque<?>) field(host, "back")).isEmpty(), "closing clears retained navigation");
        ScriptPage reopened = new ScriptPage(app); invoke(reopened, "openPage", new Class<?>[]{String.class}, "");
        check(state(reopened).getInt("created") == 1 && state(reopened).getInt("resumed") == 0, "reopened host only initializes"); reopened.onClose();
        ScriptApp broken = app(INITIAL + INIT + "function onResume(){state.value='wrong';throw 'stop';}");
        host = new ScriptPage(broken); invoke(host, "openPage", new Class<?>[]{String.class}, ""); main = state(host); main.set("value", "kept");
        click(host, broken, "open"); check(host.onBack() && state(host) == main && main.getString("value").equals("kept"), "failed host resume still displays the retained page");
        check(!((String) field(host, "toast")).isEmpty(), "failed resume produces existing localized action feedback"); host.onClose();
    }

    private static void sample(Path root) throws Exception {
        Path folder = Path.of("../../docs/samples/page-resume"); ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes); var paths = Files.walk(folder)) {
            for (Path path : paths.filter(Files::isRegularFile).filter(path -> !path.getFileName().toString().equals("README.md")).sorted().toList()) {
                zip.putNextEntry(new ZipEntry(folder.relativize(path).toString().replace('\\', '/'))); zip.write(Files.readAllBytes(path)); zip.closeEntry();
            }
        }
        ScriptApp app = ScriptAppFolder.read("page-resume.zip", bytes.toByteArray()); check(app.page("detail") != null, "actual installable example loads both pages");
        ScriptPage host = new ScriptPage(app); var storage = storage(root, app.manifest().id()); set(host, "storage", storage);
        invoke(host, "openPage", new Class<?>[]{String.class}, ""); UiState list = state(host);
        check(list.getInt("refreshes") == 0, "example initial load does not count as resume");
        var template = new TemplateInstance(app.entry().template(), "app.vue");
        list.set("query", " 2 "); click(host, app, "filter");
        check(list.getString("activeQuery").equals("2") && noteTitles(template.instantiate(list).root()).equals(List.of("便签 2")), "example filters the rendered list by title and trims whitespace");
        list.set("query", "不存在的内容"); click(host, app, "filter");
        check(noteTitles(template.instantiate(list).root()).isEmpty(), "example renders no unrelated notes when nothing matches");
        list.set("query", ""); click(host, app, "filter");
        check(noteTitles(template.instantiate(list).root()).equals(List.of("便签 1", "便签 2", "便签 3")), "empty filter restores all notes");
        list.set("query", "便签"); click(host, app, "filter"); list.set("query", "未应用草稿");
        click(host, app, "open"); state(host).set("note", "中".repeat(31) + "😀新正文"); click(host, app, "save"); click(host, app, "back");
        @SuppressWarnings("unchecked") List<Map<String, Object>> notes = (List<Map<String, Object>>) list.get("notes");
        check(state(host) == list && list.getInt("refreshes") == 1 && notes.get(0).get("summary").equals("中".repeat(31)), "return refreshes saved summary without splitting emoji");
        check(list.getString("activeQuery").equals("便签") && list.getString("query").equals("未应用草稿"), "applied filter and unapplied search draft are preserved");
        click(host, app, "open"); click(host, app, "delete"); click(host, app, "back");
        notes = (List<Map<String, Object>>) list.get("notes");
        check(list.getInt("refreshes") == 2 && notes.get(0).get("summary").equals("（暂无正文）") && !storage.get().keys().contains("note_1"), "delete then back immediately refreshes list");
        check(list.getString("query").equals("未应用草稿") && list.getString("activeQuery").equals("便签"), "deletion refresh also preserves filter and draft");
        TextMeasure font = new TextMeasure() {public int width(String text){return text.codePointCount(0,text.length())*6;} public int lineHeight(){return 9;} public String truncate(String text,int width){return text.substring(0,Math.min(text.length(),Math.max(0,width/6)));}};
        for (int[] size : new int[][]{{120,176},{226,158}}) {
            var layout = LayoutEngine.layout(new TemplateInstance(app.entry().template(), "app.vue").instantiate(list).root(), app.entry().stylesheet(), list, size[0], size[1], font, ImageSizes.NONE);
            check(layout.h <= size[1] && layout.w <= size[0], "example root fits phone and tablet");
            LayoutNode query = layoutNode(layout, "query");
            check(query != null && query.w >= 32 && query.h > 0 && com.november.mcphone.core.script.client.render.HitTest.pick(layout, 0, 0, size[0], size[1], query.x + query.w / 2.0, query.y + query.h / 2.0, font) == query, "example search input has usable width and is reachable on phone and tablet");
            LayoutNode filter = layoutNode(layout, "filter");
            check(filter != null && filter.w > 0 && filter.h > 0 && com.november.mcphone.core.script.client.render.HitTest.pick(layout, 0, 0, size[0], size[1], filter.x + filter.w / 2.0, filter.y + filter.h / 2.0, font) == filter, "example filter button is reachable on phone and tablet");
        }
        host.onClose(); ScriptPage reopened = new ScriptPage(app); set(reopened, "storage", storage); invoke(reopened, "openPage", new Class<?>[]{String.class}, "");
        check(state(reopened).getInt("refreshes") == 0 && state(reopened).getString("query").isEmpty(), "close/reopen starts a fresh page instead of resuming"); reopened.onClose();
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("mcphone-resume-").toAbsolutePath().normalize();
        // Headless assertion classpaths omit Minecraft's vanilla language asset. Keep this fixture in test output only.
        Path classes = Path.of(ClientResumeTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        Path language = classes.resolve("assets/minecraft/lang/en_us.json");
        boolean createdLanguage = !Files.exists(language);
        if (createdLanguage) {
            Files.createDirectories(language.getParent());
            Files.writeString(language, "{\"mcphone.script.client_function.failed\":\"Page action failed.\"}");
        }
        try {lifecycle(); transactions(root); requests(); host(); sample(root);}
        finally {
            if (createdLanguage) Files.deleteIfExists(language);
            try (var paths = Files.walk(root)){for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);}
        }
        System.out.println("ClientResumeTest: " + checks + " checks passed");
    }
}
