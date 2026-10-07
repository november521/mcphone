package com.november.mcphone.core.script.engine;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code ctx.shared.*} 的权威（施工方案 §32.7 的 plain 档、§20.4 的 compareAndSet）。
 *
 * <h2>为什么 CAS 就在 worker 上做，不回主线程</h2>
 *
 * §20.4 写的是"宿主侧实现：{@code compareAndSet} 在主线程上做"，而它给的<b>理由</b>是
 * "主线程天然串行，但不能依赖这一点，所以显式用 CAS"。也就是说它要的不变量是<b>原子性</b>，
 * 不是"在主线程"。{@code ConcurrentHashMap.replace(k, expected, next)} 在任何线程上都满足它，
 * 而且强于依赖 tick 串行。
 *
 * <p>回主线程的代价是可算的：一次 {@code server.execute} 往返至少等一个 tick 边界，
 * 而 §20.4 的脚本侧重试上限是 8 次 —— 一个抢购动作最坏 8 × 50 ms = 400 ms 占着一个 worker，
 * 而 worker 只有两个。
 *
 * <p>落盘仍然在主线程：{@code land()} 把脏键连同幂等账本写进同一个 SavedData、一次 setDirty。
 * 崩在中间时两者一起回滚，仍然一致。
 */
public final class SharedState implements CtxBuilder.SharedView {

    /** 一个 App 最多留几个键。 */
    public static final int MAX_KEYS_PER_APP = 4096;

    /** 一个值最多多长。与 {@link SizeGate#MAX_STRING} 同源。 */
    public static final int MAX_VALUE = com.november.mcphone.core.script.server.store.StoreQuota.SHARED_PER_VALUE;

    private final Map<String, Map<String, String>> byApp = new ConcurrentHashMap<>();

    /** 这一轮哪些 App 的哪些键脏了，主线程落盘时读它。 */
    private final Map<String, java.util.Set<String>> dirty = new ConcurrentHashMap<>();

    /** Serializes dirty-set mutation with snapshot-and-clear; value reads/writes stay lock-free. */
    private final Object dirtyLock = new Object();
    private Runnable changed = () -> {};
    private java.util.function.Consumer<String> writeGate = app -> {};
    private final Map<String,Long> appUsage = new java.util.HashMap<>();
    private long totalUsage;
    private static final com.google.gson.Gson JSON = new com.google.gson.Gson();
    private java.util.function.Supplier<com.november.mcphone.core.script.server.QuotaConfig> quotas=()->com.november.mcphone.core.script.server.QuotaConfig.DEFAULT;
    private com.november.mcphone.core.script.server.StorageBudget budget;
    private boolean restoring;
    public void quotas(java.util.function.Supplier<com.november.mcphone.core.script.server.QuotaConfig> values,com.november.mcphone.core.script.server.StorageBudget budget){this.quotas=values;this.budget=budget;byApp.keySet().forEach(app->budget.restore("global:"+app,Map.of(app,bytes(app))));}
    public void onWrite(java.util.function.Consumer<String> gate) { writeGate=java.util.Objects.requireNonNull(gate); }
    public void onChanged(Runnable callback) { changed = java.util.Objects.requireNonNull(callback); }
    public Map<String, Map<String, String>> snapshot() {
        Map<String, Map<String, String>> copy = new java.util.LinkedHashMap<>();
        byApp.forEach((app, values) -> copy.put(app, Map.copyOf(values))); return Map.copyOf(copy);
    }
    public void restore(Map<String, Map<String, String>> values) {
        clear();
        restoring=true;
        try {values.forEach((app, entries) -> {
            for (var e : entries.entrySet()) set(app, e.getKey(), e.getValue());
        });}finally{restoring=false;}
        drainDirty();
        if(budget!=null)byApp.keySet().forEach(app->budget.restore("global:"+app,Map.of(app,bytes(app))));
    }

    public String get(String appId, String key) {
        Map<String, String> m = byApp.get(appId);
        return m == null ? null : m.get(key);
    }

