package com.november.mcphone.core.script.http;

import com.november.mcphone.core.script.layout.Node;
import com.november.mcphone.core.script.layout.UiState;
import com.november.mcphone.core.script.server.store.LocalStore;
import com.november.mcphone.core.script.sfc.PageStates;
import com.november.mcphone.core.script.sfc.SfcCompiler;
import com.november.mcphone.core.script.sfc.Statements;
import com.november.mcphone.core.script.sfc.TemplateInstance;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Cancellation through actual SFC transactions, deterministic races and the real loopback transport. */
public final class ClientHttpCancelTest {
    private static int checks;
    private static final String INITIAL = "state={id:0,other:0,count:0,result:'',loading:false}\n";
    private static final String REQUEST = "ctx.http.request({url:'https://example.invalid/'},'received')";
    private static final String RECEIVE = "function received(s,b,e){state.count++;state.result=b;state.loading=false;state.id=0;}\n";
    private static final String SEND = "function send(){state.id=" + REQUEST + ";state.loading=true;}\n";
    private static final String STOP = "function stop(){ctx.http.cancel(state.id);state.id=0;state.loading=false;}\n";

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static SfcCompiler.Page page(String script, String action) {
        return SfcCompiler.compilePage("cancel.vue", "<template>\n<button @click=\"" + action
                + "\">run</button>\n</template>\n<script>\n" + script + "\n</script>\n");
    }

    private static final class Fixture implements AutoCloseable {
        final String script;
        final SfcCompiler.Page page;
        final UiState state;
        final HttpSession session;
        final List<CompletableFuture<HttpRequests.Result>> replies = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        Supplier<LocalStore> storage;

        Fixture(String functions) { this(functions, new AtomicBoolean(true)); }
        Fixture(String functions, AtomicBoolean active) {
            script = INITIAL + SEND + STOP + RECEIVE + functions;
            page = page(script, "send()");
            state = UiState.page(page.template().initialState());
            session = new HttpSession(active::get, request -> {
                var reply = new CompletableFuture<HttpRequests.Result>(); replies.add(reply); return reply;
            });
        }

        HttpRequests batch() {
            return new HttpRequests(session, requests -> {
                for (var request : requests) session.submit(request, result -> warnings.addAll(PageStates.respond(
                        page.template().script(), request, result, state, "cancel.vue", storage, batch())));
            });
        }

        Statements.Outcome fire(String action) {
            var compiled = page(script, action);
            var tree = new TemplateInstance(compiled.template(), "cancel.vue").instantiate(state);
            return tree.click(tree.root(), state, 0, storage, batch());
        }

        @Override public void close() { session.close(); }
    }

