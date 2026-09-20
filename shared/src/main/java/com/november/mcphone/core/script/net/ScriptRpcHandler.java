package com.november.mcphone.core.script.net;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.server.PlayerSnapshot;
import com.november.mcphone.core.script.server.ScriptPipeline;
import net.minecraft.server.level.ServerPlayer;

/**
 * 三个平台的登记点都指到这里（施工方案 §15.5）。<b>调到这里时已经在服务器主线程上</b> ——
 * 三个门面各自用 {@code consumerMainThread} / {@code ctx.enqueueWork} / {@code server.execute} 保证。
 *
 * <h2>管线由 ScriptHost 在开服时装上（S15g）</h2>
 *
 * 服务器运行期间 {@link #pipeline} 恒非 null（{@code ScriptHost.start} 装配、{@code ScriptHost.stop} 摘掉）。
 * 但"装上了"不等于"能跑"：部署表与授权表是 {@code DenyAll*} 占位，那两张是 S17 的交付物（§14.4）。
 *
 * <p><b>当前真实返回码要说准</b>：{@code ScriptPipeline} 的准入顺序里"连接 epoch"排在"部署判定"<b>之前</b>，
 * 而 {@code newEpoch} / {@code forget} 目前<b>还没有生产调用点</b>（成对接线属 S17）—— 于是 epochs 表恒空，
 * 真实请求在 epoch 那一档就被拒（{@code INVALID_ARGUMENT} + {@code mcphone.script.stale_connection}），
 * <b>根本走不到部署判定</b>。所以"部署表为空 ⇒ NOT_DEPLOYED"是 S17 接上握手之后的第一道，
 * 不是今天请求被拒的原因；别把这两档说混（对抗组 P1）。
 *
 * <p>登记（{@link #install}）本身 S15g 已经做了：三个包的序号由注册顺序发放（§10.3 顺序即身份），
 * 等 S17 到货再登记的话，那时追加的序号与现在追加的不是同一个。
 */
public final class ScriptRpcHandler {

    private ScriptRpcHandler() {
    }

    /** 由 {@code ScriptHost} 在开服时装上（S15g）；S17 只换部署表/授权表的实现，这个登记点不变。 */
    private static volatile ScriptPipeline pipeline;

    public static void install(ScriptPipeline p) {
        pipeline = p;
    }

    /** 服务器停止时清掉 —— 单人游戏里下一个世界不该看见上一个世界的管线。 */
    public static void clear() {
        pipeline = null;
    }

    /** 由各平台的 {@code ScriptNetworking} 指过来。 */
    public static void handle(ScriptRpc rpc, ServerPlayer player) {
        ScriptPipeline p = pipeline;
        if (p == null) {
            send(player, ScriptRpcResult.fail(rpc.requestId(), ScriptErrorCode.NOT_DEPLOYED));
            return;
        }
        PlayerSnapshot snapshot = new PlayerSnapshot(
                player.getUUID(),
                player.getGameProfile().getName(),
                player.level().dimension().location().toString(),
                player.gameMode.getGameModeForPlayer().getName(),
                0L);
        p.accept(rpc, snapshot, result -> send(player, result));
    }

    /**
     * 把结果发回去。各平台的发包函数签名不同（1.20.1 收 {@code Object}，1.21.1 收
     * {@code CustomPacketPayload}），所以由各平台的 {@code ScriptNetworking} 装一个发送器进来。
     */
    private static volatile java.util.function.BiConsumer<ServerPlayer, ScriptRpcResult> sender;

    public static void installSender(java.util.function.BiConsumer<ServerPlayer, ScriptRpcResult> s) {
        sender = s;
    }

    private static void send(ServerPlayer player, ScriptRpcResult result) {
        java.util.function.BiConsumer<ServerPlayer, ScriptRpcResult> s = sender;
        if (s == null) {
            MCphone.LOGGER.warn("[MCphone] 脚本 RPC 的发送器还没装，结果发不出去: {}", result.code());
            return;
        }
        s.accept(player, result);
    }
}
