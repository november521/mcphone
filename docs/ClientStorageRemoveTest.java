package com.november.mcphone.core.script.server.store;

import com.november.mcphone.core.script.http.HttpRequests;
import com.november.mcphone.core.script.layout.ImageSizes;
import com.november.mcphone.core.script.layout.LayoutEngine;
import com.november.mcphone.core.script.layout.LayoutNode;
import com.november.mcphone.core.script.layout.Node;
import com.november.mcphone.core.script.layout.TextMeasure;
import com.november.mcphone.core.script.layout.UiState;
import com.november.mcphone.core.script.sfc.PageStates;
import com.november.mcphone.core.script.sfc.SfcCompiler;
import com.november.mcphone.core.script.sfc.Statements;
import com.november.mcphone.core.script.sfc.TemplateInstance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Actual Rhino/SFC transactions and persisted files: deletion must never escape a failed operation. */
public final class ClientStorageRemoveTest {
    private static int checks;
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String INITIAL = "state={result:'',count:0}\n";
    private static final String RECEIVE = "function received(status,body,error){state.result=body;}\n";

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static SfcCompiler.Page page(String script, String action) {
        return SfcCompiler.compilePage("remove.vue", "<template>\n<button @click=\""
                + action.replace("&", "&amp;").replace("\"", "&quot;")
                + "\">run</button>\n</template>\n<script>\n" + script + "\n</script>\n");
    }

    private static Supplier<LocalStore> provider(Path root, String app) {
        return LocalStore.clientProvider(root, ACCOUNT, app, () -> ACCOUNT);
    }

    private static final class Fixture {
        final SfcCompiler.Page page;
        final UiState state;
        final Supplier<LocalStore> storage;
        Fixture(String body, String action, Supplier<LocalStore> storage) {
            page = page(INITIAL + "function run(){" + body + "}\n" + RECEIVE, action);
            state = UiState.page(page.template().initialState());
            this.storage = storage;
        }
        Statements.Outcome fire() { return fire(null); }
        Statements.Outcome fire(HttpRequests requests) {
            var tree = new TemplateInstance(page.template(), "remove.vue").instantiate(state);
            return tree.click(tree.root(), state, 0, storage, requests);
        }
    }

    private static Fixture fixture(Path root, String app, String body) {
        return new Fixture(body, "run()", provider(root, app));
    }

    private static void seed(Path root, String app) {
        LocalStore store = provider(root, app).get();
        store.setString("note", "原始便签 😀");
        store.setString("keep", "untouched");
        check(store.save(), "seed original values");
    }