    private static void lifecycle() {
        try (Fixture f = new Fixture("")) {
            check(f.fire("send()").applied() && f.state.getInt("id") > 0, "request returns a state-safe positive integer ID");
            int old = f.state.getInt("id");
            check(f.fire("stop()").applied() && f.replies.get(0).isCancelled(), "explicit cancel reaches local transport");
            check(f.session.available() == 4 && f.state.get("loading").equals(false), "cancellation releases App slot and commits waiting UI together");
            check(f.fire("stop()").applied(), "repeated cancel of initial zero is harmless");
            check(f.fire("send()").applied() && f.state.getInt("id") > old, "resending uses a fresh ID");
            check(!f.replies.get(0).complete(new HttpRequests.Result(200, "old", "")), "cancelled transport cannot complete later");
            f.replies.get(1).complete(new HttpRequests.Result(200, "new", "")); f.session.poll();
            check(f.warnings.isEmpty() && f.state.getInt("count") == 1 && f.state.getString("result").equals("new"), "only new result delivered");
            f.state.set("id", old); check(f.fire("stop()").applied(), "completed or unknown positive ID is a no-op");
        }
        try (Fixture f = new Fixture("")) {
            check(f.fire("send()").applied(), "queued completion setup");
            f.replies.get(0).complete(new HttpRequests.Result(200, "queued", ""));
            check(f.fire("stop()").applied(), "cancel can remove an already received but undelivered reply");
            f.session.poll(); check(f.state.getInt("count") == 0 && f.state.getString("result").isEmpty(), "queued reply suppressed");
        }
        try (Fixture f = new Fixture("function discard(){var id=" + REQUEST + ";ctx.http.cancel(id);ctx.http.cancel(id);}")) {
            check(f.fire("discard()").applied() && f.replies.isEmpty(), "request then cancel in one transaction never sends");
        }
        try (Fixture a = new Fixture(""); Fixture b = new Fixture("")) {
            check(a.fire("send()").applied() && b.fire("send()").applied(), "two App sessions start independently");
            int own = b.state.getInt("id"); b.state.set("id", a.state.getInt("id"));
            check(b.fire("stop()").applied() && !a.replies.get(0).isCancelled() && !b.replies.get(0).isCancelled(), "foreign App ID cannot cancel either request");
            b.state.set("id", own); check(b.fire("stop()").applied() && b.replies.get(0).isCancelled(), "own request remains cancellable");
            int old = a.state.getInt("id"); a.close();
            try (Fixture reopened = new Fixture("")) {
                check(reopened.fire("send()").applied() && reopened.state.getInt("id") != old, "reopened App never reuses old ID");
            }
        }
        var active = new AtomicBoolean(true);
        try (Fixture f = new Fixture("", active)) {
            check(f.fire("send()").applied(), "account switch setup");
            active.set(false); f.session.poll();
            check(f.replies.get(0).isCancelled() && f.session.available() == 0, "account switch still closes all requests");
        }
    }

    private static void atomic(Path root) throws Exception {
        for (String tail : List.of("throw 'stop';", "state.count='wrong';", "while(true){}")) {
            try (Fixture f = new Fixture("function bad(){ctx.http.cancel(state.id);state.loading=false;" + tail + "}")) {
                check(f.fire("send()").applied(), "rollback setup"); int id = f.state.getInt("id");
                check(!f.fire("bad()").applied(), "failed cancel action rejected");
                check(!f.replies.get(0).isCancelled() && f.state.getInt("id") == id && f.state.get("loading").equals(true), "script/type/budget failure leaves original request and state intact");
                f.replies.get(0).complete(new HttpRequests.Result(200, "kept", "")); f.session.poll();
                check(f.state.getString("result").equals("kept"), "rolled-back cancellation still receives original result");
            }
        }
        try (Fixture f = new Fixture("")) {
            check(f.fire("send()").applied(), "legacy statement rollback setup");
            check(!f.fire("stop(); count='wrong'").applied() && !f.replies.get(0).isCancelled(), "later legacy statement rolls back cancellation");
        }
        UUID account = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Path file = LocalStore.clientPath(root, account, "test:cancel");
        LocalStore loaded = LocalStore.openClient(root, account, "test:cancel");
        Files.createDirectories(file); Files.writeString(file.resolve("keep.txt"), "keep");
        try (Fixture f = new Fixture("function bad(){ctx.http.cancel(state.id);ctx.store.setString('x','new');state.loading=false;}")) {
            f.storage = () -> loaded;
            check(f.fire("send()").applied(), "disk failure setup");
            check(!f.fire("bad()").applied() && !f.replies.get(0).isCancelled() && f.state.get("loading").equals(true), "failed disk commit leaves transport and UI intact");
            check(Files.readString(file.resolve("keep.txt")).equals("keep"), "failed cancel transaction preserves original disk data");
        }
        for (String value : List.of("-1", "1.5", "2147483648", "NaN", "Infinity", "'1'", "null", "{}", "[]", "true", "", "1,2")) {
            try (Fixture f = new Fixture("function bad(){state.count=9;try{ctx.http.cancel(" + value + ");}catch(e){state.count=99;}}")) {
                check(!f.fire("bad()").applied() && f.state.getInt("count") == 0, "invalid ID aborts entire transaction: " + value);
            }
        }
        try (Fixture f = new Fixture("function inspect(){state.result=typeof ctx.http.cancel.getClass+':'+typeof ctx.http.cancel.constructor;}")) {
            check(f.fire("inspect()").applied() && f.state.getString("result").equals("undefined:undefined"), "cancel exposes no Java or compiler handles");
        }
    }

