package com.november.mcphone.core.script.server;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.engine.AppScope;
import com.november.mcphone.core.script.engine.ScriptBudget;
import com.november.mcphone.core.script.engine.ScriptStaticCheck;
import com.november.mcphone.core.script.pkg.AppPackage;

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
 * <h2>装配期预检 = 静态核对，不执行代码（S18 约束 1，C8.2）</h2>
 *
 * 预检只做<b>零副作用</b>的事：语法编译 + {@code require} 字面量的模块解析 + 依赖环检测
 * （{@link ScriptStaticCheck}）。<b>不建 {@code ctx}、不挂任何后端、不执行 App 代码</b>，
 * 因此不可能碰世界、动钱、写存储。顶层就抛错的包在这里<b>照样装配成功</b>，它会在第一次请求时
 * 按正常运行期错误处理（INTERNAL/STRIKE）；坏渲染的入口不再让整个 App 在装配期消失。
 * 失败（语法错、模块缺失、动态 require、有环）就整个跳过并告警 —— 请求路径不重跑坏入口。
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
            AppScope app = assembleOne(d, pkg);
            if (app != null) apps.put(d.appId(), app);
        }
        if (!apps.isEmpty()) {
            MCphone.LOGGER.info("[MCphone] 已装配 {} 个 App 后端：{}", apps.size(), String.join("、", apps.keySet()));
        }
        return apps;
    }

    /**
     * 装配<b>单个</b> App 的后端（含静态预检）。失败返回 {@code null}（已记日志），调用方据此
     * 决定"跳过"还是"回滚 + 标记不可执行"。重装配路径复用它，保证两条路装出来的是同一个东西。
     */
    static AppScope assembleOne(Deployment d, AppPackage pkg) {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String path : pkg.paths()) {
            if (!isBackendModule(path)) continue;
            sources.put(path, new String(pkg.entry(path), StandardCharsets.UTF_8));
        }
        if (!sources.containsKey(AppScope.ENTRY)) {
            MCphone.LOGGER.warn("[MCphone] {} 的包里没有 {}，本次不装配后端", d.appId(), AppScope.ENTRY);
            return null;
        }
        try {
            // 先静态核对：语法 + require 字面量解析 + 环。零副作用，不执行任何一行脚本。
            String why = ScriptStaticCheck.check(sources);
            if (why != null) {
                MCphone.LOGGER.warn("[MCphone] {} 的后端静态预检没过，本次不装配：{}", d.appId(), why);
                return null;
            }
            // 模块条数与规范名的校验在 AppScope/ScriptModules 构造器里；超限就跳过这个 App。
            // 已批准能力集在装配期冻结进 scope：worker 不许读部署表（S17 线程纪律），重批准走 reassemble。
            return new AppScope(d.appId(), ScriptBudget.server(), sources, new java.util.LinkedHashSet<>(d.approvedCapabilities()));
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            MCphone.LOGGER.warn("[MCphone] {} 的模块表装不起来（{}），跳过这个 App", d.appId(), t.toString());
            return null;
        }
    }

    /** 后端模块的谓词：{@code server.js} 与 {@code server/**} 里的 {@code .js}（前端 js 不进服务端模块表）。 */
    static boolean isBackendModule(String path) {
        return path != null && path.endsWith(".js")
                && (path.equals(ServerPackageScanner.SERVER_ENTRY) || path.startsWith(ServerPackageScanner.SERVER_DIR));
    }

    private static String short8(String digest) {
        return digest.substring(0, Math.min(8, digest.length()));
    }
}
