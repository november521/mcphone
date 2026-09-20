package com.november.mcphone.core.script.net;

import com.november.mcphone.core.net.MCphoneNetwork;

/**
 * 脚本 RPC 三个包在这一支的登记（施工方案 §15.3、§10.3）。
 *
 * <p><b>这一支的门面收的是 {@code (Class, encoder, decoder, handler)}</b>，所以
 * {@code shared} 里那三个普通 record 可以<b>直接用</b>，不必像 1.21.1 那支那样包一层
 * {@code CustomPacketPayload}。encode/decode 因此全仓只有一份。
 *
 * <p><b>必须登记在所有现有包之后</b>：{@code SimpleChannel} 按注册顺序发放整数序号，
 * 往末尾追加安全，中间插入或调换要把 {@code MCphoneNetwork.PROTOCOL_VERSION} 加一。
 * 本步是追加，所以那个数不动。
 */
public final class ScriptNetworking {

    private ScriptNetworking() {
    }

    /** 由 NetworkHandler.register 在最末尾调用。 */
    public static void register() {
        MCphoneNetwork.registerToServer(
                ScriptRpc.class, ScriptRpc::encode, ScriptRpc::decode, ScriptRpcHandler::handle);

        MCphoneNetwork.registerToClient(
                ScriptRpcResult.class, ScriptRpcResult::encode, ScriptRpcResult::decode,
                ScriptResultRouter::result);

        MCphoneNetwork.registerToClient(
                ScriptPush.class, ScriptPush::encode, ScriptPush::decode,
                ScriptResultRouter::push);

        ScriptRpcHandler.installSender(MCphoneNetwork::sendToPlayer);
        // S17 Stage 2：服务端主动推送（握手）
        ScriptPushHandler.installSender(MCphoneNetwork::sendToPlayer);
    }
}
