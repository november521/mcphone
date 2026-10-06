package com.november.mcphone.core.script.client;

import com.google.gson.*;
import com.november.mcphone.MCphone;
import com.november.mcphone.core.client.AppOptions;
import com.november.mcphone.core.script.JsonScan;
import com.november.mcphone.core.script.net.ScriptPush;
import com.november.mcphone.core.script.pkg.NetDeclaration;
import com.november.mcphone.core.script.client.tex.AppTextures;
import com.november.mcphone.core.script.server.SafeFetch;
import com.november.mcphone.core.script.server.ScriptStateData;
import net.minecraft.client.Minecraft;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.BooleanSupplier;

/** 玩家自己的 HTTPS 出口：许可、凭证和内容均只留客户端，绝不经过游戏服务器。 */
public final class ClientNetwork {
    private record Cached(long at, SafeFetch.Response response) { }
    private static final ExecutorService WORKERS = new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), r -> { var t = new Thread(r,"MCphone-private-fetch"); t.setDaemon(true); return t; },new ThreadPoolExecutor.AbortPolicy());
    // 以下表只由客户端主线程访问；worker 唯一回写入口是 Minecraft.execute。
    private static final Map<String,Cached> CACHE = new LinkedHashMap<>(16,.75f,true);
    private static final Set<String> PENDING = new HashSet<>();
    private static final Map<String,ArrayDeque<Long>> RATES = new LinkedHashMap<>();
    private static JsonObject consent;
    private static long generation, policyRevision = Long.MIN_VALUE;
    private static boolean policyAllowed;
    private ClientNetwork() { }
    private static Path path() { return Minecraft.getInstance().gameDirectory.toPath().resolve("config/mcphone/network-consent.json"); }
    private static boolean allowed(NetDeclaration net) {
        if (consent == null) {
            consent = new JsonObject();
            try {
                if (Files.exists(path())) {
                    if (Files.size(path()) > 65536) throw new IllegalArgumentException("网络许可超额");
                    String raw = Files.readString(path(), StandardCharsets.UTF_8);
                    if (JsonScan.check(raw,2) != null) throw new IllegalArgumentException("网络许可损坏");
                    consent = JsonParser.parseString(raw).getAsJsonObject();
                }
            } catch (Exception bad) { MCphone.LOGGER.warn("[MCphone] 网络许可无法读取，默认关闭"); }
        }
        JsonElement v = consent.get(net.consentKey());
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && v.getAsBoolean();
    }
    public static void register(ScriptApp app) {
        try {
            NetDeclaration net = NetDeclaration.of(app.pkg()); if (net == null) return;
            AppOptions.register(app.id(), new AppOptions.Toggle("mcphone.network.allow", "mcphone.update.on", "mcphone.update.off",
                    () -> allowed(net), value -> {
                allowed(net); JsonObject changed = consent.deepCopy(); changed.addProperty(net.consentKey(), value);
                try {
                    byte[] bytes = changed.toString().getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > 65536) throw new IllegalArgumentException("网络许可已满");
                    ScriptStateData.atomicWrite(path(), bytes); consent = changed;
                    generation++; CACHE.clear(); PENDING.clear(); AppTextures.clearRemote();
                } catch (Exception failure) { MCphone.LOGGER.warn("[MCphone] 保存网络许可失败"); }
            }));
        } catch (RuntimeException invalid) { MCphone.LOGGER.warn("[MCphone] App 网络声明无效：{}",app.id()); }
    }
    public static List<String> disclosure(ScriptApp app) {
        try {
            NetDeclaration n = NetDeclaration.of(app.pkg()); if (n == null) return List.of();
            var lines = new ArrayList<>(n.hosts().stream().sorted().toList()); lines.add(n.why()); return List.copyOf(lines);
        } catch (RuntimeException invalid) { return List.of(); }
    }
    public static void resetSession() { generation++; CACHE.clear(); PENDING.clear(); RATES.clear(); AppTextures.clearRemote(); policyAllowed = false; policyRevision = Long.MIN_VALUE; }
    public static void policy(ScriptPush push) {
        if (!push.isHost() || !push.topic().equals("mcphone:network.policy") || push.revision() <= policyRevision) return;
        try {
            String raw = new String(push.data(),StandardCharsets.UTF_8); if (JsonScan.check(raw,2) != null) return;
            JsonObject data = JsonParser.parseString(raw).getAsJsonObject();
            if (ClientHandshake.serverId() == null || !data.get("serverId").getAsString().equals(ClientHandshake.serverId().toString())
                    || data.get("epoch").getAsBigDecimal().longValueExact() != ClientHandshake.connectionEpoch()) return;
            if (!data.getAsJsonPrimitive("allowClientFetch").isBoolean()) return;
            policyRevision = push.revision(); policyAllowed = data.get("allowClientFetch").getAsBoolean();
            if (!policyAllowed) { generation++; CACHE.clear(); PENDING.clear(); AppTextures.clearRemote(); }
        } catch (RuntimeException ignored) { }
    }
    private static Map<String,Object> status(String code) { return Map.of("status",code,"text",""); }
    public static void fetch(ScriptApp app, String url, String authorization, int offset, Consumer<Map<String,Object>> callback) {
        fetchResponse(app, url, authorization, offset, response -> chunk(response, offset), callback);
    }
    public static void image(ScriptApp app, String url, String authorization, BooleanSupplier alive, Consumer<Map<String,Object>> callback) {
        fetchResponse(app, url, authorization, 0, response -> {
            if (!alive.getAsBoolean()) return status("CLOSED");
            return response.type() == SafeFetch.Type.PNG ? AppTextures.remotePng(app.pkg(), response.body()) : status("INVALID");
        }, callback);
    }
    /** 服务端出口返回的 PNG 可以在本地登记，不触发客户端网络，也不接受任意纹理地址。 */
    public static Map<String,Object> imageBytes(ScriptApp app, String encoded) {
        if (encoded == null || encoded.length() > ((AppTextures.MAX_BYTES + 2) / 3) * 4) return status("INVALID");
        try { return AppTextures.remotePng(app.pkg(), Base64.getDecoder().decode(encoded)); }
        catch (IllegalArgumentException bad) { return status("INVALID"); }
    }
    private static void fetchResponse(ScriptApp app, String url, String authorization, int offset,
            Function<SafeFetch.Response,Map<String,Object>> present, Consumer<Map<String,Object>> callback) {
        NetDeclaration net;
        try { net = NetDeclaration.of(app.pkg()); }
        catch (RuntimeException invalid) { callback.accept(status("INVALID")); return; }
        if (net == null || !allowed(net) || !policyAllowed) { callback.accept(status("DISABLED")); return; }
        try {
            SafeFetch.validate(url, net.hosts());
            if (offset < 0 || offset > 262144 || authorization.length() > 2048 || authorization.chars().anyMatch(c -> c < 32 || c > 126))
                throw new IllegalArgumentException("私人网络参数无效");
        } catch (RuntimeException invalid) { callback.accept(status("INVALID")); return; }
        String key;
        try { key = net.consentKey() + "|" + url + "|" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(authorization.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
        long now = System.currentTimeMillis(); CACHE.entrySet().removeIf(e -> now - e.getValue().at() > 21_600_000);
        Cached cached = CACHE.get(key);
        if (cached != null) { callback.accept(present.apply(cached.response())); return; }
        if (PENDING.contains(key)) { callback.accept(status("BUSY")); return; }
        RATES.entrySet().removeIf(e->e.getValue().isEmpty()||now-e.getValue().peekLast()>=60000);
        if(!RATES.containsKey(app.id().toString())&&RATES.size()>=128){callback.accept(status("RATE_LIMITED"));return;}
        var times = RATES.computeIfAbsent(app.id().toString(), k -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() >= 60000) times.removeFirst();
        if (times.size() >= 30) { callback.accept(status("RATE_LIMITED")); return; }
        long session = generation; PENDING.add(key);
        try {
            WORKERS.execute(() -> {
                SafeFetch.Response result = null;
                try { result = SafeFetch.get(url, net.hosts(), Set.of(SafeFetch.Type.TEXT,SafeFetch.Type.JSON,SafeFetch.Type.XML,SafeFetch.Type.PNG),262144,authorization.isEmpty()?null:authorization); }
                catch (Exception failure) { /* URL 与 token 都不写日志。 */ }
                SafeFetch.Response response = result;
                Minecraft.getInstance().execute(() -> {
                    if (generation != session || !allowed(net) || !policyAllowed) { callback.accept(status("DISABLED")); return; }
                    PENDING.remove(key);
                    if (response == null) { callback.accept(status("UNAVAILABLE")); return; }
                    while (CACHE.size() >= 64) CACHE.remove(CACHE.keySet().iterator().next());
                    CACHE.put(key,new Cached(System.currentTimeMillis(),response)); callback.accept(present.apply(response));
                });
            }); times.addLast(now); callback.accept(status("PENDING"));
        } catch (RejectedExecutionException busy) { PENDING.remove(key); callback.accept(status("BUSY")); }
    }
    private static Map<String,Object> chunk(SafeFetch.Response response,int offset) {
        byte[] body = response.body(); if (offset > body.length) return status("INVALID");
        int end = Math.min(body.length,offset+2048);
        var data = new LinkedHashMap<String,Object>(); data.put("status","READY"); data.put("type",response.type().name());
        data.put("size",body.length); data.put("offset",offset); data.put("nextOffset",end); data.put("eof",end==body.length);
        data.put("data",Base64.getEncoder().encodeToString(Arrays.copyOfRange(body,offset,end)));
        data.put("text",response.type()==SafeFetch.Type.PNG ? "" : new String(body,offset,end-offset,StandardCharsets.UTF_8));
        return Collections.unmodifiableMap(data);
    }
}
