package com.november.mcphone.core.script.client;

import com.google.gson.JsonParser;
import com.november.mcphone.core.script.http.HttpRequests;
import com.november.mcphone.core.script.http.HttpSession;
import com.november.mcphone.core.script.layout.*;
import com.november.mcphone.core.script.client.render.HitTest;
import com.november.mcphone.core.script.server.store.LocalStore;
import com.november.mcphone.core.script.sfc.*;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Tests the installable app in the unchanged production runtime. No real key or paid API call. */
public final class DeepSeekAppTest {
    private static final String APP_ID = "mcpapps:deepseek";
    private static final UUID ACCOUNT = new UUID(0, 91);
    private static final String KEY = "sk-test-only-not-a-real-credential";
    private static final String ANSWER = "{\"choices\":[{\"message\":{\"content\":\"你好😀\\n这是回答。\"},\"finish_reason\":\"stop\"}]}";
    private static ScriptApp app;
    private static int checks;
    private static final TextMeasure FONT = new TextMeasure() {
        public int width(String text) { return text.codePoints().map(cp -> cp < 128 ? 6 : 9).sum(); }
        public int lineHeight() { return 9; }
        public String truncate(String text, int width) {
            int end = 0, used = 0;
            while (end < text.length()) {
                int cp = text.codePointAt(end), advance = cp < 128 ? 6 : 9;
                if (used + advance > width) break;
                used += advance; end += Character.charCount(cp);
            }
            return text.substring(0, end);
        }
    };

    private static void check(boolean ok, String reason) {
        checks++;
        if (!ok) throw new AssertionError(reason);
    }

    private static Node find(Node node, String id) {
        if (id.equals(node.id())) return node;
        for (Node child : node.children()) { Node found = find(child, id); if (found != null) return found; }
        return null;
    }

    private static LayoutNode find(LayoutNode node, String id) {
        if (id.equals(node.node.id())) return node;
        for (LayoutNode child : node.children) { LayoutNode found = find(child, id); if (found != null) return found; }
        return null;
    }

    private static final class Fixture implements AutoCloseable {
        final Supplier<LocalStore> store;
        final HttpSession session;
        final UiState state;
        final List<HttpRequests.Request> sent = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();

        Fixture(Supplier<LocalStore> store, HttpSession.Transport transport) {
            this.store = store;
            session = new HttpSession(() -> true, transport);
            var opened = new PageStates().open("", app.entry().template(), "deepseek.vue", store, this::requests);
            state = opened.state();
            check(opened.warnings().isEmpty(), "app onInit succeeds");
        }

        HttpRequests requests(UiState target) {
            return new HttpRequests(session, batch -> {
                for (var request : batch) {
                    sent.add(request);
                    session.submit(request, response -> warnings.addAll(PageStates.respond(
                            app.entry().template().script(), request, response, target,
                            "deepseek.vue", store, requests(target))));
                }
            });
        }

        Statements.Outcome click(String id) {
            var tree = new TemplateInstance(app.entry().template(), "deepseek.vue").instantiate(state);
            Node node = find(tree.root(), id);
            check(node != null, "visible action exists: " + id);
            return tree.click(node, state, 0, store, requests(state));
        }

        void action(String id) {
            var result = click(id);
            check(result.applied() && result.warnings().isEmpty(), "action commits: " + id);
        }

        void configure() {
            action("settings"); state.set("keyDraft", KEY); action("save-key"); action("back");
        }

        void ready() {
            check(!Boolean.TRUE.equals(state.get("loading")) && state.getInt("requestId") == 0,
                    "request leaves a usable ready state");
            check(warnings.isEmpty(), "callback respects original sandbox and state limits");
        }

        void layout(boolean settings) {
            for (int[] size : List.of(new int[]{120, 176}, new int[]{226, 158})) {
                var tree = new TemplateInstance(app.entry().template(), "deepseek.vue").instantiate(state);
                var layout = LayoutEngine.layout(tree.root(), app.entry().stylesheet(), state,
                        size[0], size[1], FONT, ImageSizes.NONE);
                var ids = settings ? List.of("api-key", "save-key", "delete-key", "back")
                        : List.of("settings", "question", "send", "cancel", "answer");
                for (String id : ids) {
                    LayoutNode node = find(layout, id);
                    check(node != null && node.w > 8 && node.h > 0 && node.y >= 0 && node.y + node.h <= size[1],
                            "phone/tablet usable geometry: " + id);
                    if (!id.equals("answer")) check(HitTest.pick(layout, 0, 0, size[0], size[1],
                            node.x + node.w / 2.0, node.y + node.h / 2.0, FONT) == node,
                            "phone/tablet action reachable: " + id);
                }
            }
        }

        public void close() { session.close(); }
    }

