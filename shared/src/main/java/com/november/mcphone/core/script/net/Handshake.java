package com.november.mcphone.core.script.net;

import com.november.mcphone.core.net.Wire;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;

import java.util.List;
import java.util.UUID;

/**
 * 进服时服务端主动下发的那一次（施工方案 §15.7）。<b>不新开第四个包，走 {@link ScriptPush} 的保留 topic。</b>
 *
 * <h2>一个部署一条 push，不分片</h2>
 *
 * 整张表塞不进一个 4 KiB 的 {@code data}：按 §10.3 的字段上限算，一个部署要 815 字节，
 * 4 KiB 只装得下 4 个；按典型值（appId 20 字符、10 个动作）算，20 个部署是 4466 字节，也超。
 * 分片则要在客户端加一套重组状态机 —— 凭空多一个内存放大面。
 *
 * <p>所以拆成三种 topic：{@code begin}（一条，带总数）、{@code deployment}（一个部署一条）、
 * {@code end}（一条）。{@link ScriptPush#revision} 正好用来判乱序与丢失。
 *
 * <h2>⚠ 这条通道脚本也能发，所以分派时两个条件都要查</h2>
 *
 * {@code topic} 在 §16.5 里是脚本说了算的字符串。只按 topic 分派的话，
 * 任何一个 App 的 {@code server.js} 写一句 {@code ctx.notify('mcphone:handshake/begin', …)}
 * 就能把 serverId 与部署表整个改写 —— 那是 §13.5 与 §13.8 一起被打穿。
 * 用 {@link ScriptPush#isHost()}，它两个条件都查。
 */
public final class Handshake {

    private Handshake() {
    }

    /**
     * 第一条。
     *
     * @param serverId   §13.5 的服务器身份，存在存档里
     * @param serverName 显示名
     * @param scriptApi  {@link ScriptProtocol#SCRIPT_API}，<b>握手线格式版本</b>。客户端对不上就
     *                   整批不应用（Fabric 没有加载器级闸，这是唯一能识别混版本的地方）
     * @param epoch      这一次连接的标签。客户端之后每个 {@link ScriptRpc} 都要原样回填
     * @param count      接下来会推几条 deployment。<b>只数真正会推出去的</b>：编码后超过
     *                   {@link ScriptProtocol#DATA_MAX} 的条目在服务端就整条跳过、不计入，
     *                   没有"条数封顶"这回事（每条能不能装下由它自己的字节数决定）
     * @param features   服主开了哪些口子
     */
    public record Begin(UUID serverId, String serverName, int scriptApi, long epoch,
                        int count, Features features) {
    }

    /** §15.7 的 {@code features}。 */
    public record Features(boolean externalFetch, boolean vault, boolean serverScripts) {
    }

    /**
     * 一个部署。
     *
     * @param actions <b>只含当前玩家被授权的动作</b>（§13.8）。它是 UX 用的 ——
     *                客户端据此把没授权的按钮画灰，<b>不是安全边界</b>，
     *                真正的判定在服务端落地前那一次重查
     * @param approvalRevision 批准轴（同一 App 每次批准 +1），详情页"版本 N"显示的就是它。
     *                         <b>不参与</b> deployRev 对齐（那是包轴，见 {@link Deployment#revision()}）
     * @param approvedAt 批准时刻（epoch 毫秒），详情页来源行用；0 表示没记
     */
    public record Deployment(String appId, String deployRev, String frontendDigest,
                             int visibility, long approvalRevision, long approvedAt, List<String> actions) {
    }

    /** 最后一条。带着同一个 epoch，客户端据此确认这一批是完整的。 */
    public record End(long epoch) {
    }

    // ---------------------------------------------------------------- 编解码

    public static byte[] encodeBegin(Begin b) {
        FriendlyByteBuf buf = buffer();
        buf.writeUUID(b.serverId());
        buf.writeUtf(b.serverName(), ScriptProtocol.ID_MAX);
        buf.writeVarInt(b.scriptApi());
        buf.writeVarLong(b.epoch());
        buf.writeVarInt(b.count());
        buf.writeBoolean(b.features().externalFetch());
        buf.writeBoolean(b.features().vault());
        buf.writeBoolean(b.features().serverScripts());
        return bytes(buf);
    }

    public static Begin decodeBegin(byte[] data) {
        FriendlyByteBuf buf = wrap(data);
        return new Begin(buf.readUUID(), buf.readUtf(ScriptProtocol.ID_MAX), buf.readVarInt(),
                buf.readVarLong(), buf.readVarInt(),
                new Features(buf.readBoolean(), buf.readBoolean(), buf.readBoolean()));
    }

    public static byte[] encodeDeployment(Deployment d) {
        FriendlyByteBuf buf = buffer();
        buf.writeUtf(d.appId(), ScriptProtocol.ID_MAX);
        buf.writeUtf(d.deployRev(), ScriptProtocol.ID_MAX);
        buf.writeUtf(d.frontendDigest(), ScriptProtocol.DIGEST_MAX);
        buf.writeVarInt(d.visibility());
        buf.writeVarLong(d.approvalRevision());
        buf.writeVarLong(d.approvedAt());
        Wire.writeList(buf, d.actions(), ScriptProtocol.MAX_ACTIONS_PER_DEPLOYMENT,
                (s, b) -> b.writeUtf(s, ScriptProtocol.ID_MAX));
        return bytes(buf);
    }

    public static Deployment decodeDeployment(byte[] data) {
        FriendlyByteBuf buf = wrap(data);
        return new Deployment(
                buf.readUtf(ScriptProtocol.ID_MAX),
                buf.readUtf(ScriptProtocol.ID_MAX),
                buf.readUtf(ScriptProtocol.DIGEST_MAX),
                buf.readVarInt(),
                buf.readVarLong(),
                buf.readVarLong(),
                Wire.readList(buf, ScriptProtocol.MAX_ACTIONS_PER_DEPLOYMENT,
                        b -> b.readUtf(ScriptProtocol.ID_MAX)));
    }

    public static byte[] encodeEnd(End e) {
        FriendlyByteBuf buf = buffer();
        buf.writeVarLong(e.epoch());
        return bytes(buf);
    }

    public static End decodeEnd(byte[] data) {
        return new End(wrap(data).readVarLong());
    }

    /** 包成一条推送。{@code revision} 由调用方按顺序给，客户端据此判乱序。 */
    public static ScriptPush push(String topic, byte[] data, long revision) {
        return new ScriptPush(ScriptProtocol.HOST_APP_ID, topic, data, revision);
    }

    /**
     * 这条部署在握手线上要多少字节（取<b>已批准动作全集</b>，是实际上限：推给某个玩家时只会更少）。
     * <b>批准期就要用它</b>：超过 {@link ScriptProtocol#DATA_MAX} 的部署客户端永远收不到，
     * 批了也白批（表现是"本服没部署"）。运行时的整条跳过只是老存档的兜底。
     *
     * <p>参数类型写全限定名：本类里有一个同名的嵌套 {@link Deployment}（线上的那条），
     * 简单名会被它遮住。
     */
    public static int wireSize(com.november.mcphone.core.script.server.Deployment d) {
        return encodeDeployment(new Deployment(d.appId(), d.revision(), d.frontendDigest(), 0,
                d.approvalRevision(), d.approvedAt(), d.approvedActions())).length;
    }

    private static FriendlyByteBuf buffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    private static FriendlyByteBuf wrap(byte[] data) {
        return new FriendlyByteBuf(Unpooled.wrappedBuffer(data));
    }

    private static byte[] bytes(FriendlyByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }
}
