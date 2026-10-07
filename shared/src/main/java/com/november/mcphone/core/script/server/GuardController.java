package com.november.mcphone.core.script.server;

import com.november.mcphone.core.script.net.ScriptErrorCode;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.LongSupplier;

/** 守卫权威状态（§20）。只在服务器主线程使用，脚本 KV 没有到此表的引用。 */
public final class GuardController {
    public record Decision(ScriptErrorCode code, long at, long serverNow) {
        public boolean allowed() { return code == ScriptErrorCode.OK; }
    }
    public record Reservation(UUID player, String app, String action, List<String> limits,
                              Map<String, Long> cooldowns) {
        public Reservation { limits = List.copyOf(limits); cooldowns = Map.copyOf(cooldowns); }
    }
    private final LongSupplier clock;
    private final BiPredicate<String, UUID> predicates;
    private final Map<String, Long> counters = new LinkedHashMap<>();
    private final Map<String, Long> cooldowns = new LinkedHashMap<>();
    private final Map<String, Reservation> reservations = new LinkedHashMap<>();
    private final Set<String> pendingCooldowns = new HashSet<>();
    private long revision;
    public GuardController(LongSupplier clock, BiPredicate<String, UUID> predicates) {
        this.clock = clock; this.predicates = predicates;
    }
    public Decision reserve(String requestKey, UUID player, String app, String action, ActionGuards guards) {
        long now = clock.getAsLong();
        // 顺序固定：窗口、谓词、冷却、限量。全部通过后才改权威表。
        for (ActionGuards.Rule r : guards.rules()) if (r.kind().equals("window")) {
            if (now < r.value()) return new Decision(ScriptErrorCode.NOT_STARTED, r.value(), now);
            if (now > r.end()) return new Decision(ScriptErrorCode.EXHAUSTED, r.end(), now);
        }
        for (ActionGuards.Rule r : guards.rules()) if (r.kind().equals("predicate")) {
            if (!predicates.test(r.predicate(), player)) return new Decision(ScriptErrorCode.NOT_AUTHORIZED, 0, now);
        }
        Map<String, Long> nextCooldowns = new LinkedHashMap<>();
        for (ActionGuards.Rule r : guards.rules()) if (r.kind().equals("cooldown") || r.kind().equals("once")) {
            String k = key(player, app, action, r, guards);
            if (pendingCooldowns.contains(k)) return new Decision(ScriptErrorCode.IN_PROGRESS, 0, now);
            long until = cooldowns.getOrDefault(k, 0L);
            if (until > now) return new Decision(ScriptErrorCode.COOLDOWN, until, now);
            nextCooldowns.put(k, r.value());
        }
        List<String> limits = new ArrayList<>();
        for (ActionGuards.Rule r : guards.rules()) if (r.kind().equals("limit")) {
            String k = key(player, app, action, r, guards);
            if (counters.getOrDefault(k, 0L) >= r.value()) return new Decision(ScriptErrorCode.EXHAUSTED, 0, now);
            limits.add(k);
        }
        if (reservations.containsKey(requestKey)) return new Decision(ScriptErrorCode.IN_PROGRESS, 0, now);
        for (String k : limits) counters.merge(k, 1L, Math::addExact);
        pendingCooldowns.addAll(nextCooldowns.keySet());
        reservations.put(requestKey, new Reservation(player, app, action, limits, nextCooldowns));
        if (!limits.isEmpty()) revision++;
        return new Decision(ScriptErrorCode.OK, 0, now);
    }
    /** 成功才提交冷却；UNKNOWN 保留份额并锁住个人资格，等待管理员核对。 */
    public void finish(String key, ScriptErrorCode code) {
        Reservation r = reservations.get(key);
        if (r == null) return;
        if (code == ScriptErrorCode.UNKNOWN || code == ScriptErrorCode.PARTIAL) return;
        reservations.remove(key);
        pendingCooldowns.removeAll(r.cooldowns().keySet());
        if (code == ScriptErrorCode.OK) {
            long now = clock.getAsLong();
            r.cooldowns().forEach((k, duration) -> cooldowns.put(k,
                    duration == Long.MAX_VALUE || now > Long.MAX_VALUE - duration ? Long.MAX_VALUE : now + duration));
            if (!r.cooldowns().isEmpty()) revision++;
        } else {
            for (String k : r.limits()) {
                long old = counters.getOrDefault(k, 0L);
                if (old <= 0) throw new IllegalStateException("守卫预留计数损坏");
                if (old == 1) counters.remove(k); else counters.put(k, old - 1);
            }
            if (!r.limits().isEmpty()) revision++;
        }
    }
    /** 重启只能回滚 RESERVED；开始过的保留，绝不重试。 */
    public void recover(Map<UUID, Map<String, IdempotencyLedger.Entry>> entries) {
        for (var e : List.copyOf(reservations.entrySet())) {
            var ledger = entries.getOrDefault(e.getValue().player(), Map.of()).get(e.getKey());
            if (ledger == null) throw new IllegalStateException("守卫预留缺少账本");
            if (ledger.state() == IdempotencyLedger.State.RESERVED) finish(e.getKey(), ScriptErrorCode.INTERNAL);
            else if (ledger.state() == IdempotencyLedger.State.SUCCEEDED) finish(e.getKey(), ScriptErrorCode.OK);
            else if (ledger.state() == IdempotencyLedger.State.FAILED) finish(e.getKey(), ledger.code());
        }
    }
    public long revision() { return revision; }
    public Map<String, Long> counters() { return Map.copyOf(counters); }
    public Map<String, Long> cooldowns() { return Map.copyOf(cooldowns); }
    public Map<String, Reservation> reservations() { return Map.copyOf(reservations); }
    /** 显示快照不构成领取凭证；未开始的活动不回显剩余数（§20.1/§20.6）。 */
    public com.google.gson.JsonObject describe(UUID player, String app, Map<String, ActionGuards> definitions) {
        long now = clock.getAsLong();
        var root = new com.google.gson.JsonObject(); root.addProperty("serverNow", now); root.addProperty("revision", revision);
        var actions = new com.google.gson.JsonObject();
        definitions.forEach((action, guards) -> {
            var row = new com.google.gson.JsonObject();
            var window = guards.rules().stream().filter(r -> r.kind().equals("window")).findFirst();
            if (window.isPresent() && now < window.get().value()) {
                row.addProperty("code", "NOT_STARTED"); row.addProperty("startAt", window.get().value());
            } else {
                if (window.isPresent() && now > window.get().end()) row.addProperty("code", "EXHAUSTED");
                for (ActionGuards.Rule r : guards.rules()) {
                    String k = key(player, app, action, r, guards);
                    if (r.kind().equals("limit")) row.addProperty("remaining", Math.max(0, r.value() - counters.getOrDefault(k, 0L)));
                    if (r.kind().equals("cooldown") || r.kind().equals("once")) {
                        row.addProperty("nextAt", cooldowns.getOrDefault(k, 0L)); row.addProperty("pending", pendingCooldowns.contains(k));
                    }
                }
            }
            actions.add(action, row);
        });
        root.add("actions", actions); return root;
    }
    public void restore(Map<String, Long> savedCounters, Map<String, Long> savedCooldowns,
                        Map<String, Reservation> savedReservations, long revision) {
        if (revision < 0 || savedCounters.values().stream().anyMatch(v -> v < 0)
                || savedCooldowns.values().stream().anyMatch(v -> v < 0)) throw new IllegalArgumentException("守卫计数损坏");
        counters.clear(); counters.putAll(savedCounters);
        cooldowns.clear(); cooldowns.putAll(savedCooldowns);
        reservations.clear(); reservations.putAll(savedReservations);
        pendingCooldowns.clear(); savedReservations.values().forEach(r -> pendingCooldowns.addAll(r.cooldowns().keySet()));
        this.revision = revision;
    }
    private static String key(UUID player, String app, String action, ActionGuards.Rule r, ActionGuards guards) {
        // 窗口也是周期身份：同 label 换窗口开启新一轮；摘要/版本变化不会清空资格。
        String window = guards.rules().stream().filter(g -> g.kind().equals("window"))
                .map(g -> g.value() + ":" + g.end()).findFirst().orElse("");
        return app + "|" + action + "|" + r.kind() + "|" + r.label() + "|" + window + "|"
                + (r.scope().equals("global") ? "global" : player.toString());
    }
}
