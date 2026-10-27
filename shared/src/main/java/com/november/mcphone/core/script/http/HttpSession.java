package com.november.mcphone.core.script.http;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One open App's requests. submit/poll/close belong to the UI thread; workers never run script code. */
public final class HttpSession implements AutoCloseable {
    @FunctionalInterface public interface Transport {
        CompletableFuture<HttpRequests.Result> send(HttpRequests.Request request);
    }

    private record Flight(int id, CompletableFuture<HttpRequests.Result> future, Consumer<HttpRequests.Result> receiver) {}
    private final List<Flight> pending = new ArrayList<>();
    private final BooleanSupplier active;
    private final Transport transport;
    private boolean closed;
    private boolean polling;

    public HttpSession(BooleanSupplier active) { this(active, HttpSession::send); }

    public HttpSession(BooleanSupplier active, Transport transport) {
        this.active = active;
        this.transport = transport;
    }

    public int available() {
        if (closed || !active.getAsBoolean()) { close(); return 0; }
        return HttpRequests.MAX_PENDING - pending.size();
    }

    public void submit(HttpRequests.Request request, Consumer<HttpRequests.Result> receiver) {
        if (available() == 0) return;
        CompletableFuture<HttpRequests.Result> future;
        try { future = transport.send(request); }
        catch (RuntimeException e) { future = CompletableFuture.completedFuture(new HttpRequests.Result(0, "", "NETWORK_ERROR")); }
        pending.add(new Flight(request.id(), future, receiver));
    }

    Set<Integer> requestIds() {
        return pending.stream().map(Flight::id).filter(id -> id > 0).collect(Collectors.toUnmodifiableSet());
    }

    /** UI-thread only. Remove before cancelling so even an already completed future cannot deliver later. */
    public void cancel(int id) {
        for (Flight flight : List.copyOf(pending)) {
            if (flight.id() == id && id > 0 && pending.remove(flight)) flight.future().cancel(true);
        }
    }

    /** Deliver a bounded snapshot; callback-created requests cannot recurse or run until another poll. */
    public void poll() {
        if (closed || !active.getAsBoolean()) { close(); return; }
        if (polling) return;
        polling = true;
        try {
            for (Flight flight : List.copyOf(pending)) {
                if (closed || !active.getAsBoolean()) { close(); break; }
                if (!flight.future().isDone() || !pending.remove(flight)) continue;
                HttpRequests.Result result;
                try { result = flight.future().join(); }
                catch (RuntimeException e) { result = error(e); }
                flight.receiver().accept(result);
            }
        } finally { polling = false; }
    }

    @Override public void close() {
        closed = true;
        List<Flight> flights = List.copyOf(pending);
        pending.clear();
        for (Flight flight : flights) flight.future().cancel(true);
    }

    private static final class Network {
        static final Semaphore SLOTS = new Semaphore(16);
        static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    private static CompletableFuture<HttpRequests.Result> send(HttpRequests.Request request) {
        if (!Network.SLOTS.tryAcquire()) return CompletableFuture.completedFuture(new HttpRequests.Result(0, "", "BUSY"));
        CompletableFuture<HttpResponse<String>> wire;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(java.net.URI.create(request.url()))
                    .timeout(Duration.ofMillis(request.timeoutMs()));
            request.headers().forEach(builder::header);
            builder.method(request.method(), request.body().isEmpty() ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(request.body(), StandardCharsets.UTF_8));
            wire = Network.CLIENT.sendAsync(builder.build(), ignored -> new BoundedBody());
        } catch (RuntimeException e) {
            Network.SLOTS.release();
            return CompletableFuture.completedFuture(error(e));
        }
        CompletableFuture<HttpRequests.Result> result = new CompletableFuture<>();
        wire.whenComplete((response, failure) -> {
            Network.SLOTS.release();
            if (failure != null) result.complete(error(failure));
            else result.complete(new HttpRequests.Result(response.statusCode(), response.body(),
                    response.statusCode() >= 200 && response.statusCode() < 300 ? "" : "HTTP_STATUS"));
        });
        // The total deadline also covers a peer that sends headers and then stalls the response body.
        result.orTimeout(request.timeoutMs(), TimeUnit.MILLISECONDS).whenComplete((value, failure) -> {
            if (failure != null) wire.cancel(true);
        });
        return result;
    }

    private static HttpRequests.Result error(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof java.util.concurrent.ExecutionException)
                && failure.getCause() != null) failure = failure.getCause();
        String code = failure instanceof TooLarge ? "RESPONSE_TOO_LARGE"
                : failure instanceof java.nio.charset.CharacterCodingException ? "INVALID_UTF8"
                : failure instanceof TimeoutException || failure instanceof HttpTimeoutException ? "TIMEOUT" : "NETWORK_ERROR";
        return new HttpRequests.Result(0, "", code);
    }

    private static final class TooLarge extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** Cancel at the byte boundary, before decoding or collecting an unbounded response. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<String> {
        private final CompletableFuture<String> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<String> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) {
            if (subscription != null) { value.cancel(); return; }
            subscription = value;
            value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > HttpRequests.MAX_BODY_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new TooLarge());
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure) { result.completeExceptionally(failure); }
        @Override public void onComplete() {
            try {
                result.complete(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString());
            } catch (java.nio.charset.CharacterCodingException e) { result.completeExceptionally(e); }
        }
    }
}
