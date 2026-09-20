package com.november.mcphone.core.script.server;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.engine.AppScope;
import com.november.mcphone.core.script.engine.HostFn;
import com.november.mcphone.core.script.engine.ScriptBudget;
import com.november.mcphone.core.script.pkg.AppPackage;
import org.mozilla.javascript.Context;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * S17：把"已批准的部署 + 它们对应的包"装配成 {@code appId → AppScope}（施工方案 §16.3：<b>一个 App 一个 scope</b>）。
 *
 * <p>入参 {@code packages} 来自同一趟 {@link ServerPackageScanner#scan} 的解包结果 —— 不在开服时再解一遍 zip。
 * 已批准但包不在 incoming 的部署<b>不装配并告警</b>：请求会落在 {@code RhinoEvaluator.submit} 的
 * {@code app == null} 支（回 {@code NOT_DEPLOYED}），<b>不会执行任何旧代码</b>。
 *
 * <h2>哪些内容进 scope（M2/C7）</h2>
 *
 * 只取<b>后端模块</b>：{@code server.js} 与 {@code server/**}（{@link #isBackendModule}）。
 * 前端 {@code .js}（{@code ui/**}、{@code app.js} 之类）<b>不进</b>服务端模块表 —— 否则它们会吃掉
 * {@code ScriptModules} 的 16 个模块额度（一个前端 js 多的包会让后端整个装不起来），也会被
 * {@code server.js} {@code require} 进来在服务端求值。前端摘要的谓词（"除后端之外的全部"）与之对称。
 *
 * <h2>装配期预检（C5/C6）</h2>
 *
 * 每个 App 在装配期就把入口求值跑一遍（<b>在预算内</b>，与请求路径同一套 {@link ScriptBudget}）。
 * 失败就整个跳过并告警：请求路径不再"每次请求都重跑一遍坏入口"（那会按请求速率占住 worker），
 * 生产里后续请求拿到的也已经是建好的 scope —— 停服 {@code discard()} 不会再被首次求值挡在锁上。
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
                if (!isBackendModule(path)) continue;
                sources.put(path, new String(pkg.entry(path), StandardCharsets.UTF_8));
            }
            if (!sources.containsKey(AppScope.ENTRY)) {
                MCphone.LOGGER.warn("[MCphone] {} 的包里没有 {}，本次不装配后端", d.appId(), AppScope.ENTRY);
                continue;
            }
            try {
                // 模块条数与规范名的校验在 AppScope/ScriptModules 构造器里；超限就跳过这个 App
                AppScope app = new AppScope(d.appId(), ScriptBudget.server(), sources);
                if (preflight(app)) apps.put(d.appId(), app);
            } catch (Throwable t) {
                MCphone.LOGGER.warn("[MCphone] {} 的模块表装不起来（{}），跳过这个 App", d.appId(), t.toString());
            }
        }
        if (!apps.isEmpty()) {
            MCphone.LOGGER.info("[MCphone] 已装配 {} 个 App 后端：{}", apps.size(), String.join("、", apps.keySet()));
        }
        return apps;
    }

    /** 后端模块的谓词：{@code server.js} 与 {@code server/**}（前端 .js 不进服务端模块表）。 */
    static boolean isBackendModule(String path) {
        return path != null
                && (path.equals(ServerPackageScanner.SERVER_ENTRY) || path.startsWith(ServerPackageScanner.SERVER_DIR));
    }

    /** 装配期把入口跑一遍（预算内）；失败返回 false，调用方整个跳过这个 App。 */
    private static boolean preflight(AppScope app) {
        Context cx = app.budget().enterContext();
        boolean began = false;
        try {
            app.budget().begin();
            began = true;
            HostFn.resetDepth();
            app.scope(cx);          // 加固 → 装 require → 求值入口定义 actions
            return true;
        } catch (Throwable t) {
            MCphone.LOGGER.warn("[MCphone] {} 的入口预检没过（{}），本次不装配这个 App", app.appId(), t.toString());
            return false;
        } finally {
            if (began) {
                try {
                    app.budget().end();
                } catch (Throwable ignored) {
                    // 预算收尾失败只影响预检这一次，下面照常退出 Context
                }
            }
            try {
                Context.exit();
            } catch (Throwable ignored) {
                // 同上
            }
            try {
                HostFn.resetDepth();
            } catch (Throwable ignored) {
                // 同上
            }
        }
    }

    private static String short8(String digest) {
        return digest.substring(0, Math.min(8, digest.length()));
    }
}
