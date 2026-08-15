package com.november.mcphone.store;

import com.november.mcphone.MCphone;
import com.november.mcphone.api.client.store.AppInfo;
import com.november.mcphone.api.client.store.IAppSource;
import com.november.mcphone.util.SpiLoader;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.function.Consumer;

/**
 * 应用来源注册表 —— 通过 SPI 发现所有 {@link IAppSource}。
 *
 * 与 App 本身的发现机制保持一致：ServiceLoader 扫描所有已加载 jar 中的
 * META-INF/services/com.november.mcphone.api.client.store.IAppSource
 */
public final class AppSourceRegistry {

    private static final Map<ResourceLocation, IAppSource> SOURCES = new LinkedHashMap<>();
    private static boolean loaded = false;

    private AppSourceRegistry() {}

    /** 注册一个来源。同 id 冲突时保留先注册者并告警。 */
    public static boolean register(IAppSource source) {
        if (source == null || source.getId() == null) {
            MCphone.LOGGER.warn("[MCphone] 应用来源注册失败: id 为空");
            return false;
        }
        IAppSource old = SOURCES.get(source.getId());
        if (old != null) {
            MCphone.LOGGER.warn("[MCphone] 应用来源 id 冲突: '{}' 已由 {} 注册，忽略 {}",
                    source.getId(), old.getClass().getName(), source.getClass().getName());
            return false;
        }
        SOURCES.put(source.getId(), source);
        MCphone.LOGGER.info("[MCphone] 应用来源已注册: {}", source.getId());
        return true;
    }

    /** 全部已注册来源，保持注册顺序 */
    public static List<IAppSource> getSources() {
        ensureLoaded();
        return List.copyOf(SOURCES.values());
    }

    public static IAppSource getSource(ResourceLocation id) {
        ensureLoaded();
        return SOURCES.get(id);
    }

    /**
     * 汇总所有可用来源的 App 列表。
     *
     * 各来源可能异步返回，这里在全部回调到齐后才调用 callback 一次。
     * 与 IAppSource 的约定一致：本方法的 callback 同样在客户端主线程执行。
     */
    public static void listAllAvailable(Consumer<List<AppInfo>> callback) {
        List<IAppSource> sources = new ArrayList<>();
        for (IAppSource s : getSources()) {
            if (s.isReady()) sources.add(s);
        }

        if (sources.isEmpty()) {
            callback.accept(List.of());
            return;
        }

        List<AppInfo> merged = new ArrayList<>();
        int[] remaining = { sources.size() };

        for (IAppSource s : sources) {
            s.listAvailable(list -> {
                if (list != null) merged.addAll(list);
                if (--remaining[0] == 0) callback.accept(List.copyOf(merged));
            });
        }
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;

        // 走 SpiLoader：一个附属的来源类构造失败不该让整张来源表加载不上，
        // 那会连本地来源一起丢掉，商店直接变空。理由详见 SpiLoader 的注释
        int count = 0;
        for (IAppSource s : SpiLoader.loadSafely(IAppSource.class, "应用来源")) {
            try {
                if (register(s)) count++;
            } catch (Throwable t) {
                MCphone.LOGGER.error("[MCphone] 应用来源 {} 登记时抛异常，已跳过",
                        s.getClass().getName(), t);
            }
        }
        MCphone.LOGGER.info("[MCphone] 应用来源扫描完成，共 {} 个", count);
    }
}
