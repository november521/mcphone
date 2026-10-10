package com.november.mcphone.core.script.net;

import com.november.mcphone.core.net.MCphoneNetwork;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 脚本 RPC 三个包在这一支的登记（施工方案 §15.3、§10.3）。
 *
 * <p>这一支的门面收 {@code CustomPacketPayload.Type} 加 {@code StreamCodec}，所以走
 * {@code layers/version/1.20.5+/} 里那三个包装类。<b>包装只持有 shared 的 record，
 * 不复制它的字段</b>，encode/decode 全仓只有一份。
 *
 * <p>登记在所有现有包之后 —— 顺序即身份（§10.3）。
 */
public final class ScriptNetworking {

    private ScriptNetworking() {
    }

    /** 由 NetworkHandler.register 在最末尾调用。 */
    public static void register(PayloadRegistrar registrar) {
        MCphoneNetwork.registerToServer(registrar,
                ScriptRpcPayload.TYPE, ScriptRpcPayload.STREAM_CODEC,
                (payload, player) -> ScriptRpcHandler.handle(payload.msg(), player));

        MCphoneNetwork.registerToClient(registrar,
                ScriptRpcResultPayload.TYPE, ScriptRpcResultPayload.STREAM_CODEC,
                payload -> ScriptResultRouter.result(payload.msg()));

        MCphoneNetwork.registerToClient(registrar,
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
