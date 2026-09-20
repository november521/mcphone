package com.november.mcphone.core.script.server;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.engine.AppScope;
import com.november.mcphone.core.script.engine.CtxBuilder;
import com.november.mcphone.core.script.engine.RhinoEvaluator;
import com.november.mcphone.core.script.engine.SharedState;
import com.november.mcphone.core.script.engine.StrikeTracker;
import com.november.mcphone.core.script.net.ScriptRpcHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;

/**
 * 脚本宿主的唯一装配点（S15g，施工方案 §15.5）。把"各自做完、但从没接在一起"的四段接成一条路：
 * {@code ScriptRpcHandler.handle → ScriptPipeline → RhinoEvaluator → CtxBuilder.build}。
 *
 * <h2>生命周期：与 {@link ScriptWorkers} 同轴</h2>
 *
 * 开服建、停服清。平台侧只做接线（{@code ScriptWorkers.start(); ScriptHost.start(server);} /
 * {@code ScriptHost.stop(); ScriptWorkers.stop();}），顺序见平台里的注释：
 * <b>先关货币网关 → 再停脚本这条路 → 最后停 worker</b>。
 * 单人游戏里同一个 JVM 会连续开关世界，所以 {@link #stop()} 必须把管线、scope 全丢掉，
 * 不许留任何跨世界的静态引用。
 *
 * <h2>为什么 {@link StrikeTracker} 必须在这里建</h2>
 *
 * 它记的是<b>构造它的那条线程</b>，而它的四张表只许服务端主线程碰（E36）。本方法由服务器
 * {@code ServerStartedEvent} 触发，正在主线程上，所以在这里 {@code new} 是正确的唯一时点。
 *
 * <h2>本步的注册表口径（P4，E37）</h2>
 *
 * <b>{@code CurrencyRegistry} 保持普通 {@code LinkedHashMap}，不加锁、不换并发容器。</b>
 * 写（{@code register}/{@code clear}）只许发生在 {@code EconomyRuntime.install/stop} 内；
 * 读只许来自主线程（启动扫描属合规路径）。本步 {@code Backends.currencies} 传 {@code null}
 * —— 生产里还没有 App 求值（装配属 S17），{@code ctx.currency} 没有消费者；
 * 因此本步<b>不新增任何 worker 侧的注册表读取</b>。S17 接 {@code ctx.currency} 时，
 * 必须在装配期一次性取出冻结引用放进 {@code Backends}，或改为并发结构 —— 见 {@code EconomyRuntime.registry()}。
 *
 * <h2>本步不做的</h2>
 *
 * <b>不做部署表 / 授权表 / 审批链 / 能力勾选</b>（S17）：两个视图是 {@link DenyAllDeployments} /
 * {@link DenyAllAuthority}，只做"空表即拒"，所以生产里请求仍一律 {@code NOT_DEPLOYED}。
 * <b>也不做 AppScope 的生产装配</b>：那需要"已部署的 server 包"这个来源，正是 S17 的本体；
 * 本步的 {@code apps} 表为空，端到端只由 {@code docs/} 的断言用真 {@link RhinoEvaluator} 跑通。
 */
public final class ScriptHost {

    /** 只在类锁内读写：{@link #start} / {@link #stop} / {@link #current}。 */
    private static ScriptHost current;

    private final Map<String, AppScope> apps;
    private final StrikeTracker strikes;
    private final RhinoEvaluator evaluator;
    private final ScriptPipeline pipeline;

    private ScriptHost(Map<String, AppScope> apps, StrikeTracker strikes,
                       RhinoEvaluator evaluator, ScriptPipeline pipeline) {
        this.apps = apps;
        this.strikes = strikes;
        this.evaluator = evaluator;
        this.pipeline = pipeline;
    }

    /**
     * 开服时在主线程上调。<b>重复调会先把上一份停掉</b>（单人游戏换世界）。
     *
     * <p><b>装配失败不许把服务器弄崩</b>（S17 约束 5）：任何非虚拟机级异常都在这里被接住 ——
     * 不装管线、脚本后端降级为"一律 {@code NOT_DEPLOYED}"，并留下一条可观测 ERROR。
     * 平台差异随之无关紧要：Fabric 的事件回调不 catch 也不会因此开服失败。
     */
    public static synchronized void start(MinecraftServer server) {
        stop();
        try {
            startBackend(server);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable t) {
            ScriptRpcHandler.clear();
            current = null;
            MCphone.LOGGER.error("[MCphone] ⚠ 脚本后端装配失败，已降级为「未启用」：所有脚本请求会回 NOT_DEPLOYED。"
                    + "修好之后重开服务器（本步不做热重载）", t);
        }
    }

