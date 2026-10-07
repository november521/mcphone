package com.november.mcphone.core.script.client;

import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.core.script.pkg.FrontendDigest;
import net.minecraft.resources.ResourceLocation;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 本地脚本的已接受版本。文件扫描只产出候选；只有这里能提交替换。
 * 全部调用在客户端主线程。注册表提交成功之后才释放旧资源，失败与页面占用都保留旧版。
 */
final class LocalScriptCatalog {
    private final Map<ResourceLocation, ScriptAppAdapter> accepted = new HashMap<>();
    private final Function<ResourceLocation, IPhoneApp> lookup;
    private final BiPredicate<ScriptAppAdapter, ScriptAppAdapter> replace;
    private final Consumer<ScriptAppAdapter> release;

    LocalScriptCatalog(Function<ResourceLocation, IPhoneApp> lookup,
                       BiPredicate<ScriptAppAdapter, ScriptAppAdapter> replace,
                       Consumer<ScriptAppAdapter> release) {
        this.lookup = lookup;
        this.replace = replace;
        this.release = release;
    }

    ScriptAppAdapter get(ResourceLocation id) {
        return accepted.get(id);
    }

    ScriptAppAdapter observe(ScriptApp candidate, BiPredicate<ScriptApp, ScriptApp> allowed,
                             Predicate<ResourceLocation> inUse) {
        ScriptAppAdapter old = accepted.get(candidate.id());
        if (old != null && old.script() == candidate) return old;
        IPhoneApp registered = lookup.apply(candidate.id());
        // 新文件只成为商店候选，不改变安装状态；同名的内建/附属 App 不能抢占。
        if (registered != null && registered != old) return old;
        ScriptAppAdapter fresh = new ScriptAppAdapter(candidate);
        if (old == null || registered == null) {
            accepted.put(candidate.id(), fresh);
            if (old != null) release.accept(old);
            return fresh;
        }
        if (!allowed.test(old.script(), candidate) || inUse.test(candidate.id())) return old;
        if (!replace.test(old, fresh)) return old;
        accepted.put(candidate.id(), fresh);
        release.accept(old);
        return fresh;
    }

    /** 自动刷新只接纯前端、递增版本；带后端的旧路径由显式扫描另行处理。 */
    static boolean isFrontend(ScriptApp app) {
        return app.pkg() == null || app.pkg().paths().stream().noneMatch(FrontendDigest::isServerSide);
    }

    static boolean newer(ScriptApp old, ScriptApp candidate) {
        String[] a = old.manifest().version().split("\\.");
        String[] b = candidate.manifest().version().split("\\.");
        for (int i = 0; i < 3; i++) {
            int order = new BigInteger(b[i]).compareTo(new BigInteger(a[i]));
            if (order != 0) return order > 0;
        }
        return false;
    }
}
