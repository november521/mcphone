package com.november.mcphone.feature.store.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.api.client.store.AppInfo;
import com.november.mcphone.api.client.store.IAppSource;
import com.november.mcphone.util.SpiLoader;
import net.minecraft.resources.ResourceLocation;

import java.util.*;
import java.util.function.Consumer;

/** 应用来源注册表，通过 SPI 发现所有 {@link IAppSource} */
public final class AppSourceRegistry {

    private static final Map<ResourceLocation, IAppSource> SOURCES = new LinkedHashMap<>();
    private static boolean loaded = false;

    private AppSourceRegistry() {}

    /** 同 id 冲突时保留先注册者并告警 */
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

    /** 各来源可能异步返回，全部到齐后才调用 callback 一次；callback 在客户端主线程执行 */
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

        // 必须走 SpiLoader：一个来源类构造失败会连本地来源一起丢掉，商店直接变空
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
