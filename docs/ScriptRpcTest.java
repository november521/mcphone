package com.november.mcphone.core.script.net;

import com.november.mcphone.core.script.server.ActionEvaluator;
import com.november.mcphone.core.script.server.ActionIntent;
import com.november.mcphone.core.script.server.AuthorityView;
import com.november.mcphone.core.script.server.DeploymentView;
import com.november.mcphone.core.script.server.IdempotencyKey;
import com.november.mcphone.core.script.server.IdempotencyLedger;
import com.november.mcphone.core.script.server.PlayerSnapshot;
import com.november.mcphone.core.script.server.ScriptPipeline;
import com.november.mcphone.core.script.server.ScriptRateLimiter;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 脚本 RPC 的纯逻辑部分（施工方案 §15.4 错误码表 / §15.6 幂等 / §15.8 限流 / §15.9 epoch 判定）。
 *
 * <p><b>这里测不了的</b>：三种环境能不能起、握手抓包、限流压测下 TPS 掉不掉 —— 都要服务器。
 * 理由与 S11 同一条：{@code docs/} 的断言测试是裸 {@code JavaExec}，没有 {@code Bootstrap.bootStrap()}。
 *
 * <p>跑法：{@code ./gradlew assertTests}（在 {@code platforms/<目标名>/} 下）。
 */
