package com.november.mcphone.core.script.server;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 非阻塞外网内容缓存。脚本先取得 PENDING，再通过偏移读取 2 KiB 分片；网络从不占用脚本 worker。 */
public final class FetchCache implements AutoCloseable {
    public record Policy(boolean enabled, Set<String> hosts, int maxBytes, long ttlMs, boolean clientAllowed) {
        public static final Policy DEFAULT = new Policy(false, Set.of(), SafeFetch.MAX_BYTES, 21_600_000, true);
        public Policy {
            hosts = Set.copyOf(hosts);
            if (hosts.size() > 128 || maxBytes < 1 || maxBytes > SafeFetch.MAX_BYTES || ttlMs < 1000 || ttlMs > 86_400_000)
                throw new IllegalArgumentException("网络配置超界");
            for (String host : hosts) SafeFetch.validate("https://" + host + "/", hosts);
        }
        public static Policy parse(JsonObject root) {
            if (!root.has("net")) return DEFAULT;
            JsonObject n = root.getAsJsonObject("net"); Set<String> hosts = new LinkedHashSet<>();
            if (n.has("allowed_hosts")) for (JsonElement h : n.getAsJsonArray("allowed_hosts")) {
                String value = h.getAsString();
                if (!value.equals(value.toLowerCase(Locale.ROOT)) || !hosts.add(value)) throw new IllegalArgumentException("域名需小写且不重复");
            }
            for (String key : List.of("server_fetch", "allow_client_fetch"))
                if (n.has(key) && (!n.get(key).isJsonPrimitive() || !n.getAsJsonPrimitive(key).isBoolean()))
                    throw new IllegalArgumentException("网络开关必须是布尔值");
            return new Policy(n.has("server_fetch") && n.get("server_fetch").getAsBoolean(), hosts,
                    n.has("max_bytes") ? n.get("max_bytes").getAsBigDecimal().intValueExact() : SafeFetch.MAX_BYTES,
                    n.has("cache_ttl_ms") ? n.get("cache_ttl_ms").getAsBigDecimal().longValueExact() : 21_600_000,
                    !n.has("allow_client_fetch") || n.get("allow_client_fetch").getAsBoolean());
        }
    }
    @FunctionalInterface public interface Transport { SafeFetch.Response get(String url, Policy policy) throws Exception; }
    private record Entry(long at, SafeFetch.Response response, String status,int space) { }
    private final Supplier<Policy> policy;
    private final LongSupplier clock;
    private final Transport transport;
    private final ExecutorService workers;
    private final Map<String, Entry> cache = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, ArrayDeque<Long>> byHost = new HashMap<>();
    private final ArrayDeque<Long> global = new ArrayDeque<>();
    private boolean closed;
    private Supplier<QuotaConfig> quotas=()->QuotaConfig.DEFAULT;
    public FetchCache quotas(Supplier<QuotaConfig> value){quotas=value;return this;}
    public synchronized long bytes(){return cache.values().stream().mapToLong(Entry::space).sum();}
    private void trim(long allowed){var it=cache.entrySet().iterator();long space=bytes();while(it.hasNext()&&space>allowed){Entry e=it.next().getValue();if(e.status().equals("PENDING"))continue;space-=e.space();it.remove();}}
    public FetchCache(Supplier<Policy> policy) {
        this(policy, System::currentTimeMillis, (url, p) -> SafeFetch.get(url, p.hosts(),
                Set.of(SafeFetch.Type.TEXT, SafeFetch.Type.JSON, SafeFetch.Type.XML, SafeFetch.Type.PNG), p.maxBytes(), null));
    }
    public FetchCache(Supplier<Policy> policy, LongSupplier clock, Transport transport) {
        this.policy = policy; this.clock = clock; this.transport = transport;
        workers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), r -> {
            Thread t = new Thread(r, "MCphone-content-fetch"); t.setDaemon(true); return t;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
    public synchronized String read(String url, int offset) {
        Policy p = policy.get();
        long maximum=quotas.get().get("net.cache");if (closed || !p.enabled()||maximum==0) {cache.clear();return status("DISABLED");}
        String host = SafeFetch.validate(url, p.hosts()).getHost().toLowerCase(Locale.ROOT);
        if (offset < 0 || offset > p.maxBytes()) throw new IllegalArgumentException("内容分片偏移无效");
        long now = clock.getAsLong();
        cache.entrySet().removeIf(e -> !e.getValue().status().equals("PENDING")
                && now - e.getValue().at() > (e.getValue().response() == null ? 5000 : Math.min(p.ttlMs(),quotas.get().get("net.ttl_hours")*3600000L)));
        trim(maximum);
        Entry entry = cache.get(url);
        if (entry != null) return chunk(entry, offset);
        var hostTimes = byHost.computeIfAbsent(host, k -> new ArrayDeque<>());
        clean(hostTimes, now); clean(global, now);
        // 只有真正新建的外网请求消耗额度；百人读同一 URL 不会吃掉百个令牌。
        if (hostTimes.size() >= 6 || global.size() >= 60) return status("RATE_LIMITED");
        if (cache.size() >= 2048) {
            var oldest = cache.entrySet().stream().filter(e -> !e.getValue().status().equals("PENDING")).findFirst();
            if (oldest.isEmpty()) return status("BUSY");
            cache.remove(oldest.get().getKey());
        }
        trim(Math.max(0,maximum-p.maxBytes()));if(bytes()+p.maxBytes()>maximum)return status("BUSY");
        Entry pending = new Entry(now, null, "PENDING",p.maxBytes()); cache.put(url, pending);
        try {
            workers.execute(() -> {
                SafeFetch.Response response = null;
                try { response = transport.get(url, p); }
                catch (Exception failed) { /* 地址/凭证不进日志；状态只报告不可用。 */ }
                synchronized (FetchCache.this) {
                    if (!closed && cache.get(url) == pending) {cache.put(url,
                            new Entry(clock.getAsLong(), response, response == null ? "UNAVAILABLE" : "READY",response==null?0:response.size()));trim(quotas.get().get("net.cache"));}
                }
            });
            hostTimes.addLast(now); global.addLast(now);
        } catch (RejectedExecutionException busy) { cache.remove(url); return status("BUSY"); }
        return status("PENDING");
    }
    private static void clean(ArrayDeque<Long> times, long now) { while (!times.isEmpty() && now - times.peekFirst() >= 60000) times.removeFirst(); }
    private static String status(String status) { JsonObject v = new JsonObject(); v.addProperty("status", status); return v.toString(); }
    private static String chunk(Entry entry, int offset) {
        if (entry.response() == null) return status(entry.status());
        byte[] body = entry.response().body();
        if (offset > body.length) throw new IllegalArgumentException("分片偏移超过内容末尾");
        int end = Math.min(body.length, offset + 2048);
        JsonObject v = new JsonObject(); v.addProperty("status", "READY"); v.addProperty("type", entry.response().type().name());
        v.addProperty("size", body.length); v.addProperty("offset", offset); v.addProperty("nextOffset", end);
        v.addProperty("eof", end == body.length); v.addProperty("encoding", "base64");
        v.addProperty("data", Base64.getEncoder().encodeToString(Arrays.copyOfRange(body, offset, end)));
        return v.toString();
    }
    public synchronized void clear() { cache.clear(); byHost.clear(); global.clear(); }
    @Override public synchronized void close() { closed = true; cache.clear(); workers.shutdownNow(); }
}
