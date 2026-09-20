package com.november.mcphone.core.script.server;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.net.Handshake;
import com.november.mcphone.core.script.net.ScriptProtocol;
import com.november.mcphone.core.script.net.ScriptPush;
import com.november.mcphone.core.script.net.ScriptPushHandler;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 登录握手（施工方案 §15.7）：把服务端身份、一次性 epoch 与部署表推给客户端。
 *
 * <p>三条推送：{@code begin}（一条）→ {@code deployment}（每个部署一条）→ {@code end}（一条）。
 * <b>部署里只带"这个玩家被授权的动作"</b>（{@code backend.actions}）—— 那是 UX，不是边界；
 * 真正的判定在每次请求的服务端重查（§13.8）。
 *
 * <p>不推 {@code server.js}、也不推任何后端代码：客户端拿到的只有 appId / deployRev / frontendDigest / actions。
 *
 * <h2>三条硬约束（定向对抗 S2-1/S2-2）</h2>
 *
 * <ul>
 *   <li><b>绝不许打断登录</b>：整个过程包在 try/catch 里。握手是 UX；一个超长 MOTD 或一条装不下的
 *       部署都不该让玩家进不了服（Fabric 的事件回调还不 catch，那就直接崩在登录上）。</li>
 *   <li><b>入队前按线上限裁剪</b>：serverName 截到 {@link ScriptProtocol#ID_MAX}；每条 deployment 先编码，
 *       超过 {@link ScriptProtocol#DATA_MAX} 的<b>整条跳过并告警</b>（不静默截断动作列表 —— 那会把部署内容
 *       悄悄改小）。中文动作名比 ASCII 占更多字节，32 条 64 字符的动作会超。</li>
 *   <li><b>revision 跨登录单调</b>：{@link #SEQ} 是服务端生命周期级的计数器。原先每次登录从 0 重来，
 *       客户端拿不到任何"这是新一批还是一次重放"的判据（{@link ScriptPush} 的契约正是"客户端据此判乱序与丢失"）。</li>
 * </ul>
 */
public final class HandshakeService {

    /** 跨登录单调。客户端在 BEGIN 处只接受 revision 更大的批次。 */
    private static final AtomicLong SEQ = new AtomicLong();

    private HandshakeService() {
    }

    /**
     * 登录时（拿到 epoch 之后）调用。
     *
     * @param epoch 由 {@link ScriptHost#newEpoch(ServerPlayer)} 返回；为 0 表示脚本后端降级/未装，
     *              这时不发握手 —— 客户端按"本服没有脚本后端"处理
     */
    public static void pushTo(ServerPlayer player, long epoch) {
        try {
            ScriptHost host = ScriptHost.current();
            if (host == null || epoch == 0L) return;

            // 先把明细编好并按线上限过滤：Begin 的 count 必须是【真正会推出去】的条数
            List<String> appIds = new ArrayList<>();
            List<byte[]> payloads = new ArrayList<>();
            for (Deployment d : host.deployments().deployments()) {
                List<String> actions = new ArrayList<>();
                for (String action : d.approvedActions()) {
                    if (host.allows(player.getUUID(), d.appId(), action)) actions.add(action);
                }
                byte[] data = Handshake.encodeDeployment(new Handshake.Deployment(d.appId(), d.revision(),
                        d.frontendDigest(), 0, d.approvalRevision(), d.approvedAt(), actions));
                if (data.length > ScriptProtocol.DATA_MAX) {
                    MCphone.LOGGER.warn("[MCphone] 部署 {} 的握手数据 {} 字节，超过单包上限 {}，本次不推（不静默截断动作）",
                            d.appId(), data.length, ScriptProtocol.DATA_MAX);
                    continue;
                }
                appIds.add(d.appId());
                payloads.add(data);
            }

            // 用 level().getServer()：ServerPlayer.getServer() 在 1.21.1 上不存在，而 level() 两版都有
            net.minecraft.server.MinecraftServer server = player.level().getServer();
            String serverName = clip(server == null ? "" : server.getMotd(), ScriptProtocol.ID_MAX);

            long rev = SEQ.incrementAndGet();
            ScriptPushHandler.push(player, Handshake.push(ScriptProtocol.TOPIC_HANDSHAKE_BEGIN,
                    Handshake.encodeBegin(new Handshake.Begin(host.serverId(), serverName,
                            ScriptProtocol.PROTOCOL, epoch, payloads.size(),
                            new Handshake.Features(false, false, true))), rev));
            for (int i = 0; i < payloads.size(); i++) {
                ScriptPushHandler.push(player, Handshake.push(ScriptProtocol.TOPIC_HANDSHAKE_DEPLOYMENT,
                        payloads.get(i), SEQ.incrementAndGet()));
            }
            ScriptPushHandler.push(player, Handshake.push(ScriptProtocol.TOPIC_HANDSHAKE_END,
                    Handshake.encodeEnd(new Handshake.End(epoch)), SEQ.incrementAndGet()));
            MCphone.LOGGER.info("[MCphone] 握手已下发：{} 个部署，epoch={}", payloads.size(), epoch);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            // 握手失败绝不许打断登录：客户端拿不到 epoch 的后果是"请求判过期连接"，那是有明确返回码的
            MCphone.LOGGER.error("[MCphone] ⚠ 握手下发失败（不打断登录；这名玩家本次连接的脚本请求会判过期连接）", t);
        }
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
