package com.november.mcphone.core.script.server;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.net.Handshake;
import com.november.mcphone.core.script.net.ScriptProtocol;
import com.november.mcphone.core.script.net.ScriptPushHandler;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 登录握手（施工方案 §15.7）：把服务端身份、一次性 epoch 与部署表推给客户端。
 *
 * <p>三条推送：{@code begin}（一条）→ {@code deployment}（每个部署一条）→ {@code end}（一条）。
 * <b>部署里只带"这个玩家被授权的动作"</b>（{@code backend.actions}）—— 那是 UX，不是边界；
 * 真正的判定在每次请求的服务端重查（§13.8）。
 *
 * <p>不推 {@code server.js}、也不推任何后端代码：客户端拿到的只有 appId / deployRev / frontendDigest / actions。
 */
public final class HandshakeService {

    private HandshakeService() {
    }

    /**
     * 登录时（拿到 epoch 之后）调用。
     *
     * @param epoch 由 {@link ScriptHost#newEpoch(ServerPlayer)} 返回；为 0 表示脚本后端降级/未装，
     *              这时不发握手 —— 客户端按"本服没有脚本后端"处理
     */
    public static void pushTo(ServerPlayer player, long epoch) {
        ScriptHost host = ScriptHost.current();
        if (host == null || epoch == 0L) return;
        List<Deployment> all = host.deployments().deployments();
        int count = Math.min(all.size(), ScriptProtocol.HANDSHAKE_MAX_DEPLOYMENTS);
        // 用 level().getServer()：ServerPlayer.getServer() 在 1.21.1 上不存在，而 level() 两版都有
        net.minecraft.server.MinecraftServer server = player.level().getServer();
        String serverName = server == null ? "" : server.getMotd();
        long rev = 0;
        ScriptPushHandler.push(player, Handshake.push(ScriptProtocol.TOPIC_HANDSHAKE_BEGIN,
                Handshake.encodeBegin(new Handshake.Begin(host.serverId(), serverName,
                        ScriptProtocol.PROTOCOL, epoch, count,
                        new Handshake.Features(false, false, true))), rev++));
        for (int i = 0; i < count; i++) {
            Deployment d = all.get(i);
            List<String> actions = new ArrayList<>();
            for (String action : d.approvedActions()) {
                if (host.allows(player.getUUID(), d.appId(), action)) actions.add(action);
            }
            ScriptPushHandler.push(player, Handshake.push(ScriptProtocol.TOPIC_HANDSHAKE_DEPLOYMENT,
                    Handshake.encodeDeployment(new Handshake.Deployment(d.appId(), d.revision(),
                            d.frontendDigest(), 0, actions)), rev++));
        }
        ScriptPushHandler.push(player, Handshake.push(ScriptProtocol.TOPIC_HANDSHAKE_END,
                Handshake.encodeEnd(new Handshake.End(epoch)), rev));
        MCphone.LOGGER.info("[MCphone] 握手已下发：{} 个部署，epoch={}", count, epoch);
    }
}