    private static Supplier<LocalStore> storage(Path root, String suffix) {
        return LocalStore.clientProvider(root.resolve(suffix), ACCOUNT, APP_ID, () -> ACCOUNT);
    }

    private static void configuration(Path root) {
        var store = storage(root, "config");
        try (var f = new Fixture(store, r -> { throw new AssertionError("configuration must not send HTTP"); })) {
            check(!Boolean.TRUE.equals(f.state.get("configured")), "fresh install has no key");
            f.layout(false);
            f.state.set("draft", "你好"); f.action("send");
            check(f.sent.isEmpty() && !Boolean.TRUE.equals(f.state.get("loading")), "missing key does not send");
            f.action("settings"); f.layout(true);
            for (String invalid : List.of("", "  ", "sk-bad key", "中文密钥", "sk-bad\nkey", "x".repeat(257))) {
                f.state.set("keyDraft", invalid); f.action("save-key");
                check(!Boolean.TRUE.equals(f.state.get("configured")) && store.get().keys().isEmpty(),
                        "invalid key is not persisted");
            }
            f.layout(true);
            f.state.set("keyDraft", "  " + KEY + "\n"); f.action("save-key");
            check(store.get().getString("apiKey", "").equals(KEY), "key stored after explicit save");
            check(f.state.getString("keyDraft").isEmpty(), "save clears visible key draft");
            f.action("back");
            check(!f.state.getString("status").contains(KEY) && f.sent.isEmpty(), "key is not echoed or sent by configuration");
        }
        try (var f = new Fixture(store, r -> { throw new AssertionError("reopening must not send HTTP"); })) {
            check(Boolean.TRUE.equals(f.state.get("configured")) && f.state.getString("keyDraft").isEmpty(), "reopen finds saved key without revealing it");
            f.action("settings"); f.state.set("keyDraft", KEY + "-replacement"); f.action("save-key"); f.action("back");
            check(store.get().getString("apiKey", "").equals(KEY + "-replacement"), "explicit save replaces the persisted key");
            f.action("settings"); f.state.set("keyDraft", "unsaved-edit"); f.action("back");
            check(f.state.getString("keyDraft").isEmpty() && store.get().getString("apiKey", "").equals(KEY + "-replacement"), "leaving settings discards draft only");
            f.action("settings"); f.action("delete-key"); f.action("back");
            check(store.get().keys().isEmpty() && !Boolean.TRUE.equals(f.state.get("configured")), "delete removes saved key");
        }
    }

    private static void requestsAndCancel(Path root) {
        var replies = new ArrayList<CompletableFuture<HttpRequests.Result>>();
        try (var f = new Fixture(storage(root, "requests"), r -> {
            var reply = new CompletableFuture<HttpRequests.Result>(); replies.add(reply); return reply;
        })) {
            f.configure(); f.action("send"); check(f.sent.isEmpty(), "empty prompt is rejected");
            f.state.set("draft", "  请解释红石\n包含中文😀  "); f.action("send");
            f.layout(false);
            var request = f.sent.get(0); var body = JsonParser.parseString(request.body()).getAsJsonObject();
            check(request.url().equals("https://api.deepseek.com/chat/completions") && request.method().equals("POST"), "fixed official endpoint and POST");
            check(request.headers().get("authorization").equals("Bearer " + KEY), "explicit bearer auth");
            check(!request.body().contains(KEY) && body.get("model").getAsString().equals("deepseek-flash"), "key absent from body and current model selected");
            check(!body.get("stream").getAsBoolean() && body.getAsJsonObject("thinking").get("type").getAsString().equals("disabled"), "ordinary nonstream response requested");
            check(body.get("max_tokens").getAsInt() == 768 && request.timeoutMs() == 90000, "bounded generation and timeout");
            var messages = body.getAsJsonArray("messages");
            check(messages.size() == 2 && messages.get(1).getAsJsonObject().get("content").getAsString().equals("请解释红石\n包含中文😀"), "one trimmed current prompt, not chat history");
            f.action("send"); f.action("settings"); check(f.sent.size() == 1 && !Boolean.TRUE.equals(f.state.get("settings")), "waiting guards repeat send and settings");
            replies.get(0).complete(new HttpRequests.Result(200, ANSWER, "")); f.session.poll(); f.ready();
            check(f.state.getString("answer").equals("你好😀\n这是回答。"), "JSON answer and paragraph content displayed");
            f.layout(false);
            f.action("send"); replies.get(1).complete(new HttpRequests.Result(200, "{\"choices\":[{\"message\":{\"content\":\"stale\"}}]}", ""));
            f.action("cancel"); f.session.poll(); f.ready();
            check(!f.state.getString("answer").equals("stale"), "cancel suppresses already queued response");
            f.state.set("draft", "第二个问题"); f.action("send");
            check(!f.sent.get(2).body().contains("请解释红石"), "later request is independent");
            f.action("cancel"); check(replies.get(2).isCancelled(), "cancel attempts local transport cancellation");
        }
    }