public class ScriptRpcTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) {
            failures.add(what + "  期望 " + expected + "，实际 " + actual);
        }
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures.add(what);
    }

    static final UUID SERVER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    static final UUID P2 = UUID.fromString("00000000-0000-0000-0000-000000000002");

    static PlayerSnapshot snap(UUID id) {
        return new PlayerSnapshot(id, "p", "minecraft:overworld", "survival", 0);
    }

    // ================================================================ §15.4 错误码表

    static void errorTable() {
        // §15.4 是 15 行；S18 按 §20.9/§23.4 的允许追加了第 16 个 INVENTORY_FULL（只许追加在末尾）
        eq(ScriptErrorCode.values().length, 16, "§15.4 的 15 行 + S18 追加的 INVENTORY_FULL");

        // 序号即身份：中间插一个就会让旧客户端把 A 当 B
        eq(ScriptErrorCode.OK.toWire(), 0, "OK 是 0");
        eq(ScriptErrorCode.INTERNAL.toWire(), 14, "INTERNAL 仍在第 15 位（旧客户端认得它）");
        eq(ScriptErrorCode.INVENTORY_FULL.toWire(), 15, "INVENTORY_FULL 追加在末尾");
        for (ScriptErrorCode c : ScriptErrorCode.values()) {
            eq(ScriptErrorCode.fromWire(c.toWire()), c, "往返 " + c);
            check(!c.defaultMessageKey().isEmpty(), c + " 要有可读文案键");
            check(c.defaultMessageKey().startsWith("mcphone.script.code."), c + " 的键有前缀");
        }

        // 认不出的序号不许抛：抛在解码里 = netty 断线 = 新服务端把旧客户端踢下线
        eq(ScriptErrorCode.fromWire(999), ScriptErrorCode.INTERNAL, "未来的码归 INTERNAL");
        eq(ScriptErrorCode.fromWire(-1), ScriptErrorCode.INTERNAL, "负数也是");
        check(!ScriptErrorCode.known(999), "认不出");
        check(ScriptErrorCode.known(0), "认得出 0");

        // UNKNOWN 必须存在（§15.4 专门论证过）
        boolean hasUnknown = false;
        for (ScriptErrorCode c : ScriptErrorCode.values()) if (c == ScriptErrorCode.UNKNOWN) hasUnknown = true;
        check(hasUnknown, "UNKNOWN 必须存在：少了它只能在 OK 与 INTERNAL 之间二选一，两边都会重复发奖或漏发");
    }

    // ================================================================ 线格式往返

    static FriendlyByteBuf buf() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    static void wireRoundTrip() {
        ScriptRpc rpc = new ScriptRpc(ScriptProtocol.PROTOCOL, 42L, 7L,
                "example:market", "rev1", "buy", new byte[]{1, 2, 3}, "abc");
        FriendlyByteBuf b = buf();
        ScriptRpc.encode(rpc, b);
        ScriptRpc back = ScriptRpc.decode(b);
        eq(back.requestId(), 42L, "requestId 往返");
        eq(back.connectionEpoch(), 7L, "epoch 往返");
        eq(back.appId(), "example:market", "appId 往返");
        eq(back.params().length, 3, "params 往返");
        eq(back.frontendDigest(), "abc", "frontendDigest 往返 —— §15.3 有它，§10.3 的示例漏了");
        eq(b.readableBytes(), 0, "读干净了");

        ScriptRpcResult r = new ScriptRpcResult(42L, ScriptErrorCode.COOLDOWN, new byte[]{9},
                "myapp.msg.late", List.of("a", "b"), 1500, 77);
        FriendlyByteBuf b2 = buf();
        ScriptRpcResult.encode(r, b2);
        ScriptRpcResult back2 = ScriptRpcResult.decode(b2);
        eq(back2.code(), ScriptErrorCode.COOLDOWN, "code 往返");
        eq(back2.messageArgs(), List.of("a", "b"), "args 往返");
        eq(back2.retryAfterMs(), 1500L, "retryAfterMs 往返");
        eq(back2.stateRevision(), 77L, "stateRevision 往返");
        eq(b2.readableBytes(), 0, "读干净了");

        ScriptPush p = new ScriptPush("example:market", "example:market/news", new byte[]{5}, 3);
        FriendlyByteBuf b3 = buf();
        ScriptPush.encode(p, b3);
        eq(ScriptPush.decode(b3).revision(), 3L, "push 往返");
    }

    /** 上限必须带着读：漏了就是解码侧的内存放大面。 */
    static void wireLimits() {
        FriendlyByteBuf b = buf();
        b.writeVarLong(1);
        b.writeVarInt(0);
        b.writeVarInt(ScriptProtocol.DATA_MAX + 1);      // 声称 data 比上限还大
        boolean threw = false;
        try {
            ScriptRpcResult.decode(b);
        } catch (RuntimeException e) {
            threw = true;
        }
        check(threw, "data 超上限必须在分配之前抛");
    }

    /** 宿主保留的 appId 与 topic：两个条件都满足才算宿主发的（脚本能自己选 topic）。 */
    static void hostPushGuard() {
        check(new ScriptPush(ScriptProtocol.HOST_APP_ID,
                ScriptProtocol.TOPIC_HANDSHAKE_BEGIN, new byte[0], 0).isHost(), "宿主的握手认得出");
        check(!new ScriptPush("example:evil",
                ScriptProtocol.TOPIC_HANDSHAKE_BEGIN, new byte[0], 0).isHost(),
                "脚本用宿主 topic 伪造握手：appId 对不上，拒");
        check(!new ScriptPush(ScriptProtocol.HOST_APP_ID,
                "example:evil/x", new byte[0], 0).isHost(), "宿主 appId 配非宿主 topic：拒");
        check(!"example:evil".startsWith(ScriptProtocol.HOST_TOPIC_PREFIX), "普通 appId 不在保留命名空间里");
    }

    // ================================================================ 握手装得下吗

    /** §15.7 的握手一个部署一条 push。按字段上限算，每条都必须 ≤ DATA_MAX。 */
    static void handshakeFits() {
        List<String> actions = new ArrayList<>();
        for (int i = 0; i < ScriptProtocol.MAX_ACTIONS_PER_DEPLOYMENT; i++) {
            actions.add("x".repeat(ScriptProtocol.ID_MAX));           // 每个动作都顶到 64 字符
        }
        byte[] one = Handshake.encodeDeployment(new Handshake.Deployment(
                "x".repeat(ScriptProtocol.ID_MAX),
                "x".repeat(ScriptProtocol.ID_MAX),
                "x".repeat(ScriptProtocol.DIGEST_MAX),
                1, Long.MAX_VALUE, Long.MAX_VALUE, actions));
        check(one.length <= ScriptProtocol.DATA_MAX,
                "最坏情况下的一个部署要 " + one.length + " 字节，上限 " + ScriptProtocol.DATA_MAX);

        // 反过来记一笔：整张表塞一条 push 是装不下的，这正是拆成三种 topic 的理由
        check(20 * 221 > ScriptProtocol.DATA_MAX,
                "20 个部署按典型值算 " + (20 * 221) + " 字节，一条 push 装不下");

        Handshake.Begin begin = new Handshake.Begin(SERVER, "服务器", 1, 12345L, 20,
                new Handshake.Features(true, false, true));
        Handshake.Begin b2 = Handshake.decodeBegin(Handshake.encodeBegin(begin));
        eq(b2.serverId(), SERVER, "serverId 往返");
        eq(b2.epoch(), 12345L, "epoch 往返");
        eq(b2.count(), 20, "count 往返");
        check(b2.features().externalFetch() && !b2.features().vault(), "features 往返");
        eq(Handshake.decodeEnd(Handshake.encodeEnd(new Handshake.End(9L))).epoch(), 9L, "end 往返");

        Handshake.Deployment d = Handshake.decodeDeployment(Handshake.encodeDeployment(
                new Handshake.Deployment("example:market", "rev1", "digest", 0, 7L, 1_700_000_000_000L,
                        List.of("buy", "sell"))));
        eq(d.actions(), List.of("buy", "sell"), "actions 往返");
        eq(d.approvalRevision(), 7L, "批准轴往返（详情页的版本 N）");
        eq(d.approvedAt(), 1_700_000_000_000L, "批准时间往返（详情页来源行）");
    }

    // ================================================================ §15.6 幂等键

    static void idempotencyKey() {
        byte[] a = IdempotencyKey.of(SERVER, P1, "app", "rev", "act", 1);
        byte[] b = IdempotencyKey.of(SERVER, P1, "app", "rev", "act", 1);
        eq(IdempotencyKey.hex(a), IdempotencyKey.hex(b), "同样的输入同样的键");
        eq(a.length, 32, "SHA-256 是 32 字节");

        // 六个输入各自都要影响结果
        check(!IdempotencyKey.hex(a).equals(IdempotencyKey.hex(
                IdempotencyKey.of(SERVER, P2, "app", "rev", "act", 1))), "换玩家换键");
        check(!IdempotencyKey.hex(a).equals(IdempotencyKey.hex(
                IdempotencyKey.of(SERVER, P1, "app", "rev", "act", 2))), "换 requestId 换键");
        check(!IdempotencyKey.hex(a).equals(IdempotencyKey.hex(
                IdempotencyKey.of(SERVER, P1, "app2", "rev", "act", 1))), "换 appId 换键");
        check(!IdempotencyKey.hex(a).equals(IdempotencyKey.hex(
                IdempotencyKey.of(SERVER, P1, "app", "rev2", "act", 1))), "换 deployRev 换键");
        check(!IdempotencyKey.hex(a).equals(IdempotencyKey.hex(
                IdempotencyKey.of(SERVER, P1, "app", "rev", "act2", 1))), "换 actionId 换键");

        // 长度前缀：既不能在相邻字段之间借位，也不能靠把旧分隔符挪到另一个字段来碰撞
        check(!IdempotencyKey.hex(IdempotencyKey.of(SERVER, P1, "ab", "c", "act", 1))
                        .equals(IdempotencyKey.hex(IdempotencyKey.of(SERVER, P1, "a", "bc", "act", 1))),
                "长度前缀阻止相邻字段借位");
        check(!IdempotencyKey.hex(IdempotencyKey.of(SERVER, P1, "a\u001fb", "c", "act", 1))
                        .equals(IdempotencyKey.hex(IdempotencyKey.of(SERVER, P1, "a", "b\u001fc", "act", 1))),
                "字段里即使含旧分隔符也不能碰撞");
        check(!IdempotencyKey.hex(IdempotencyKey.of(SERVER, P1, null, "rev", "act", 1))
                        .equals(IdempotencyKey.hex(IdempotencyKey.of(SERVER, P1, "", "rev", "act", 1))),
                "null 与空串不能碰撞");
    }

    // ================================================================ §15.6 账本

    static IdempotencyLedger ledger(AtomicLong clock) {
        return new IdempotencyLedger(clock::get);
    }

    static void ledgerFourRows() {
        AtomicLong t = new AtomicLong(1000);
        IdempotencyLedger l = ledger(t);
        byte[] k = IdempotencyKey.of(SERVER, P1, "app", "rev", "act", 1);
        byte[] d = IdempotencyKey.digestOf(new byte[]{1});

        check(l.check(P1, k, d) instanceof IdempotencyLedger.Verdict.Fresh, "键不存在 → 正常处理");
        l.reserve(P1, k, d);
        check(l.check(P1, k, d) instanceof IdempotencyLedger.Verdict.InProgress, "RESERVED → IN_PROGRESS");

        l.settle(P1, k, ScriptErrorCode.OK, new byte[]{7}, 0, 5);
        Object v = l.check(P1, k, d);
        check(v instanceof IdempotencyLedger.Verdict.Replay, "已结算 → 返回上次结果");
        eq(((IdempotencyLedger.Verdict.Replay) v).code(), ScriptErrorCode.OK, "码是上次的");
        eq(((IdempotencyLedger.Verdict.Replay) v).stateRevision(), 5L, "revision 是上次的");

        byte[] other = IdempotencyKey.digestOf(new byte[]{9});
        check(l.check(P1, k, other) instanceof IdempotencyLedger.Verdict.ParamsChanged,
                "同 requestId 换参数 → INVALID_ARGUMENT");
    }

    /** 连发 100 次只执行一次 —— §15.9 原文那一条。 */
    static void hundredTimes() {
        AtomicLong t = new AtomicLong(1000);
        IdempotencyLedger l = ledger(t);
        byte[] k = IdempotencyKey.of(SERVER, P1, "app", "rev", "act", 1);
        byte[] d = IdempotencyKey.digestOf(new byte[]{1});

        int executed = 0;
        for (int i = 0; i < 100; i++) {
            if (l.check(P1, k, d) instanceof IdempotencyLedger.Verdict.Fresh) {
                executed++;
                l.reserve(P1, k, d);
                l.settle(P1, k, ScriptErrorCode.OK, new byte[]{1}, 0, 0);
            }
        }
        eq(executed, 1, "连发 100 次只执行一次");
        eq(l.size(P1), 1, "账本里只有一条");
    }

    /**
     * 驱逐攻击：§15.6 原文"超出按时间淘汰最旧的"会让攻击者把要防重放的那条挤出去。
     * 判据从 100 改成 MAX+1 才照得出来。
     */
    static void evictionAttack() {
        AtomicLong t = new AtomicLong(1000);
        IdempotencyLedger l = ledger(t);
        byte[] victim = IdempotencyKey.of(SERVER, P1, "app", "rev", "act", 1);
        byte[] d = IdempotencyKey.digestOf(new byte[]{1});

        l.reserve(P1, victim, d);
        l.settle(P1, victim, ScriptErrorCode.OK, new byte[]{1}, 0, 0);

        // 灌满：每条都没过 TTL，所以一条都淘汰不掉
        int full = 0;
        for (int i = 2; i < IdempotencyLedger.MAX_PER_PLAYER + 50; i++) {
            byte[] junk = IdempotencyKey.of(SERVER, P1, "app", "rev", "act", i);
            Object v = l.check(P1, junk, d);
            if (v instanceof IdempotencyLedger.Verdict.Full) {
                full++;
            } else {
                l.reserve(P1, junk, d);
                l.settle(P1, junk, ScriptErrorCode.OK, new byte[0], 0, 0);
            }
        }
        check(full > 0, "账本满了要回 Full（RATE_LIMITED），而不是淘汰别人");
        check(l.size(P1) <= IdempotencyLedger.MAX_PER_PLAYER, "条数不超上限");
        check(l.check(P1, victim, d) instanceof IdempotencyLedger.Verdict.Replay,
                "被瞄准的那一条还在 —— 灌 " + IdempotencyLedger.MAX_PER_PLAYER + "+ 条垃圾挤不掉它");
    }

    /** 过了 TTL 的已结算条目可以淘汰，RESERVED 的永远不行。 */
    static void evictionRules() {
        AtomicLong t = new AtomicLong(1000);
        IdempotencyLedger l = ledger(t);
        byte[] k = IdempotencyKey.of(SERVER, P1, "app", "rev", "act", 1);
        byte[] d = IdempotencyKey.digestOf(new byte[0]);
        l.reserve(P1, k, d);
        l.settle(P1, k, ScriptErrorCode.OK, new byte[0], 0, 0);

        t.set(1000 + IdempotencyLedger.TTL_MS - 1);
        l.sweep();
        eq(l.size(P1), 1, "没到 TTL 不扫");

        t.set(1000 + IdempotencyLedger.TTL_MS);
        l.sweep();
        eq(l.size(P1), 0, "到了 TTL 扫掉");

        // RESERVED 的：效果可能已经发生了，过多久都不许扫
        byte[] k2 = IdempotencyKey.of(SERVER, P1, "app", "rev", "act", 2);
        l.reserve(P1, k2, d);
        t.set(t.get() + IdempotencyLedger.TTL_MS * 10);
        l.sweep();
        eq(l.size(P1), 1, "RESERVED 的一条都不许扫 —— 效果可能已经发生");

        // check() is a decision-only API. Even when a full box contains expired entries, actual
        // eviction happens only when the caller follows Fresh with reserve().
        AtomicLong t2 = new AtomicLong(1);
        IdempotencyLedger pure = ledger(t2);
        for (int i = 0; i < IdempotencyLedger.MAX_PER_PLAYER; i++) {
            byte[] old = IdempotencyKey.of(SERVER, P2, "app", "rev", "act", i);
            pure.reserve(P2, old, d);
            pure.settle(P2, old, ScriptErrorCode.OK, new byte[0], 0, 0);
        }
        t2.addAndGet(IdempotencyLedger.TTL_MS);
        byte[] fresh = IdempotencyKey.of(SERVER, P2, "app", "rev", "act", 9999);
        check(pure.check(P2, fresh, d) instanceof IdempotencyLedger.Verdict.Fresh,
                "满箱中有过期条目时可接收新请求");
        eq(pure.size(P2), IdempotencyLedger.MAX_PER_PLAYER, "check 不修改账本");
        pure.reserve(P2, fresh, d);
        eq(pure.size(P2), 1, "reserve 才清理过期条目并写入新条目");
    }

    /** 结果太大不进账本，重放时回 UNKNOWN 而不是一个空的 OK。 */
    static void bigResultBecomesUnknown() {
        AtomicLong t = new AtomicLong(1000);
        IdempotencyLedger l = ledger(t);
        byte[] k = IdempotencyKey.of(SERVER, P1, "app", "rev", "act", 1);
        byte[] d = IdempotencyKey.digestOf(new byte[0]);
        l.reserve(P1, k, d);
        l.settle(P1, k, ScriptErrorCode.OK, new byte[IdempotencyLedger.REPLAYABLE_DATA_MAX + 1], 0, 0);

        Object v = l.check(P1, k, d);
        check(v instanceof IdempotencyLedger.Verdict.Replay, "还是重放");
        eq(((IdempotencyLedger.Verdict.Replay) v).code(), ScriptErrorCode.UNKNOWN,
                "存不下的结果重放时回 UNKNOWN，不许回一个空 data 冒充成功");
        eq(((IdempotencyLedger.Verdict.Replay) v).data().length, 0, "data 没存");

        // 刚好在上限上的存得下
        byte[] k2 = IdempotencyKey.of(SERVER, P1, "app", "rev", "act", 2);
        l.reserve(P1, k2, d);
        l.settle(P1, k2, ScriptErrorCode.OK, new byte[IdempotencyLedger.REPLAYABLE_DATA_MAX], 0, 0);
        eq(((IdempotencyLedger.Verdict.Replay) l.check(P1, k2, d)).code(), ScriptErrorCode.OK, "刚好到顶存得下");
    }

    // ================================================================ §15.8 限流算术

    static void rateLimits() {
        AtomicLong t = new AtomicLong(0);
        ScriptRateLimiter rl = new ScriptRateLimiter(t::get);

        // 突发 40：第一秒能连过 40 次
        int passed = 0;
        for (int i = 0; i < 60; i++) if (rl.allow(P1, "a" + i).allowed()) passed++;
        eq(passed, (int) ScriptRateLimiter.PLAYER_BURST, "每玩家突发就是 " + ScriptRateLimiter.PLAYER_BURST);

        ScriptRateLimiter.Decision d = rl.allow(P1, "z");
        check(!d.allowed(), "超了");
        check(d.retryAfterMs() > 0, "要带 retryAfterMs，客户端据此退避");

        // 攒一秒，回来 20 个
        t.set(1000);
        passed = 0;
        for (int i = 0; i < 40; i++) if (rl.allow(P1, "b" + i).allowed()) passed++;
        eq(passed, (int) ScriptRateLimiter.PLAYER_RATE_PER_SEC, "一秒攒回 " + ScriptRateLimiter.PLAYER_RATE_PER_SEC);

        // 每动作 5 次/秒
        ScriptRateLimiter rl2 = new ScriptRateLimiter(new AtomicLong(0)::get);
        passed = 0;
        for (int i = 0; i < 20; i++) if (rl2.allow(P2, "same").allowed()) passed++;
        eq(passed, (int) ScriptRateLimiter.ACTION_BURST, "同一个动作一秒只过 " + ScriptRateLimiter.ACTION_BURST);
    }

    /** 动作桶封顶：伪造的 actionId 撑不爆桶表。 */
    static void actionBucketCap() {
        AtomicLong t = new AtomicLong(0);
        ScriptRateLimiter rl = new ScriptRateLimiter(t::get);
        for (int i = 0; i < 500; i++) {
            t.set(i * 1000L);                      // 每次隔一秒，总量桶不会成为瓶颈
            rl.allow(P1, "forged" + i);
        }
        check(rl.actionBucketCount(P1) <= ScriptRateLimiter.MAX_ACTION_BUCKETS_PER_PLAYER,
                "动作桶封顶在 " + ScriptRateLimiter.MAX_ACTION_BUCKETS_PER_PLAYER
                        + "，实际 " + rl.actionBucketCount(P1));
    }

    static void uploadQuota() {
        AtomicLong t = new AtomicLong(0);
        ScriptRateLimiter rl = new ScriptRateLimiter(t::get);
        check(rl.allowUpload(P1, ScriptRateLimiter.UPLOAD_BYTES_PER_MIN).allowed(), "一分钟的量刚好过");
        check(!rl.allowUpload(P1, 1).allowed(), "再多一个字节就拒");
        t.set(60_000);
        check(rl.allowUpload(P1, 1).allowed(), "下一分钟窗口重开");
    }

    // ================================================================ 管线：epoch / 授权 / 队列满

    /** 一个什么都批准的部署表。 */
    static DeploymentView allDeployed(String rev) {
        return new DeploymentView() {
            public boolean deployed(String appId) {
                return true;
            }

            public boolean hasAction(String appId, String actionId) {
                return true;
            }

            public String deployRev(String appId) {
                return rev;
            }
        };
    }

    static ScriptRpc rpc(long requestId, long epoch, byte[] params) {
        return new ScriptRpc(ScriptProtocol.PROTOCOL, requestId, epoch,
                "example:app", "rev1", "act", params, "d");
    }

    /** 立刻出结果的求值替身。 */
    static ActionEvaluator instant(ActionEvaluator.Outcome outcome) {
        return (req, onDone) -> {
            onDone.accept(outcome);
            return true;
        };
    }

    static void epochMismatch() {
        AtomicLong t = new AtomicLong(0);
        List<ScriptRpcResult> out = new ArrayList<>();
        ScriptPipeline p = new ScriptPipeline(SERVER, ledger(t), new ScriptRateLimiter(t::get),
                allDeployed("rev1"), (a, b, c) -> true,
                instant(ActionEvaluator.Outcome.ok(new byte[0], 1, List.of())));

        long epoch = p.newEpoch(P1);
        p.accept(rpc(1, epoch, new byte[0]), snap(P1), out::add);
        eq(out.get(0).code(), ScriptErrorCode.OK, "epoch 对得上");

        out.clear();
        p.accept(rpc(2, epoch + 1, new byte[0]), snap(P1), out::add);
        eq(out.get(0).code(), ScriptErrorCode.INVALID_ARGUMENT, "epoch 对不上 → INVALID_ARGUMENT");
        eq(out.get(0).messageKey(), ScriptPipeline.KEY_STALE_CONNECTION, "有专门的文案键");

        // 断线重连：epoch 换了，旧 epoch 的一律拒；用新 epoch 重发同一个 requestId 命中账本
        long fresh = p.newEpoch(P1);
        out.clear();
        p.accept(rpc(1, epoch, new byte[0]), snap(P1), out::add);
        eq(out.get(0).code(), ScriptErrorCode.INVALID_ARGUMENT, "重连后旧 epoch 一律拒");
        out.clear();
        p.accept(rpc(1, fresh, new byte[0]), snap(P1), out::add);
        eq(out.get(0).code(), ScriptErrorCode.OK, "新 epoch 重发同一 requestId → 拿回上次结果");
        check(out.get(0).code() == ScriptErrorCode.OK,
                "键里不含 epoch，所以重连后同一个 requestId 仍然命中账本，不会再执行一次");
    }

    /** §15.9：请求在队列里时撤销授权 → 落地前被拒，效果没发生。 */
    static void authorityRevokedBeforeLanding() {
        AtomicLong t = new AtomicLong(0);
        boolean[] allow = {true};
        List<ActionIntent> landed = new ArrayList<>();
        AuthorityView authority = (player, appId, actionId) -> allow[0];

        // 求值替身：收下之后先把授权撤掉，再出结果 —— 模拟"在队列里的时候 OP 撤销了"
        ActionEvaluator evaluator = (req, onDone) -> {
            allow[0] = false;
            onDone.accept(ActionEvaluator.Outcome.ok(new byte[]{1}, 1,
                    List.of(new ActionIntent("item.give", new byte[]{1}))));
            return true;
        };

        List<ScriptRpcResult> out = new ArrayList<>();
        ScriptPipeline p = new ScriptPipeline(SERVER, ledger(t), new ScriptRateLimiter(t::get),
                allDeployed("rev1"), authority, evaluator);
        p.accept(rpc(1, p.newEpoch(P1), new byte[0]), snap(P1), out::add);

        eq(out.get(0).code(), ScriptErrorCode.NOT_AUTHORIZED, "落地前重查没过 → NOT_AUTHORIZED");
        eq(landed.size(), 0, "意图一条都没落地");
    }

    static void authorityFailureClosesReservation() {
        AtomicLong t = new AtomicLong(0);
        IdempotencyLedger ledger = ledger(t);
        List<ScriptRpcResult> out = new ArrayList<>();
        ScriptPipeline p = new ScriptPipeline(SERVER, ledger, new ScriptRateLimiter(t::get),
                allDeployed("rev1"), (player, appId, actionId) -> {
                    throw new NoSuchMethodError("broken authority provider");
                }, instant(ActionEvaluator.Outcome.ok(new byte[0], 1, List.of())));
        long epoch = p.newEpoch(P1);
        ScriptRpc request = rpc(77, epoch, new byte[0]);
        p.accept(request, snap(P1), out::add);
        eq(out.get(0).code(), ScriptErrorCode.INTERNAL, "授权实现异常返回 INTERNAL");

        byte[] key = IdempotencyKey.of(SERVER, P1, request.appId(), request.deployRev(),
                request.actionId(), request.requestId());
        byte[] digest = IdempotencyKey.digestOf(request.params());
        check(ledger.check(P1, key, digest) instanceof IdempotencyLedger.Verdict.Replay,
                "授权实现异常后 RESERVED 已结算，不会永久 IN_PROGRESS");
    }

    /** 队列满：回 RATE_LIMITED，而且用的是"服务器忙"那个键，不是"你太快了"。 */
    static void queueFull() {
        AtomicLong t = new AtomicLong(0);
        List<ScriptRpcResult> out = new ArrayList<>();
        ActionEvaluator busy = (req, onDone) -> false;     // 永远收不下
        ScriptPipeline p = new ScriptPipeline(SERVER, ledger(t), new ScriptRateLimiter(t::get),
                allDeployed("rev1"), (a, b, c) -> true, busy);

        p.accept(rpc(1, p.newEpoch(P1), new byte[0]), snap(P1), out::add);
        eq(out.get(0).code(), ScriptErrorCode.RATE_LIMITED, "队列满 → RATE_LIMITED");
        eq(out.get(0).messageKey(), ScriptPipeline.KEY_SERVER_BUSY, "与「你太快了」分开的文案键");
        check(out.get(0).retryAfterMs() > 0, "要带退避");

        // 不许把这个键卡在 IN_PROGRESS 上：下一次重发要能重新尝试，而不是永远 IN_PROGRESS
        out.clear();
        p.accept(rpc(1, p.newEpoch(P1), new byte[0]), snap(P1), out::add);
        check(out.get(0).code() != ScriptErrorCode.IN_PROGRESS,
                "队列满之后那个键必须结掉，不能永远卡在 IN_PROGRESS");
    }

    /** 协议号对不上回 VERSION_MISMATCH，不断线 —— fabric 上这是唯一的版本闸。 */
    static void protocolMismatch() {
        AtomicLong t = new AtomicLong(0);
        List<ScriptRpcResult> out = new ArrayList<>();
        ScriptPipeline p = new ScriptPipeline(SERVER, ledger(t), new ScriptRateLimiter(t::get),
                allDeployed("rev1"), (a, b, c) -> true,
                instant(ActionEvaluator.Outcome.ok(new byte[0], 0, List.of())));
        long e = p.newEpoch(P1);
        ScriptRpc bad = new ScriptRpc(ScriptProtocol.PROTOCOL + 1, 1, e,
                "example:app", "rev1", "act", new byte[0], "d");
        p.accept(bad, snap(P1), out::add);
        eq(out.get(0).code(), ScriptErrorCode.VERSION_MISMATCH, "协议号对不上");
    }

    /** 未知 appId / 未知 actionId：NOT_DEPLOYED，而且连桶都不建。 */
    static void unknownAppMakesNoBucket() {
        AtomicLong t = new AtomicLong(0);
        ScriptRateLimiter rl = new ScriptRateLimiter(t::get);
        List<ScriptRpcResult> out = new ArrayList<>();
        DeploymentView none = new DeploymentView() {
            public boolean deployed(String appId) {
                return false;
            }

            public boolean hasAction(String appId, String actionId) {
                return false;
            }

            public String deployRev(String appId) {
                return null;
            }
        };
        ScriptPipeline p = new ScriptPipeline(SERVER, ledger(t), rl, none, (a, b, c) -> true,
                instant(ActionEvaluator.Outcome.ok(new byte[0], 0, List.of())));
        for (int i = 0; i < 100; i++) {
            p.accept(rpc(i, p.newEpoch(P1), new byte[0]), snap(P1), out::add);
        }
        eq(out.get(0).code(), ScriptErrorCode.NOT_DEPLOYED, "未部署 → NOT_DEPLOYED");
        eq(rl.playerCount(), 0, "连桶都没建 —— 否则伪造 appId 就能把桶表撑爆");
    }

    /** 部署版本对不上 → VERSION_MISMATCH。 */
    static void deployRevMismatch() {
        AtomicLong t = new AtomicLong(0);
        List<ScriptRpcResult> out = new ArrayList<>();
        ScriptPipeline p = new ScriptPipeline(SERVER, ledger(t), new ScriptRateLimiter(t::get),
                allDeployed("rev2"), (a, b, c) -> true,
                instant(ActionEvaluator.Outcome.ok(new byte[0], 0, List.of())));
        p.accept(rpc(1, p.newEpoch(P1), new byte[0]), snap(P1), out::add);
        eq(out.get(0).code(), ScriptErrorCode.VERSION_MISMATCH, "客户端报 rev1、服务端是 rev2");
    }

    public static void main(String[] args) {
        errorTable();
        wireRoundTrip();
        wireLimits();
        hostPushGuard();
        handshakeFits();
        idempotencyKey();
        ledgerFourRows();
        hundredTimes();
        evictionAttack();
        evictionRules();
        bigResultBecomesUnknown();
        rateLimits();
        actionBucketCap();
        uploadQuota();
        epochMismatch();
        authorityRevokedBeforeLanding();
        authorityFailureClosesReservation();
        queueFull();
        protocolMismatch();
        unknownAppMakesNoBucket();
        deployRevMismatch();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
