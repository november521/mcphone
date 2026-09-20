package com.november.mcphone.core.script.server;

import com.november.mcphone.core.script.net.ScriptErrorCode;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 幂等账本（施工方案 §15.6）：同一次点击被重复投递时，不重复执行。
 *
 * <h2>本步是内存账本，落盘是 S20 的事</h2>
 *
 * §27.1 把「S20 幂等账本 §20.7」单列在 P2，那一步管的是重启后
 * {@code RESERVED} → 回滚、{@code EFFECT_STARTED} → {@code UNKNOWN} 那张状态表。
 * 本步只欠 §15.6 的四行行为。<b>所以现在的"只执行一次"在一次服务器会话内成立，跨重启不成立</b> ——
 * 这一条要写在 S12 的验收里，别当成已经有了。
 *
 * <h2>淘汰按状态，不按时间 —— 按时间是一个可被利用的驱逐攻击</h2>
 *
 * §15.6 原文是"单玩家上限 256 条，超出按时间淘汰最旧的"。<b>那样淘汰的对象由攻击者控制</b>：
 * {@code requestId} 是客户端生成的，发 {@link #MAX_PER_PLAYER}+1 个垃圾 requestId 就能把真正
 * 要防重放的那一条挤出账本，然后重发原来的 requestId —— 再执行一次。
 * 而 §15.9 的判据是"连发 100 次"，100 &lt; 256，<b>判据全绿，洞照样在</b>。
 *
 * <p>所以：<b>只淘汰已结束且已过期的条目</b>；全是不可淘汰的条目时，新请求直接
 * {@link ScriptErrorCode#RATE_LIMITED}，<b>不驱逐</b>。
 *
 * <h2>结果太大就不存，重放时回 UNKNOWN</h2>
 *
 * §15.6 要"返回上次的结果"，而结果最大 4 KiB。全存的话每玩家 256 条就是 1 MiB，
 * 100 人在线 100 MiB，而账本保留 24 小时、下线的人也占着。
 * 所以只有 ≤ {@link #REPLAYABLE_DATA_MAX} 的结果进账本；更大的重放命中时回
 * {@link ScriptErrorCode#UNKNOWN} —— 那正是这个码的语义（结果不明 + 绝不自动重试）。
 *
 * <p>不是线程安全的：只在服务器主线程上用（落地与准入都在主线程）。
 */
public final class IdempotencyLedger {

    /** 单玩家的条数上限（§15.6）。 */
    public static final int MAX_PER_PLAYER = 256;

    /** 保留期（§15.6）。 */
    public static final long TTL_MS = 24L * 3600 * 1000;

    /** 结果大于它就不进账本，重放时回 UNKNOWN。理由见类注释。 */
    public static final int REPLAYABLE_DATA_MAX = 512;

    /** 账本满且一条都淘汰不掉时，让客户端等多久再来。 */
    public static final long FULL_RETRY_AFTER_MS = 5_000L;

    /** 一条的状态。{@code RESERVED} 之后的落地状态机是 S20 的事，这里只要分得出"结束没结束"。 */
    public enum State {
        /** 收下了、还没出结果。重复投递回 {@link ScriptErrorCode#IN_PROGRESS}。 */
        RESERVED,
        /** 出结果了。重复投递回上次的结果。 */
        SETTLED
    }

    /** 账本里的一条。 */
    public record Entry(State state, byte[] paramsDigest, long at,
                        ScriptErrorCode code, byte[] data, long retryAfterMs, long stateRevision) {
    }

    /** 查一次的结论。 */
    public sealed interface Verdict {
        /** 没见过，可以执行。调用方拿到它之后必须 {@link #reserve} 或什么都不做。 */
        record Fresh() implements Verdict {
        }

        /** 见过且已出结果，把它原样回给客户端。 */
        record Replay(ScriptErrorCode code, byte[] data, long retryAfterMs, long stateRevision) implements Verdict {
        }

        /** 见过、还在处理。 */
        record InProgress() implements Verdict {
        }

        /** 同一个 requestId 换了参数 —— 客户端有 bug，或在试探。 */
        record ParamsChanged() implements Verdict {
        }

        /** 账本满了，且一条都淘汰不掉。 */
        record Full(long retryAfterMs) implements Verdict {
        }
    }

    /** 玩家 → （幂等键十六进制 → 条目）。用 LinkedHashMap 保插入序，扫描淘汰时顺序稳定。 */
    private final Map<UUID, LinkedHashMap<String, Entry>> byPlayer = new java.util.HashMap<>();

    /** 现在几点。测试喂一个假的进来。 */
    private final java.util.function.LongSupplier clock;

    public IdempotencyLedger(java.util.function.LongSupplier clock) {
        this.clock = clock;
    }

    /** §15.6 那张四行表。<b>不改状态</b>，只回答"该怎么办"。 */
    public Verdict check(UUID player, byte[] key, byte[] paramsDigest) {
        LinkedHashMap<String, Entry> box = byPlayer.get(player);
        if (box == null) return new Verdict.Fresh();
        Entry e = box.get(IdempotencyKey.hex(key));
        if (e == null) {
            return box.size() >= MAX_PER_PLAYER && !hasExpired(box)
                    ? new Verdict.Full(FULL_RETRY_AFTER_MS)
                    : new Verdict.Fresh();
        }
        if (!java.util.Arrays.equals(e.paramsDigest(), paramsDigest)) return new Verdict.ParamsChanged();
        if (e.state() == State.RESERVED) return new Verdict.InProgress();
        return new Verdict.Replay(e.code(), e.data(), e.retryAfterMs(), e.stateRevision());
    }

    /** 记下"开始处理了"。调用方在 {@link Verdict.Fresh} 之后调。 */
    public void reserve(UUID player, byte[] key, byte[] paramsDigest) {
        LinkedHashMap<String, Entry> entries = box(player);
        String hex = IdempotencyKey.hex(key);
        if (!entries.containsKey(hex) && entries.size() >= MAX_PER_PLAYER) {
            evictExpired(entries);
        }
        if (!entries.containsKey(hex) && entries.size() >= MAX_PER_PLAYER) {
            throw new IllegalStateException("reserve called after a Full verdict");
        }
        entries.put(hex,
                new Entry(State.RESERVED, paramsDigest, clock.getAsLong(),
                        ScriptErrorCode.INTERNAL, new byte[0], 0, 0));
    }

    /**
     * 记下结果。{@code data} 超过 {@link #REPLAYABLE_DATA_MAX} 时<b>不存它</b>，
     * 并把码改记成 {@link ScriptErrorCode#UNKNOWN} —— 重放时才不会回一个空 data 冒充成功。
     */
    public void settle(UUID player, byte[] key, ScriptErrorCode code, byte[] data,
                       long retryAfterMs, long stateRevision) {
        LinkedHashMap<String, Entry> box = box(player);
        String hex = IdempotencyKey.hex(key);
        Entry old = box.get(hex);
        byte[] digest = old == null ? new byte[0] : old.paramsDigest();
        boolean tooBig = data != null && data.length > REPLAYABLE_DATA_MAX;
        box.put(hex, new Entry(State.SETTLED, digest, clock.getAsLong(),
                tooBig ? ScriptErrorCode.UNKNOWN : code,
                tooBig ? new byte[0] : (data == null ? new byte[0] : data),
                retryAfterMs, stateRevision));
    }

    /** 这个玩家现在有几条。只给测试与日志用。 */
    public int size(UUID player) {
        LinkedHashMap<String, Entry> box = byPlayer.get(player);
        return box == null ? 0 : box.size();
    }

    /** 玩家退出时不清 —— 账本保留 24 小时，跨重连命中正是它存在的理由（见 IdempotencyKey）。 */
    public void sweep() {
        long now = clock.getAsLong();
        Iterator<Map.Entry<UUID, LinkedHashMap<String, Entry>>> it = byPlayer.entrySet().iterator();
        while (it.hasNext()) {
            LinkedHashMap<String, Entry> box = it.next().getValue();
            box.entrySet().removeIf(e -> expired(e.getValue(), now));
            if (box.isEmpty()) it.remove();
        }
    }

    private LinkedHashMap<String, Entry> box(UUID player) {
        return byPlayer.computeIfAbsent(player, p -> new LinkedHashMap<>());
    }

    private boolean hasExpired(LinkedHashMap<String, Entry> box) {
        long now = clock.getAsLong();
        for (Map.Entry<String, Entry> e : box.entrySet()) {
            if (expired(e.getValue(), now)) return true;
        }
        return false;
    }

    /** Mutation belongs to reserve/sweep, never to the read-only {@link #check} decision. */
    private void evictExpired(LinkedHashMap<String, Entry> box) {
        long now = clock.getAsLong();
        box.entrySet().removeIf(e -> expired(e.getValue(), now));
    }

    private static boolean expired(Entry e, long now) {
        return e.state() == State.SETTLED && now - e.at() >= TTL_MS;
    }
}
