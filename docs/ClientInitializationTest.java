package com.november.mcphone.core.script.sfc;

import com.november.mcphone.core.script.layout.Node;
import com.november.mcphone.core.script.layout.UiState;
import com.november.mcphone.core.script.server.store.LocalStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Real page-state lifecycle, hardened functions and file transactions, without starting Minecraft. */
public final class ClientInitializationTest {
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String APP = "test:initialization";
    private static final String INITIAL = "state = { draft: 'default', count: 0 }\n";
    private static int checks;

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static CompiledTemplate page(String script) {
        return SfcCompiler.compilePage("init.vue", "<template>\n<column><text>{{ draft }}</text></column>\n</template>\n<script>\n"
                + script + "\n</script>").template();
    }

    private static Supplier<LocalStore> provider(Path root, UUID account, String app) {
        return LocalStore.clientProvider(root, account, app, () -> account);
    }

    private static void lifecycle() {
        CompiledTemplate template = page(INITIAL + "function onInit() { prepare(); }\n"
                + "function prepare() { state.count++; state.draft = 'initialized'; }");
        check(template.initialState().get("count").equals(0), "compilation does not execute onInit");
        TemplateInstance renderer = new TemplateInstance(template, "init.vue");
        UiState literal = UiState.of(template.initialState());
        for (int i = 0; i < 5; i++) renderer.instantiate(literal);
        check(literal.getInt("count") == 0, "rendering alone never initializes a page");
        AtomicInteger reads = new AtomicInteger();
        Supplier<LocalStore> unused = () -> { reads.incrementAndGet(); throw new AssertionError("unexpected I/O"); };
        PageStates states = new PageStates();
        PageStates.Opened first = states.open("", template, "init.vue", unused);
        check(first.warnings().isEmpty() && first.state().getInt("count") == 1, "first entry runs hook and its helper");
        check(renderer.instantiate(first.state()).root().children().get(0).str("text", "").equals("initialized"), "first rendered tree sees initialized values");
        first.state().set("draft", "edited");
        int revision = first.state().revision();
        for (int i = 0; i < 5; i++) renderer.instantiate(first.state());
        check(first.state().revision() == revision, "relayout cannot re-run initialization");
        PageStates.Opened child = states.open("details", template, "details.vue", unused);
        check(child.state() != first.state() && child.state().getInt("count") == 1, "new child page initializes its own state");
        child.state().set("draft", "child edit");
        PageStates.Opened returned = states.open("", template, "init.vue", unused);
        check(returned.state() == first.state() && returned.state().getString("draft").equals("edited"), "returning preserves main-page edits");
        check(returned.state().revision() == revision && returned.warnings().isEmpty(), "returning neither retries nor emits warnings");
        check(states.open("details", template, "details.vue", unused).state().getString("draft").equals("child edit"), "child state also remains available");
        states.clear();
        PageStates.Opened reopened = states.open("", template, "init.vue", unused);
        check(reopened.state() != first.state() && reopened.state().getInt("count") == 1
                && reopened.state().getString("draft").equals("initialized"), "App close releases state; reopening initializes again");
        check(states.open("details", template, "details.vue", unused).state() != child.state(), "App close also releases child pages");
        check(reads.get() == 0, "state-only hooks perform no storage I/O");

        for (CompiledTemplate legacy : new CompiledTemplate[]{page(INITIAL), page(INITIAL + "function other() { throw 'unused'; }"),
                TemplateCompiler.compile("<text>{{ draft }}</text>", Map.of("draft", "default"))}) {
            PageStates.Opened old = new PageStates().open("", legacy, "legacy.vue", unused);
            check(old.warnings().isEmpty() && old.state().getString("draft").equals("default"), "pages without hook preserve defaults and execute no functions");
        }
        try { page(INITIAL + "function onInit(value) {}"); check(false, "hook parameters rejected"); }
        catch (SfcError expected) { check(expected.code() == SfcError.Code.E_SCRIPT_FUNCTION, "onInit uses existing zero-argument grammar"); }
        try { page(INITIAL + "function onInit() {} function onInit() {}"); check(false, "duplicate hook rejected"); }
        catch (SfcError expected) { check(expected.code() == SfcError.Code.E_SCRIPT_FUNCTION, "duplicate hook follows existing function rule"); }
        try { page(INITIAL + "function onInit() { state.count = " + "!".repeat(5000) + "true; }"); check(false, "deep hook rejected"); }
        catch (SfcError expected) { check(expected.code() == SfcError.Code.E_SCRIPT_FUNCTION, "deep hook reports compilation error instead of overflowing the host"); }
        check(new PageStates().open("", template, "after-depth.vue", unused).state().getInt("count") == 1, "normal initialization still works after rejected compilation");
    }

