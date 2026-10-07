package com.november.mcphone.core.script.client;

import com.google.gson.*;
import com.november.mcphone.core.script.JsonScan;
import com.november.mcphone.core.script.net.*;
import com.november.mcphone.core.script.server.GuardSubscriptions;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 页面显示用的守卫快照。服务器身份/epoch/部署版三轴隔离，乱序或缺失重新拉取。 */
public final class ClientGuardState {
    private record State(long revision, Map<String, Object> values) { }
    private static final Map<String, State> states = new HashMap<>();
    private static final Map<String, Long> watching = new HashMap<>();
    private static final Map<String, Long> retryAt = new HashMap<>();
    private static final Set<String> active = new HashSet<>(), refreshing = new HashSet<>();
    private static long generation;
    private ClientGuardState() { }
    public static void open(String app) { active.add(app); ensureWatching(app); }
    public static void ensureWatching(String app) {
        if (!active.contains(app) || ClientHandshake.connectionEpoch() == 0 || ClientHandshake.deployment(app) == null) return;
        long handshake = ClientHandshake.revision();
        if (Objects.equals(watching.get(app), handshake)) return;
        Long retry = retryAt.get(app);
        if (retry != null && retry - System.nanoTime() > 0) return;
        watching.put(app, handshake);
        long epoch = ClientHandshake.connectionEpoch();
        ScriptCall.call(app, GuardSubscriptions.WATCH, new byte[0], null, result -> {
            if (epoch != ClientHandshake.connectionEpoch()) return;
            if (!active.contains(app)) { unwatch(app); return; }
            if (result.code() == ScriptErrorCode.OK) accept(result.data(), true);
            else if (result.code() == ScriptErrorCode.RATE_LIMITED || result.code() == ScriptErrorCode.UNAVAILABLE
                    || result.code() == ScriptErrorCode.IN_PROGRESS) {
                watching.remove(app);
                long delay = Math.min(15_000L, Math.max(1000L, result.retryAfterMs()));
                retryAt.put(app, System.nanoTime() + delay * 1_000_000L);
            }
        });
    }
    public static void close(String app) { active.remove(app); watching.remove(app); retryAt.remove(app); unwatch(app); states.remove(app); generation++; }
    private static void unwatch(String app) {
        if (ClientHandshake.connectionEpoch() != 0 && ClientHandshake.deployment(app) != null)
            ScriptCall.call(app, GuardSubscriptions.UNWATCH, new byte[0], null, ignored -> { });
    }
    public static void onPush(ScriptPush push) {
        if (push != null && push.isHost() && GuardSubscriptions.TOPIC.equals(push.topic())) accept(push.data(), false);
    }
    static void accept(byte[] bytes, boolean snapshot) {
        try {
            if (bytes.length > ScriptProtocol.DATA_MAX) return;
            String json = new String(bytes, StandardCharsets.UTF_8);
            if (JsonScan.check(json, 10) != null) return;
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            String app = root.get("appId").getAsString();
            ClientHandshake.Entry deployment = ClientHandshake.deployment(app);
            if (!active.contains(app) || deployment == null || ClientHandshake.serverId() == null
                    || !ClientHandshake.serverId().toString().equals(root.get("serverId").getAsString())
                    || ClientHandshake.connectionEpoch() != root.get("epoch").getAsLong()
                    || !deployment.deployRev().equals(root.get("deployRev").getAsString())) return;
            if (!root.has("revision")) { refresh(app); return; }
            long revision = root.get("revision").getAsBigDecimal().longValueExact(); State previous = states.get(app);
            if (!snapshot && (previous == null || revision != previous.revision() + 1)) {
                refresh(app); return;
            }
            if (previous != null && revision < previous.revision()) return;
            @SuppressWarnings("unchecked") Map<String, Object> values = (Map<String, Object>) value(root);
            states.put(app, new State(revision, values)); generation++;
        } catch (RuntimeException malformed) { /* 坏显示包不影响握手与世界状态。 */ }
    }
    private static void refresh(String app) {
        if (!refreshing.add(app)) return;
        long epoch = ClientHandshake.connectionEpoch();
        ScriptCall.call(app, GuardSubscriptions.SNAPSHOT, new byte[0], null, result -> {
            refreshing.remove(app);
            if (epoch == ClientHandshake.connectionEpoch() && result.code() == ScriptErrorCode.OK) accept(result.data(), true);
        });
    }
    private static Object value(JsonElement el) {
        if (el.isJsonObject()) {
            Map<String, Object> out = new LinkedHashMap<>(); el.getAsJsonObject().entrySet().forEach(e -> out.put(e.getKey(), value(e.getValue())));
            return Collections.unmodifiableMap(out);
        }
        if (el.isJsonPrimitive()) {
            JsonPrimitive p = el.getAsJsonPrimitive(); if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) {
                // 前端值域只接受 int；时间戳、epoch 等大数须保留原始十进制精度。
                try { return p.getAsBigDecimal().intValueExact(); }
                catch (ArithmeticException largeOrFractional) { return p.getAsString(); }
            }
            return p.getAsString();
        }
        if (el.isJsonArray()) { List<Object> out = new ArrayList<>(); el.getAsJsonArray().forEach(e -> out.add(value(e))); return Collections.unmodifiableList(out); }
        return null;
    }
    public static Map<String, Object> values(String app) { State state = states.get(app); return state == null ? Map.of() : state.values(); }
    public static long generation() { return generation; }
    public static void clear() { states.clear(); watching.clear(); retryAt.clear(); active.clear(); refreshing.clear(); generation++; }
}
