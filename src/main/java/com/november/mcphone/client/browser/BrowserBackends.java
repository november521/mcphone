package com.november.mcphone.client.browser;

import com.november.mcphone.MCphone;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;

/**
 * 浏览器后端的挂载点 —— 全局一个，谁接上算谁的。
 *
 * 与 EmcWallets 是同一个形状，理由也一样：同时存在两个浏览器后端的世界里，
 * "该用哪一个"没有正确答案。已有人接管时再 set 会被拒绝并告警，不静默顶掉。
 *
 * 与 EmcWallets 不同的是这里有个默认实现会自己接上：{@link #installDefault()}
 * 在客户端启动时被调一次，装了 MCEF 就接 MCEF。附属模组想换成别的后端，在那
 * 之前调 {@link #set} 即可。
 */
public final class BrowserBackends {

    private BrowserBackends() {}

    private static final String MCEF_MODID = "mcef";

    /**
     * 默认后端：永远不可用。
     *
     * 存在的意义是让调用方不必判 null。没装 MCEF 时浏览器 App 压根不会登记
     * （见 BrowserApp.isAvailable），所以正常情况下没人会碰到它——它兜的是
     * "装了 MCEF 但初始化失败"那种情况。
     */
    public static final IBrowserBackend NONE = new IBrowserBackend() {
        @Override public boolean isAvailable() { return false; }

        @Override public Component unavailableReason() {
            return Component.translatable("mcphone.browser.no_backend");
        }

        @Override public IBrowser create(String url, int width, int height) { return null; }
    };

    private static IBrowserBackend current = NONE;

    /** 当前后端。永不为 null */
    public static IBrowserBackend get() {
        return current;
    }

    /**
     * 接上一个后端。
     *
     * @return true 表示接上了；已经有人接过时返回 false 并保留原来那个
     */
    public static boolean set(IBrowserBackend backend) {
        if (backend == null) {
            MCphone.LOGGER.warn("[MCphone] 浏览器后端登记失败: 传入了 null");
            return false;
        }
        if (current != NONE) {
            MCphone.LOGGER.warn("[MCphone] 浏览器后端已由 {} 接管，忽略 {}",
                    current.getClass().getName(), backend.getClass().getName());
            return false;
        }
        current = backend;
        MCphone.LOGGER.info("[MCphone] 浏览器后端已接入: {}", backend.getClass().getName());
        return true;
    }

    /** 装了 MCEF 吗。浏览器 App 用它决定自己该不该出现 */
    public static boolean isMcefLoaded() {
        return ModList.get().isLoaded(MCEF_MODID);
    }

    /**
     * 客户端启动时调一次：装了 MCEF 就把它接上。
     *
     * 判断与真正 new 它分在两个方法里，理由见 {@link IBrowserBackend} 的类注释。
     * 别把 installMcef 并回来。
     */
    public static void installDefault() {
        if (!isMcefLoaded()) {
            MCphone.LOGGER.info("[MCphone] 未装 MCEF，浏览器功能不可用");
            return;
        }
        try {
            installMcef();
        } catch (Throwable t) {
            // 兜 Throwable 而不是 Exception：这里最可能的死法是 MCEF 换了类名或
            // 签名，抛出来的是 NoClassDefFoundError / NoSuchMethodError，都属于
            // Error。兜住的代价是浏览器不可用，不兜的代价是整个客户端起不来。
            MCphone.LOGGER.error("[MCphone] 接入 MCEF 失败，浏览器功能不可用", t);
        }
    }

    /** 真正碰 MCEF 的地方。只在上面确认装了之后才会被调到 */
    private static void installMcef() {
        set(new McefBackend());
    }
}