    private static void capacityAndPoll() {
        try (Fixture f = new Fixture("function replace(){ctx.http.cancel(state.id);ctx.http.cancel(state.id);state.id=" + REQUEST + ";}")) {
            for (int i = 0; i < 4; i++) check(f.fire("send()").applied(), "fill App pending slots");
            check(f.session.available() == 0 && !f.fire("send()").applied(), "fifth outstanding request rejected");
            int old = f.state.getInt("id");
            check(f.fire("replace()").applied() && f.state.getInt("id") != old && f.replies.get(3).isCancelled(), "cancel then resend succeeds even when all slots were occupied");
            check(f.session.available() == 0 && f.replies.size() == 5, "repeated cancel releases exactly one App slot");
            check(!f.fire("replace();send()").applied() && !f.replies.get(4).isCancelled(), "cancel cannot create extra capacity or partially commit");
        }
        for (boolean fail : List.of(false, true)) {
            var futures = new ArrayList<CompletableFuture<HttpRequests.Result>>();
            var callbacks = new java.util.concurrent.atomic.AtomicInteger();
            try (HttpSession session = new HttpSession(() -> true, ignored -> {var next = new CompletableFuture<HttpRequests.Result>();futures.add(next);return next;})) {
                String source = INITIAL + "function received(s,b,e){state.count++;ctx.http.cancel(state.other);" + (fail ? "throw 'rollback';" : "") + "}";
                var page = page(source, "count++"); UiState state = UiState.page(page.template().initialState());
                try (HttpRequests requests = new HttpRequests(session, batch -> {
                    for (var request : batch) session.submit(request, result -> {
                        callbacks.incrementAndGet();
                        try (HttpRequests cancel = new HttpRequests(session, ignored -> {})) {
                            PageStates.respond(page.template().script(), request, result, state, "cancel.vue", null, cancel);
                        }
                    });
                })) {
                    var request = new HttpRequests.Request("https://example.invalid/", "GET", Map.of(), "", 1000, "received");
                    requests.add(request); state.set("other", requests.enqueue(request)); requests.commit();
                }
                futures.forEach(future -> future.complete(new HttpRequests.Result(200, "", ""))); session.poll();
                check(state.getInt("count") == (fail ? 0 : 1), "successful callback suppresses next ready callback; failed callback rolls back cancellation");
                check(callbacks.get() == (fail ? 2 : 1), "failed callback leaves the next ready callback eligible for delivery");
                check(session.available() == 4, "poll snapshot releases all completed/cancelled flights exactly once");
            }
        }
    }

    private static Node byId(Node node, String id) {
        if (id.equals(node.id())) return node;
        for (Node child : node.children()) { Node found = byId(child, id); if (found != null) return found; }
        return null;
    }

    private static void initialization() {
        for (boolean fail : List.of(false, true)) {
            try (Fixture f = new Fixture("")) {
                check(f.fire("send()").applied(), "initialization cancel setup");
                String script = INITIAL + RECEIVE + "function onInit(){ctx.http.cancel(" + f.state.getInt("id")
                        + ");state.count=1;" + (fail ? "throw 'rollback';" : "") + "}";
                var template = page(script, "count++").template();
                var opened = new PageStates().open("detail", template, "detail.vue", null, ignored -> f.batch());
                check(f.replies.get(0).isCancelled() != fail && opened.warnings().isEmpty() != fail,
                        "initialization commits cancellation only on success");
            }
        }
    }

