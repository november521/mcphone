package com.november.mcphone.core.script.http;

import com.november.mcphone.core.script.layout.UiState;
import com.november.mcphone.core.script.layout.Node;
import com.november.mcphone.core.script.layout.LayoutNode;
import com.november.mcphone.core.script.layout.LayoutEngine;
import com.november.mcphone.core.script.layout.ImageSizes;
import com.november.mcphone.core.script.layout.TextMeasure;
import com.november.mcphone.core.script.server.store.LocalStore;
import com.november.mcphone.core.script.sfc.PageStates;
import com.november.mcphone.core.script.sfc.SfcCompiler;
import com.november.mcphone.core.script.sfc.Statements;
import com.november.mcphone.core.script.sfc.TemplateInstance;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** Actual SFC transactions, deterministic completions and real loopback HTTP; no remote services or API keys. */
public final class ClientHttpTest {
    private static int checks;
    private static final UUID ACCOUNT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String INITIAL = "state = { count: 0, result: '', status: 0, error: '', loading: false }\n";
    private static final String RECEIVE = "function received(status, body, error) { state.status = status; state.result = body.slice(0, 4096); state.error = error; state.loading = false; }\n";

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static final class Fixture implements AutoCloseable {
        final SfcCompiler.Page page;
        final UiState state;
        final TemplateInstance template;
        final Supplier<LocalStore> storage;
        final HttpSession session;
        final List<HttpRequests.Request> sent = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        final Thread owner = Thread.currentThread();

        Fixture(String script, String action, Supplier<LocalStore> storage, HttpSession session) {
            page = SfcCompiler.compilePage("http.vue", "<template>\n<column><button @click=\"" + action
                    + "\">send</button><text>{{ result }}</text></column>\n</template>\n<script>\n" + script + "\n</script>\n");
            state = UiState.page(page.template().initialState());
            template = new TemplateInstance(page.template(), "http.vue");
            this.storage = storage;
            this.session = session;
        }

        HttpRequests requests(UiState target) {
            return new HttpRequests(session.available(), batch -> {
                for (var request : batch) {
                    sent.add(request);
                    session.submit(request, result -> {
                        check(Thread.currentThread() == owner, "callback delivered on polling/UI thread");
                        warnings.addAll(PageStates.respond(page.template().script(), request, result, target,
                                "http.vue", storage, requests(target)));
                    });
                }
            });
        }

        Statements.Outcome fire() {
            var tree = template.instantiate(state);
            return tree.click(tree.root().children().get(0), state, 0, storage, requests(state));
        }

        @Override public void close() { session.close(); }
    }

    private static Fixture fixture(String sendBody, String callback, HttpSession session) {
        return new Fixture(INITIAL + "function send() { state.loading = true; " + sendBody + " }\n" + callback,
                "send()", null, session);
    }

    private static String request(String url) {
        return "ctx.http.request({url: '" + url + "'}, 'received');";
    }

