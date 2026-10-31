package com.november.mcphone.core.script.timer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** One active page's bounded, UI-thread-only tasks. No worker thread or author closure is retained. */
public final class DelayedCallbacks implements AutoCloseable {
    public static final int MAX_PENDING = 8;
    public static final int MIN_DELAY_MS = 50;
    public static final int MAX_DELAY_MS = 60_000;
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);
    private record Request(int id, int delayMs, String callback) {}
    private record Flight(Request request, long due, Consumer<String> receiver) {}
    private final Map<Integer, Flight> pending = new LinkedHashMap<>();
    private final BooleanSupplier active;
    private final LongSupplier clock;
    private boolean closed;
    private boolean polling;

    public DelayedCallbacks(BooleanSupplier active) { this(active, System::nanoTime); }

    /** Injectable monotonic clock for deterministic checks; production always uses nanoTime. */
    public DelayedCallbacks(BooleanSupplier active, LongSupplier clock) {
        this.active = java.util.Objects.requireNonNull(active);
        this.clock = java.util.Objects.requireNonNull(clock);
    }

    private boolean usable() {
        if (closed || !active.getAsBoolean()) { close(); return false; }
        return true;
    }

    public Batch batch(Consumer<String> receiver) { return new Batch(this, receiver); }

    /** At most one due callback per page tick. Remove before invoking; new work waits for a later poll. */
    public void poll() {
        if (!usable() || polling) return;
        polling = true;
        try {
            long now = clock.getAsLong();
            Flight due = null;
            for (Flight candidate : pending.values()) {
                if (now - candidate.due() >= 0 && (due == null || candidate.due() - due.due() < 0)) due = candidate;
            }
            if (due != null) {
                pending.remove(due.request().id());
                due.receiver().accept(due.request().callback());
            }
        } finally { polling = false; }
    }

    @Override public void close() { closed = true; pending.clear(); }

    /** Scheduling/cancellation are staged with state/store/HTTP and discarded unless their transaction commits. */
    public static final class Batch implements AutoCloseable {
        private final DelayedCallbacks owner;
        private final Consumer<String> receiver;
        private final Set<Integer> owned;
        private final Set<Integer> cancellations = new LinkedHashSet<>();
        private final List<Request> staged = new ArrayList<>();
        private boolean closed;

        private Batch(DelayedCallbacks owner, Consumer<String> receiver) {
            this.owner = owner;
            this.receiver = java.util.Objects.requireNonNull(receiver);
            this.owned = owner.usable() ? Set.copyOf(owner.pending.keySet()) : Set.of();
        }

        public int after(int delayMs, String callback) {
            requireOpen();
            if (delayMs < MIN_DELAY_MS || delayMs > MAX_DELAY_MS) throw invalid("延迟须为 50–60000 毫秒的整数");
            if (callback == null || !callback.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) throw invalid("延迟回调函数名不合法");
            if (owned.size() - cancellations.size() + staged.size() >= MAX_PENDING) throw invalid("当前页面最多同时等待 8 个延迟任务");
            int id = NEXT_ID.getAndUpdate(value -> value == Integer.MAX_VALUE ? value : value + 1);
            if (id == Integer.MAX_VALUE) throw invalid("延迟任务编号已用尽，请重启游戏");
            staged.add(new Request(id, delayMs, callback));
            return id;
        }

        /** Unknown, completed, other-page IDs and the initial value 0 are harmless no-ops. */
        public void cancel(int id) {
            requireOpen();
            if (id < 0) throw invalid("任务编号须为非负的 32 位整数");
            if (!staged.removeIf(request -> request.id() == id) && owned.contains(id)) cancellations.add(id);
        }

        public void commit() {
            if (closed) throw new IllegalStateException("延迟任务批次已经结束");
            if (owner.usable()) {
                for (int id : cancellations) owner.pending.remove(id);
                long now = owner.clock.getAsLong();
                for (Request request : staged) {
                    owner.pending.put(request.id(), new Flight(request, now + request.delayMs() * 1_000_000L, receiver));
                }
            }
            close();
        }

        private void requireOpen() {
            if (closed || !owner.usable()) throw invalid("当前页面的延迟任务宿主已关闭");
        }

        @Override public void close() { closed = true; staged.clear(); cancellations.clear(); }
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
