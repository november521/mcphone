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
public final class SharedState {

    /** 一个 App 最多留几个键。 */
    public static final int MAX_KEYS_PER_APP = 4096;

    /** 一个值最多多长。与 {@link SizeGate#MAX_STRING} 同源。 */
    public static final int MAX_VALUE = SizeGate.MAX_STRING;

    private final Map<String, Map<String, String>> byApp = new ConcurrentHashMap<>();

    /** 这一轮哪些 App 的哪些键脏了，主线程落盘时读它。 */
    private final Map<String, java.util.Set<String>> dirty = new ConcurrentHashMap<>();

    /** Serializes dirty-set mutation with snapshot-and-clear; value reads/writes stay lock-free. */
    private final Object dirtyLock = new Object();

    public String get(String appId, String key) {
        Map<String, String> m = byApp.get(appId);
        return m == null ? null : m.get(key);
    }

    public void set(String appId, String key, String value) {
        check(appId, key, value);
        byApp.computeIfAbsent(appId, a -> new ConcurrentHashMap<>()).put(key, value);
        markDirty(appId, key);
    }

    /**
     * 原子地把 {@code key} 从 {@code expected} 换成 {@code next}。
     *
     * <p>{@code expected} 为 null 表示"这个键现在不存在"。限量竞争的唯一原语（§32.7）。
     */
    public boolean compareAndSet(String appId, String key, String expected, String next) {
        check(appId, key, next);
        Map<String, String> m = byApp.computeIfAbsent(appId, a -> new ConcurrentHashMap<>());
        boolean ok;
        if (expected == null) {
            ok = m.putIfAbsent(key, next) == null;
        } else {
            ok = m.replace(key, expected, next);
        }
        if (ok) markDirty(appId, key);
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
        byApp.clear();
        synchronized (dirtyLock) {
            dirty.clear();
        }
    }

    private void markDirty(String appId, String key) {
        synchronized (dirtyLock) {
            dirty.computeIfAbsent(appId, a -> ConcurrentHashMap.newKeySet()).add(key);
        }
    }

    private void check(String appId, String key, String value) {
        if (key == null || key.isEmpty()) throw HostError.invalid("shared 的键不能为空");
        if (value != null && value.length() > MAX_VALUE) {
            throw HostError.quota("shared 的值 " + value.length() + " 字符，上限 " + MAX_VALUE);
        }
        Map<String, String> m = byApp.get(appId);
        if (m != null && m.size() >= MAX_KEYS_PER_APP && !m.containsKey(key)) {
            throw HostError.quota(appId + " 的 shared 键数超过 " + MAX_KEYS_PER_APP);
        }
    }
}