    private static void atomic(Path root) throws Exception {
        var future = new CompletableFuture<HttpRequests.Result>();
        HttpSession session = new HttpSession(() -> true, ignored -> future);
        try (Fixture f = fixture(request("https://example.invalid/data"), RECEIVE, session)) {
            check(f.sent.isEmpty(), "compiling and rendering never send requests");
            check(f.fire().applied() && f.sent.size() == 1 && f.state.get("loading").equals(true), "successful click publishes request and waiting state");
            check(f.state.getString("result").isEmpty(), "no premature completion");
            Thread worker = new Thread(() -> future.complete(new HttpRequests.Result(200, "你好 😀", "")));
            worker.start(); worker.join();
            check(f.state.getString("result").isEmpty(), "worker completion cannot touch UI state");
            f.state.set("count", 7);
            session.poll();
            check(f.warnings.isEmpty() && f.state.getString("result").equals("你好 😀"), "callback updates page with Unicode response");
            check(f.state.getInt("count") == 7 && f.state.get("loading").equals(false), "callback uses latest state, preserves intervening edits");
            session.poll();
            check(f.state.getInt("status") == 200 && session.available() == 4, "completion is delivered once and slot released");
        }
        Supplier<LocalStore> store = LocalStore.clientProvider(root, ACCOUNT, "test:http", () -> ACCOUNT);
        LocalStore initial = store.get(); initial.setString("keep", "original"); check(initial.save(), "prepare original store");
        Path file = LocalStore.clientPath(root, ACCOUNT, "test:http");
        byte[] before = Files.readAllBytes(file);
        for (String tail : List.of("throw 'stop';", "state.count = 'bad';", "while (true) {}")) {
            try (Fixture f = new Fixture(INITIAL + "function send() { ctx.store.setString('keep','changed'); "
                    + request("https://example.invalid/data") + " state.count = 9; " + tail + " }\n" + RECEIVE,
                    "send(); call('noop')", store, new HttpSession(() -> true, ignored -> { throw new AssertionError("rolled-back request was sent"); }))) {
                check(!f.fire().applied() && f.sent.isEmpty(), "script/state/budget failure discards request");
                check(f.state.getInt("count") == 0 && Arrays.equals(before, Files.readAllBytes(file)), "failed click keeps UI and disk");
            }
        }
        try (Fixture f = new Fixture(INITIAL + "function send() { " + request("https://example.invalid/data") + " }\n" + RECEIVE,
                "send(); count = 'bad'; call('noop')", store, new HttpSession(() -> true))) {
            var outcome = f.fire();
            check(!outcome.applied() && outcome.calls().isEmpty() && f.sent.isEmpty(), "later legacy statement rolls back HTTP and RPC");
        }
        var reply = new CompletableFuture<HttpRequests.Result>();
        try (Fixture f = new Fixture(INITIAL + "function send() { " + request("https://example.invalid/data") + " }\n"
                + "function received(s,b,e) { ctx.store.setString('keep','wrong'); state.count=42; "
                + request("https://example.invalid/next") + " throw 'stop'; }", "send()", store, new HttpSession(() -> true, ignored -> reply))) {
            check(f.fire().applied(), "callback rollback setup");
            reply.complete(new HttpRequests.Result(200, "{}", "")); f.session.poll();
            check(!f.warnings.isEmpty() && f.state.getInt("count") == 0 && f.sent.size() == 1, "failed callback rolls back state and follow-up requests");
            check(Arrays.equals(before, Files.readAllBytes(file)), "failed callback preserves file");
        }
        Path conflict = LocalStore.clientPath(root, ACCOUNT, "test:http_io");
        LocalStore loaded = LocalStore.openClient(root, ACCOUNT, "test:http_io");
        Files.createDirectories(conflict); Files.writeString(conflict.resolve("keep.txt"), "keep");
        try (Fixture f = new Fixture(INITIAL + "function send() { ctx.store.setString('keep','wrong'); "
                + request("https://example.invalid/data") + " state.count=42; }\n" + RECEIVE,
                "send(); call('noop')", () -> loaded, new HttpSession(() -> true))) {
            var outcome = f.fire();
            check(!outcome.applied() && f.sent.isEmpty() && outcome.calls().isEmpty() && f.state.getInt("count") == 0, "disk commit failure prevents network and RPC");
            check(Files.readString(conflict.resolve("keep.txt")).equals("keep"), "disk conflict kept intact");
        }
        var savedReply = new CompletableFuture<HttpRequests.Result>();
        try (Fixture f = new Fixture(INITIAL + "function send(){" + request("https://example.invalid/") + "}\n"
                + "function received(s,b,e){state.count=b.length; state.result=JSON.parse(b).answer; ctx.store.setString('keep',state.result);}",
                "send()", store, new HttpSession(() -> true, ignored -> savedReply))) {
            check(f.fire().applied(), "large response setup");
            String json="{\"answer\":\"saved response\",\"padding\":\""+"x".repeat(5000)+"\"}";
            savedReply.complete(new HttpRequests.Result(200,json,"")); f.session.poll();
            check(f.warnings.isEmpty() && f.state.getInt("count")==json.length() && f.state.getString("result").equals("saved response"),
                    "response above state string limit can be parsed without widening UI policy");
            check(store.get().getString("keep","").equals("saved response"), "successful callback persists atomically");
        }
        var diskReply = new CompletableFuture<HttpRequests.Result>();
        try (Fixture f = new Fixture(INITIAL + "function send(){" + request("https://example.invalid/") + "}\n"
                + "function received(s,b,e){state.count=42;ctx.store.setString('keep','new');"+request("https://example.invalid/next")+"}",
                "send()", () -> loaded, new HttpSession(() -> true, ignored -> diskReply))) {
            check(f.fire().applied(), "callback disk failure setup");
            diskReply.complete(new HttpRequests.Result(200,"ok","")); f.session.poll();
            check(!f.warnings.isEmpty() && f.state.getInt("count")==0 && f.sent.size()==1, "callback disk failure rolls back state and follow-up request");
        }
    }

