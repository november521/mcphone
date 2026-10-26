package com.november.mcphone.core.script.server.store;

import com.november.mcphone.core.script.layout.Node;
import com.november.mcphone.core.script.layout.UiState;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Real files and actual SFC click transactions; no Minecraft connection or server identity. */
public final class ClientStorageTest {
    private static int checks;
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final String APP = "test:storage";
    private static final String INITIAL = "state = { name: 'Alice', result: '', count: 0 }\n";

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static final class Fixture {
        final UiState state;
        final TemplateInstance template;
        final Supplier<LocalStore> storage;
        Fixture(String script, String action, Supplier<LocalStore> storage) {
            SfcCompiler.Page page = SfcCompiler.compilePage("storage.vue", "<template>\n<column>\n<button @click=\""
                    + action.replace("&", "&amp;").replace("\"", "&quot;") + "\">run</button>\n<text>{{ result }}</text>\n</column>\n</template>\n<script>\n"
                    + script + "\n</script>\n");
            state = UiState.of(page.template().initialState());
            template = new TemplateInstance(page.template(), "storage.vue");
            this.storage = storage;
        }
        Statements.Outcome fire() {
            TemplateInstance.Tree tree = template.instantiate(state);
            return tree.click(tree.root().children().get(0), state, 0, storage);
        }
        String echo() { return template.instantiate(state).root().children().get(1).str("text", ""); }
    }

    private static Supplier<LocalStore> provider(Path root, UUID account, String app) {
        return LocalStore.clientProvider(root, account, app, () -> account);
    }

    private static Fixture fixture(Path root, String body) {
        return new Fixture(INITIAL + "function run() { " + body + " }", "run()", provider(root, ACCOUNT, APP));
    }

    private static void quota(Runnable operation, String message) {
        try { operation.run(); check(false, message); }
        catch (StoreQuota.QuotaExceeded expected) { check(true, message); }
    }

    private static void rollback(Path root, String tail) throws Exception {
        Path file = LocalStore.clientPath(root, ACCOUNT, APP);
        byte[] before = Files.readAllBytes(file);
        Fixture fixture = fixture(root, "ctx.store.setString('name', 'new'); state.count = 9; " + tail);
        Map<String, Object> prior = Map.copyOf(fixture.state.values());
        Statements.Outcome outcome = fixture.fire();
        check(!outcome.applied() && !outcome.warnings().isEmpty() && outcome.calls().isEmpty(), "failure reported: " + tail);
        check(fixture.state.values().equals(prior) && fixture.state.revision() == 0, "UI rollback: " + tail);
        check(Arrays.equals(before, Files.readAllBytes(file)), "disk rollback: " + tail);
    }

