package com.november.mcphone.core.script.net;

/**
 * 脚本 RPC 的线上常量（施工方案 §15.3、§10.3）。<b>改这里的任何一个数都是改线格式。</b>
 */
public final class ScriptProtocol {

    private ScriptProtocol() {
    }

    /**
     * 脚本 RPC 自己的协议号，与加载器的握手版本无关。
     *
     * <p><b>它不是冗余的</b>：三个平台里只有两个有加载器级的版本闸
     * （1.20.1-forge 的 {@code PROTOCOL_VERSION = "6"}、1.21.1-neoforge 的 {@code registrar("3")}），
     * <b>1.21.1-fabric 一个都没有</b>（对 {@code platforms/1.21.1-fabric} 的 grep 零命中）。
     * Fabric 上这个字段是 RPC 方向唯一的版本闸；<b>握手方向的闸是 {@link #SCRIPT_API}</b>。
     *
     * <p>所以对不上时必须回 {@link ScriptErrorCode#VERSION_MISMATCH}，<b>不许断线</b> ——
     * 断线在 Fabric 上会把版本不一致表现成"连不上服务器"，玩家无从知道该更新。
     */
    public static final int PROTOCOL = 1;

    /**
     * 握手线格式版本（{@code begin.scriptApi}）。<b>改 {@link Handshake} 里任何一个编解码就是改它。</b>
     *
     * <p>1 = Stage 2 第一批的形态；2 = 部署项加了 {@code approvalRevision} / {@code approvedAt}
     * 两个 varlong（旧读法会把批准轴当动作表长度，从批准时刻的字节里读 UTF，解不开）。
     *
     * <p>为什么必须有：这个仓库的成文规矩是"字段格式变了就抬闸"，而三个目标里
     * 1.20.1-forge / 1.21.1-neoforge 有加载器闸可抬，<b>Fabric 没有</b> —— 混版本在 Fabric 上
     * 唯一能被识别的点就是 {@code begin} 里的这个数。客户端对不上就整批不应用
     * （表现是"本服没有已批准部署"，而不是拿到半批垃圾）。
     */
    public static final int SCRIPT_API = 2;

    /** {@code params} 与 {@code data} 的上限（§15.3）。解码侧必须带着它读，否则是内存放大面。 */
    public static final int PARAMS_MAX = 4096;

    /** 同上。 */
    public static final int DATA_MAX = 4096;

    /** {@code appId} / {@code deployRev} / {@code actionId} 的字符上限（§10.3 的示例就是 64）。 */
    public static final int ID_MAX = 64;

    /** {@code messageKey} 的字符上限。本地化键，不是文本。 */
    public static final int KEY_MAX = 128;

    /** {@code messageArgs} 的条数与单条长度上限。 */
    public static final int ARGS_MAX = 8;
    public static final int ARG_LEN_MAX = 128;

    /** {@code frontendDigest} 的字符上限：sha256 的十六进制是 64 位。 */
    public static final int DIGEST_MAX = 64;

    /** {@code topic} 的字符上限（§15.3 的 script_push）。 */
    public static final int TOPIC_MAX = 128;

    // ---------------------------------------------------------------- 宿主保留的命名空间

    /**
     * 宿主自己发的推送用这个 appId（§13.5、§13.8）。
     *
     * <p><b>为什么要保留</b>：握手（serverId / 部署表 / 已授权动作）走 {@code script_push}，
     * 而 {@code topic} 在 §16.5 里是<b>脚本说了算</b>的字符串（{@code ctx.notify(topic, data)}）。
     * 不保留的话，任何一个普通 App 的 {@code server.js} 写一句
     * {@code ctx.notify('mcphone:handshake/begin', …)} 就能把 serverId 与部署表整个改写。
     *
     * <p>两道闸，缺一不可：
     * <ol>
     *   <li>装包时拒绝 {@code mcphone} 命名空间的 appId（{@code Manifest.RESERVED_NAMESPACE} 已经在拦）</li>
     *   <li>脚本侧的 {@code ctx.notify} 只许发以自己 appId 开头的 topic（<b>S13 落实</b>，本步只定常量）</li>
     * </ol>
     * 客户端分派握手时两个条件都要查：appId 等于这个，且 topic 以 {@link #HOST_TOPIC_PREFIX} 开头。
     */
    public static final String HOST_APP_ID = "mcphone:host";

    /** 宿主保留的 topic 前缀。脚本发的 topic 一律拒这个前缀。 */
    public static final String HOST_TOPIC_PREFIX = "mcphone:";

    /** 握手的三个 topic，见 {@link Handshake}。 */
    public static final String TOPIC_HANDSHAKE_BEGIN = "mcphone:handshake/begin";
    public static final String TOPIC_HANDSHAKE_DEPLOYMENT = "mcphone:handshake/deployment";
    public static final String TOPIC_HANDSHAKE_END = "mcphone:handshake/end";

    /**
     * 待批候选表的上限（{@code DeploymentData.MAX_CANDIDATES} 用的就是它）。
     *
     * <p><b>握手不再按条数封顶</b>：推不推由<b>每条的编码字节数</b>决定 —— 一条 deployment
     * 编码后 &gt; {@link #DATA_MAX} 就整条跳过并告警（见 {@code HandshakeService}）。
     * 超过本值只是候选表按 LRU 淘汰，与握手推几条没有关系；客户端不再有"按需去拉"的分支。
     */
    public static final int HANDSHAKE_MAX_DEPLOYMENTS = 64;

    /** 一个部署最多声明几个动作。握手按这个数算得出单条 push 的上限。 */
    public static final int MAX_ACTIONS_PER_DEPLOYMENT = 32;
}
