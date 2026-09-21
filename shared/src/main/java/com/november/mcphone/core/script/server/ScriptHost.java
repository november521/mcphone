package com.november.mcphone.core.script.server;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.engine.AppScope;
import com.november.mcphone.core.script.engine.CtxBuilder;
import com.november.mcphone.core.script.engine.HostError;
import com.november.mcphone.core.script.engine.RhinoEvaluator;
import com.november.mcphone.core.script.engine.SharedState;
import com.november.mcphone.core.script.engine.StrikeTracker;
import com.november.mcphone.core.script.net.ScriptErrorCode;
import com.november.mcphone.core.script.net.ScriptRpcHandler;
import com.november.mcphone.core.script.pkg.AppPackage;
import com.november.mcphone.core.script.server.economy.Scores;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
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
    private final UUID serverId;
    private final DeploymentData deployments;
    /** 生产授权视图。握手要用它把"这个玩家被授权的动作"筛出来（只给 UX）。 */
    private final ServerAuthority authority;
    /**
     * 能力与边界配置 + 判定（S18）。<b>policy 的配置是 volatile 快照</b>：
     * {@code capabilities reload} 在主线程换一份，之后 worker 上的能力判定读到的就是新值
     * （"切换后已装 App 的行为随之改变"）。部署/epoch/scope 都不动。
     */
    private final CapabilityPolicy capabilityPolicy;

    private ScriptHost(Map<String, AppScope> apps, StrikeTracker strikes,
                       RhinoEvaluator evaluator, ScriptPipeline pipeline,
                       UUID serverId, DeploymentData deployments, ServerAuthority authority,
                       CapabilityPolicy capabilityPolicy) {
        this.apps = apps;
        this.strikes = strikes;
        this.evaluator = evaluator;
        this.pipeline = pipeline;
        this.serverId = serverId;
        this.deployments = deployments;
        this.authority = authority;
        this.capabilityPolicy = capabilityPolicy;
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
        // 生产装配：每个已部署 App 一个 AppScope（server.js + 模块），跨调用复用、停服 discard。
        // 【可变表】：重装配要按 appId 换项，而 RhinoEvaluator 持有的是这个引用（读的时候看得到新 scope）
        Map<String, AppScope> apps = new LinkedHashMap<>(ServerAppAssembler.assemble(deployments, scan.packages()));

        // 必须在主线程建：StrikeTracker 的 owner 就是构造它的这条线程
        StrikeTracker strikes = new StrikeTracker(System::currentTimeMillis);
        // 能力与边界配置：独立文件、坏配置不崩服（S18）。它只在这里读一次 + 命令 reload。
        CapabilityConfig capabilities = CapabilityConfig.load(server);
        CapabilityPolicy capabilityPolicy = new CapabilityPolicy(capabilities);
        // 没有后端的项整项不挂（E12）：item / cycle / store / sealed / currencies（本步）全是 null；
        // actionIntents=true 表示挂 ctx.give（落地端 ServerIntentApplier 已接）；
        // ctx.predicate（§18.3）接平台门面：只读判定，玩家按 uuid 现查；
        // ctx.score（§18.6）借经济的网关回主线程；网关不在（没装经济/停服中）就不挂。
        com.november.mcphone.core.script.server.economy.CurrencyGateway scoreGateway =
                com.november.mcphone.core.script.server.economy.EconomyRuntime.gatewayOrNull();
        CtxBuilder.ScoreView scoreView = scoreGateway == null ? null : new CtxBuilder.ScoreView() {
            @Override
            public int get(java.util.UUID player, String objective) {
                return scoreCall(scoreGateway, () -> Scores.get(server, objective, scoreHolder(server, player)));
            }

            @Override
            public void set(java.util.UUID player, String objective, int value) {
                scoreCall(scoreGateway, () -> {
                    scoreWritable(server, objective);
                    Scores.set(server, objective, scoreHolder(server, player), value);
                    return null;
                });
            }

            @Override
            public void add(java.util.UUID player, String objective, int value) {
                scoreCall(scoreGateway, () -> {
                    scoreWritable(server, objective);
                    String holder = scoreHolder(server, player);
                    Scores.set(server, objective, holder, Scores.get(server, objective, holder) + value);
                    return null;
                });
            }
        };
        CtxBuilder.Backends backends = new CtxBuilder.Backends(new SharedState(), null, null, null, null, null, true,
                (predicateId, snapshot) -> {
                    ServerPlayer player = server.getPlayerList().getPlayer(snapshot.uuid());
                    if (player == null) return null;
                    net.minecraft.resources.ResourceLocation id =
                            net.minecraft.resources.ResourceLocation.tryParse(predicateId);
                    if (id == null) return null;
                    return com.november.mcphone.platform.Predicates.test(player, id);
                },
                scoreView);
        RhinoEvaluator evaluator = new RhinoEvaluator(apps, strikes, backends, server::execute, capabilityPolicy);
        UUID serverId = ServerIdentity.idOf(server);
        ServerAuthority authorityView = new ServerAuthority(deployments, authority);
        ScriptPipeline pipeline = new ScriptPipeline(serverId,
                new IdempotencyLedger(System::currentTimeMillis),
                new ScriptRateLimiter(System::currentTimeMillis),
                new ServerDeployments(deployments), authorityView, evaluator,
                new ServerIntentApplier(uuid -> server.getPlayerList().getPlayer(uuid)),
                capabilityPolicy);
        // 登记之后 ScriptRpcHandler.handle 才会把请求交给这条管线（此前一律 NOT_DEPLOYED）
        ScriptRpcHandler.install(pipeline);
        current = new ScriptHost(apps, strikes, evaluator, pipeline, serverId, deployments, authorityView, capabilityPolicy);
        MCphone.LOGGER.info("[MCphone] 脚本宿主已装配：apps={}，已批准部署 {}，候选 {}{}，管线已登记",
                apps.size(), deployments.deployments().size(), deployments.candidates().size(),
                scan.changed() == 0 ? "" : "（本次进队 " + scan.changed() + "）");
        MCphone.LOGGER.info("[MCphone] 能力配置：预设 {}，全服关闭 {} 项{}",
                capabilities.preset(), capabilities.disabled().size(),
                capabilities.disabled().isEmpty() ? "" : "（" + String.join("、", capabilities.disabled()) + "）");
    }

    /** 计分板用不了的本地化键（主线程忙、只读 objective、查不到玩家名都走它）。 */
    static final String SCORE_UNAVAILABLE = "mcphone.script.score.unavailable";

    /**
     * 经网关回主线程执行计分板操作。<b>只在这里碰 {@link Scores}</b>。
     * 网关自己的拒绝（正在停/排队满/主线程忙/等超了）与主线程上的任何 RuntimeException
     * 都换成脚本接得住的 {@code UNAVAILABLE} —— 它是"此刻做不了"，不是"脚本写错了"。
     */
    private static <T> T scoreCall(com.november.mcphone.core.script.server.economy.CurrencyGateway gateway,
                                   java.util.function.Supplier<T> op) {
        try {
            return gateway.call(op);
        } catch (HostError e) {
            throw e;
        } catch (RuntimeException e) {
            throw HostError.denied(ScriptErrorCode.UNAVAILABLE, SCORE_UNAVAILABLE,
                    "计分板调用没做成：" + e.getClass().getSimpleName());
        }
    }

    /** 建不出来（只读 objective 占了名字/创建失败）就是"用不了"，不是 0 分。 */
    private static void scoreWritable(MinecraftServer server, String objective) {
        if (!Scores.ensureObjective(server, objective, objective)) {
            throw HostError.denied(ScriptErrorCode.UNAVAILABLE, SCORE_UNAVAILABLE,
                    "计分项建不出来（名字被只读 objective 占了？）：" + objective);
        }
    }

    /** 计分板按玩家名存（服主用 /scoreboard 就能看能改）；查不到名字就是"用不了"。 */
    private static String scoreHolder(MinecraftServer server, java.util.UUID player) {
        String holder = Scores.nameOf(server, player);
        if (holder == null) {
            throw HostError.denied(ScriptErrorCode.UNAVAILABLE, SCORE_UNAVAILABLE, "查不到玩家名");
        }
        return holder;
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
     */
    public boolean hasApp(String appId) {
        return apps.containsKey(appId);
    }

    /**
     * 能力与边界配置（S18）。<b>运行期只读</b>；{@code capabilities reload} 或重开服时整份替换。
     */
    public CapabilityConfig capabilities() {
        return capabilityPolicy.config();
    }

    /** 能力判定（S18）。worker 上可直接调：配置是 volatile 快照、判定无副作用。 */
    public CapabilityPolicy capabilityPolicy() {
        return capabilityPolicy;
    }

    /**
     * 重新读一遍能力配置（{@code /mcphone script capabilities reload}）。
     *
     * <p>只换配置快照，<b>不重建 App scope、不动 epoch、不动部署表</b>：服务端脚本与部署是两回事。
     * 换完之后 worker 上的能力判定读到的就是新值（"切换预设后已装 App 的行为随之改变"，§31.4）。
     *
     * @return 没开服（脚本后端降级/未装）时 false
     */
    public static synchronized boolean reloadCapabilities(MinecraftServer server) {
        ScriptHost h = current;
        if (h == null) return false;
        CapabilityConfig fresh = CapabilityConfig.load(server);
        h.capabilityPolicy.reload(fresh);
        MCphone.LOGGER.info("[MCphone] 能力配置已重载：预设 {}，全服关闭 {} 项{}",
                fresh.preset(), fresh.disabled().size(),
                fresh.disabled().isEmpty() ? "" : "（" + String.join("、", fresh.disabled()) + "）");
        return true;
    }

    /**
     * 批准/撤部署之后在<b>主线程</b>上重装配受影响的 App（S17 Stage 2 约束 1）。
     *
     * <p>做法：按当前部署表拿到新摘要 → 扫 incoming 找那个包 → {@link ServerAppAssembler#assembleOne}
     * 建出<b>完整的新 scope</b>（含预检）→ 成功才替换 apps 表项并 {@code discard()} 旧 scope。
     * <b>在飞的求值持有旧引用、不打断</b>；新请求走新 scope。全程主线程，不需要额外锁。
     *
     * <p>失败：<b>不替换</b>，并把该 App 从 apps 里摘掉 —— 请求回 {@code NOT_DEPLOYED}，
     * 绝不出现"判定说已批准、执行却是空/旧包"的中间态；返回 false 让命令面报错。
     *
     * @return 成功（撤部署也算成功：表里没有就摘掉 scope）
     */
    public static synchronized boolean reassemble(MinecraftServer server, String appId) {
        ScriptHost h = current();
        if (h == null) return false;      // 脚本后端降级/未装：命令面照旧写表，重开服生效
        Deployment d = h.deployments.deployment(appId);
        if (d == null) {
            h.removeApp(appId);           // 撤部署：旧 scope 消失
            return true;
        }
        ServerPackageScanner.Scan scan = ServerPackageScanner.scan(server, h.deployments);
        AppPackage pkg = scan.packages().get(d.packageDigest());
        if (pkg == null) {
            MCphone.LOGGER.error("[MCphone] {} 已批准，但包（{}…）不在 incoming，重装配失败：该 App 现在不可执行（NOT_DEPLOYED）。"
                    + "把包装回待审目录后重试，或重启", appId, short8(d.packageDigest()));
            h.removeApp(appId);
            return false;
        }
        AppScope fresh = ServerAppAssembler.assembleOne(d, pkg);
        if (fresh == null) {
            MCphone.LOGGER.error("[MCphone] {} 的重装配失败：该 App 现在不可执行（NOT_DEPLOYED），修好后重新 approve 或重启", appId);
            h.removeApp(appId);
            return false;
        }
        AppScope old = h.apps.put(appId, fresh);
        if (old != null) old.discard();   // 在飞的求值持有旧引用，discard 只是把 AppScope 的缓存置空
        MCphone.LOGGER.info("[MCphone] {} 已重装配（批准轴 {}，包 {}…）", appId, d.approvalRevision(), short8(d.packageDigest()));
        return true;
    }

    private void removeApp(String appId) {
        AppScope old = apps.remove(appId);
        if (old != null) old.discard();
    }

    private static String short8(String digest) {
        return digest.substring(0, Math.min(8, digest.length()));
    }

    /** 这一局的服务器身份（§13.5），握手把它下发给客户端。 */
    public UUID serverId() {
        return serverId;
    }

    /** 已批准部署表。读的人必须守它的线程纪律（主线程装配/命令期写、运行期只读）。 */
    public DeploymentData deployments() {
        return deployments;
    }

    /** 握手筛"这个玩家被授权的动作"用（只给 UX）；判定链本身在管线里各自再查。 */
    public boolean allows(UUID player, String appId, String actionId) {
        return authority.allows(player, appId, actionId);
    }

    /**
     * 玩家登录：给这一次连接一个新 epoch（§15.9）。管线没装（装配失败降级）时返回 0、安全无操作。
     *
     * <p>与 {@link #forget(UUID)} <b>必须成对</b>：只建不忘 ⇒ epochs 表按玩家无界增长；
     * 只忘不建 ⇒ 所有请求判成过期连接。两个调用点要写在同一个登录/登出接线处。
     */
    public static long newEpoch(ServerPlayer player) {
        ScriptHost h = current();
        return h == null ? 0L : h.pipeline.newEpoch(player.getUUID());
    }

    /** 玩家登出：只忘 epoch，不清账本（账本保留 24 小时，跨重连命中正是它存在的理由）。 */
    public static void forget(UUID player) {
        ScriptHost h = current();
        if (h != null) h.pipeline.forget(player);
    }
}