    public void set(String appId, String key, String value) {
        writeGate.accept(appId);
        synchronized (dirtyLock) {
            check(appId, key, value);
            String old=get(appId,key);long next=appUsage.getOrDefault(appId,0L)+bytes(key,value)-bytes(key,old);
            Runnable write=()->{byApp.computeIfAbsent(appId,a->new ConcurrentHashMap<>()).put(key,value);account(appId,key,old,value);markDirty(appId,key);};
            if(budget==null||restoring)write.run();else budget.replace("global:"+appId,Map.of(appId,next),write);
        }
        changed.run();
    }

    /**
     * 原子地把 {@code key} 从 {@code expected} 换成 {@code next}。
     *
     * <p>{@code expected} 为 null 表示"这个键现在不存在"。限量竞争的唯一原语（§32.7）。
     */
    public boolean compareAndSet(String appId, String key, String expected, String next) {
        writeGate.accept(appId);
        boolean ok;
        synchronized (dirtyLock) {
            check(appId, key, next);
            Map<String, String> m = byApp.computeIfAbsent(appId, a -> new ConcurrentHashMap<>());
            String current=m.get(key);ok=java.util.Objects.equals(current,expected);
            if(ok){long usage=appUsage.getOrDefault(appId,0L)+bytes(key,next)-bytes(key,current);Runnable write=()->{m.put(key,next);account(appId,key,current,next);markDirty(appId,key);};if(budget==null)write.run();else budget.replace("global:"+appId,Map.of(appId,usage),write);}
        }
        if (ok) changed.run();
        return ok;
    }

    /** 取出并清空脏键。主线程落盘时调。 */
    public Map<String, java.util.Set<String>> drainDirty() {
        synchronized (dirtyLock) {
            Map<String, java.util.Set<String>> out = new java.util.HashMap<>();
            dirty.forEach((app, keys) -> out.put(app, Set.copyOf(keys)));
            dirty.clear();
            return Map.copyOf(out);
        }
    }

    /** 服务器停止时清掉 —— 静态表会把上一个世界钉住。 */
    public void clear() {
        synchronized (dirtyLock) {
            if(budget!=null)byApp.keySet().forEach(app->budget.restore("global:"+app,Map.of()));
            byApp.clear(); appUsage.clear(); totalUsage=0;
            dirty.clear();
        }
    }

    private void markDirty(String appId, String key) {
        synchronized (dirtyLock) {
            dirty.computeIfAbsent(appId, a -> ConcurrentHashMap.newKeySet()).add(key);
        }
    }

    private void check(String appId, String key, String value) {
        if (key == null || !key.matches("[A-Za-z0-9_.-]{1,64}")) throw HostError.invalid("shared 的键必须在 1–64 个字母、数字、点或横线内");
        if (value == null) throw HostError.invalid("shared 的值不能为 null");
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_VALUE) {
            throw HostError.quota("shared 的单值超过 " + MAX_VALUE + " 字节");
        }
        Map<String, String> m = byApp.get(appId);
        if (m != null && m.size() >= MAX_KEYS_PER_APP && !m.containsKey(key)) {
            throw HostError.quota(appId + " 的 shared 键数超过 " + MAX_KEYS_PER_APP);
        }
        long old = m != null ? bytes(key,m.get(key)) : 0;
        long delta = bytes(key,value) - old;
        long appMax=restoring?32L*1024*1024:quotas.get().get("kv.per_app"),serverMax=restoring?64L*1024*1024:quotas.get().get("data.server");
        if (delta>0&&(appUsage.getOrDefault(appId,0L)+delta>appMax||totalUsage+delta>serverMax))
            throw HostError.quota("shared 达到 App 或全服总配额");
    }
    private static long bytes(String key,String value) {
        return value==null ? 0 : (JSON.toJson(key)+":"+JSON.toJson(value)+",").getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
    private void account(String app,String key,String old,String next) {
        long delta=bytes(key,next)-bytes(key,old); totalUsage+=delta; appUsage.merge(app,delta,Long::sum);
    }
    public long bytes(String app) { synchronized(dirtyLock) { return appUsage.getOrDefault(app,0L); } }
    public long totalBytes() { synchronized(dirtyLock) { return totalUsage; } }
}
