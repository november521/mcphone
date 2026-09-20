package com.november.mcphone.core.script.net;

import com.november.mcphone.MCphone;

import java.util.function.Consumer;

/**
 * 客户端收到 {@link ScriptRpcResult} 与 {@link ScriptPush} 之后交给谁（施工方案 §15.1）。
 *
 * <h2>为什么要这一层</h2>
 *
 * 真正的接收方是 {@code phone.call} 那张回调表，而那是 S13 的脚本运行时。
 * 本步只有登记 —— 登记必须现在做，包的序号由注册顺序发放（§10.3 顺序即身份），
 * 等 S13 再登记的话序号就不一样了。
 *
 * <p><b>这个类里不许出现任何客户端类型</b>：它的路径里没有 {@code /client/}，
 * 而 {@code verifyDistIsolation} 会扫产物字节码，专用服务端加载到客户端类型就崩。
 * 真要碰界面的那一段住在带 {@code /client/} 的类里，这里只留一个 {@link Consumer}。
 */
public final class ScriptResultRouter {

    private ScriptResultRouter() {
    }

    private static volatile Consumer<ScriptRpcResult> onResult;
    private static volatile Consumer<ScriptPush> onPush;

    /** 客户端侧装上自己的接收方。S13 的脚本运行时调它。 */
    public static void install(Consumer<ScriptRpcResult> result, Consumer<ScriptPush> push) {
        onResult = result;
        onPush = push;
    }

    /**
     * 只装推送接收方（S17 Stage 2 的握手用）。与 {@link #install} 分开是因为调用时机不同：
     * 握手在客户端初始化时就要收，而结果回调表要等脚本运行时（S13）到货。
     */
    public static void installPush(Consumer<ScriptPush> push) {
        onPush = push;
    }

    /**
     * 只装结果接收方（S17 Stage 2 的客户端调用用）。与 {@link #installPush} 分开：
     * 一次进服里两方都要装，谁后装不许把谁挤掉（{@link #install} 是"两个一起换"的老口子，
     * 留给将来真正的脚本运行时）。
     */
    public static void installResult(Consumer<ScriptRpcResult> result) {
        onResult = result;
    }

    /** 由各平台的登记点指过来。 */
    public static void result(ScriptRpcResult r) {
        Consumer<ScriptRpcResult> c = onResult;
        if (c != null) {
            c.accept(r);
        } else {
            // 本步的常态：脚本运行时还没到货。记一条就够，别刷屏
            MCphone.LOGGER.debug("[MCphone] 收到脚本结果但还没有接收方: req={} code={}", r.requestId(), r.code());
        }
    }

    /** 同上。<b>宿主自己发的（握手）与脚本发的要分开认</b>，见 {@link ScriptPush#isHost()}。 */
    public static void push(ScriptPush p) {
        Consumer<ScriptPush> c = onPush;
        if (c != null) {
            c.accept(p);
        } else {
            MCphone.LOGGER.debug("[MCphone] 收到脚本推送但还没有接收方: app={} topic={}", p.appId(), p.topic());
        }
    }
}
