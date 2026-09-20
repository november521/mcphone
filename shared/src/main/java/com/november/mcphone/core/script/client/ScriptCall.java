package com.november.mcphone.core.script.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.net.ScriptErrorCode;
import com.november.mcphone.core.script.net.ScriptProtocol;
import com.november.mcphone.core.script.net.ScriptResultRouter;
import com.november.mcphone.core.script.net.ScriptRpc;
import com.november.mcphone.core.script.net.ScriptRpcResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 客户端发出的 {@link ScriptRpc}（施工方案 §15.1、§15.9 的客户端那一半）。
 *
 * <h2>回填三元组，一个都不能少</h2>
 *
 * <ul>
 *   <li>{@code connectionEpoch} —— {@link ClientHandshake#connectionEpoch()}，<b>登录握手那一刻的标签</b>。
 *       服务端拿它挡旧连接的迟到响应；没握手时是 0，请求不会被发出去（见下）。</li>
 *   <li>{@code deployRev} —— {@link ClientHandshake#deployment(String)} 里服务端下发的包摘要。
 *       客户端<b>不自己推断</b>版本：本地装的是哪份包，服务端比这里清楚。</li>
 *   <li>{@code frontendDigest} —— 本地前端算出来的摘要。<b>这不是安全边界</b>（§13.3）：
 *       它只用于服务端回显"界面被本地改过"，恶意客户端可以伪造，任何判定都不许依赖它。</li>
 * </ul>
 *
 * <h2>没握手时不发，客户端合成 {@code UNAVAILABLE}</h2>
 *
 * 服务端没装 MCphone、握手失败、批次作废，这三种情况客户端都拿不到 epoch。
 * 发出去只会换来一条 {@code INVALID_ARGUMENT + stale_connection}，对玩家是"重试"这种
 * 误导性的提示；{@link ScriptErrorCode#UNAVAILABLE} 才是这个类注释里写死的
 * "服务端没装 MCphone / 功能被服主关了"（§15.4 把它标成<b>客户端合成</b>）。
 *
 * <h2>同一个 App 最多 4 个在飞（§15.1）</h2>
 *
 * 超了立即回 {@code IN_PROGRESS}，<b>不排队</b> —— 排队会让玩家连点之后看到一串迟到的 toast。
 *
 * <h2>没有客户端超时（定向对抗 ADV-S2b-4 的裁定）</h2>
 *
 * 名额回收只依赖三条，任何一条断了都不该由"客户端超时"来补：
 * <ol>
 *   <li><b>服务端对每条请求恰好回一条结果</b>（管线八条返回路径条条回包，队列满也回）；</li>
 *   <li><b>连接断开</b>时三平台在 {@code LoggingOut} 调 {@link #clear()}，名额与 pending 一起回收；</li>
 *   <li><b>本地编/发失败</b>时回滚名额并本地合成 {@code UNAVAILABLE}（见 {@link #call}）。</li>
 * </ol>
 * 加超时是反的：名额放掉之后结果仍可能到达并已在服务端落地（甚至钱已动），玩家看不到反馈
 * 就会再点 —— 那是<b>新的 requestId / 新的幂等键</b>，服务端会再执行一次。真要回收，必须由
 * 服务端定义超时并回 {@code UNKNOWN}（"结果未知，别自动重试"），客户端只展示。
 *
 * <h2>线程</h2>
 *
 * 生产里的调用点（{@code ScriptPage} 的点击）与结果回调（网络层已切回主线程）都在客户端
 * 主线程上。表本身用并发容器，是为了 {@code docs/} 断言能在别的线程上喂结果。
 */
public final class ScriptCall {

    /** 同一个 App 同时最多几个未完成调用（§15.1）。 */
    public static final int MAX_IN_FLIGHT = 4;

    private ScriptCall() {
    }

    /** 平台装进来的发包器。各平台包类型不同（1.20.1 直接发 record，1.21.1 要包一层）。 */
    @FunctionalInterface
    public interface Sender {
        void send(ScriptRpc rpc);
    }

    private record Pending(String appId, String actionId, Consumer<ScriptRpcResult> callback) {
    }

    private static volatile Sender sender;

    private static final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

    /**
     * 单调递增 + 随机高位（§15.3）。低 16 位是计数器，高 48 位是这次客户端启动的随机前缀 ——
     * 同一个玩家的两次客户端会话几乎不可能撞上同一个 {@code requestId}，而幂等账本跨重连
     * 保留 24 小时，撞了就会被当成"重放"。
     */
    private static final AtomicLong REQUEST_ID = new AtomicLong(seed());

    private static long seed() {
        return (ThreadLocalRandom.current().nextLong() & 0xFFFFFFFFFFFF0000L) | 1L;
    }

    /** 由各平台在注册网络包时调用。 */
    public static void installSender(Sender s) {
        sender = s;
    }

    /** 客户端初始化时调一次：把结果接收方接到 {@link #onResult}（与握手的 installPush 不互相挤）。 */
    public static void install() {
        ScriptResultRouter.installResult(ScriptCall::onResult);
    }

    /** 断线/换服：丢掉在飞表。旧连接的结果回来时 requestId 已经无主，被安静忽略。 */
    public static void clear() {
        if (!pending.isEmpty()) {
            MCphone.LOGGER.debug("[MCphone] 连接断开，丢弃 {} 个在飞的脚本调用", pending.size());
        }
        pending.clear();
        inFlight.clear();
    }

    /**
     * 生产入口：App 点了一下要调某个动作。字段从握手状态回填，见类注释。
     *
     * @param localFrontendDigest 本地前端摘要；单文件 .vue 等算不出来的场合传 null，
     *                            回退到握手那一格（没有可比的一份，不谎报"改了"）
     * @return 这次调用的 requestId（结果通过 callback 回来）
     */
    public static long call(String appId, String actionId, byte[] params, String localFrontendDigest,
                            Consumer<ScriptRpcResult> callback) {
        long id = REQUEST_ID.getAndIncrement();
        Sender s = sender;
        if (s == null) {
            return finishNow(id, ScriptErrorCode.UNAVAILABLE, callback);
        }
        long epoch = ClientHandshake.connectionEpoch();
        if (epoch == 0L) {
            return finishNow(id, ScriptErrorCode.UNAVAILABLE, callback);
        }
        if (!reserve(appId)) {
            return finishNow(id, ScriptErrorCode.IN_PROGRESS, callback);
        }

        ClientHandshake.Entry entry = ClientHandshake.deployment(appId);
        String deployRev = entry == null ? "" : entry.deployRev();
        String digest = localFrontendDigest != null
                ? localFrontendDigest
                : (entry == null ? "" : entry.frontendDigest());

        // 发送这一步是"本地编码 + 交给网络层"，字段超限（如 appId > 64 字符）会当场抛。
        // 不兜的话异常冲出点击处理 = 崩客户端，而名额与 pending 已经记上，4 次之后这个 App
        // 会被卡死成永远 IN_PROGRESS。失败必须回滚成一次本地 UNAVAILABLE（ADV-S2b-2）
        pending.put(id, new Pending(appId, actionId, callback));
        try {
            s.send(new ScriptRpc(ScriptProtocol.PROTOCOL, id, epoch, appId, deployRev, actionId,
                    params == null ? new byte[0] : params, digest));
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            pending.remove(id);
            release(appId);
            MCphone.LOGGER.error("[MCphone] 脚本调用在本地发不出去（已回滚名额）id={} app={} action={}",
                    id, appId, actionId, t);
            return finishNow(id, ScriptErrorCode.UNAVAILABLE, callback);
        }
        return id;
    }

    /**
     * 不走回填、按给定字段直接发一条。给<b>验收/对抗用</b>的客户端调试命令留的口子
     * （伪造 {@code deployRev}/{@code frontendDigest} 那几条剧本）；普通 App 走
     * {@link #call}，那里字段是回填的，作者改不了。
     *
     * <p>epoch 照样用实时的握手值（否则请求到不了部署判定那一层）；没有握手时按 0 发出去
     * 也是有意的 —— 调试命令要能复现"过期连接"这一档。它不占 {@link #MAX_IN_FLIGHT} 的名额。
     */
    public static long sendRaw(String appId, String actionId, String deployRev, String frontendDigest,
                               Consumer<ScriptRpcResult> callback) {
        long id = REQUEST_ID.getAndIncrement();
        Sender s = sender;
        if (s == null) {
            return finishNow(id, ScriptErrorCode.UNAVAILABLE, callback);
        }
        pending.put(id, new Pending(appId, actionId, callback));
        try {
            s.send(new ScriptRpc(ScriptProtocol.PROTOCOL, id, ClientHandshake.connectionEpoch(),
                    appId, deployRev == null ? "" : deployRev, actionId, new byte[0],
                    frontendDigest == null ? "" : frontendDigest));
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            pending.remove(id);
            MCphone.LOGGER.error("[MCphone] 调试调用在本地发不出去 id={} app={} action={}", id, appId, actionId, t);
            return finishNow(id, ScriptErrorCode.UNAVAILABLE, callback);
        }
        return id;
    }

    /** 由网络层指过来（{@link ScriptResultRouter}）。 */
    static void onResult(ScriptRpcResult result) {
        Pending p = pending.remove(result.requestId());
        if (p == null) {
            // 重连之后旧连接的迟到结果（在飞表已清）、或调试命令之外的回包：安静丢弃
            MCphone.LOGGER.debug("[MCphone] 收到无主的脚本结果 req={} code={}", result.requestId(), result.code());
            return;
        }
        release(p.appId());
        if (p.callback() == null) return;
        try {
            p.callback().accept(result);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            MCphone.LOGGER.error("[MCphone] 脚本结果回调抛了 app={} action={}",
                    p.appId(), p.actionId(), t);
        }
    }

    // ---------------------------------------------------------------- 内部

    /** 同一个 App 的在飞名额。拿到了才发，拿不到回 IN_PROGRESS（不排队）。 */
    private static boolean reserve(String appId) {
        AtomicInteger n = inFlight.computeIfAbsent(appId, k -> new AtomicInteger());
        while (true) {
            int cur = n.get();
            if (cur >= MAX_IN_FLIGHT) return false;
            if (n.compareAndSet(cur, cur + 1)) return true;
        }
    }

    private static void release(String appId) {
        AtomicInteger n = inFlight.get(appId);
        if (n != null && n.decrementAndGet() <= 0) {
            inFlight.remove(appId, n);
        }
    }

    /** 本地合成一个结果回给调用方：请求根本没有出门。 */
    private static long finishNow(long requestId, ScriptErrorCode code, Consumer<ScriptRpcResult> callback) {
        if (callback != null) {
            ScriptRpcResult local = new ScriptRpcResult(requestId, code, new byte[0],
                    code.defaultMessageKey(), List.of(), 0, 0);
            try {
                callback.accept(local);
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (Throwable t) {
                MCphone.LOGGER.error("[MCphone] 本地合成的脚本结果回调抛了 code={}", code, t);
            }
        }
        return requestId;
    }
}