    private static void rollback(Path root, String tail) throws Exception {
        Path file = LocalStore.clientPath(root, ACCOUNT, APP);
        byte[] saved = Files.readAllBytes(file);
        CompiledTemplate template = page(INITIAL + "function onInit() { ctx.store.setString('draft', 'lost'); state.count = 7; " + tail + " }");
        PageStates states = new PageStates();
        AtomicInteger reads = new AtomicInteger();
        Supplier<LocalStore> storage = () -> { reads.incrementAndGet(); return LocalStore.openClient(root, ACCOUNT, APP); };
        PageStates.Opened failed = states.open("", template, "test:init.vue", storage);
        check(!failed.warnings().isEmpty() && failed.warnings().get(0).contains("test:init.vue onInit()"), "failure identifies hook and page: " + tail);
        check(failed.state().values().equals(template.initialState()) && failed.state().revision() == 0, "failure preserves all defaults: " + tail);
        check(Arrays.equals(saved, Files.readAllBytes(file)), "failure preserves saved bytes: " + tail);
        failed.state().set("draft", "editable");
        PageStates.Opened returned = states.open("", template, "test:init.vue", storage);
        check(reads.get() == 1 && returned.warnings().isEmpty() && returned.state().getString("draft").equals("editable"), "failed hook is attempted once and leaves usable page");
    }