    private static void invalid() {
        List<String> bad = List.of(
                "ctx.http.request({url:'file:///tmp/data'}, 'received');",
                "ctx.http.request({url:'https://user:password@example.com/'}, 'received');",
                "ctx.http.request({url:'https://example.com/#secret'}, 'received');",
                "ctx.http.request({url:'https://example.com/', method:'PUT'}, 'received');",
                "ctx.http.request({url:'https://example.com/', body:'oops'}, 'received');",
                "ctx.http.request({url:'https://example.com/', timeoutMs:999}, 'received');",
                "ctx.http.request({url:'https://example.com/', timeoutMs:120001}, 'received');",
                "ctx.http.request({url:'https://example.com/', timeoutMs:1000.5}, 'received');",
                "ctx.http.request({url:'https://example.com/', headers:{Host:'example.com'}}, 'received');",
                "ctx.http.request({url:'https://example.com/', headers:{'X-Token':'a\\r\\nb'}}, 'received');",
                "ctx.http.request({url:'https://example.com/', headers:{'X-A':'a','x-a':'b'}}, 'received');",
                "ctx.http.request({url:'https://example.com/', unknown:true}, 'received');",
                "ctx.http.request({url:{toString:function(){return 'https://example.com';}}}, 'received');",
                "ctx.http.request(Object.create({url:'https://example.com/'}), 'received');",
                "var o={}; Object.defineProperty(o,'url',{get:function(){throw 'getter executed';}}); ctx.http.request(o,'received');",
                "ctx.http.request({url:'https://example.com/'}, received);",
                "ctx.http.request({url:'https://example.com/'}, 'missing');",
                "ctx.http.request({url:'https://example.com/'}, 'send');",
                "ctx.http.request({url:'https://example.com/'}, 'onInit');",
                "ctx.http.request({url:'https://example.com/', method:'POST', body:'中'.repeat(22000)}, 'received');",
                "ctx.http.request({url:'https://example.com/', method:'POST', body:'\\ud800'}, 'received');",
                "for(var i=0;i<5;i++){ctx.http.request({url:'https://example.com/'},'received');}"
        );
        for (String code : bad) {
            try (Fixture f = fixture("try { " + code + " } catch(e) { state.count=99; }", RECEIVE, new HttpSession(() -> true))) {
                check(!f.fire().applied() && f.sent.isEmpty() && f.state.getInt("count") == 0, "invalid request rejects whole transaction: " + code);
            }
        }
        try (Fixture f = fixture(request("https://example.invalid/"), RECEIVE, new HttpSession(() -> true))) {
            var tree = f.template.instantiate(f.state);
            check(!tree.click(tree.root().children().get(0), f.state, 0).applied(), "host-less compatibility overload has no network access");
        }
        try (Fixture f = fixture("state.result=typeof ctx.http.getClass + ':' + typeof ctx.http.request.constructor;", RECEIVE, new HttpSession(() -> true))) {
            check(f.fire().applied() && f.state.getString("result").equals("undefined:undefined"), "HTTP host exposes no Java or dynamic compilation handles");
        }
    }