    private static void idLimit() throws Exception {
        var field = HttpRequests.class.getDeclaredField("NEXT_ID"); field.setAccessible(true);
        var counter = (java.util.concurrent.atomic.AtomicInteger) field.get(null); int saved = counter.get();
        try (Fixture f = new Fixture("")) {
            counter.set(Integer.MAX_VALUE - 1);
            check(f.fire("send()").applied() && f.state.getInt("id") == Integer.MAX_VALUE - 1, "last allocatable ID stays in state integer range");
            check(!f.fire("send()").applied() && f.replies.size() == 1, "ID exhaustion rejects atomically instead of wrapping or reusing");
        } finally {counter.set(saved);}
    }

    private static void sample() throws Exception {
        Path path = Path.of("../../docs/samples/http-json.vue");
        if (!Files.isRegularFile(path)) path = Path.of("docs/samples/http-json.vue");
        var sample = SfcCompiler.compile("http-json.vue", Files.readString(path));
        UiState state = UiState.page(sample.template().initialState());
        var replies = new ArrayList<CompletableFuture<HttpRequests.Result>>();
        try (HttpSession session = new HttpSession(() -> true, request -> {var next = new CompletableFuture<HttpRequests.Result>(); replies.add(next); return next;})) {
            var template = new TemplateInstance(sample.template(), "http-json.vue");
            for (String button : List.of("send", "cancel", "send")) {
                var tree = template.instantiate(state);
                try (HttpRequests requests = new HttpRequests(session, batch -> {
                    for (var request : batch) session.submit(request, result -> check(PageStates.respond(sample.template().script(),
                            request, result, state, "http-json.vue", null, null).isEmpty(), "sample callback commits"));
                })) {
                    check(tree.click(byId(tree.root(), button), state, 0, null, requests).applied(), "actual sample button works: " + button);
                }
            }
            check(replies.get(0).isCancelled() && state.get("loading").equals(true), "sample cancels first and starts second request");
            replies.get(1).complete(new HttpRequests.Result(200, "{\"answer\":\"new only\"}", "")); session.poll();
            check(state.getString("answer").equals("new only") && state.getInt("requestId") == 0 && state.get("loading").equals(false), "sample displays only new reply and clears request ID");
        }
    }

    private static void transport() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        var executor = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); server.setExecutor(executor);
        server.createContext("/slow", exchange -> {
            entered.countDown();
            try { release.await(8, TimeUnit.SECONDS); exchange.sendResponseHeaders(200, -1); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException expected) { /* client cancellation may close the connection */ }
            finally { exchange.close(); }
        });
        server.start();
        try (HttpSession session = new HttpSession(() -> true)) {
            int id;
            try (HttpRequests batch = new HttpRequests(session, requests -> {
                for (var request : requests) session.submit(request, result -> {throw new AssertionError("cancelled real request delivered");});
            })) {
                id = batch.enqueue(new HttpRequests.Request("http://127.0.0.1:" + server.getAddress().getPort() + "/slow", "GET", Map.of(), "", 5000, "received"));
                batch.commit();
            }
            check(entered.await(5, TimeUnit.SECONDS), "real HTTP reached loopback server");
            try (HttpRequests batch = new HttpRequests(session, ignored -> {})) {batch.cancel(id); batch.commit();}
            // Inspect only resource accounting; the server deliberately keeps processing until finally releases it.
            var slots = Class.forName(HttpSession.class.getName() + "$Network").getDeclaredField("SLOTS"); slots.setAccessible(true);
            Semaphore semaphore = (Semaphore) slots.get(null); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (semaphore.availablePermits() != 16 && System.nanoTime() < deadline) Thread.sleep(5);
            check(semaphore.availablePermits() == 16 && session.available() == 4, "real cancellation frees local transport permit before server finishes");
            session.poll(); check(release.getCount() == 1, "cancelled response stays suppressed while remote processing may continue");
        } finally {release.countDown(); server.stop(0); executor.shutdownNow();}
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("mcphone-http-cancel-");
        try {lifecycle(); atomic(root); capacityAndPoll(); initialization(); sample(); transport(); idLimit();}
        finally {try (var paths = Files.walk(root)) {for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);}}
        System.out.println("ClientHttpCancelTest: " + checks + " checks passed");
    }
}
