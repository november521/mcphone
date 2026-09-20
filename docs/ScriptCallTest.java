package com.november.mcphone.core.script.client;

import com.november.mcphone.core.script.net.Handshake;
import com.november.mcphone.core.script.net.ScriptErrorCode;
import com.november.mcphone.core.script.net.ScriptProtocol;
import com.november.mcphone.core.script.net.ScriptPush;
import com.november.mcphone.core.script.net.ScriptRpc;
import com.november.mcphone.core.script.net.ScriptRpcResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * S17 Stage 2 的断言：客户端 {@link ScriptCall} 的发送端。
 *
 * <p>覆盖四件事：字段回填（epoch/deployRev/摘要）、没握手时的本地合成、同 App 4 个在飞的上限、
 * 结果的按 requestId 分发与断线清空。
 */
public class ScriptCallTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!Objects.equals(actual, expected)) failures.add(what + "  期望 " + expected + "，实际 " + actual);
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures.add(what);
    }

    static final UUID SERVER = UUID.nameUUIDFromBytes("server".getBytes());
    static final long EPOCH = 987654321L;
    static final String APP = "example:shop";

    static final List<ScriptRpc> sent = new ArrayList<>();
    static final List<ScriptRpcResult> callbacks = new ArrayList<>();

    static void begin(long revision, int count) {
        ClientHandshake.onPush(Handshake.push(ScriptProtocol.TOPIC_HANDSHAKE_BEGIN,
                Handshake.encodeBegin(new Handshake.Begin(SERVER, "测试服", ScriptProtocol.PROTOCOL, EPOCH, count,
                        new Handshake.Features(false, false, true))), revision));
    }

    static void deployment(long revision, String appId, String rev, String front, List<String> actions) {
        ClientHandshake.onPush(Handshake.push(ScriptProtocol.TOPIC_HANDSHAKE_DEPLOYMENT,
                Handshake.encodeDeployment(new Handshake.Deployment(appId, rev, front, 0, 7L, 1_700_000_000_000L,
                        actions)), revision));
    }

    static void end(long revision) {
        ClientHandshake.onPush(Handshake.push(ScriptProtocol.TOPIC_HANDSHAKE_END,
                Handshake.encodeEnd(new Handshake.End(EPOCH)), revision));
    }

    static void reset() {
        ScriptCall.clear();
        ClientHandshake.clear();
        sent.clear();
        callbacks.clear();
    }

    /** 没握手：不发，本地合成 UNAVAILABLE（§15.4 标明它是客户端合成的码）。 */
    static void noHandshakeIsLocalUnavailable() {
        reset();
        ScriptCall.installSender(sent::add);
        ScriptCall.call(APP, "claim", new byte[0], "local-front", callbacks::add);
        eq(sent.size(), 0, "没握手时一个包都不发");
        eq(callbacks.size(), 1, "回调照样回来（本地合成）");
        eq(callbacks.get(0).code(), ScriptErrorCode.UNAVAILABLE, "本地合成的是 UNAVAILABLE，不是过期连接");
    }

    /** 有握手：回填 epoch 与部署版本；本地摘要优先于握手那格。 */
    static void fillsFieldsFromHandshake() {
        reset();
        ScriptCall.installSender(sent::add);
        begin(10, 1);
        deployment(11, APP, "rev-1", "server-front", List.of("claim"));
        end(12);

        ScriptCall.call(APP, "claim", new byte[0], "local-front", callbacks::add);
        eq(sent.size(), 1, "握手齐了才发");
        ScriptRpc rpc = sent.get(0);
        eq(rpc.protocol(), ScriptProtocol.PROTOCOL, "协议号");
        eq(rpc.connectionEpoch(), EPOCH, "epoch 来自握手");
        eq(rpc.appId(), APP, "appId");
        eq(rpc.deployRev(), "rev-1", "deployRev 用服务端下发的那份，不自己推断");
        eq(rpc.frontendDigest(), "local-front", "本地前端摘要优先（只用于回显，不是边界）");
        eq(callbacks.size(), 0, "结果还没回来");

        ScriptCall.onResult(ScriptRpcResult.ok(rpc.requestId(), new byte[0], 0));
        eq(callbacks.size(), 1, "结果按 requestId 分发");
        eq(callbacks.get(0).code(), ScriptErrorCode.OK, "OK 原样交给回调");
    }

    /** 本地摘要算不出来（单文件 .vue）时回退到握手那一格，不谎报"改了"。 */
    static void fallsBackToNegotiatedDigest() {
        reset();
        ScriptCall.installSender(sent::add);
        begin(20, 1);
        deployment(21, APP, "rev-2", "server-front", List.of());
        end(22);

        ScriptCall.call(APP, "claim", new byte[0], null, callbacks::add);
        eq(sent.get(0).frontendDigest(), "server-front", "没有本地摘要时用握手那格");
    }

    /** 同一 App 最多 4 个在飞：第 5 个立即回 IN_PROGRESS，不排队；结果回来后才让位。 */
    static void capsInFlightAtFour() {
        reset();
        ScriptCall.installSender(sent::add);
        begin(30, 1);
        deployment(31, APP, "rev-3", "front", List.of());
        end(32);

        for (int i = 0; i < ScriptCall.MAX_IN_FLIGHT; i++) {
            ScriptCall.call(APP, "claim", new byte[0], "f", callbacks::add);
        }
        eq(sent.size(), 4, "前 4 个都在飞");
        ScriptCall.call(APP, "claim", new byte[0], "f", callbacks::add);
        eq(sent.size(), 4, "第 5 个不发");
        eq(callbacks.size(), 1, "第 5 个立即拿到结果");
        eq(callbacks.get(0).code(), ScriptErrorCode.IN_PROGRESS, "超上限立即 IN_PROGRESS，不排队");

        ScriptCall.onResult(ScriptRpcResult.ok(sent.get(0).requestId(), new byte[0], 0));
        ScriptCall.call(APP, "claim", new byte[0], "f", callbacks::add);
        eq(sent.size(), 5, "有一个落地之后名额放出来");
    }

    /** 无主结果（旧连接的迟到回包）不该动任何状态；断线清空后回调也不再触发。 */
    static void ignoresStrayResultsAndClears() {
        reset();
        ScriptCall.installSender(sent::add);
        begin(40, 1);
        deployment(41, APP, "rev-4", "front", List.of());
        end(42);

        ScriptCall.call(APP, "claim", new byte[0], "f", callbacks::add);
        eq(sent.size(), 1, "先发一条");
        ScriptCall.onResult(ScriptRpcResult.ok(999999L, new byte[0], 0));
        eq(callbacks.size(), 0, "无主结果被安静忽略");

        ScriptCall.clear();
        ScriptCall.onResult(ScriptRpcResult.ok(sent.get(0).requestId(), new byte[0], 0));
        eq(callbacks.size(), 0, "断线清空之后，旧请求的结果不再回调");
        ScriptCall.call(APP, "claim", new byte[0], "f", callbacks::add);
        eq(sent.size(), 2, "清空也把在飞名额放掉，之后还能发");
    }

    /** 调试命令的绕过口子：deployRev / 摘要按参数来；epoch 照样用实时的。 */
    static void rawSendUsesOverrides() {
        reset();
        ScriptCall.installSender(sent::add);
        begin(50, 1);
        deployment(51, APP, "rev-5", "front", List.of());
        end(52);

        ScriptCall.sendRaw(APP, "steal", "forged-rev", "forged-front", callbacks::add);
        ScriptRpc rpc = sent.get(0);
        eq(rpc.deployRev(), "forged-rev", "调试命令可以伪造 deployRev");
        eq(rpc.frontendDigest(), "forged-front", "调试命令可以伪造 frontendDigest");
        eq(rpc.connectionEpoch(), EPOCH, "epoch 仍然用实时握手值（否则到不了部署判定）");
        eq(rpc.actionId(), "steal", "伪造 actionId");
    }

    /** requestId 单调递增（低 16 位计数器在动），且两次调用不会相同。 */
    static void requestIdsAreMonotonic() {
        reset();
        ScriptCall.installSender(sent::add);
        begin(60, 1);
        deployment(61, APP, "rev-6", "front", List.of());
        end(62);

        ScriptCall.call(APP, "a", new byte[0], "f", callbacks::add);
        ScriptCall.call(APP, "b", new byte[0], "f", callbacks::add);
        long first = sent.get(0).requestId();
        long second = sent.get(1).requestId();
        check(second == first + 1, "requestId 单调递增：" + first + " → " + second);
        check(first != second, "两次调用的 requestId 不重复");
    }

    public static void main(String[] args) {
        noHandshakeIsLocalUnavailable();
        fillsFieldsFromHandshake();
        fallsBackToNegotiatedDigest();
        capsInFlightAtFour();
        ignoresStrayResultsAndClears();
        rawSendUsesOverrides();
        requestIdsAreMonotonic();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
