package com.november.mcphone.feature.browser.client;

import com.november.mcphone.MCphone;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;

/** 浏览器后端的挂载点：全局一个，先接上的算数，后来者被拒绝并告警。 */
public final class BrowserBackends {

    private BrowserBackends() {}

    /** MCEF 的 modid，浏览器 App 声明前置时也用它，只此一处 */
    public static final String MCEF_MODID = "mcef";

    /** 永远不可用的默认后端，兜"装了 MCEF 但初始化失败"的情况，调用方不必判 null */
    public static final IBrowserBackend NONE = new IBrowserBackend() {
        @Override public boolean isAvailable() { return false; }

        @Override public Component unavailableReason() {
            return Component.translatable("mcphone.browser.no_backend");
        }

        @Override public IBrowser create(String url, int width, int height) { return null; }
    };

    private static IBrowserBackend current = NONE;

    /** 永不为 null */
    public static IBrowserBackend get() {
        return current;
    }

    /** 已经有人接过时返回 false 并保留原来那个 */
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

    /** 装了 MCEF 吗（是"装没装"，不是"此刻能不能用"） */
    public static boolean isMcefLoaded() {
        return ModList.get().isLoaded(MCEF_MODID);
    }

    /** 客户端启动时调一次。判断与真正 new 分在两个方法里，别把 installMcef 并回来（理由见 IBrowserBackend） */
    public static void installDefault() {
        if (!isMcefLoaded()) {
            MCphone.LOGGER.info("[MCphone] 未装 MCEF，浏览器功能不可用");
            return;
        }
        try {
            installMcef();
        } catch (Throwable t) {
            // 兜 Throwable 而非 Exception：MCEF 换了类名或签名时抛的是 NoClassDefFoundError / NoSuchMethodError
            MCphone.LOGGER.error("[MCphone] 接入 MCEF 失败，浏览器功能不可用", t);
        }
    }

    /** 唯一提到 McefBackend 类名的地方，只在确认装了之后才会被调到 */
    private static void installMcef() {
        set(new McefBackend());
    }
}
