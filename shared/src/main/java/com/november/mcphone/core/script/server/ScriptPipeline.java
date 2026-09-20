package com.november.mcphone.core.script.server;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.net.ScriptErrorCode;
import com.november.mcphone.core.script.net.ScriptProtocol;
import com.november.mcphone.core.script.net.ScriptRpc;
import com.november.mcphone.core.script.net.ScriptRpcResult;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 一次 {@link ScriptRpc} 从收下到落地（施工方案 §15.5、§15.6）。
 *
 * <h2>四层，不是方案画的三层</h2>
 *
 * 分层与理由写在 {@link ScriptWorkers} 的类注释里。这个类是<b>主线程</b>那两层：
 * 准入（{@link #accept}）与落地（{@link #land}）。
 *
 * <h2>准入的顺序是有讲究的</h2>
 *
 * <ol>
 *   <li><b>协议号</b> —— 对不上回 {@link ScriptErrorCode#VERSION_MISMATCH}，<b>不断线</b>。
 *       1.21.1-fabric 上没有任何加载器级的版本闸，这个字段是唯一的一道</li>
 *   <li><b>连接 epoch</b> —— 过期的一律 {@link ScriptErrorCode#INVALID_ARGUMENT}，
 *       <b>不入账本、不计限流</b>：它是传输层丢弃，不是一次业务请求</li>
 *   <li><b>部署与动作</b> —— 两轴都不在就 {@link ScriptErrorCode#NOT_DEPLOYED}，<b>连桶都不建</b></li>
 *   <li><b>限流</b> —— 排在部署之后，这样伪造的 appId/actionId 撑不爆桶表</li>
 *   <li><b>幂等账本</b> —— 排在限流之后，这样重放攻击先被限流挡掉一层</li>
 * </ol>
 *
 * <p>不是线程安全的：只在服务器主线程上用。
 */
public final class ScriptPipeline {

    /** 队列满时让客户端等多久。 */
    public static final long BUSY_RETRY_AFTER_MS = 1_000L;

    /** 连接 epoch 过期的文案键。 */
    public static final String KEY_STALE_CONNECTION = "mcphone.script.stale_connection";

    /** 服务器忙的文案键。<b>与"你太快了"分开</b>：一个该退避，一个该稍后再试。 */
    public static final String KEY_SERVER_BUSY = "mcphone.script.server_busy";

    /** App 已部署、但请求的动作没在包里声明。码仍是 {@code NOT_DEPLOYED}（两轴都不在），文案分开。 */
    public static final String KEY_NO_SUCH_ACTION = "mcphone.script.no_such_action";

    private final IdempotencyLedger ledger;
    private final ScriptRateLimiter limiter;
    private final DeploymentView deployments;
    private final AuthorityView authority;
    private final ActionEvaluator evaluator;
    private final UUID serverId;

    /** 玩家 → 这一次连接的 epoch。登录时写，登出时删。 */
    private final Map<UUID, Long> epochs = new HashMap<>();

    private long seq;

    public ScriptPipeline(UUID serverId, IdempotencyLedger ledger, ScriptRateLimiter limiter,
                          DeploymentView deployments, AuthorityView authority, ActionEvaluator evaluator) {
        this.serverId = serverId;
        this.ledger = ledger;
        this.limiter = limiter;
        this.deployments = deployments;
        this.authority = authority;
        this.evaluator = evaluator;
    }

    /** 玩家登录时给一个新 epoch，随握手下发。 */
    public long newEpoch(UUID player) {
        long e = System.nanoTime() ^ (player.getMostSignificantBits() * 31);
        epochs.put(player, e);
        return e;
    }

    /** 玩家登出。<b>只删 epoch，不清账本</b> —— 账本保留 24 小时，跨重连命中正是它存在的理由。 */
    public void forget(UUID player) {
        epochs.remove(player);
    }

    /**
     * 准入。<b>在服务器主线程上调</b>（门面已经保证了）。
     *
     * @param send 立刻要回给客户端的结果走它；异步的那一条由 {@link #land} 之后自己回
     */
    public void accept(ScriptRpc rpc, PlayerSnapshot player, Consumer<ScriptRpcResult> send) {
        UUID id = player.uuid();

        if (rpc.protocol() != ScriptProtocol.PROTOCOL) {
            send.accept(ScriptRpcResult.fail(rpc.requestId(), ScriptErrorCode.VERSION_MISMATCH));
            return;
        }

        Long epoch = epochs.get(id);
        if (epoch == null || epoch != rpc.connectionEpoch()) {
            // 回一个响应而不是静默丢弃：§15.1 写死同 App 最多 4 个未完成调用，
            // 静默丢 4 个就把 App 卡死在 IN_PROGRESS，玩家只能重开
            send.accept(new ScriptRpcResult(rpc.requestId(), ScriptErrorCode.INVALID_ARGUMENT,
                    new byte[0], KEY_STALE_CONNECTION, java.util.List.of(), 0, 0));
            return;
        }

        if (!deployments.deployed(rpc.appId())) {
            send.accept(ScriptRpcResult.fail(rpc.requestId(), ScriptErrorCode.NOT_DEPLOYED));
            return;
        }
        if (!deployments.hasAction(rpc.appId(), rpc.actionId())) {
            // 部署在、但这个动作没在包里声明：还是 NOT_DEPLOYED（两轴都不在），但给一条自己的文案键，
            // 别让玩家看到"本服没有这个 App"（它明明在）——对抗组 Q1 的粒度修正
            send.accept(new ScriptRpcResult(rpc.requestId(), ScriptErrorCode.NOT_DEPLOYED,
                    new byte[0], KEY_NO_SUCH_ACTION, java.util.List.of(), 0, 0));
            return;
        }

        String serverRev = deployments.deployRev(rpc.appId());
        if (serverRev != null && !serverRev.equals(rpc.deployRev())) {
            send.accept(ScriptRpcResult.fail(rpc.requestId(), ScriptErrorCode.VERSION_MISMATCH));
            return;
        }

        ScriptRateLimiter.Decision d = limiter.allow(id, rpc.actionId());
        if (!d.allowed()) {
            send.accept(ScriptRpcResult.rateLimited(rpc.requestId(), d.retryAfterMs(),
                    ScriptErrorCode.RATE_LIMITED.defaultMessageKey()));
            return;
        }

        byte[] key = IdempotencyKey.of(serverId, id, rpc.appId(), rpc.deployRev(), rpc.actionId(), rpc.requestId());
        byte[] digest = IdempotencyKey.digestOf(rpc.params());
        IdempotencyLedger.Verdict v = ledger.check(id, key, digest);

        if (v instanceof IdempotencyLedger.Verdict.Replay r) {
            send.accept(new ScriptRpcResult(rpc.requestId(), r.code(), r.data(),
                    r.code().defaultMessageKey(), java.util.List.of(), r.retryAfterMs(), r.stateRevision()));
            return;
        }
        if (v instanceof IdempotencyLedger.Verdict.InProgress) {
            send.accept(ScriptRpcResult.fail(rpc.requestId(), ScriptErrorCode.IN_PROGRESS));
            return;
        }
        if (v instanceof IdempotencyLedger.Verdict.ParamsChanged) {
            send.accept(ScriptRpcResult.fail(rpc.requestId(), ScriptErrorCode.INVALID_ARGUMENT));
            return;
        }
        if (v instanceof IdempotencyLedger.Verdict.Full f) {
            send.accept(ScriptRpcResult.rateLimited(rpc.requestId(), f.retryAfterMs(), KEY_SERVER_BUSY));
            return;
        }

        ledger.reserve(id, key, digest);
        ActionEvaluator.Request req = new ActionEvaluator.Request(
                rpc.appId(), rpc.actionId(), rpc.params(), player, rpc.deployRev(), ++seq);

        boolean taken = evaluator.submit(req, outcome -> {
            ScriptRpcResult result = land(rpc, id, key, outcome);
            send.accept(result);
        });

        if (!taken) {
            // 队列满。把刚写的 RESERVED 结掉，否则这个键会一直卡在 IN_PROGRESS 上
            ledger.settle(id, key, ScriptErrorCode.RATE_LIMITED, new byte[0], BUSY_RETRY_AFTER_MS, 0);
            send.accept(ScriptRpcResult.rateLimited(rpc.requestId(), BUSY_RETRY_AFTER_MS, KEY_SERVER_BUSY));
        }
    }

    /**
     * 落地。<b>在服务器主线程上调</b>，而且<b>落地前重查一次授权</b>（§15.5 第二条）。
     *
     * <p>不能沿用求值开始时的权限：请求排队期间 OP 可能撤销了。
     * 这个方法是 §15.9 那条"请求在队列里时撤销授权 → 落地前被拒，效果没发生"的落点。
     */
    public ScriptRpcResult land(ScriptRpc rpc, UUID player, byte[] key, ActionEvaluator.Outcome outcome) {
        try {
            if (!authority.allows(player, rpc.appId(), rpc.actionId())) {
                // 重查没过：意图一条都不落地
                ledger.settle(player, key, ScriptErrorCode.NOT_AUTHORIZED, new byte[0], 0, 0);
                return ScriptRpcResult.fail(rpc.requestId(), ScriptErrorCode.NOT_AUTHORIZED);
            }
            ledger.settle(player, key, outcome.code(), outcome.data(), outcome.retryAfterMs(), outcome.stateRevision());
            return new ScriptRpcResult(rpc.requestId(), outcome.code(), outcome.data(),
                    outcome.messageKey(), outcome.messageArgs(), outcome.retryAfterMs(), outcome.stateRevision());
        } catch (Throwable failure) {
            // No intent has been applied by this S15 landing layer yet. Close RESERVED even when
            // an authority implementation is broken; otherwise one callback can pin the key forever.
            MCphone.LOGGER.error("[MCphone] script landing failed app={} action={} request={}",
                    rpc.appId(), rpc.actionId(), rpc.requestId(), failure);
            ledger.settle(player, key, ScriptErrorCode.INTERNAL, new byte[0], 0, 0);
            return ScriptRpcResult.fail(rpc.requestId(), ScriptErrorCode.INTERNAL);
        }
    }
}