    private static void run(Path root) throws Exception {
        Path file = LocalStore.clientPath(root, ACCOUNT, APP);
        Fixture save = fixture(root, "ctx.store.setString('name', state.name); state.result = 'saved';");
        save.state.set("name", "中文 😀 Alice");
        check(save.fire().applied() && save.echo().equals("saved"), "successful save and UI commit");
        check(Files.isRegularFile(file), "actual local file created");
        Fixture reopened = fixture(root, "state.result = ctx.store.getString('name', 'missing');");
        check(reopened.fire().applied() && reopened.echo().equals("中文 😀 Alice"), "new App instance reads persisted Unicode");
        check(LocalStore.openClient(root, ACCOUNT, APP).getString("name", "").equals("中文 😀 Alice"), "fresh store reload");
        check(LocalStore.clientPath(root, ACCOUNT, "a:b_c").equals(LocalStore.clientPath(root, ACCOUNT, "a:b_c")), "stable App namespace");
        check(!LocalStore.clientPath(root, ACCOUNT, "a:b_c").equals(LocalStore.clientPath(root, ACCOUNT, "a_b:c")), "App ids that collided in legacy sanitization remain separate");
        check(!file.equals(LocalStore.clientPath(root, OTHER, APP)), "account namespaces differ");
        check(!file.equals(LocalStore.clientPath(root, ACCOUNT, "test:other")), "App namespaces differ");
        check(LocalStore.clientPath(root, ACCOUNT, "../../outside").toAbsolutePath().normalize()
                .startsWith(root.resolve(LocalStore.ROOT).toAbsolutePath().normalize()), "App ids cannot traverse directories");
        check(!file.equals(root.resolve(LocalStore.ROOT).resolve(ACCOUNT.toString()).resolve(LocalStore.safe(APP)).resolve("local.json")), "new frontend scope does not reuse legacy server data");
        Fixture otherAccount = new Fixture(INITIAL + "function run() { state.result = ctx.store.getString('name', 'none'); }", "run()", provider(root, OTHER, APP));
        check(otherAccount.fire().applied() && otherAccount.echo().equals("none"), "other account cannot read value");
        Fixture otherApp = new Fixture(INITIAL + "function run() { state.result = ctx.store.getString('name', 'none'); }", "run()", provider(root, ACCOUNT, "test:other"));
        check(otherApp.fire().applied() && otherApp.echo().equals("none"), "other App cannot read value");
        check(!Files.exists(LocalStore.clientPath(root, OTHER, APP)), "read-only fallback creates no file");
        AtomicReference<UUID> active = new AtomicReference<>(ACCOUNT);
        Fixture switched = new Fixture(INITIAL + "function run() { ctx.store.setString('name', 'wrong'); }", "run()",
                LocalStore.clientProvider(root, ACCOUNT, APP, active::get));
        active.set(OTHER);
        check(!switched.fire().applied(), "old page rejects changed login account");
        check(reopened.fire().applied() && reopened.echo().equals("中文 😀 Alice"), "account switch did not alter old data");
        Fixture noAccount = new Fixture(INITIAL + "function run() { state.result = ctx.store.getString('name'); }", "run()",
                LocalStore.clientProvider(root, null, APP, () -> null));
        check(!noAccount.fire().applied(), "no account never falls back to shared anonymous data");
        AtomicInteger opens = new AtomicInteger();
        Fixture lazy = new Fixture(INITIAL + "function run() { state.count++; }", "run()", () -> { opens.incrementAndGet(); throw new AssertionError("unneeded I/O"); });
        check(lazy.fire().applied() && opens.get() == 0, "old state-only functions do no storage I/O");

        Fixture ordered = new Fixture(INITIAL + "function save() { ctx.store.setString('name', 'ordered'); }\n"
                + "function read() { state.result = ctx.store.getString('name'); }", "save(); read()", provider(root, ACCOUNT, APP));
        check(ordered.fire().applied() && ordered.echo().equals("ordered"), "multiple calls share staged writes");
        LocalStore externally = LocalStore.openClient(root, ACCOUNT, APP);
        externally.setString("name", "fresh");
        check(externally.save(), "replace existing file atomically");
        check(reopened.fire().applied() && reopened.echo().equals("fresh"), "each click reloads current file");
        Fixture fullValue = fixture(root, "ctx.store.setString('long', 'x'.repeat(4096)); state.result = 'saved';");
        check(fullValue.fire().applied(), "full 4 KiB string value can be stored");
        Fixture boundedDisplay = fixture(root, "var text = ctx.store.getString('long'); state.count = text.length; state.result = text.slice(0, 64);");
        check(boundedDisplay.fire().applied() && boundedDisplay.state.getInt("count") == 4096 && boundedDisplay.echo().length() == 64,
                "storage quota is independent of existing UI text limit");
        rollback(root, "throw new Error('stop');");
        rollback(root, "state.result = 7;");
        rollback(root, "while(true) {};");
        rollback(root, "try { ctx.store.setString('bad key', 'x'); } catch(e) { state.result = 'wrong'; }");
        rollback(root, "ctx.store.setString('key', 'x'.repeat(4097));");
        rollback(root, "ctx.store.setString('key', '\\u0000'.repeat(3000));");
        rollback(root, "ctx.store.setString('../outside', 'x');");
        rollback(root, "ctx.store.setString('key', 7);");
        rollback(root, "ctx.store.setString('key', '\\ud800');");
        rollback(root, "try { ctx.store.setString('key', 7); } catch(e) { state.result = 'wrong'; }");
        rollback(root, "ctx.store.setString({ toString: function() { return 'key'; } }, 'x');");
        rollback(root, "ctx.store.getString('key', {});");
        rollback(root, "ctx.store.getString('key', 'x'.repeat(4097));");
        rollback(root, "ctx.store.setString('k'.repeat(65), 'x');");
        rollback(root, "ctx.store.getString();");
        rollback(root, "ctx.store.setString('key');");
        rollback(root, "state.result = ctx.store.getClass().getName();");
        rollback(root, "state.result = ctx.store.setString.constructor('return 1')();");
        Fixture laterFailure = new Fixture(INITIAL + "function run() { ctx.store.setString('name', 'new'); state.count = 7; }",
                "run(); count = 'bad'; call('noop')", provider(root, ACCOUNT, APP));
        byte[] saved = Files.readAllBytes(file);
        check(!laterFailure.fire().applied() && laterFailure.state.getInt("count") == 0 && Arrays.equals(saved, Files.readAllBytes(file)), "later legacy statement failure rolls back UI and store");
        Fixture recovery = fixture(root, "if (state.count === 0) { ctx.store.setString('name', 'lost'); throw 'bad'; } ctx.store.setString('name', 'recovered'); state.result = 'ok';");
        check(!recovery.fire().applied(), "first failed save");
        recovery.state.set("count", 1);
        check(recovery.fire().applied() && LocalStore.openClient(root, ACCOUNT, APP).getString("name", "").equals("recovered"), "next click succeeds after rollback");

        Path conflict = LocalStore.clientPath(root, ACCOUNT, "test:io_failure");
        LocalStore loaded = LocalStore.openClient(root, ACCOUNT, "test:io_failure");
        Files.createDirectories(conflict);
        Path sentinel = conflict.resolve("old-data.txt");
        Files.writeString(sentinel, "keep");
        Fixture io = new Fixture(INITIAL + "function run() { ctx.store.setString('name', 'new'); state.count = 9; state.result = 'saved'; }",
                "run(); call('noop')", () -> loaded);
        Statements.Outcome ioResult = io.fire();
        check(!ioResult.applied() && ioResult.calls().isEmpty() && io.state.getInt("count") == 0 && io.echo().isEmpty(), "real atomic replacement failure rolls back UI and pending RPC");
        check(Files.readString(sentinel).equals("keep"), "failed replacement preserves existing target data");
        try (var listing = Files.list(conflict.getParent())) {
            check(listing.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "failed atomic save cleans temporary file");
        }
        for (String invalid : new String[]{"{", "[]", "{\"name\":true}", "{\"name\":{}}", "{\"bad key\":\"x\"}",
                "{\"name\":\"\\ud800\"}", "{\"name\":\"a\",\"name\":\"b\"}", "{'name':'x'}", "{\"name\":\"x\"}junk",
                " ".repeat(StoreQuota.LOCAL_PER_APP * 4 + 1)}) {
            Path broken = LocalStore.clientPath(root, ACCOUNT, "test:corrupt");
            Files.writeString(broken, invalid);
            Fixture bad = new Fixture(INITIAL + "function run() { ctx.store.setString('name', 'erase'); state.result = 'saved'; }", "run()", provider(root, ACCOUNT, "test:corrupt"));
            check(!bad.fire().applied() && bad.echo().isEmpty(), "damaged storage explicitly fails");
            check(Files.readString(broken).equals(invalid), "damaged storage is not overwritten");
        }

        LocalStore quota = LocalStore.openClient(root, ACCOUNT, "test:quota");
        for (int i = 0; i < 4; i++) quota.setString("k" + i, "x".repeat(4000));
        quota.setString("k0", "y".repeat(4000));
        check(quota.getString("k0", "").startsWith("y") && quota.bytes() <= StoreQuota.LOCAL_PER_APP, "replacement charges only final data, not old plus new");
        quota(() -> quota.setString("extra", "x".repeat(1000)), "total quota");
        quota(() -> quota.setString("k0", "x".repeat(4097)), "single-value quota");
        quota(() -> quota.setString("k0", "中".repeat(2048)), "quota counts UTF-8 bytes");
        check(quota.getString("k0", "").startsWith("y"), "failed replacement keeps previous in-memory data");
        LocalStore keys = LocalStore.openClient(root, ACCOUNT, "test:keys");
        for (int i = 0; i < StoreQuota.LOCAL_KEYS; i++) keys.setString("k" + i, "");
        keys.setString("k0", "updated");
        check(keys.keys().size() == 64 && keys.getString("k0", "").equals("updated"), "overwrite allowed at key quota");
        quota(() -> keys.setString("extra", ""), "65th key rejected");
        quota(() -> keys.setBool("extra", true), "legacy bool writer respects same quota");
        check(keys.save() && LocalStore.openClient(root, ACCOUNT, "test:keys").keys().size() == 64, "full key set reloads");
        Fixture facade = fixture(root, "state.result = Object.keys(ctx).join(',') + ':' + Object.keys(ctx.store).sort().join(',');");
        check(facade.fire().applied() && facade.echo().equals("store:getString,setString"), "only the two storage methods are exposed");
        AtomicReference<UUID> retainedAccount = new AtomicReference<>(ACCOUNT);
        Fixture retained = new Fixture(INITIAL + "function run() { var box = JSON.parse.prototype; "
                + "if (state.count === 0) { box.savedStore = ctx.store; box.savedStore.getString('name'); state.count = 1; } "
                + "else if (state.count === 1) { state.result = box.savedStore.getString('name', 'none'); } "
                + "else { state.result = ctx.store.getString('name', 'none'); } }", "run()",
                () -> LocalStore.openClient(root, retainedAccount.get(), APP));
        Statements.Outcome retainedFirst = retained.fire();
        check(retainedFirst.applied(), "retention setup: " + retainedFirst.warnings());
        retainedAccount.set(OTHER);
        check(!retained.fire().applied() && retained.echo().isEmpty(), "previous click's store handle expires instead of exposing another account snapshot");
        retained.state.set("count", 2);
        check(retained.fire().applied() && retained.echo().equals("none"), "current click's handle still uses current account");
        Fixture values = new Fixture(INITIAL + "function run() { if (state.count === 0) { JSON.parse.prototype.saved = state.name; state.count = 1; } "
                + "else { state.result = typeof JSON.parse.prototype.saved; } }", "run()", provider(root, ACCOUNT, APP));
        check(values.fire().applied() && values.fire().applied() && values.echo().equals("undefined"), "native prototype child objects carry no data between calls");
        LocalStore.Transaction expired = new LocalStore.Transaction(provider(root, ACCOUNT, APP));
        check(expired.getString("name", "").equals("recovered"), "transaction snapshot before close");
        expired.close();
        try { expired.getString("name", ""); check(false, "expired read"); }
        catch (IllegalStateException expected) { check(true, "expired transaction cannot expose cached values"); }
        try { expired.commit(); check(false, "expired commit"); }
        catch (IllegalStateException expected) { check(true, "expired transaction cannot commit"); }
        try { new Fixture(INITIAL + "function ctx() {}", "ctx()", provider(root, ACCOUNT, APP)); check(false, "ctx reserved"); }
        catch (SfcError e) { check(e.code() == SfcError.Code.E_SCRIPT_FUNCTION, "host ctx cannot be shadowed by top-level function"); }

        Path sample = Path.of("../../docs/samples/client-functions.vue");
        if (!Files.isRegularFile(sample)) sample = Path.of("docs/samples/client-functions.vue");
        SfcCompiler.App app = SfcCompiler.compile("client-functions.vue", Files.readString(sample, StandardCharsets.UTF_8));
        TemplateInstance template = new TemplateInstance(app.template(), "client-functions.vue");
        UiState first = UiState.of(app.template().initialState());
        first.set("draft", "  Alice  ");
        TemplateInstance.Tree tree = template.instantiate(first);
        check(tree.click(find(tree.root(), "save"), first, 0, provider(root, ACCOUNT, app.manifest().id())).applied(), "real sample save button");
        UiState second = UiState.of(app.template().initialState());
        TemplateInstance.Tree again = template.instantiate(second);
        check(again.click(find(again.root(), "load"), second, 0, provider(root, ACCOUNT, app.manifest().id())).applied()
                && second.getString("draft").equals("Alice") && second.getString("result").equals("已读取保存的名字"), "real sample reads after App state reset");
    }

    private static Node find(Node node, String id) {
        if (id.equals(node.id())) return node;
        for (Node child : node.children()) { Node found = find(child, id); if (found != null) return found; }
        return null;
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("mcphone-client-storage-test-").toAbsolutePath().normalize();
        try { run(root); }
        finally {
            if (!root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())) throw new AssertionError("cleanup outside owned temporary root");
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
        System.out.println("ClientStorageTest: " + checks + " checks passed");
    }
}
