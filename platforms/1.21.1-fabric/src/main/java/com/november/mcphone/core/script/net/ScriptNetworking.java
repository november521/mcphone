package com.november.mcphone.core.script.net;

import com.november.mcphone.core.net.MCphoneNetwork;

/**
 * 脚本 RPC 三个包在这一支的登记（施工方案 §15.3、§10.3）。
 *
 * <p>与 neoforge 那一支同形，只是这一支的门面不收 {@code registrar}。
 * 包装类在 {@code layers/version/1.20.5+/}，两个 1.21.1 目标共用同一份 —— <b>不产生孪生</b>。
 *
 * <p>⚠ <b>这一支没有任何加载器级的版本闸</b>（全平台 grep {@code PROTOCOL_VERSION} 与
 * {@code registrar(} 零命中），所以 {@link ScriptProtocol#PROTOCOL} 这个字段在这里是唯一的一道。
 * 对不上必须回 {@code VERSION_MISMATCH}，不许断线。
 */
public final class ScriptNetworking {

    private ScriptNetworking() {
    }

    /** 由 NetworkHandler.registerServer 在最末尾调用。 */
    public static void register() {
        MCphoneNetwork.registerToServer(
                ScriptRpcPayload.TYPE, ScriptRpcPayload.STREAM_CODEC,
                (payload, player) -> ScriptRpcHandler.handle(payload.msg(), player));

        MCphoneNetwork.registerToClient(
                ScriptRpcResultPayload.TYPE, ScriptRpcResultPayload.STREAM_CODEC,
                payload -> ScriptResultRouter.result(payload.msg()));

        MCphoneNetwork.registerToClient(
                ScriptPushPayload.TYPE, ScriptPushPayload.STREAM_CODEC,
                payload -> ScriptResultRouter.push(payload.msg()));

        ScriptRpcHandler.installSender((player, result) ->
                MCphoneNetwork.sendToPlayer(player, new ScriptRpcResultPayload(result)));
        // S17 Stage 2：服务端主动推送（握手）
        ScriptPushHandler.installSender((player, push) ->
                MCphoneNetwork.sendToPlayer(player, new ScriptPushPayload(push)));
        // S17 Stage 2：客户端发出的脚本调用（回填 epoch/deployRev/摘要后走这个口子）
        com.november.mcphone.core.script.client.ScriptCall.installSender(
                rpc -> MCphoneNetwork.sendToServer(new ScriptRpcPayload(rpc)));
    }
}
