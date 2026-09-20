package com.november.mcphone.core.script.engine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 连续超预算就临时禁用（施工方案 §16.6、§16.7）。
 *
 * <h2>为什么主键是 (App, 玩家) 而不是 App</h2>
 *
 * §16.6 只说"连续 3 次则临时禁用该 App 的后端 5 分钟"。<b>按 App 计数的话，
 * 任何一个玩家挑一个吃 CPU 的动作连调 3 次，就把这个 App 对所有人关 5 分钟</b> ——
 * 而 §32.7 的"限量抢购"正是最值得这么打的场景：活动开场把它打掉五分钟。
 *
 * <p>所以主键是 {@code (appId, player)}：谁超预算禁谁。
 * App 级的熔断单列一条，要<b>不同玩家</b>各自触发才累计 —— 那才是"这个脚本真写坏了"的信号。
 */
public final class StrikeTracker {

    private final Thread owner = Thread.currentThread();

    /** 单个玩家连续几次超预算就禁他。 */
    public static final int PLAYER_STRIKES = 3;

    /** 禁多久（§16.6）。 */
    public static final long PLAYER_BAN_MS = 5 * 60_000L;

    /** 多少个<b>不同</b>玩家在窗口内各自触发，才熔断整个 App。 */
    public static final int APP_DISTINCT_PLAYERS = 5;

    /** App 级熔断的统计窗口。 */
    public static final long APP_WINDOW_MS = 60_000L;

    /** 复合键的分隔符，取 ASCII 的单元分隔符：id 与 uuid 里都不会出现它。 */
    private static final char SEP = 31;

    private record PlayerState(int strikes, long bannedUntil) {
    }

    private final Map<String, PlayerState> players = new HashMap<>();
    private final Map<String, Set<UUID>> appOffenders = new HashMap<>();
    private final Map<String, Long> appWindowStart = new HashMap<>();
    private final Map<String, Long> appBannedUntil = new HashMap<>();
    private final LongSupplier clock;

    public StrikeTracker(LongSupplier clock) {
        this.clock = clock;
    }

    /** 现在允不允许这个玩家调这个 App 的后端。 */
    public boolean allowed(String appId, UUID player) {
        requireOwnerThread();
        long now = clock.getAsLong();
        Long appUntil = appBannedUntil.get(appId);
        if (appUntil != null && now < appUntil) return false;
        PlayerState s = players.get(key(appId, player));
        return s == null || now >= s.bannedUntil();
    }

    /** 记一次超预算。返回这一次是不是把人禁了。 */
    public boolean recordAbort(String appId, UUID player) {
        requireOwnerThread();
        long now = clock.getAsLong();
        String k = key(appId, player);
        PlayerState s = players.getOrDefault(k, new PlayerState(0, 0));
        int strikes = s.strikes() + 1;

        if (strikes >= PLAYER_STRIKES) {
            players.put(k, new PlayerState(0, now + PLAYER_BAN_MS));
            noteAppOffender(appId, player, now);
            return true;
        }
        players.put(k, new PlayerState(strikes, s.bannedUntil()));
        return false;
    }

    /** 记一次正常结束。<b>"连续"就是这里清零的。</b> */
    public void recordOk(String appId, UUID player) {
        requireOwnerThread();
        String k = key(appId, player);
        PlayerState s = players.get(k);
        if (s != null && s.strikes() > 0) players.put(k, new PlayerState(0, s.bannedUntil()));
    }

    /** 这个玩家被禁到什么时候，没被禁返回 0。管理界面读它。 */
    public long bannedUntil(String appId, UUID player) {
        requireOwnerThread();
        PlayerState s = players.get(key(appId, player));
        return s == null ? 0 : s.bannedUntil();
    }

    /** 整个 App 被熔断到什么时候，没熔断返回 0。 */
    public long appBannedUntil(String appId) {
        requireOwnerThread();
        return appBannedUntil.getOrDefault(appId, 0L);
    }

    private void noteAppOffender(String appId, UUID player, long now) {
        requireOwnerThread();
        long start = appWindowStart.getOrDefault(appId, 0L);
        if (now - start >= APP_WINDOW_MS) {
            appWindowStart.put(appId, now);
            appOffenders.put(appId, new HashSet<>());
        }
        Set<UUID> set = appOffenders.computeIfAbsent(appId, a -> new HashSet<>());
        set.add(player);
        if (set.size() >= APP_DISTINCT_PLAYERS) {
            appBannedUntil.put(appId, now + PLAYER_BAN_MS);
            set.clear();
        }
    }

    private static String key(String appId, UUID player) {
        return appId + SEP + player;
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("StrikeTracker may only be accessed by its owner thread");
        }
    }
}
