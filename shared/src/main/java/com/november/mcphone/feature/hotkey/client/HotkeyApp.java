package com.november.mcphone.feature.hotkey.client;

import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.core.client.PhoneApp;

/**
 * 「遥控器」—— 把用得少的模组键位收进来当按钮。
 *
 * <h2>它解决的是什么</h2>
 *
 * 科技模组盔甲的模块键、鞘翅模组的模式切换键，这些键位一年按不了几次，
 * 却各占一个顺手的位置。装十几个模组之后，顺手的那几十个键位就没了，
 * 玩家只能退而求其次把它们绑到别扭的地方，或者彼此冲突。
 *
 * <p>这个 App 把那些键位变成一条<b>列表</b>：点一下就等于按了那一下。
 * 想腾键位的话，就把原键位解绑 —— 解绑之后手机照样点得到，
 * 因为触发走的是<b>对象</b>而不是键（理由见 {@link HotkeyBackend}）。
 *
 * <h2>哪些键位触发得了，哪些触发不了</h2>
 *
 * 覆盖大部分模组：它们在客户端 tick 里轮询自己那个 {@code KeyMapping.consumeClick()}。
 * 覆盖不到的有两类，页面会如实说出来而不是静默失灵 —— 见 {@link KeyTrigger.Outcome}。
 *
 * <h2>贴图</h2>
 *
 * {@code assets/mcphone/textures/app/hotkey.png}（20×20）
 */
public final class HotkeyApp extends PhoneApp {

    public HotkeyApp() {
        super("hotkey");
    }

    /**
     * 这个目标能不能真的注入点击。三个目标现在都是 true（neoforge 与 forge 用 AT、
     * fabric 用 access widener，三边都把 {@code KeyMapping.clickCount} 放开了），
     * 所以这个 App 在主屏上都有。
     *
     * <p>这一问仍然留着，没有并进常量：{@code isAvailable()} 的用法本来是"缺个前置模组就别摆出来"，
     * 而"这个目标上还没接上"与它是同一个结论。门留在一处，判断就不会散到两个地方去。
     */
    @Override
    public boolean isAvailable() {
        return KeyTrigger.available();
    }

    @Override
    public IPhonePage openPage() {
        return new HotkeyPage();
    }

    /** 覆盖了 {@link #openPage()} 之后走不到这里，接口要求实现 */
    @Override
    public void onPress() {}
}