    private static void files(Path root) throws Exception {
        LocalStore existing = LocalStore.openClient(root, ACCOUNT, APP);
        existing.setString("draft", "中文🦎");
        check(existing.save(), "seed actual stored file");
        CompiledTemplate restore = page(INITIAL + "function onInit() { state.draft = ctx.store.getString('draft', 'missing'); state.count++; }");
        Supplier<LocalStore> storage = provider(root, ACCOUNT, APP);
        PageStates states = new PageStates();
        PageStates.Opened first = states.open("", restore, "restore.vue", storage);
        check(first.warnings().isEmpty() && first.state().getString("draft").equals("中文🦎"), "automatic initialization restores Unicode from real file");
        check(first.state().getInt("count") == 1, "restoration hook runs once");
        states.clear();
        check(states.open("", restore, "restore.vue", storage).state().getString("draft").equals("中文🦎"), "reopen reads persisted data with fresh state");
        check(new PageStates().open("", restore, "other.vue", provider(root, OTHER, APP)).state().getString("draft").equals("missing"), "another account cannot read saved data");
        check(new PageStates().open("", restore, "other.vue", provider(root, ACCOUNT, "test:other")).state().getString("draft").equals("missing"), "another App cannot read saved data");
        CompiledTemplate writes = page(INITIAL + "function onInit() { prepare(); state.draft = ctx.store.getString('initialized'); } "
                + "function prepare() { ctx.store.setString('initialized', 'ready'); }");
        check(new PageStates().open("", writes, "writes.vue", storage).state().getString("draft").equals("ready"), "hook and helper share staged writes");
        check(LocalStore.openClient(root, ACCOUNT, APP).getString("initialized", "").equals("ready"), "successful initialization commits staged data");

        for (String tail : new String[]{"throw new Error('stop');", "state.draft = 7;", "while (true) {}", "state.draft = 'x'.repeat(4097);",
                "state.added = true;", "try { ctx.store.setString('bad key', 'x'); } catch(e) {}",
                "ctx.store.setString('draft', 'x'.repeat(4097));", "state.draft = ctx.store.getClass().getName();"}) rollback(root, tail);

        CompiledTemplate missingProvider = page(INITIAL + "function onInit() { state.draft = ctx.store.getString('draft'); }");
        check(!new PageStates().open("", missingProvider, "missing.vue", null).warnings().isEmpty(), "missing storage reports failure with default state");
        AtomicReference<UUID> active = new AtomicReference<>(ACCOUNT);
        Supplier<LocalStore> captured = LocalStore.clientProvider(root, ACCOUNT, APP, active::get);
        active.set(OTHER);
        check(!new PageStates().open("", restore, "switched.vue", captured).warnings().isEmpty(), "account change rejects the captured page provider");

        Path damaged = LocalStore.clientPath(root, ACCOUNT, "test:damaged_init");
        Files.writeString(damaged, "{");
        PageStates.Opened corrupt = new PageStates().open("", restore, "corrupt.vue", provider(root, ACCOUNT, "test:damaged_init"));
        check(!corrupt.warnings().isEmpty() && Files.readString(damaged).equals("{"), "corrupt storage is reported and kept intact");
        check(corrupt.state().getString("draft").equals("default"), "corrupt storage keeps default page values");

        String ioApp = "test:init_io_failure";
        LocalStore loaded = LocalStore.openClient(root, ACCOUNT, ioApp);
        Path conflict = LocalStore.clientPath(root, ACCOUNT, ioApp);
        Files.createDirectories(conflict);
        Path sentinel = conflict.resolve("keep.txt");
        Files.writeString(sentinel, "old");
        PageStates.Opened io = new PageStates().open("", writes, "io.vue", () -> loaded);
        check(!io.warnings().isEmpty() && io.state().getString("draft").equals("default") && io.state().revision() == 0, "real atomic replacement failure rolls back initialized state");
        check(Files.readString(sentinel).equals("old"), "failed initialization preserves existing target data");
        try (var entries = Files.list(conflict.getParent())) {
            check(entries.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "failed initialization cleans temporary file");
        }

        PageStates retry = new PageStates();
        AtomicInteger attempts = new AtomicInteger();
        Supplier<LocalStore> temporaryFailure = () -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("unavailable");
            return LocalStore.openClient(root, ACCOUNT, APP);
        };
        check(!retry.open("", restore, "retry.vue", temporaryFailure).warnings().isEmpty(), "initial failed attempt");
        retry.clear();
        check(retry.open("", restore, "retry.vue", temporaryFailure).state().getString("draft").equals("中文🦎") && attempts.get() == 2, "reopening can retry after a transient failure");
    }

    private static Node find(Node node, String id) {
        if (id.equals(node.id())) return node;
        for (Node child : node.children()) { Node found = find(child, id); if (found != null) return found; }
        return null;
    }

    private static void sample(Path root) throws Exception {
        Path source = Path.of("../../docs/samples/client-functions.vue");
        if (!Files.isRegularFile(source)) source = Path.of("docs/samples/client-functions.vue");
        SfcCompiler.App app = SfcCompiler.compile("client-functions.vue", Files.readString(source, StandardCharsets.UTF_8));
        Supplier<LocalStore> storage = provider(root, ACCOUNT, app.manifest().id());
        PageStates states = new PageStates();
        PageStates.Opened first = states.open("", app.template(), "client-functions.vue", storage);
        check(first.warnings().isEmpty() && first.state().getString("draft").isEmpty(), "sample without saved name opens with empty input");
        check(first.state().getString("result").equals("输入名字后点击检查"), "sample keeps welcome prompt when no saved name");
        TemplateInstance renderer = new TemplateInstance(app.template(), "client-functions.vue");
        first.state().set("draft", "  Alice  ");
        TemplateInstance.Tree tree = renderer.instantiate(first.state());
        check(tree.click(find(tree.root(), "save"), first.state(), 0, storage).applied(), "real sample save button still works");
        states.clear();
        PageStates.Opened reopened = states.open("", app.template(), "client-functions.vue", storage);
        check(reopened.state().getString("draft").equals("Alice") && reopened.state().getString("result").equals("已恢复保存的名字"), "sample automatically restores name after App close/reopen");
        check(renderer.instantiate(reopened.state()).root().children().get(1).str("bind", "").equals("draft"), "sample input binds to restored state");
        reopened.state().set("draft", "unsaved edit");
        check(states.open("", app.template(), "client-functions.vue", storage).state().getString("draft").equals("unsaved edit"), "revisiting sample preserves unsaved edits");
        LocalStore longName = LocalStore.openClient(root, ACCOUNT, app.manifest().id());
        longName.setString("name", "x".repeat(4096));
        check(longName.save(), "prepare a maximum-size stored string");
        states.clear();
        PageStates.Opened bounded = states.open("", app.template(), "client-functions.vue", storage);
        check(bounded.warnings().isEmpty() && bounded.state().getString("draft").length() == 32, "sample bounds stored value to input limit before display");
    }

    public static void main(String[] args) throws Exception {
        lifecycle();
        Path root = Files.createTempDirectory("mcphone-on-init-test-").toAbsolutePath().normalize();
        try { files(root); sample(root); }
        finally {
            if (!root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())) throw new AssertionError("cleanup outside owned temporary root");
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("ClientInitializationTest: " + checks + " checks passed");
    }
}
