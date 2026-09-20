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
 *
 * <h2>一批要"收齐"才算数（定向对抗 S2-2/S2-3）</h2>
 *
 * <ul>
 *   <li><b>拒旧批</b>：{@code BEGIN} 的 {@link ScriptPush#revision()} 不比上一批大就整条忽略 ——
 *       重放旧 begin 改不动状态（服务端的 revision 跨登录单调）。</li>
 *   <li><b>收齐</b>：{@code END} 必须 epoch 一致<b>且</b>收到的 deployment 条数 == begin 的 count，
 *       否则整批作废（epoch 清 0、内容清空）。</li>
 *   <li><b>不完整不可见</b>：{@link #deployment(String)} 在 {@code !complete} 时一律返回 null ——
 *       进行中的半成品与作废批次的残留都不会被界面当成"本服有部署"。</li>
 * </ul>
 */
public final class ClientHandshake {

    /**
     * 一个部署在本客户端的可见信息（都是 UX：deployRev 用于版本比对，actions 用于灰按钮，
     * visibility 给商店/详情页用）。
     */
    public record Entry(String deployRev, String frontendDigest, int visibility, List<String> actions) {
        public Entry {
            actions = List.copyOf(actions);
        }
    }

    private static volatile UUID serverId;
    private static volatile long epoch;
    private static volatile boolean complete;
    /** 上一批 BEGIN 的 revision；重放/乱序的旧批不比它大就被忽略。 */
    private static volatile long lastBeginRevision = Long.MIN_VALUE;
    private static volatile int expectedCount;
    private static volatile int receivedCount;
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
                        if (push.revision() <= lastBeginRevision) {
                            MCphone.LOGGER.warn("[MCphone] 忽略一批旧的/重放的握手（revision {} <= {}）",
                                    push.revision(), lastBeginRevision);
                            return;
                        }
                        lastBeginRevision = push.revision();
                        serverId = begin.serverId();
                        epoch = begin.epoch();
                        expectedCount = begin.count();
                        receivedCount = 0;
                        complete = false;
                        deployments.clear();      // 新的一批：先清空，等 end 才算齐
                    }
                }
                case ScriptProtocol.TOPIC_HANDSHAKE_DEPLOYMENT -> {
                    Handshake.Deployment d = Handshake.decodeDeployment(push.data());
                    synchronized (ClientHandshake.class) {
                        if (complete) return;               // 这一批已经收尾：迟到的明细不认
                        if (expectedCount > 0 && receivedCount >= expectedCount) return;
                        deployments.put(d.appId(), new Entry(d.deployRev(), d.frontendDigest(),
                                d.visibility(), d.actions()));
                        receivedCount++;
                    }
                }
                case ScriptProtocol.TOPIC_HANDSHAKE_END -> {
                    Handshake.End end = Handshake.decodeEnd(push.data());
                    synchronized (ClientHandshake.class) {
                        // epoch 对 + 条数收齐，这一批才可用；否则整批作废（内容清空，别让界面看见半批）
                        if (end.epoch() == epoch && receivedCount == expectedCount) {
                            complete = true;
                        } else {
                            MCphone.LOGGER.warn("[MCphone] 握手批次作废：epoch {} vs {}，条数 {} vs {}",
                                    end.epoch(), epoch, receivedCount, expectedCount);
                            complete = false;
                            epoch = 0L;
                            deployments.clear();
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
        return complete && epoch != 0L ? epoch : 0L;
    }

    /** 这一批握手完整收到了没有（begin…end 都在且条数收齐）。 */
    public static boolean complete() {
        return complete;
    }

    /** 某个 App 的部署信息；本服没有、或握手还没收齐/已作废时 null（界面据此走空壳降级，§13.7）。 */
    public static Entry deployment(String appId) {
        synchronized (ClientHandshake.class) {
            return complete ? deployments.get(appId) : null;
        }
    }

    /** 断线/换服/退出时清空——按 serverId 分桶的本地状态由各自的层负责。 */
    public static void clear() {
        synchronized (ClientHandshake.class) {
            serverId = null;
            epoch = 0L;
            complete = false;
            lastBeginRevision = Long.MIN_VALUE;
            expectedCount = 0;
            receivedCount = 0;
            deployments.clear();
        }
    }
}
