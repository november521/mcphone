package com.november.mcphone.core.script.server;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.engine.AppScope;
import com.november.mcphone.core.script.engine.ScriptBudget;
import com.november.mcphone.core.script.pkg.AppPackage;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S17：把"已批准的部署 + 它们对应的包"装配成 {@code appId → AppScope}（施工方案 §16.3：<b>一个 App 一个 scope</b>）。
 *
 * <p>入参 {@code packages} 来自同一趟 {@link ServerPackageScanner#scan} 的解包结果 —— 不在开服时再解一遍 zip。
 * 已批准但包不在 incoming 的部署<b>不装配并告警</b>：它的动作会停在部署判定（有部署表、无 scope 的 App
 * 在 {@code RhinoEvaluator} 里被当成 {@code NOT_DEPLOYED}），不会静默执行旧代码。
 *
 * <h2>哪些内容进 scope</h2>
 *
 * 只取包内 {@code *.js} —— {@code server.js} 与它的模块，规范名就是包内路径（{@link AppScope#ENTRY} 是入口）。
 * 前端（{@code app.vue}、语言、图标）不在这里，它们不下发也不执行。{@code META/} 本来就不在
 * {@link AppPackage#entries()} 里。
 */
public final class ServerAppAssembler {

    private ServerAppAssembler() {
    }

    public static Map<String, AppScope> assemble(DeploymentData deployments, Map<String, AppPackage> packages) {
        Map<String, AppScope> apps = new LinkedHashMap<>();
        for (Deployment d : deployments.deployments()) {
            AppPackage pkg = packages.get(d.packageDigest());
            if (pkg == null) {
                MCphone.LOGGER.warn("[MCphone] {} 已批准，但它的包（{}…）不在 incoming，本次不装配后端",
                        d.appId(), short8(d.packageDigest()));
                continue;
            }
            Map<String, String> sources = new LinkedHashMap<>();
            for (String path : pkg.paths()) {
                if (!path.endsWith(".js")) continue;
                sources.put(path, new String(pkg.entry(path), StandardCharsets.UTF_8));
            }
            if (!sources.containsKey(AppScope.ENTRY)) {
                MCphone.LOGGER.warn("[MCphone] {} 的包里没有 {}，本次不装配后端", d.appId(), AppScope.ENTRY);
                continue;
            }
            try {
                // 模块条数与规范名的校验在 AppScope/ScriptModules 构造器里；超限就跳过这个 App
                apps.put(d.appId(), new AppScope(d.appId(), ScriptBudget.server(), sources));
            } catch (Throwable t) {
                MCphone.LOGGER.warn("[MCphone] {} 的模块表装不起来（{}），跳过这个 App", d.appId(), t.toString());
            }
        }
        if (!apps.isEmpty()) {
            MCphone.LOGGER.info("[MCphone] 已装配 {} 个 App 后端：{}", apps.size(), String.join("、", apps.keySet()));
        }
        return apps;
    }

    private static String short8(String digest) {
        return digest.substring(0, Math.min(8, digest.length()));
    }
}
