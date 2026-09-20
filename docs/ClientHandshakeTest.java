package com.november.mcphone.core.script.client;

import com.november.mcphone.core.script.net.Handshake;
import com.november.mcphone.core.script.net.ScriptProtocol;
import com.november.mcphone.core.script.net.ScriptPush;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * S17 Stage 2 的断言：客户端握手状态（begin → deployment × N → end）。
 *
 * <p>纯数据结构，不需要真客户端：用 {@link Handshake#push} 造推送、直接喂给 {@code onPush}
 * （包内可见），断言 serverId / epoch / 部署表 / 完整性。
 */
public class ClientHandshakeTest {

    static int checks = 0;
    static final java.util.List<String> failures = new java.util.ArrayList<>();

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!Objects.equals(actual, expected)) failures.add(what + "  期望 " + expected + "，实际 " + actual);
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures.add(what);
    }

    static final UUID SERVER = UUID.nameUUIDFromBytes("server".getBytes());
    static final long EPOCH = 123456789L;

    static ScriptPush begin() {
        return Handshake.push(ScriptProtocol.TOPIC_HANDSHAKE_BEGIN,
                Handshake.encodeBegin(new Handshake.Begin(SERVER, "测试服", ScriptProtocol.PROTOCOL, EPOCH, 1,
                        new Handshake.Features(false, false, true))), 0);
    }

    static ScriptPush deployment(String appId, String rev, String front, List<String> actions) {
        return Handshake.push(ScriptProtocol.TOPIC_HANDSHAKE_DEPLOYMENT,
                Handshake.encodeDeployment(new Handshake.Deployment(appId, rev, front, 0, actions)), 1);
    }

    static ScriptPush end(long epoch) {
        return Handshake.push(ScriptProtocol.TOPIC_HANDSHAKE_END,
                Handshake.encodeEnd(new Handshake.End(epoch)), 2);
    }

    static void handshakeLifecycle() {
        ClientHandshake.clear();
        eq(ClientHandshake.connectionEpoch(), 0L, "没握手时 epoch 是 0（服务端会判过期，这是正确的）");
        eq(ClientHandshake.serverId(), null, "没握手时没有 serverId");

        ClientHandshake.onPush(begin());
        eq(ClientHandshake.serverId(), SERVER, "serverId 来自服务端，不是自己推断的");
        eq(ClientHandshake.complete(), false, "只收到 begin：还不完整，epoch 先不给");
        eq(ClientHandshake.connectionEpoch(), 0L, "不完整时 connectionEpoch 是 0");

        ClientHandshake.onPush(deployment("example:shop", "rev1", "front1", List.of("buy", "sell")));
        eq(ClientHandshake.deployment("example:shop").deployRev(), "rev1", "部署版本记下来了");
        eq(ClientHandshake.deployment("example:shop").actions(), List.of("buy", "sell"), "授权动作记下来了");

        ClientHandshake.onPush(end(EPOCH));
        eq(ClientHandshake.complete(), true, "begin…end 齐了");
        eq(ClientHandshake.connectionEpoch(), EPOCH, "完整之后才把 epoch 交出去回填");
    }

    static void rejectsForgedAndMismatched() {
        ClientHandshake.clear();

        // 脚本能选 topic：不是宿主发的（appId 不对）一律忽略
        ClientHandshake.onPush(new ScriptPush("example:evil", ScriptProtocol.TOPIC_HANDSHAKE_BEGIN, new byte[0], 0));
        eq(ClientHandshake.serverId(), null, "非宿主 appId 的推送忽略");
        eq(ClientHandshake.connectionEpoch(), 0L, "伪造的 begin 改不动 epoch");

        // 宿主 topic 前缀不对也忽略
        ClientHandshake.onPush(new ScriptPush(ScriptProtocol.HOST_APP_ID, "mcphone:other/thing", new byte[0], 0));
        eq(ClientHandshake.serverId(), null, "宿主 appId 但 topic 不是握手的忽略");

        // end 的 epoch 与 begin 对不上：整批作废，宁可当没有后端
        ClientHandshake.onPush(begin());
        ClientHandshake.onPush(end(EPOCH + 1));
        eq(ClientHandshake.complete(), false, "end 的 epoch 对不上：整批作废");
        eq(ClientHandshake.connectionEpoch(), 0L, "作废之后 epoch 是 0");

        // 坏数据不许把客户端打崩，也不许改变状态
        ClientHandshake.clear();
        ClientHandshake.onPush(new ScriptPush(ScriptProtocol.HOST_APP_ID, ScriptProtocol.TOPIC_HANDSHAKE_BEGIN,
                new byte[]{1, 2, 3}, 0));
        eq(ClientHandshake.serverId(), null, "解不开的推送被接住并忽略（状态保持空）");
    }

    public static void main(String[] args) {
        handshakeLifecycle();
        rejectsForgedAndMismatched();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