    private static void lifecycle() throws Exception {
        var future = new CompletableFuture<HttpRequests.Result>();
        var active = new AtomicBoolean(true);
        try (Fixture f = fixture(request("https://example.invalid/"), RECEIVE, new HttpSession(active::get, ignored -> future))) {
            check(f.fire().applied(), "close lifecycle starts request");
            f.session.close();
            check(future.isCancelled() && !future.complete(new HttpRequests.Result(200,"late","")), "closing cancels transport");
            f.session.poll();
            check(f.state.getString("result").isEmpty() && f.session.available() == 0, "closed session cannot deliver or reopen");
        }
        try (Fixture f = fixture(request("https://example.invalid/"), RECEIVE,
                new HttpSession(() -> true, ignored -> CompletableFuture.completedFuture(new HttpRequests.Result(200,"late",""))))) {
            check(f.fire().applied(), "queued completion setup");
            f.session.close(); f.session.poll();
            check(f.state.getString("result").isEmpty(), "closing discards already completed but undelivered response");
        }
        var changed = new CompletableFuture<HttpRequests.Result>();
        try (Fixture f = fixture(request("https://example.invalid/"), RECEIVE, new HttpSession(active::get, ignored -> changed))) {
            check(f.fire().applied(), "identity lifecycle starts request");
            active.set(false); f.session.poll();
            check(changed.isCancelled() && f.state.getString("result").isEmpty(), "account switch cancels and discards results");
        }
        var pending = new ArrayList<CompletableFuture<HttpRequests.Result>>();
        try (Fixture f = fixture(request("https://example.invalid/"), RECEIVE, new HttpSession(() -> true, ignored -> {
            var next = new CompletableFuture<HttpRequests.Result>(); pending.add(next); return next;
        }))) {
            for (int i=0;i<4;i++) check(f.fire().applied(), "bounded concurrent request admitted");
            check(!f.fire().applied() && f.sent.size()==4, "fifth request fails before commit");
            pending.get(0).complete(new HttpRequests.Result(200,"done","")); f.session.poll();
            check(f.fire().applied() && f.sent.size()==5, "completed slot reusable");
        }
        var states = new PageStates();
        try (Fixture f = fixture("ctx.http.request({url:'https://example.invalid/'},'received');", RECEIVE,
                new HttpSession(() -> true, ignored -> CompletableFuture.completedFuture(new HttpRequests.Result(200,"initialized",""))))) {
            var initialized = SfcCompiler.compilePage("init.vue", "<template>\n<text>{{ result }}</text>\n</template>\n<script>\n"
                    + INITIAL + "function onInit(){" + request("https://example.invalid/") + "}\n" + RECEIVE + "</script>\n");
            var opened = states.open("", initialized.template(), "init.vue", null, target -> new HttpRequests(f.session.available(), batch -> {
                for(var request: batch) f.session.submit(request, result -> f.warnings.addAll(PageStates.respond(initialized.template().script(),
                        request, result, target, "init.vue", null, null)));
            }));
            UiState otherPage = states.open("other", f.page.template(), "other.vue", null).state();
            check(opened.warnings().isEmpty() && opened.state().getString("result").isEmpty(), "onInit schedules without synchronous callback");
            f.session.poll();
            check(opened.state().getString("result").equals("initialized") && otherPage.getString("result").isEmpty(), "navigation preserves result ownership; other page untouched");
            check(states.open("", initialized.template(), "init.vue", null).state() == opened.state(), "return retains original state");
        }
        try (Fixture f = fixture(request("https://example.invalid/"), "function received(s,b,e){state.count++; if(state.count<2){"
                +request("https://example.invalid/next")+"}}",new HttpSession(() -> true,ignored -> CompletableFuture.completedFuture(new HttpRequests.Result(200,"ok",""))))) {
            check(f.fire().applied(), "follow-up request setup");
            f.session.poll(); check(f.state.getInt("count")==1 && f.sent.size()==2, "successful callback publishes its own staged request");
            f.session.poll(); check(f.state.getInt("count")==2, "follow-up result uses independent callback transaction");
        }
        var failedInit = SfcCompiler.compilePage("init.vue", "<template>\n<text>{{ result }}</text>\n</template>\n<script>\n"
                + INITIAL + "function onInit(){" + request("https://example.invalid/") + "throw 'stop';}\n" + RECEIVE + "</script>\n");
        AtomicInteger sends = new AtomicInteger();
        var initStates = new PageStates();
        var failed = initStates.open("", failedInit.template(), "init.vue", null, target -> new HttpRequests(4, batch -> sends.addAndGet(batch.size())));
        check(!failed.warnings().isEmpty() && sends.get()==0, "failed initialization never sends staged requests");
        check(initStates.open("", failedInit.template(), "init.vue", null).state()==failed.state(), "failed hook remains attempted");
        AtomicInteger callbacks = new AtomicInteger();
        try (HttpSession session = new HttpSession(() -> true, ignored -> CompletableFuture.completedFuture(new HttpRequests.Result(200,"ok","")))) {
            var request = new HttpRequests.Request("https://example.invalid/","GET",Map.of(),"",1000,"received");
            session.submit(request, first -> { callbacks.incrementAndGet(); session.submit(request, second -> callbacks.incrementAndGet()); });
            session.poll(); check(callbacks.get()==1, "callback-created completion is deferred, not recursive");
            session.poll(); check(callbacks.get()==2, "deferred completion runs at next poll");
        }
    }

