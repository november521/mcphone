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
 * <p>按定向对抗收口后的语义：一批要"收齐"（epoch 对 + 条数对）才可用；旧 revision 的批次整条忽略；
 * 不完整/作废批次的部署对外不可见。
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

    static ScriptPush begin(long revision, long epoch, int count) {
        return beginWithApi(revision, ScriptProtocol.SCRIPT_API, epoch, count);
    }

    static ScriptPush beginWithApi(long revision, int scriptApi, long epoch, int count) {
        return Handshake.push(ScriptProtocol.TOPIC_HANDSHAKE_BEGIN,
                Handshake.encodeBegin(new Handshake.Begin(SERVER, "测试服", scriptApi, epoch, count,
                        new Handshake.Features(false, false, true))), revision);
    }

    static ScriptPush deployment(long revision, String appId, String rev, String front, List<String> actions) {
        return Handshake.push(ScriptProtocol.TOPIC_HANDSHAKE_DEPLOYMENT,
                Handshake.encodeDeployment(new Handshake.Deployment(appId, rev, front, 0, 7L,
                        1_700_000_000_000L, actions)), revision);
    }

    static ScriptPush end(long revision, long epoch) {
        return Handshake.push(ScriptProtocol.TOPIC_HANDSHAKE_END,
                Handshake.encodeEnd(new Handshake.End(epoch)), revision);
    }

    static void handshakeLifecycle() {
        ClientHandshake.clear();
        eq(ClientHandshake.connectionEpoch(), 0L, "没握手时 epoch 是 0（服务端会判过期，这是正确的）");
        eq(ClientHandshake.serverId(), null, "没握手时没有 serverId");

        ClientHandshake.onPush(begin(10, EPOCH, 1));
        eq(ClientHandshake.serverId(), SERVER, "serverId 来自服务端，不是自己推断的");
        eq(ClientHandshake.complete(), false, "只收到 begin：还不完整");
        eq(ClientHandshake.connectionEpoch(), 0L, "不完整时 connectionEpoch 是 0");
        eq(ClientHandshake.serverName(), "", "不完整时 serverName 不交出去");

        ClientHandshake.onPush(deployment(11, "example:shop", "rev1", "front1", List.of("buy", "sell")));
        eq(ClientHandshake.deployment("example:shop"), null, "不完整批次里的部署对外不可见（S2-3）");

        ClientHandshake.onPush(end(12, EPOCH));
        eq(ClientHandshake.complete(), true, "begin…end 齐了且条数对得上");
        eq(ClientHandshake.connectionEpoch(), EPOCH, "完整之后才把 epoch 交出去回填");
        eq(ClientHandshake.serverName(), "测试服", "完整之后 serverName 可用（§13.7 的空壳横幅）");
        eq(ClientHandshake.revision(), 10L, "批次修订号交出来（begin 那一条，ScriptPage 据此重排）");
        ClientHandshake.Entry e = ClientHandshake.deployment("example:shop");
        check(e != null, "收齐之后部署可见");
        eq(e.deployRev(), "rev1", "部署版本记下来了");
        eq(e.frontendDigest(), "front1", "前端摘要记下来了（加载集=摘要集的落点）");
        eq(e.visibility(), 0, "visibility 记下来了");
        eq(e.approvalRevision(), 7L, "批准轴（详情页的版本 N）记下来了");
        eq(e.approvedAt(), 1_700_000_000_000L, "批准时间记下来了（详情页来源行）");
        eq(e.actions(), List.of("buy", "sell"), "授权动作记下来了");

        // §13.8 的只读上下文：available / serverName / actions
        java.util.Map<String, Object> backend = ClientHandshake.backendValues("example:shop");
        eq(backend.get("available"), true, "backend.available");
        eq(backend.get("serverName"), "测试服", "backend.serverName");
        eq(backend.get("actions"), List.of("buy", "sell"), "backend.actions");
        eq(ClientHandshake.backendValues("example:nope").get("available"), false, "没部署的 App 是 false");
        eq(((java.util.Map<?, ?>) ClientHandshake.backendContext("example:shop").get("backend")).get("available"),
                true, "backendContext 的键是 backend（模板注入用的那一个）");
        eq(ClientHandshake.deployments().size(), 1, "部署表的只读快照");
        eq(ClientHandshake.deployments().get("example:shop").actions(), List.of("buy", "sell"), "快照内容一致");
    }

    static void rejectsForgedAndMismatched() {
        ClientHandshake.clear();

        // ADV-S2b-1：握手线版本对不上（Fabric 没有加载器闸，这是唯一识别点）→ 整批不应用
        ClientHandshake.onPush(beginWithApi(1, ScriptProtocol.SCRIPT_API + 1, EPOCH, 1));
        eq(ClientHandshake.serverId(), null, "版本不匹配：serverId 不落地");
        eq(ClientHandshake.complete(), false, "版本不匹配：批次不算数");
        eq(ClientHandshake.scriptApiMismatch(), true, "留一个可查的不匹配标记（诊断命令用）");
        ClientHandshake.onPush(deployment(2, "example:shop", "rev1", "front1", List.of("buy")));
        eq(ClientHandshake.deployment("example:shop"), null, "版本不匹配之后，随后的明细也不收（批次没打开）");
        ClientHandshake.onPush(end(3, EPOCH));
        eq(ClientHandshake.complete(), false, "版本不匹配的批次 END 也救不回来");

        // 恢复正常版本：新批次的 revision 更大，照常应用，标记清掉
        ClientHandshake.onPush(begin(4, EPOCH, 1));
        eq(ClientHandshake.scriptApiMismatch(), false, "正常版本把不匹配标记清掉");
        ClientHandshake.onPush(deployment(5, "example:shop", "rev1", "front1", List.of("buy")));
        ClientHandshake.onPush(end(6, EPOCH));
        eq(ClientHandshake.complete(), true, "版本恢复后正常收齐");
        ClientHandshake.clear();

        // ADV-S2b-8.3：没有 BEGIN 打开的批次，明细一条都不收
        ClientHandshake.onPush(deployment(10, "example:orphan", "rev", "front", List.of()));
        eq(ClientHandshake.deployment("example:orphan"), null, "批次外的明细不收");

        // 批内同 App 的批准轴只增不减：重放旧条目不覆盖新条目（另一条不同 App 的照常收，凑齐条数）
        ClientHandshake.onPush(begin(20, EPOCH, 2));
        ClientHandshake.onPush(Handshake.push(ScriptProtocol.TOPIC_HANDSHAKE_DEPLOYMENT,
                Handshake.encodeDeployment(new Handshake.Deployment("example:shop", "rev2", "front2", 0, 9L,
                        1_700_000_000_000L, List.of("buy", "sell"))), 21));
        ClientHandshake.onPush(Handshake.push(ScriptProtocol.TOPIC_HANDSHAKE_DEPLOYMENT,
                Handshake.encodeDeployment(new Handshake.Deployment("example:shop", "rev1", "front1", 0, 5L,
                        1_600_000_000_000L, List.of("buy"))), 22));
        ClientHandshake.onPush(Handshake.push(ScriptProtocol.TOPIC_HANDSHAKE_DEPLOYMENT,
                Handshake.encodeDeployment(new Handshake.Deployment("example:other", "rev1", "front1", 0, 1L,
                        1_600_000_000_000L, List.of())), 23));
        ClientHandshake.onPush(end(24, EPOCH));
        ClientHandshake.Entry kept = ClientHandshake.deployment("example:shop");
        check(kept != null, "批次收齐后条目在");
        eq(kept.deployRev(), "rev2", "批准轴 9 的条目没被批准轴 5 的旧条目盖回去");
        eq(kept.approvalRevision(), 9L, "批准轴取新的");

        // 脚本能选 topic：不是宿主发的（appId 不对）一律忽略
        ClientHandshake.clear();
        ClientHandshake.onPush(new ScriptPush("example:evil", ScriptProtocol.TOPIC_HANDSHAKE_BEGIN, new byte[0], 1));
        eq(ClientHandshake.serverId(), null, "非宿主 appId 的推送忽略");

        // 宿主 topic 前缀不对也忽略
        ClientHandshake.onPush(new ScriptPush(ScriptProtocol.HOST_APP_ID, "mcphone:other/thing", new byte[0], 1));
        eq(ClientHandshake.serverId(), null, "宿主 appId 但 topic 不是握手的忽略");

        // 先来一批完整的
        ClientHandshake.onPush(begin(100, EPOCH, 1));
        ClientHandshake.onPush(deployment(101, "example:shop", "rev1", "front1", List.of("buy")));
        ClientHandshake.onPush(end(102, EPOCH));
        eq(ClientHandshake.complete(), true, "第一批完整");

        // S2-2：revision 更小的 begin 是旧的/重放的 —— 整条忽略，状态不动
        ClientHandshake.onPush(begin(50, EPOCH + 999, 0));
        eq(ClientHandshake.connectionEpoch(), EPOCH, "旧批 begin 不覆盖当前 epoch");
        eq(ClientHandshake.complete(), true, "旧批 begin 不把当前批次打成未完成");
        check(ClientHandshake.deployment("example:shop") != null, "旧批 begin 不清掉当前部署");

        // S2-2：条数没对上的批次作废，且内容清空
        ClientHandshake.onPush(begin(200, EPOCH, 2));
        ClientHandshake.onPush(deployment(201, "example:shop", "rev1", "front1", List.of("buy")));
        ClientHandshake.onPush(end(202, EPOCH));
        eq(ClientHandshake.complete(), false, "只收到 1 条、声明 2 条：整批作废");
        eq(ClientHandshake.connectionEpoch(), 0L, "作废批次不给 epoch");
        eq(ClientHandshake.deployment("example:shop"), null, "作废批次的内容清空（S2-3）");

        // epoch 对不上：同样作废
        ClientHandshake.onPush(begin(300, EPOCH, 1));
        ClientHandshake.onPush(deployment(301, "example:shop", "rev1", "front1", List.of("buy")));
        ClientHandshake.onPush(end(302, EPOCH + 1));
        eq(ClientHandshake.complete(), false, "end 的 epoch 对不上：整批作废");
        eq(ClientHandshake.deployment("example:shop"), null, "作废之后部署不可见");

        // 坏数据不许把客户端打崩，也不许改变状态
        ClientHandshake.clear();
        ClientHandshake.onPush(new ScriptPush(ScriptProtocol.HOST_APP_ID, ScriptProtocol.TOPIC_HANDSHAKE_BEGIN,
                new byte[]{1, 2, 3}, 1));
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