    private static void persistence(Path root) throws Exception {
        String app = "test:remove"; seed(root, app);
        Fixture f = fixture(root, app, "ctx.store.remove('note');state.result=ctx.store.getString('note','missing');");
        check(f.fire().applied() && f.state.getString("result").equals("missing"), "same operation reads fallback after deletion");
        LocalStore fresh = provider(root, app).get();
        check(!fresh.keys().contains("note") && fresh.getString("keep", "").equals("untouched"), "reopened store loses only selected key");
        check(fixture(root, app, "ctx.store.remove('keep');state.result=typeof ctx.store.remove('keep');").fire().applied(), "repeated delete succeeds");
        Fixture returns = fixture(root, app, "state.result=typeof ctx.store.remove('missing');");
        check(returns.fire().applied() && returns.state.getString("result").equals("undefined"), "remove returns undefined");
        check(Files.readString(LocalStore.clientPath(root, ACCOUNT, app)).equals("{}"), "last key leaves a valid empty JSON file");
        String empty = "test:remove_empty";
        LocalStore store = provider(root, empty).get(); store.setString("note", ""); check(store.save(), "save an empty string key");
        check(fixture(root, empty, "ctx.store.remove('note');").fire().applied() && provider(root, empty).get().keys().isEmpty(), "empty-string keys are actually removed");

        String absent = "test:remove_absent"; Path absentFile = LocalStore.clientPath(root, ACCOUNT, absent);
        check(fixture(root, absent, "ctx.store.remove('missing');").fire().applied() && !Files.exists(absentFile), "missing key creates no data file");
        seed(root, app);
        Path file = LocalStore.clientPath(root, ACCOUNT, app); byte[] before = Files.readAllBytes(file);
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_234_560_000L)); FileTime timestamp = Files.getLastModifiedTime(file);
        check(fixture(root, app, "ctx.store.remove('missing');").fire().applied(), "missing key in existing store succeeds");
        check(Arrays.equals(before, Files.readAllBytes(file)) && Files.getLastModifiedTime(file).equals(timestamp), "no-op deletion does not rewrite an existing file");
        check(fixture(root, app, "ctx.store.setString('note','new');ctx.store.remove('note');state.result=ctx.store.getString('note','gone');").fire().applied(), "write then delete in one operation succeeds");
        Fixture replace = fixture(root, app, "ctx.store.remove('note');ctx.store.setString('note','replacement');state.result=ctx.store.getString('note');");
        check(replace.fire().applied() && replace.state.getString("result").equals("replacement"), "delete then write follows statement order");
    }

    private static void quotas(Path root) {
        String app = "test:remove_keys"; LocalStore full = provider(root, app).get();
        for (int i = 0; i < StoreQuota.LOCAL_KEYS; i++) full.setString("k" + i, "");
        check(full.save(), "seed all 64 slots");
        check(!fixture(root, app, "ctx.store.setString('extra','');").fire().applied(), "65th key remains rejected");
        check(fixture(root, app, "ctx.store.remove('k0');ctx.store.setString('extra','');").fire().applied(), "delete frees a slot within the same transaction");
        LocalStore fresh = provider(root, app).get();
        check(fresh.keys().size() == 64 && !fresh.keys().contains("k0") && fresh.keys().contains("extra"), "freed key slot persists after reopen");
        check(!fixture(root, app, "ctx.store.remove('extra');ctx.store.remove('extra');ctx.store.setString('next','');ctx.store.setString('overflow','');").fire().applied(), "repeated delete cannot free two slots");
        check(provider(root, app).get().keys().contains("extra") && !provider(root, app).get().keys().contains("next"), "quota failure rolls back deletion and replacement");
        app = "test:remove_bytes"; full = provider(root, app).get();
        for (int i = 0; i < 4; i++) full.setString("k" + i, "x".repeat(4000));
        check(full.save(), "seed near byte quota"); long before = full.bytes();
        check(!fixture(root, app, "ctx.store.setString('extra','中'.repeat(1000));").fire().applied(), "extra UTF-8 value exceeds total quota");
        check(fixture(root, app, "ctx.store.remove('k0');ctx.store.setString('extra','中'.repeat(1000));").fire().applied(), "deletion frees UTF-8 byte budget before a later write");
        fresh = provider(root, app).get();
        check(fresh.bytes() < before && fresh.getString("extra", "").equals("中".repeat(1000)), "byte quota and new Unicode value survive reopen");
    }

    private static void rollback(Path root) throws Exception {
        String app = "test:remove_rollback"; seed(root, app);
        Path file = LocalStore.clientPath(root, ACCOUNT, app); byte[] before = Files.readAllBytes(file);
        for (String tail : List.of("throw 'stop';", "state.count='wrong';", "while(true){}", "ctx.store.setString('big','x'.repeat(4097));")) {
            Fixture f = new Fixture("ctx.store.remove('note');state.count=9;ctx.http.request({url:'https://example.invalid/'},'received');" + tail,
                    "run();call('noop')", provider(root, app));
            AtomicInteger sent = new AtomicInteger(); var outcome = f.fire(new HttpRequests(4, batch -> sent.addAndGet(batch.size())));
            check(!outcome.applied() && !outcome.warnings().isEmpty() && outcome.calls().isEmpty(), "failed deletion reports error and discards RPC");
            check(f.state.getInt("count") == 0 && sent.get() == 0 && Arrays.equals(before, Files.readAllBytes(file)), "script/type/budget/quota failure preserves state, HTTP and disk");
        }
        Fixture later = new Fixture("ctx.store.remove('note');state.count=9;", "run();count='wrong';call('noop')", provider(root, app));
        check(!later.fire().applied() && Arrays.equals(before, Files.readAllBytes(file)), "later legacy statement failure rolls back deletion");
        for (String argument : List.of("", "'note','extra'", "''", "'bad key'", "'../outside'", "'中'", "'a'.repeat(65)",
                "1", "true", "null", "undefined", "{}", "[]", "{toString:function(){return 'note';}}")) {
            Fixture f = fixture(root, app, "ctx.store.remove('note');try{ctx.store.remove(" + argument + ");}catch(e){state.count=99;}");
            check(!f.fire().applied() && f.state.getInt("count") == 0, "invalid delete is not catchable: " + argument);
            check(Arrays.equals(before, Files.readAllBytes(file)), "invalid argument preserves earlier staged deletion");
        }
        Fixture surface = fixture(root, app, "state.result=typeof ctx.store.remove.constructor+':'+typeof ctx.store.remove.getClass;");
        check(surface.fire().applied() && surface.state.getString("result").equals("undefined:undefined"), "remove exposes no compiler or Java handles");
        String conflictApp = "test:remove_io"; LocalStore loaded = provider(root, conflictApp).get(); loaded.setString("note", "original");
        Path conflict = LocalStore.clientPath(root, ACCOUNT, conflictApp); Files.createDirectories(conflict); Files.writeString(conflict.resolve("keep.txt"), "keep");
        Fixture io = new Fixture("ctx.store.remove('note');state.count=9;", "run();call('noop')", () -> loaded);
        var failed = io.fire();
        check(!failed.applied() && failed.calls().isEmpty() && io.state.getInt("count") == 0, "actual atomic save failure rolls back UI and RPC");
        check(loaded.getString("note", "").equals("original") && Files.readString(conflict.resolve("keep.txt")).equals("keep"), "save failure preserves provider snapshot and original target");
        try (var files = Files.list(conflict.getParent())) {check(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")), "failed deletion save cleans temporary file");}
        for (String damaged : List.of("{", "[]", "{\"note\":true}", "{\"note\":\"a\",\"note\":\"b\"}")) {
            String broken = "test:remove_damaged"; Path path = LocalStore.clientPath(root, ACCOUNT, broken); Files.writeString(path, damaged);
            check(!fixture(root, broken, "ctx.store.remove('note');").fire().applied() && Files.readString(path).equals(damaged), "deletion cannot overwrite damaged storage");
        }
    }

    private static void isolation(Path root) {
        String app = "test:remove_scope"; seed(root, app);
        LocalStore other = LocalStore.openClient(root, OTHER, app); other.setString("note", "other account"); check(other.save(), "seed other account");
        seed(root, "test:remove_other_app");
        check(fixture(root, app, "ctx.store.remove('note');").fire().applied(), "own key deletion succeeds");
        check(LocalStore.openClient(root, OTHER, app).getString("note", "").equals("other account")
                && provider(root, "test:remove_other_app").get().keys().contains("note"), "deletion leaves other account and App untouched");
        seed(root, app); AtomicReference<UUID> active = new AtomicReference<>(ACCOUNT);
        Fixture oldPage = new Fixture("ctx.store.remove('note');", "run()", LocalStore.clientProvider(root, ACCOUNT, app, active::get));
        active.set(OTHER);
        check(!oldPage.fire().applied() && provider(root, app).get().keys().contains("note"), "account switch blocks old page from deleting");
        Fixture anonymous = new Fixture("ctx.store.remove('note');", "run()", LocalStore.clientProvider(root, null, app, () -> null));
        check(!anonymous.fire().applied(), "missing account cannot delete anonymous/shared data");
        LocalStore.Transaction expired = new LocalStore.Transaction(provider(root, app)); expired.getString("note", ""); expired.close();
        try {expired.remove("note"); check(false, "expired deletion rejected");}
        catch (IllegalStateException expected) {check(provider(root, app).get().keys().contains("note"), "expired transaction cannot remove cached values");}
        String key = "a".repeat(64);
        check(fixture(root, app, "ctx.store.setString('" + key + "','ok');ctx.store.remove('" + key + "');").fire().applied(), "maximum-length valid key can be deleted");
    }

    private static void hooks(Path root) {
        for (boolean fail : List.of(false, true)) {
            String app = "test:remove_init_" + fail; seed(root, app);
            var template = page(INITIAL + "function onInit(){ctx.store.remove('note');state.count=9;" + (fail ? "throw 'stop';" : "") + "}", "count++").template();
            var opened = new PageStates().open("", template, "remove.vue", provider(root, app));
            check(opened.warnings().isEmpty() != fail && opened.state().getInt("count") == (fail ? 0 : 9), "initialization state and deletion commit together");
            check(provider(root, app).get().keys().contains("note") == fail, "initialization rollback preserves deleted key");
            app = "test:remove_callback_" + fail; seed(root, app);
            template = page(INITIAL + "function received(s,b,e){ctx.store.remove('note');state.count=9;" + (fail ? "throw 'stop';" : "") + "}", "count++").template();
            UiState state = UiState.page(template.initialState());
            var request = new HttpRequests.Request("https://example.invalid/", "GET", Map.of(), "", 1000, "received");
            var warnings = PageStates.respond(template.script(), request, new HttpRequests.Result(200, "ok", ""), state, "remove.vue", provider(root, app), null);
            check(warnings.isEmpty() != fail && state.getInt("count") == (fail ? 0 : 9), "HTTP callback state and deletion commit together");
            check(provider(root, app).get().keys().contains("note") == fail, "failed callback preserves deleted key");
        }
    }

    private static Node find(Node node, String id) {
        if (id.equals(node.id())) return node;
        for (Node child : node.children()) {Node found = find(child, id); if (found != null) return found;}
        return null;
    }

    private static LayoutNode find(LayoutNode node, String id) {
        if (id.equals(node.node.id())) return node;
        for (LayoutNode child : node.children) {LayoutNode found = find(child, id); if (found != null) return found;}
        return null;
    }

    private static void sample(Path root) throws Exception {
        var app = SfcCompiler.compile("textarea.vue", Files.readString(Path.of("../../docs/samples/textarea.vue")));
        check(app.manifest().version().equals("1.1.0"), "installable notes example is updated");
        var storage = provider(root, app.manifest().id()); var pages = new PageStates();
        UiState state = pages.open("", app.template(), "textarea.vue", storage).state();
        var template = new TemplateInstance(app.template(), "textarea.vue"); state.set("note", "第一段\n第二段😀");
        var tree = template.instantiate(state);
        check(tree.click(find(tree.root(), "save"), state, 0, storage).applied(), "actual sample saves a Unicode note");
        tree = template.instantiate(state); check(tree.click(find(tree.root(), "clear"), state, 0, storage).applied(), "actual clear-editor action succeeds");
        pages.clear(); state = pages.open("", app.template(), "textarea.vue", storage).state();
        check(state.getString("note").equals("第一段\n第二段😀"), "clearing editor still preserves the saved note");
        tree = template.instantiate(state); check(tree.click(find(tree.root(), "delete"), state, 0, storage).applied(), "actual delete-saved-note action commits");
        check(state.getString("note").isEmpty() && !storage.get().keys().contains("note"), "sample clears editor and removes saved key together");
        pages.clear(); state = pages.open("", app.template(), "textarea.vue", storage).state();
        check(state.getString("note").isEmpty() && storage.get().keys().isEmpty() && storage.get().bytes() == 0, "reopening cannot restore deleted note and storage quota is released");
        TextMeasure font = new TextMeasure() {
            public int width(String text) {return text.codePointCount(0, text.length()) * 6;}
            public int lineHeight() {return 9;}
            public String truncate(String text, int width) {return text.substring(0, Math.min(text.length(), Math.max(0, width / 6)));}
        };
        for (int[] size : new int[][]{{120, 176}, {226, 158}}) {
            var layout = LayoutEngine.layout(template.instantiate(state).root(), app.stylesheet(), state, size[0], size[1], font, ImageSizes.NONE);
            LayoutNode button = find(layout, "delete"), editor = find(layout, "note");
            check(button != null && button.y + button.h <= size[1] && button.w <= size[0], "delete button fits phone/tablet content area");
            check(editor != null && editor.h >= 18, "note editor retains usable height");
        }
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("mcphone-storage-remove-").toAbsolutePath().normalize();
        try {persistence(root); quotas(root); rollback(root); isolation(root); hooks(root); sample(root);}
        finally {try (var paths = Files.walk(root)) {for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);}}
        System.out.println("ClientStorageRemoveTest: " + checks + " checks passed");
    }
}
