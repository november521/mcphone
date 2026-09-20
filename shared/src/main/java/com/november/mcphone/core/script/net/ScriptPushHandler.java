package com.november.mcphone.core.script.net;

import com.november.mcphone.MCphone;
import net.minecraft.server.level.ServerPlayer;

import java.util.function.BiConsumer;

/**
 * 服务端 → 客户端的<b>推送</b>发送器（{@link ScriptPush}）。与结果发送器（{@link ScriptRpcHandler}）分开：
 * 结果只回给发起请求的玩家，推送是服务端主动发的（本阶段只有握手）。
 *
 * <p>各平台的发包函数签名不同（1.20.1 收 {@code Object}，1.21.1 收 {@code CustomPacketPayload}），
 * 所以由各平台的 {@code ScriptNetworking} 装一个发送器进来 —— 与 {@code installSender} 同一个做法。
 */
public final class ScriptPushHandler {

    private ScriptPushHandler() {
    }

    private static volatile BiConsumer<ServerPlayer, ScriptPush> sender;

    public static void installSender(BiConsumer<ServerPlayer, ScriptPush> s) {
        sender = s;
    }

    /**
     * 发一条推送。发送器没装时记一条并返回 false。
     *
     * <p>注意：这个发送器在客户端也会被装上（{@code ScriptNetworking.register} 两端都跑），
     * 只是客户端永不调用发送 —— 不是"客户端侧不会装"。
     */
    public static boolean push(ServerPlayer player, ScriptPush push) {
        BiConsumer<ServerPlayer, ScriptPush> s = sender;
        if (s == null) {
            MCphone.LOGGER.warn("[MCphone] 脚本推送的发送器还没装，发不出去: topic={}", push.topic());
            return false;
        }
        s.accept(player, push);
        return true;
    }
}
