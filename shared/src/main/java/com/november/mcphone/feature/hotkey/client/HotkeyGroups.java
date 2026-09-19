package com.november.mcphone.feature.hotkey.client;

import java.util.HashSet;
import java.util.Set;

/**
 * 「遥控器」列表里哪几组是展开的。
 *
 * <h2>为什么不挂在页面上</h2>
 *
 * 因为这一页<b>随时会被销毁</b>：声明"只在世界里生效"的键位一点就把手机收起来了
 * （见 {@link HotkeyContext#closePhoneFirst()}），页面随之销毁；玩家想再点一个得重新开机。
 * 展开状态要是挂在页面的字段上，每次回来都是"全部收起"，那这个折叠就等于没法用。
 *
 * <h2>为什么只记在内存里</h2>
 *
 * 它是"我这会儿想看哪几组"的临时口味，不是设置：不值得为它落一个配置文件，
 * 也不该跨游戏会话继承 —— 换一局、换一批模组，上次展开的那几组可能一个都不在。
 * 与 {@code PhoneSession} 同一个分寸，只是它连进世界都不用清：记的是界面习惯，
 * 与在哪个服务器无关。
 *
 * <h2>默认只展开置顶那一段</h2>
 *
 * 分类默认是收起的（上百个键位摊开来找不动），置顶除外 ——
 * 那几条是玩家自己一条条挑出来的，默认藏起来等于白置顶。
 */
public final class HotkeyGroups {

    /**
     * 置顶那一段的组名。
     *
     * <p>分类名来自键位自己（形如 {@code key.categories.movement}），都以 {@code key.} 打头，
     * 撞不上这个带 {@code @} 的名字。
     */
    public static final String PINNED = "@pinned";

    /** 展开着的那几组 */
    private static final Set<String> OPEN = new HashSet<>(Set.of(PINNED));

    private HotkeyGroups() {}

    /** 这一组现在展开着吗 */
    public static boolean isOpen(String group) {
        return OPEN.contains(group);
    }

    /** 点一下表头：展开的收起，收起的展开 */
    public static void toggle(String group) {
        if (!OPEN.remove(group)) OPEN.add(group);
    }
}
