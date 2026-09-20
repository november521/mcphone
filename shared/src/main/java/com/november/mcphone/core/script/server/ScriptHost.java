package com.november.mcphone.core.script.server;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.engine.AppScope;
import com.november.mcphone.core.script.engine.CtxBuilder;
import com.november.mcphone.core.script.engine.RhinoEvaluator;
import com.november.mcphone.core.script.engine.SharedState;
import com.november.mcphone.core.script.engine.StrikeTracker;
import com.november.mcphone.core.script.net.ScriptRpcHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
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
     * @param apps appId → 那个 App 的 scope。本步为空（装配属 S17）；有值时必须一个 App 一个 scope、跨调用复用
     */
    public static synchronized void start(MinecraftServer server, Map<String, AppScope> apps) {
        stop();
        // 必须在主线程建：StrikeTracker 的 owner 就是构造它的这条线程
        StrikeTracker strikes = new StrikeTracker(System::currentTimeMillis);
        // 没有后端的项整项不挂（E12）：item / cycle / store / sealed / currencies（本步）全是 null
        CtxBuilder.Backends backends = new CtxBuilder.Backends(new SharedState(), null, null, null, null, null);
        RhinoEvaluator evaluator = new RhinoEvaluator(apps, strikes, backends, server::execute);
        ScriptPipeline pipeline = new ScriptPipeline(serverIdOf(server),
                new IdempotencyLedger(System::currentTimeMillis),
                new ScriptRateLimiter(System::currentTimeMillis),
                new DenyAllDeployments(), new DenyAllAuthority(), evaluator);
        // 登记之后 ScriptRpcHandler.handle 才会把请求交给这条管线（此前一律 NOT_DEPLOYED）
        ScriptRpcHandler.install(pipeline);
        current = new ScriptHost(apps, strikes, evaluator, pipeline);
        MCphone.LOGGER.info("[MCphone] 脚本宿主已装配：apps={}，管线已登记（部署表/授权表为空，请求一律 NOT_DEPLOYED，等 S17）",
                apps.size());
    }

    /** 开服时在主线程上调，没有 App scope 的简写。 */
    public static synchronized void start(MinecraftServer server) {
        start(server, Map.of());
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
     * 服务器身份：按世界根路径取一个确定性的 UUID。幂等键把 serverId 算进去，所以同一份世界
     * 重开时键一致（账本本身是内存态，重启即清，见 {@link IdempotencyLedger}）。
     */
    static UUID serverIdOf(MinecraftServer server) {
        String path = server.getWorldPath(LevelResource.ROOT).toString();
        return UUID.nameUUIDFromBytes(path.getBytes(StandardCharsets.UTF_8));
    }
}
