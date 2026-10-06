package com.november.mcphone.core.script.server;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 脚本 RPC 的限流（施工方案 §15.8）。<b>令牌桶，五个维度。</b>
 *
 * <h2>为什么不能用现成的 RequestThrottle</h2>
 *
 * 那个类的 {@code Kind} 是编译期枚举、只做最小间隔、<b>登出即清</b>。脚本要的是动态桶
 * （appId 与 actionId 都是运行时才知道的字符串），而且不能登出即清。
 *
 * <h2>玩法冷却绝不放这里</h2>
 *
 * 这里的桶会过期、会被回收。把"每天只能领一次"放进来，等于告诉玩家：重连一次就能再领。
 * 玩法冷却进 §20 的守卫存储。
 *
 * <h2>桶表本身也要防着被撑爆</h2>
 *
 * §15.8 只写了"未知 appId 不创建桶"。<b>那一条挡不住 actionId 这一轴</b>：
 * {@code actionId} 是客户端说了算的字符串（线上允许 64 个字符），
 * 拿一个<b>合法的</b> appId 配上无穷多个伪造 actionId，照样能把桶表撑爆。
 *
 * <p>所以建桶之前要过两道，<b>两道都在部署表上查</b>（§13.4 第 3 层）：appId 有没有已批准的部署、
 * actionId 在不在那个部署声明的动作里。不过就不建桶、不计数。
 * 再加一道兜底：单玩家的动作桶封顶 {@link #MAX_ACTION_BUCKETS_PER_PLAYER}，
 * 超出就退化到"每玩家总量"那个桶。
 *
 * <h2>回收是惰性的，因为这个仓库没有服务端 tick 钩子</h2>
 *
 * §15.8 要求"桶 5 分钟无活动即回收"。新开一个服务端 tick 钩子要动三个平台
 * （三处新接缝，还要重跑 updateSeamsDoc）。不值得：在 {@link #allow} 里顺带扫，
 * 加一道"桶数超硬上限就整表扫一遍"的兜底，零新接缝。
 *
 * <p>不是线程安全的：只在服务器主线程上用。
 */
public final class ScriptRateLimiter {

    /** 每玩家总量：20 次/秒，突发 40。 */
    public static final double PLAYER_RATE_PER_SEC = 20;
    public static final double PLAYER_BURST = 40;

    /** 每玩家每动作：5 次/秒。突发就是它自己。 */
    public static final double ACTION_RATE_PER_SEC = 5;
    public static final double ACTION_BURST = 5;

    /** 全服总量：500 次/秒。 */
    public static final double SERVER_RATE_PER_SEC = 500;
    public static final double SERVER_BURST = 500;

    /** 每玩家上传字节：512 KiB/分钟。 */
    public static final long UPLOAD_BYTES_PER_MIN = 512L * 1024;

    /** 桶多久没动就回收。 */
    public static final long IDLE_MS = 5 * 60_000L;

    /** 单玩家的动作桶封顶。超出退化到总量桶，理由见类注释。 */
    public static final int MAX_ACTION_BUCKETS_PER_PLAYER = 64;

    /** 玩家桶总数封顶。到了就整表扫一遍，扫不掉再拒。 */
    public static final int MAX_PLAYERS = 512;

    /** 允许，或者告诉调用方等多久。 */
    public record Decision(boolean allowed, long retryAfterMs) {
        static final Decision OK = new Decision(true, 0);

        /** 别改名叫 wait —— 那会撞上 Object.wait(long)，编译期就红。 */
        static Decision retry(long ms) {
            return new Decision(false, Math.max(1, ms));
        }
    }

    private static final class Bucket {
        double tokens;
        long at;

        Bucket(double tokens, long at) {
            this.tokens = tokens;
            this.at = at;
        }

        /** 攒令牌，然后看够不够一个。 */
        Decision take(double ratePerSec, double burst, long now) {
            double elapsed = (now - at) / 1000.0;
            tokens = Math.min(burst, tokens + elapsed * ratePerSec);
            at = now;
            if (tokens >= 1) {
                tokens -= 1;
                return Decision.OK;
            }
            // 还差 (1 - tokens) 个令牌，按速率折成毫秒
            return Decision.retry((long) Math.ceil((1 - tokens) / ratePerSec * 1000));
        }
    }

    private static final class PlayerState {
        final Bucket total;
        final Map<String, Bucket> actions = new HashMap<>();
        long uploadWindowStart;
        long uploadBytes;
        long lastSeen;

        PlayerState(long now) {
            this.total = new Bucket(PLAYER_BURST, now);
            this.uploadWindowStart = now;
            this.lastSeen = now;
        }
    }

    private final Map<UUID, PlayerState> players = new HashMap<>();
    private final Bucket server;
    private final LongSupplier clock;

    public ScriptRateLimiter(LongSupplier clock) {
        this.clock = clock;
        this.server = new Bucket(SERVER_BURST, clock.getAsLong());
    }

    /**
     * 一次 RPC 过不过。<b>调用方必须先确认 appId 与 actionId 都在已批准的部署里</b> ——
     * 没过就直接 {@code NOT_DEPLOYED}，<b>连桶都不要建</b>（§15.8）。
     *
     * <p>三个维度按<b>从贵到便宜</b>的顺序查，全服那个放最前：它被拒时不该先去给这个玩家建桶。
     */
    public Decision allow(UUID player, String actionId) {
        long now = clock.getAsLong();
        reclaim(now);

        Decision d = server.take(SERVER_RATE_PER_SEC, SERVER_BURST, now);
        if (!d.allowed()) return d;

        if(!players.containsKey(player)&&players.size()>=MAX_PLAYERS)return Decision.retry(1000);
        PlayerState p = players.computeIfAbsent(player, k -> new PlayerState(now));
        p.lastSeen = now;

        d = p.total.take(PLAYER_RATE_PER_SEC, PLAYER_BURST, now);
        if (!d.allowed()) return d;

        // 动作桶满了就不再建新的：已经过了总量那一关，退化成"只受总量限制"
        Bucket a = p.actions.get(actionId);
        if (a == null) {
            if (p.actions.size() >= MAX_ACTION_BUCKETS_PER_PLAYER) return Decision.OK;
            a = new Bucket(ACTION_BURST, now);
            p.actions.put(actionId, a);
        }
        return a.take(ACTION_RATE_PER_SEC, ACTION_BURST, now);
    }

    /** 上传字节配额，滚动一分钟窗口。超了就拒绝新的上传会话（§15.8）。 */
    public Decision allowUpload(UUID player, long bytes) {
        long now = clock.getAsLong();
        if(bytes<0)return Decision.retry(60000);
        reclaim(now);
        if(!players.containsKey(player)&&players.size()>=MAX_PLAYERS)return Decision.retry(1000);
        PlayerState p = players.computeIfAbsent(player, k -> new PlayerState(now));
        p.lastSeen = now;
        if (now - p.uploadWindowStart >= 60_000L) {
            p.uploadWindowStart = now;
            p.uploadBytes = 0;
        }
        if (p.uploadBytes + bytes > UPLOAD_BYTES_PER_MIN) {
            return Decision.retry(60_000L - (now - p.uploadWindowStart));
        }
        p.uploadBytes += bytes;
        return Decision.OK;
    }

    /** 现在有几个玩家桶。只给测试与日志用。 */
    public int playerCount() {
        return players.size();
    }

    /** 这个玩家有几个动作桶。只给测试用。 */
    public int actionBucketCount(UUID player) {
        PlayerState p = players.get(player);
        return p == null ? 0 : p.actions.size();
    }

    /** 惰性回收：顺手扫一遍闲置的。桶数到顶时无论如何都扫。 */
    private void reclaim(long now) {
        if (players.size() < MAX_PLAYERS) {
            // 便宜的那一半：只在表大到一定程度才扫，平时一次 map 查找都不多花
            if (players.size() < 64) return;
        }
        Iterator<Map.Entry<UUID, PlayerState>> it = players.entrySet().iterator();
        while (it.hasNext()) {
            PlayerState p = it.next().getValue();
            if (now - p.lastSeen >= IDLE_MS) {
                it.remove();
            } else {
                p.actions.entrySet().removeIf(e -> now - e.getValue().at >= IDLE_MS);
            }
        }
    }
}