    private static Node byId(Node node, String id) {
        if(id.equals(node.id())) return node;
        for(Node child:node.children()) { Node found=byId(child,id); if(found!=null) return found; }
        return null;
    }

    private static void sample() throws Exception {
        Path path=Path.of("../../docs/samples/http-json.vue");
        if(!Files.isRegularFile(path)) path=Path.of("docs/samples/http-json.vue");
        var app=SfcCompiler.compile("http-json.vue", Files.readString(path,StandardCharsets.UTF_8));
        UiState state=UiState.page(app.template().initialState());
        var template=new TemplateInstance(app.template(),"http-json.vue");
        var sent=new ArrayList<HttpRequests.Request>();
        var ready=new ArrayList<CompletableFuture<HttpRequests.Result>>();
        try(HttpSession session=new HttpSession(() -> true, request -> { sent.add(request); var future=new CompletableFuture<HttpRequests.Result>();ready.add(future);return future;})) {
            for(var response:List.of(new HttpRequests.Result(200,"{\"answer\":\"完整回复 😀\"}",""),new HttpRequests.Result(500,"{}","HTTP_STATUS"),
                    new HttpRequests.Result(0,"","TIMEOUT"),new HttpRequests.Result(200,"not json",""),
                    new HttpRequests.Result(200,"{\"answer\":\""+"长".repeat(5000)+"\"}",""))) {
                var tree=template.instantiate(state);
                check(tree.warnings().isEmpty(),"actual sample instantiates");
                try(HttpRequests requests=new HttpRequests(4,batch -> {
                    for(var request:batch) session.submit(request,result -> check(PageStates.respond(app.template().script(),request,result,state,
                            "http-json.vue",null,null).isEmpty(),"actual sample callback commits"));
                })) {
                    check(tree.click(byId(tree.root(),"send"),state,0,null,requests).applied(),"actual sample sends");
                }
                var waiting=template.instantiate(state);
                check(state.get("loading").equals(true) && Boolean.FALSE.equals(byId(waiting.root(),"send").props().get("enabled")),"sample disables repeat sends while waiting");
                check(sent.get(sent.size()-1).body().contains("你好"),"sample sends JSON question");
                ready.get(ready.size()-1).complete(response); session.poll();
                check(state.get("loading").equals(false) && !state.getString("answer").isEmpty(),"sample clears waiting after success, HTTP error, timeout or malformed JSON");
                if(response.body().contains("完整回复")) check(state.getString("answer").equals("完整回复 😀"),"sample displays returned answer");
                if(response.body().length()>4096) check(state.getString("answer").length()==4096,"sample bounds large extracted answer to page policy");
                TextMeasure font=new TextMeasure(){ public int width(String s){return s.codePointCount(0,s.length())*6;} public int lineHeight(){return 9;} public String truncate(String s,int width){return s.substring(0,Math.min(s.length(),Math.max(0,width/6)));} };
                for(int[] size:new int[][]{{120,176},{226,158}}) {
                    LayoutNode layout=LayoutEngine.layout(template.instantiate(state).root(),app.stylesheet(),state,size[0],size[1],font,ImageSizes.NONE);
                    LayoutNode viewport=layout.children.get(layout.children.size()-1);
                    check(viewport.h>0 && viewport.y+viewport.h<=size[1],"sample reply viewport fits phone and tablet without clipping controls");
                }
            }
        }
    }

