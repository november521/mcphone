package com.november.mcphone.core.script.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.client.PhoneScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * 本地目录自动刷新：读盘与编译在单个后台线程，启用、权限检查和纹理释放回客户端主线程。
 * 两秒最多一轮，不积压任务；没有进入世界时不启用。新 App 仍需在商店手动安装。
 */
public final class LocalScriptUpdates {
    private static final long INTERVAL_NANOS = 2_000_000_000L;
    private static final java.util.concurrent.Executor WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "mcphone-local-app-scan");
        thread.setDaemon(true);
        return thread;
    });
    private static CompletableFuture<List<ScriptApp>> pending;
    private static long nextScan;
    private static final Map<ResourceLocation, ScriptApp> observed = new HashMap<>();

    private LocalScriptUpdates() {}

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null) return;
        // 商店/详情页会缓存图标引用；手机界面关闭后再提交，避免正在显示的元数据变成失效纹理。
        if (mc.screen instanceof PhoneScreen) return;
        if (pending != null) {
            if (!pending.isDone()) return;
            CompletableFuture<List<ScriptApp>> finished = pending;
            pending = null;
            try {
                LocalScriptSource.refresh(stableCandidates(finished.join(), observed), true);
            } catch (java.util.concurrent.CompletionException e) {
                MCphone.LOGGER.warn("[MCphone] 本地 App 扫描失败，保留已接受版本", e.getCause());
            }
        }
        long now = System.nanoTime();
        if (nextScan != 0 && now - nextScan < 0) return;
        Path dir = ScriptAppFolder.dir();
        if (dir == null) return;
        nextScan = now + INTERVAL_NANOS;
        pending = CompletableFuture.supplyAsync(() -> ScriptAppFolder.scan(dir), WORKER);
    }

    /** 连续两轮读取相同内容才进入启用阶段，避免通常的文件复制中间态。 */
    static List<ScriptApp> stableCandidates(List<ScriptApp> candidates, Map<ResourceLocation, ScriptApp> previous) {
        List<ScriptApp> stable = new ArrayList<>();
        Map<ResourceLocation, ScriptApp> next = new HashMap<>();
        for (ScriptApp app : candidates) {
            if (previous.get(app.id()) == app) stable.add(app);
            next.put(app.id(), app);
        }
        previous.clear();
        previous.putAll(next);
        return List.copyOf(stable);
    }
}