    /** 真正装配；异常向上抛给 {@link #start} 统一降级。 */
    private static void startBackend(MinecraftServer server) {
        // S17：三张世界级表随服务器装配（身份 / 部署 / 授权）；扫一趟 incoming。
        // 【扫描不给任何特权】：候选要 OP 用命令逐条批准后才成为 Deployment（§14.4）。
        DeploymentData deployments = DeploymentData.get(server);
        AuthorityData authority = AuthorityData.get(server);
        ServerPackageScanner.Scan scan = ServerPackageScanner.scan(server, deployments);
        // 生产装配：每个已部署 App 一个 AppScope（server.js + 模块），跨调用复用、停服 discard
        Map<String, AppScope> apps = ServerAppAssembler.assemble(deployments, scan.packages());

        // 必须在主线程建：StrikeTracker 的 owner 就是构造它的这条线程
        StrikeTracker strikes = new StrikeTracker(System::currentTimeMillis);
        // 没有后端的项整项不挂（E12）：item / cycle / store / sealed / currencies（本步）全是 null
        CtxBuilder.Backends backends = new CtxBuilder.Backends(new SharedState(), null, null, null, null, null);
        RhinoEvaluator evaluator = new RhinoEvaluator(apps, strikes, backends, server::execute);
        ScriptPipeline pipeline = new ScriptPipeline(ServerIdentity.idOf(server),
                new IdempotencyLedger(System::currentTimeMillis),
                new ScriptRateLimiter(System::currentTimeMillis),
                new ServerDeployments(deployments), new ServerAuthority(deployments, authority), evaluator);
        // 登记之后 ScriptRpcHandler.handle 才会把请求交给这条管线（此前一律 NOT_DEPLOYED）
        ScriptRpcHandler.install(pipeline);
        current = new ScriptHost(apps, strikes, evaluator, pipeline);
        MCphone.LOGGER.info("[MCphone] 脚本宿主已装配：apps={}，已批准部署 {}，候选 {}{}，管线已登记",
                apps.size(), deployments.deployments().size(), deployments.candidates().size(),
                scan.changed() == 0 ? "" : "（本次进队 " + scan.changed() + "）");
    }

    /**
     * 停服时在主线程上调，<b>在 {@code ScriptWorkers.stop()} 之前</b>（这样在飞的求值还有机会落地）。
     * 重复调安全。
     */
    public static synchronized void stop() {
        ScriptHost h = current;
        current = null;
        if (h != null) {
            // 先摘掉登记点：新请求立刻回 NOT_DEPLOYED，不再往管线上添新活
            ScriptRpcHandler.clear();
            for (AppScope app : h.apps.values()) app.discard();
            MCphone.LOGGER.info("[MCphone] 脚本宿主已停：管线已摘、{} 个 App scope 已丢弃", h.apps.size());
        } else {
            // 没装过也要保证登记点是干净的（比如启动中途失败）
            ScriptRpcHandler.clear();
        }
    }

    /** 当前这一份；没开服、或已停就是 null。 */
    public static synchronized ScriptHost current() {
        return current;
    }

    /** 这一次装配用的管线。只给日志/断言用；生产调用点是 {@code ScriptRpcHandler}。 */
    public ScriptPipeline pipeline() {
        return pipeline;
    }

    public StrikeTracker strikes() {
        return strikes;
    }

    /**
     * 这个 App 现在有没有装配好的后端（也就是它此刻跑的是<b>哪一份包</b>）。
     *
     * <p>命令面用它拒绝"换包不重启"：`approve` 换同一个 appId 的新包时，判定读的是实时部署表
     * （deployed/hasAction/deployRev 全来自新包），而执行用的 {@code apps} 还是开服时装配的旧包 ——
     * 客户端按新包发、服务端跑旧包，两端都不会说话（定向对抗第 5 条）。本步不做热重载，所以只能拒绝并要重启。
     */
    public boolean hasApp(String appId) {
        return apps.containsKey(appId);
    }

    /**
     * 玩家登录：给这一次连接一个新 epoch（§15.9）。管线没装（装配失败降级）时安全无操作。
     *
     * <p>与 {@link #forget(UUID)} <b>必须成对</b>：只建不忘 ⇒ epochs 表按玩家无界增长；
     * 只忘不建 ⇒ 所有请求判成过期连接。两个调用点要写在同一个登录/登出接线处。
     */
    public static void newEpoch(ServerPlayer player) {
        ScriptHost h = current();
        if (h != null) h.pipeline.newEpoch(player.getUUID());
    }

    /** 玩家登出：只忘 epoch，不清账本（账本保留 24 小时，跨重连命中正是它存在的理由）。 */
    public static void forget(UUID player) {
        ScriptHost h = current();
        if (h != null) h.pipeline.forget(player);
    }
}