    private static void responses(Path root) {
        List<HttpRequests.Result> cases = new ArrayList<>();
        for (int status : List.of(400, 401, 402, 429, 500, 503)) cases.add(new HttpRequests.Result(status, KEY, "HTTP_STATUS"));
        for (String error : List.of("TIMEOUT", "NETWORK_ERROR", "RESPONSE_TOO_LARGE", "BUSY", "INVALID_UTF8")) cases.add(new HttpRequests.Result(0, "", error));
        for (String body : List.of("not-json", "null", "{}", "{\"choices\":[]}", "{\"choices\":[null]}", "{\"choices\":[{\"message\":{\"content\":null}}]}", "{\"choices\":[{\"message\":{\"content\":\"  \"}}]}")) cases.add(new HttpRequests.Result(200, body, ""));
        int index = 0;
        for (var response : cases) {
            try (var f = new Fixture(storage(root, "response-" + index++), r -> CompletableFuture.completedFuture(response))) {
                f.configure(); f.state.set("draft", "你好"); f.action("send"); f.session.poll(); f.ready();
                check(!f.state.getString("status").isBlank() && !f.state.getString("status").contains(KEY), "failure is visible without raw response/key");
                check(f.state.getString("answer").isEmpty(), "failure does not invent an answer");
                if (response.status() == 401) f.layout(false);
            }
        }
        for (String text : List.of("a".repeat(4095) + "😀tail", "短回答")) {
            String json = "{\"choices\":[{\"message\":{\"content\":\"" + text + "\"},\"finish_reason\":\"length\"}]}";
            try (var f = new Fixture(storage(root, "length-" + text.length()), r -> CompletableFuture.completedFuture(new HttpRequests.Result(200, json, "")))) {
                f.configure(); f.state.set("draft", "长回答测试"); f.action("send"); f.session.poll(); f.ready();
                check(f.state.getString("answer").length() <= 4096 && !Character.isHighSurrogate(f.state.getString("answer").charAt(f.state.getString("answer").length() - 1)), "display cap does not split emoji");
                check(f.state.getString("status").contains("截短") || f.state.getString("status").contains("长度上限"), "truncation is explicit");
            }
        }
    }

    private static void diskFailure(Path root) throws Exception {
        Path folder = root.resolve("disk-failure"); LocalStore loaded = LocalStore.openClient(folder, ACCOUNT, APP_ID);
        Path conflict = LocalStore.clientPath(folder, ACCOUNT, APP_ID); Files.createDirectories(conflict); Files.writeString(conflict.resolve("keep.txt"), "kept");
        try (var f = new Fixture(() -> loaded, r -> { throw new AssertionError("save must not send HTTP"); })) {
            f.action("settings"); f.state.set("keyDraft", KEY); var result = f.click("save-key");
            check(!result.applied() && !Boolean.TRUE.equals(f.state.get("configured")) && f.state.getString("keyDraft").equals(KEY), "failed save keeps editable draft and original state");
            check(Files.readString(conflict.resolve("keep.txt")).equals("kept"), "disk conflict preserved");
        }
    }

    private static void loopback(Path root) throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = ANSWER.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try (HttpSession wire = new HttpSession(() -> true);
             var f = new Fixture(storage(root, "wire"), request -> {
                 var reply = new CompletableFuture<HttpRequests.Result>();
                 wire.submit(new HttpRequests.Request("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions",
                         request.method(), request.headers(), request.body(), request.timeoutMs(), request.callback(), request.id()), reply::complete);
                 return reply;
             })) {
            f.configure(); f.state.set("draft", "本机测试😀"); f.action("send");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (Boolean.TRUE.equals(f.state.get("loading")) && System.nanoTime() < deadline) { wire.poll(); f.session.poll(); Thread.sleep(10); }
            f.ready(); check(f.state.getString("answer").equals("你好😀\n这是回答。") && received.get().contains("本机测试"), "real production HTTP transport, UTF-8, JSON and callback round trip");
        } finally { server.stop(0); }
    }

    public static void main(String[] args) throws Exception {
        app = ScriptAppFolder.read("deepseek.vue", Files.readAllBytes(Path.of("../../demo-apps/deepseek/deepseek.vue")));
        check(app.id().toString().equals(APP_ID), "installable independent app identity");
        Path root = Files.createTempDirectory("mcphone-deepseek-").toAbsolutePath().normalize();
        try { configuration(root); requestsAndCancel(root); responses(root); diskFailure(root); loopback(root); }
        finally { try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
        System.out.println("DeepSeekAppTest: " + checks + " checks passed");
    }
}