    private static void waitFor(Fixture fixture) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (fixture.session.available() < 4 && System.nanoTime() < deadline) { fixture.session.poll(); Thread.sleep(5); }
        check(fixture.session.available() == 4, "request completed within test deadline");
    }

    private static void network() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "http-test"); t.setDaemon(true); return t; });
        server.setExecutor(workers);
        var received = new AtomicInteger();
        var redirectHit = new AtomicInteger();
        var blocked = new CountDownLatch(1);
        server.createContext("/json", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (exchange.getRequestMethod().equals("POST") && body.equals("{\"question\":\"你好\"}")
                    && "Bearer test-only".equals(exchange.getRequestHeaders().getFirst("Authorization"))
                    && exchange.getRequestHeaders().getFirst("Content-Type").startsWith("application/json")) received.incrementAndGet();
            byte[] answer = "{\"answer\":\"完整回答 😀\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, answer.length); exchange.getResponseBody().write(answer); exchange.close();
        });
        server.createContext("/error", exchange -> { byte[] body="bad request".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(401,body.length); exchange.getResponseBody().write(body); exchange.close(); });
        server.createContext("/redirect", exchange -> { exchange.getResponseHeaders().add("Location","/target"); exchange.sendResponseHeaders(302,-1); exchange.close(); });
        server.createContext("/target", exchange -> { redirectHit.incrementAndGet(); exchange.sendResponseHeaders(200,-1); exchange.close(); });
        server.createContext("/large", exchange -> { byte[] bytes=new byte[65537]; Arrays.fill(bytes,(byte)'x'); exchange.sendResponseHeaders(200,0); try { exchange.getResponseBody().write(bytes); } catch(java.io.IOException ignored) {} finally {exchange.close();} });
        server.createContext("/max", exchange -> { byte[] bytes=new byte[65536]; Arrays.fill(bytes,(byte)'x'); exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close(); });
        server.createContext("/utf8", exchange -> { exchange.sendResponseHeaders(200,1); exchange.getResponseBody().write(new byte[]{(byte)0xc0}); exchange.close(); });
        server.createContext("/slow", exchange -> { exchange.sendResponseHeaders(200,0); exchange.getResponseBody().flush(); try { blocked.await(6,TimeUnit.SECONDS); } catch(InterruptedException e) {Thread.currentThread().interrupt();} finally {exchange.close();} });
        server.start();
        String base="http://127.0.0.1:"+server.getAddress().getPort();
        try {
            try (Fixture f=fixture("ctx.http.request({url:'"+base+"/json', method:'POST', headers:{Authorization:'Bearer test-only'}, body:JSON.stringify({question:'你好'})},'received');",
                    "function received(s,b,e){state.loading=false;state.status=s;state.error=e;state.result=JSON.parse(b).answer;}", new HttpSession(()->true))) {
                check(f.fire().applied() && f.state.get("loading").equals(true),"POST starts without waiting for response");
                waitFor(f);
                check(f.warnings.isEmpty() && received.get()==1 && f.state.getString("result").equals("完整回答 😀"),"real POST/JSON/header/UTF-8 round trip");
            }
            for (String[] test: List.of(new String[]{"/json","200",""},new String[]{"/error","401","HTTP_STATUS"},new String[]{"/redirect","302","HTTP_STATUS"},
                    new String[]{"/max","200",""},new String[]{"/large","0","RESPONSE_TOO_LARGE"},new String[]{"/utf8","0","INVALID_UTF8"},new String[]{"/slow","0","TIMEOUT"})) {
                try (Fixture f=fixture("ctx.http.request({url:'"+base+test[0]+"',timeoutMs:1000},'received');",RECEIVE,new HttpSession(()->true))) {
                    check(f.fire().applied(),"real request accepted: "+test[0]); waitFor(f);
                    check(f.warnings.isEmpty() && f.state.getInt("status")==Integer.parseInt(test[1]) && f.state.getString("error").equals(test[2]),
                            "real response classification: "+test[0]+" got "+f.state.getInt("status")+" "+f.state.getString("error"));
                    check(f.state.get("loading").equals(false),"failure/success callback clears waiting state");
                }
            }
            check(redirectHit.get()==0,"redirect not followed or forwarded credentials");
        } finally { blocked.countDown(); server.stop(0); workers.shutdownNow(); }
        try(Fixture f=fixture(request(base+"/json"),RECEIVE,new HttpSession(()->true))) {
            check(f.fire().applied(),"connection failure setup"); waitFor(f);
            check(f.state.getInt("status")==0 && f.state.getString("error").equals("NETWORK_ERROR"),"connection failure classified without exposing network exception details");
        }
    }

    public static void main(String[] args) throws Exception {
        invalid(); lifecycle(); sample();
        Path root=Files.createTempDirectory("mcphone-http-test-").toAbsolutePath().normalize();
        try { atomic(root); network(); }
        finally {
            if(!root.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())) throw new AssertionError("cleanup outside temporary root");
            try(var paths=Files.walk(root)) { for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        }
        System.out.println("ClientHttpTest: "+checks+" checks passed");
    }
}
