package com.november.mcphone.core.script.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.net.Handshake;
import com.november.mcphone.core.script.net.ScriptProtocol;
import com.november.mcphone.core.script.net.ScriptPush;
import com.november.mcphone.core.script.net.ScriptResultRouter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端侧的握手状态（施工方案 §15.7）：服务端登录时下发的那一批
 * {@code begin → deployment × N → end}。
 *
 * <h2>按 serverId 分桶，不自己推断</h2>
 *
 * {@link #serverId()} 来自服务端握手（§13.5），<b>不是</b>按 IP/地址推出来的 —— 那个键会碰撞、
 * 同一 IP 换服主还会复用。本类不写盘：断线/换服清空（{@link #clear()}），要持久化的（授权缓存、保险箱）
 * 由各自的层按这个 id 分桶。
 *
 * <h2>epoch 是连接归属，不是授权</h2>
 *
 * {@link #connectionEpoch()} 只用于在自己发出的 {@code ScriptRpc} 里回填；服务端拿它防旧响应串台。
 * <b>epoch 对不表示有权限</b>：权限每次由服务端重查。
 */
public final class ClientHandshake {

    /** 一个部署在本客户端的可见信息（都是 UX：deployRev 用于版本比对，actions 用于灰按钮）。 */
    public record Entry(String deployRev, String frontendDigest, List<String> actions) {
        public Entry {
            actions = List.copyOf(actions);
        }
    }

    private static volatile UUID serverId;
    private static volatile long epoch;
    private static volatile boolean complete;
    private static final Map<String, Entry> deployments = new LinkedHashMap<>();

    private ClientHandshake() {
    }

    /** 客户端初始化时调一次：把推送接收方接到 {@link #onPush}。 */
    public static void install() {
        ScriptResultRouter.installPush(ClientHandshake::onPush);
    }

    /** 收到一条推送。只认宿主发的握手 topic（{@link ScriptPush#isHost()}），别的忽略。 */
    static void onPush(ScriptPush push) {
        if (push == null || !push.isHost()) return;
        try {
            switch (push.topic()) {
                case ScriptProtocol.TOPIC_HANDSHAKE_BEGIN -> {
                    Handshake.Begin begin = Handshake.decodeBegin(push.data());
                    synchronized (ClientHandshake.class) {
                        serverId = begin.serverId();
                        epoch = begin.epoch();
                        complete = false;
                        deployments.clear();      // 新的一批：先清空，等 end 才算齐
                    }
                }
                case ScriptProtocol.TOPIC_HANDSHAKE_DEPLOYMENT -> {
                    Handshake.Deployment d = Handshake.decodeDeployment(push.data());
                    synchronized (ClientHandshake.class) {
                        deployments.put(d.appId(), new Entry(d.deployRev(), d.frontendDigest(), d.actions()));
                    }
                }
                case ScriptProtocol.TOPIC_HANDSHAKE_END -> {
                    Handshake.End end = Handshake.decodeEnd(push.data());
                    synchronized (ClientHandshake.class) {
                        // epoch 对不上说明这一批掺了旧的/乱的：整批作废，宁可当"没有后端"
                        if (end.epoch() == epoch) complete = true;
                        else {
                            complete = false;
                            epoch = 0L;
                        }
                    }
                }
                default -> {
                    // 宿主将来可能用别的 topic；不认识的忽略
                }
            }
        } catch (Throwable t) {
            MCphone.LOGGER.warn("[MCphone] 握手推送解不开，忽略这一条: topic={}", push.topic(), t);
        }
    }

    /** 服务端身份；还没握手/已断开时 null。 */
    public static UUID serverId() {
        return serverId;
    }

    /** 这一次连接要回填进 {@code ScriptRpc} 的 epoch；没握手/不完整时 0（服务端会判过期，这是正确的）。 */
    public static long connectionEpoch() {
        return complete ? epoch : 0L;
    }

    /** 这一批握手完整收到了没有（begin…end 都在）。 */
    public static boolean complete() {
        return complete;
    }

    /** 某个 App 的部署信息；本服没有就是 null（界面据此走空壳降级，§13.7）。 */
    public static Entry deployment(String appId) {
        synchronized (ClientHandshake.class) {
            return deployments.get(appId);
        }
    }

    /** 断线/换服/退出时清空——按 serverId 分桶的本地状态由各自的层负责。 */
    public static void clear() {
        synchronized (ClientHandshake.class) {
            serverId = null;
            epoch = 0L;
            complete = false;
            deployments.clear();
        }
    }
}
