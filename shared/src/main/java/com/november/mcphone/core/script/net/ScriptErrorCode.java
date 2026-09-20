package com.november.mcphone.core.script.net;

/**
 * 脚本 RPC 的结果码（施工方案 §15.4，全 15 行）。
 *
 * <h2>线上按序号传，且解码侧不许抛</h2>
 *
 * 这个仓库有过血的教训：{@code MCphoneNetwork} 的类注释写着「0.11.1 的客户端收到一个它不认识的
 * 序号是<b>解码抛异常、netty 当场断线</b>」。所以新码只许<b>追加在末尾</b>，
 * 而且 {@link #fromWire} 遇到不认识的序号<b>回 {@link #INTERNAL} 并记日志，不抛</b> ——
 * 否则新服务端给旧客户端回一个新码就等于把玩家踢下线。
 *
 * <h2>谁产生哪个码</h2>
 *
 * 本步（S12）的管线只产生得出其中 8 个。剩下的分两类，标在每一项上：
 * <ul>
 *   <li><b>S13+ 才有</b>：出自 §20 的守卫或 §24 的吊销表，本步只有测试替身产生得出</li>
 *   <li><b>客户端合成</b>：服务端发不出来 —— 一台没装 MCphone 的服务端不可能发
 *       "服务端没装 MCphone"，只能由客户端在超时或没收到握手时自己合成</li>
 * </ul>
 * 不标出来的话，实现者会为吊销写一条永远走不到的分支，而 {@code UNAVAILABLE} 一条都不写。
 *
 * <h2>少一个 INVENTORY_FULL</h2>
 *
 * §20.9 的 {@code reject} 策略原文是"返回 {@code fail('INVENTORY_FULL')}"，
 * 而 §15.4 的 15 行里<b>没有它</b>——方案两处打架。本步照 §15.4 的 15 行做，
 * 因为 §23.4 把"加新的错误码"明确列在<b>允许</b>那一列：等 §20.9 真的要实现时（S19），
 * 把它追加成第 16 个即可，不必现在猜。
 */
public enum ScriptErrorCode {

    /** 成功。按 {@code data} 更新。 */
    OK,

    /** 服务端没装 MCphone / 功能被服主关了。灰掉整个 App。<b>客户端合成</b>：服务端发不出这个码。 */
    UNAVAILABLE,

    /** 本服没有这个 App 的已批准部署。§13.7 的空壳提示。 */
    NOT_DEPLOYED,

    /** 协议或部署版本对不上。提示更新，<b>不重试</b>。 */
    VERSION_MISMATCH,

    /** 动作不在交集里（§9.3）。「你还没有被授权」+ 申请按钮。<b>本步靠 {@link com.november.mcphone.core.script.server.AuthorityView} 的替身产生</b>，真授权表是 S17。 */
    NOT_AUTHORIZED,

    /** 限流，或有界队列满了。按 {@code retryAfterMs} 退避，<b>不许自动重试不可逆动作</b>。 */
    RATE_LIMITED,

    /** 参数不合 schema、连接 epoch 过期、同一 requestId 换了参数。开发者问题，显示 messageKey。 */
    INVALID_ARGUMENT,

    /** 业务冷却未到。倒计时，按 {@code data.nextAt}。<b>S13+</b>：出自 §20 的守卫。 */
    COOLDOWN,

    /** 限量已领完（§20.4）。「已领完」。<b>S13+</b>。 */
    EXHAUSTED,

    /** 活动还没开始。倒计时，按 {@code data.startAt}。<b>S13+</b>。 */
    NOT_STARTED,

    /** 同一幂等键的请求正在处理。等，不重发。 */
    IN_PROGRESS,

    /** 部分成功。显示 {@code data} 里说明哪部分成了。<b>S13+</b>。 */
    PARTIAL,

    /**
     * 结果不明。「结果未确认，请联系管理员」+ <b>绝不自动重试</b>。
     *
     * <p><b>它不是可有可无的</b>：命令执行、跨系统副作用、崩溃窗口都会产生"不知道成没成"。
     * 少了它，实现者只能在 {@link #OK} 和 {@link #INTERNAL} 之间二选一，两个选择都会造成
     * 重复发奖或漏发。本步还有一处会走到它：重放命中但上次的 {@code data} 太大没进账本
     * （见 {@code IdempotencyLedger.REPLAYABLE_DATA_MAX}）。
     * 货币调用结果不明（{@code OutcomeUnknown}：provider 动钱时抛了或没给结果）也回它；脚本自己 {@code ctx.fail('UNKNOWN')} 同样回得出来。
     */
    UNKNOWN,

    /** 这个版本被吊销了（§24）。提示必须升级，不重试。<b>S23 才有吊销表</b>，本步产生不出来。 */
    REVOKED,

    /** 后端脚本抛异常 / 宿主错误。通用失败，已记审计。 */
    INTERNAL;

    /** 线上的序号。只许追加，不许中间插。 */
    public int toWire() {
        return ordinal();
    }

    /** 不认识的序号<b>不抛</b>，当 {@link #INTERNAL}。理由见类注释。 */
    public static ScriptErrorCode fromWire(int wire) {
        ScriptErrorCode[] v = values();
        return (wire >= 0 && wire < v.length) ? v[wire] : INTERNAL;
    }

    /** 收到的序号本机认不认得。调用方据此决定要不要记一条"对面比我新"的日志。 */
    public static boolean known(int wire) {
        return wire >= 0 && wire < values().length;
    }

    /**
     * 默认文案的本地化键，{@code mcphone.script.code.<小写名>}。
     *
     * <p>后端可以用 {@code ctx.fail(code, data, 'myapp.msg.xxx')} 盖掉它，
     * 但那个键必须在 App 自己的 {@code lang/*.json} 里 —— 服务端发键不发文本（§15.3）。
     */
    public String defaultMessageKey() {
        return "mcphone.script.code." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
