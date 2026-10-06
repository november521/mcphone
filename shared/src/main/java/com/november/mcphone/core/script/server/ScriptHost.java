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
import java.util.Set;

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
    private ServerStore store;
    private ServerScriptItems itemHandles;
    private ServerNotifications notifications;
    private ServerAuthorRevocations authorRevocations;
    private AuditExportControls auditExports;
    private final Map<String,java.util.Set<String>> backgroundActions=new LinkedHashMap<>();
    private QuotaManager quotas;private StorageBudget storageBudget;
    private ResourceRegistry resources;
    private final Map<String, Map<String, ActionGuards>> actionGuards;
    private GuardController guards;
    private GuardSubscriptions subscriptions;
    private NativeAdminControls administration;
    private IdempotencyLedger ledger;
    private MinecraftServer server;
    private ServerMailbox mailbox;
    private ServerItemEscrow escrow;
    private com.november.mcphone.core.script.server.economy.ScriptCurrencyEscrows currencyEscrows;
    private ServerGifts gifts;
    private FetchCache fetch;
    private CommandRunner commands;
    private long mailboxSweep;
    private java.util.concurrent.atomic.AtomicReference<ScriptRuntimeConfig> runtimeConfig;
    private final Map<String, Long> packageVersions = new LinkedHashMap<>();
    private final Map<String,byte[]> packageKeys=new LinkedHashMap<>();
    private VersionWitness witnesses;
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
                       CapabilityPolicy capabilityPolicy, Map<String, Map<String, ActionGuards>> actionGuards) {
        this.apps = apps;
        this.strikes = strikes;
        this.evaluator = evaluator;
        this.pipeline = pipeline;
        this.serverId = serverId;
        this.deployments = deployments;
        this.authority = authority;
        this.capabilityPolicy = capabilityPolicy;
        this.actionGuards = actionGuards;
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
    private static void startBackend(MinecraftServer server) throws java.io.IOException {
        AdminConfiguration.recover(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
        var runtime = new java.util.concurrent.atomic.AtomicReference<>(ScriptRuntimeConfig.load(server));
        // S17：三张世界级表随服务器装配（身份 / 部署 / 授权）；扫一趟 incoming。
        // 【扫描不给任何特权】：候选要 OP 用命令逐条批准后才成为 Deployment（§14.4）。
        DeploymentData deployments = DeploymentData.get(server);
        AuthorityData authority = AuthorityData.get(server);
        VersionWitness witnesses=new VersionWitness(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("mcphone/version-witness.json"));
        ServerPackageScanner.Scan scan = ServerPackageScanner.scan(server, deployments,witnesses);
        for(Deployment deployment:deployments.deployments()){
            AppPackage approved=scan.packages().get(deployment.packageDigest());
            if(approved!=null)witnesses.observe(approved,true);
        }
        // 生产装配：每个已部署 App 一个 AppScope（server.js + 模块），跨调用复用、停服 discard。
        // 【可变表】：重装配要按 appId 换项，而 RhinoEvaluator 持有的是这个引用（读的时候看得到新 scope）
        Map<String, AppScope> apps = new LinkedHashMap<>(runtime.get().serverScripts()?ServerAppAssembler.assemble(deployments, scan.packages()):Map.of());

        // 必须在主线程建：StrikeTracker 的 owner 就是构造它的这条线程
        StrikeTracker strikes = new StrikeTracker(System::currentTimeMillis);
        // 能力与边界配置：独立文件、坏配置不崩服（S18）。它只在这里读一次 + 命令 reload。
        CapabilityConfig capabilities = CapabilityConfig.load(server);
        CapabilityPolicy capabilityPolicy = new CapabilityPolicy(capabilities);
        CommandRunner commands = new CommandRunner(server);
        capabilityPolicy.commandTemplates(commands::enabled);
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
        SharedState sharedState = new SharedState();
        QuotaManager quotas=new QuotaManager(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT));
        StorageBudget storageBudget=new StorageBudget(quotas::current);
        var economy = com.november.mcphone.core.script.server.economy.EconomyRuntime.current();
        var currencies = economy == null || economy.registry() == null ? null : economy.registry().snapshot();
        var currencyEscrows=new com.november.mcphone.core.script.server.economy.ScriptCurrencyEscrows(
                server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("mcphone/economy/script-escrows.json"),System::currentTimeMillis);
        FetchCache fetch = new FetchCache(() -> runtime.get().net()).quotas(quotas::current);
        CtxBuilder.Backends backends = new CtxBuilder.Backends(sharedState, null,
                new CtxBuilder.Cycle(runtime.get().zone(), runtime.get().dailyAt()), null, null, currencies, true,
                (predicateId, snapshot) -> {
                    return scoreCall(scoreGateway, () -> {
                        ServerPlayer player = server.getPlayerList().getPlayer(snapshot.uuid());
                        if (player == null) return null;
                        net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(predicateId);
                        return id == null ? null : com.november.mcphone.platform.Predicates.test(player, id);
                    });
                },
                scoreView);
        RhinoEvaluator evaluator = new RhinoEvaluator(apps, strikes, backends, server::execute, capabilityPolicy);
        UUID serverId = ServerIdentity.idOf(server);
        var durableStore = new com.november.mcphone.core.script.server.store.DurablePlayerStore(
                server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("mcphone/kv").resolve(serverId.toString()),quotas::current,storageBudget);
        Map<String, java.util.ArrayDeque<Long>> storeWrites = new LinkedHashMap<>();
        ResourceRegistry resources=new ResourceRegistry(ResourceRegistry.load(server));
        ServerScriptItems itemHandles=new ServerScriptItems(server,scoreGateway);
        evaluator.releaseBackends(itemHandles::forget);
        evaluator.invocationBackends(request -> new CtxBuilder.Backends(new CtxBuilder.SharedView(){
                    public String get(String app,String key){return scoreCall(scoreGateway,()->{requireAccess(server,request,"storage.global.read");return sharedState.get(app,key);});}
                    public void set(String app,String key,String value){scoreCall(scoreGateway,()->{requireAccess(server,request,"storage.global.write");sharedState.set(app,key,value);return null;});}
                    public boolean compareAndSet(String app,String key,String expected,String next){return scoreCall(scoreGateway,()->{requireAccess(server,request,"storage.global.write");return sharedState.compareAndSet(app,key,expected,next);});}
                }, itemHandles.view(request),
                new CtxBuilder.Cycle(runtime.get().zone(), runtime.get().dailyAt()),
                new com.november.mcphone.core.script.server.store.PlayerKvBackend(server, scoreGateway,
                        request.player().uuid(), serverId, request.appId(), storeWrites,durableStore).quotas(quotas::current).authorization(()->requireAccess(server,request,"storage.self")), new com.november.mcphone.core.script.server.store.SealedBackend() {
                    private String namespace(String app) {
                        if(!app.equals(request.appId())) throw HostError.invalid("保险箱 App 不匹配");
                        return com.november.mcphone.core.script.server.store.ScriptKv.namespace(serverId.toString(),"app:"+app,app,1);
                    }
                    public void put(String app,String key,com.november.mcphone.core.script.server.store.SealedRecord record) {
                        throw HostError.denied(ScriptErrorCode.UNAVAILABLE,"mcphone.vault.locked","后端脚本不能封装玩家密文");
                    }
                    public com.november.mcphone.core.script.server.store.SealedRecord get(String app,String key) {
                        if(!key.matches("[A-Za-z0-9_.-]{1,64}")) throw HostError.invalid("保险箱键无效");
                        return scoreCall(scoreGateway,()->{requireAccess(server,request,"sealed.store");return durableStore.sealed(request.player().uuid(),namespace(app),key);});
                    }
                },
                currencies==null?null:currencies.forScript(scoreCall(scoreGateway,()->{
                    requireAccess(server,request,null);ScriptHost host=current();return new com.november.mcphone.core.script.server.economy.ScriptCurrencyEscrows.Scope(request.appId(),java.util.Base64.getEncoder().encodeToString(host.author(request.deployRev())),request.player().uuid(),request.deployRev(),host.version(request.deployRev()));
                }),currencyEscrows,operation->{
                    ScriptHost host=current();Deployment live=deployments.deployment(request.appId());
                    if(host==null||host.epoch(request.player().uuid())!=request.connectionEpoch()||server.getPlayerList().getPlayer(request.player().uuid())==null||live==null
                            ||!live.revision().equals(request.deployRev())||!host.allows(request.player().uuid(),request.appId(),request.actionId())
                            ||host.revokedRule(request.appId())!=null)return com.november.mcphone.api.economy.TxnResult.NOT_AUTHORIZED;
                    if(operation.equals("mint")||operation.equals("burn")){
                        CapabilityPolicy.Verdict verdict=capabilityPolicy.check("currency.mint",java.util.Set.copyOf(live.approvedCapabilities()));
                        if(verdict!=CapabilityPolicy.Verdict.OK)return verdict==CapabilityPolicy.Verdict.NOT_APPROVED
                                ?com.november.mcphone.api.economy.TxnResult.NOT_AUTHORIZED:com.november.mcphone.api.economy.TxnResult.UNAVAILABLE;
                    }return com.november.mcphone.api.economy.TxnResult.OK;
                }), true, (predicate,snapshot)->scoreCall(scoreGateway,()->{
                    ServerPlayer player=requireAccess(server,request,"predicate.test");var id=net.minecraft.resources.ResourceLocation.tryParse(predicate);
                    return id==null?null:com.november.mcphone.platform.Predicates.test(player,id);
                }),new CtxBuilder.ScoreView(){
                    public int get(UUID player,String objective){return scoreCall(scoreGateway,()->{requireAccess(server,request,"score.rw");return scoreView.get(player,objective);});}
                    public void set(UUID player,String objective,int value){scoreCall(scoreGateway,()->{requireAccess(server,request,"score.rw");scoreView.set(player,objective,value);return null;});}
                    public void add(UUID player,String objective,int value){scoreCall(scoreGateway,()->{requireAccess(server,request,"score.rw");scoreView.add(player,objective,value);return null;});}
                },fetch::read, new CtxBuilder.CommandView() {
                    public boolean affectsOthers(String id) { return commands.affectsOthers(id); }
                    public CommandRunner.Result run(String id, String params, Runnable beforeExecute) {
                        return scoreCall(scoreGateway, () -> {
                            Deployment live = deployments.deployment(request.appId());
                            ScriptHost host = current();
                            String cap = "command.template:" + id;
                            if (host == null || host.epoch(request.player().uuid())!=request.connectionEpoch()||live == null || !live.revision().equals(request.deployRev())
                                    || !host.authority.allows(request.player().uuid(), request.appId(), request.actionId())
                                    || host.revokedRule(request.appId()) != null
                                    || capabilityPolicy.check(cap,java.util.Set.copyOf(live.approvedCapabilities())) != CapabilityPolicy.Verdict.OK
                                    || commands.affectsOthers(id) && capabilityPolicy.check("command.affect_others",java.util.Set.copyOf(live.approvedCapabilities())) != CapabilityPolicy.Verdict.OK)
                                return new CommandRunner.Result(com.november.mcphone.core.script.net.ScriptErrorCode.NOT_AUTHORIZED,java.util.List.of());
                            return commands.run(server.getPlayerList().getPlayer(request.player().uuid()),request.appId(),id,params,beforeExecute);
                        });
                    }
                },new CtxBuilder.ResourceView() {
                    private ServerPlayer player(String capability) {
                        ScriptHost host=current();ServerPlayer player=server.getPlayerList().getPlayer(request.player().uuid());
                        Deployment live=deployments.deployment(request.appId());
                        if(host==null||host.epoch(request.player().uuid())!=request.connectionEpoch()||player==null||live==null||!live.revision().equals(request.deployRev())||!host.authority.allows(request.player().uuid(),request.appId(),request.actionId())||host.revokedRule(request.appId())!=null
                                ||capabilityPolicy.check(capability,java.util.Set.copyOf(live.approvedCapabilities()))!=CapabilityPolicy.Verdict.OK)
                            throw HostError.denied(ScriptErrorCode.NOT_AUTHORIZED,"mcphone.script.not_authorized","资源读取授权已经改变");
                        return player;
                    }
                    public java.util.List<com.november.mcphone.api.sdk.resources.ResourceType> list(){return scoreCall(scoreGateway,resources::list);}
                    public com.november.mcphone.api.sdk.resources.ResourceType defaultType(String kind){return scoreCall(scoreGateway,()->resources.defaultType(kind));}
                    public com.november.mcphone.api.sdk.resources.ResourceReading item(String type,int slot){return scoreCall(scoreGateway,()->resources.item(player("resource.read.item"),type,slot));}
                    public com.november.mcphone.api.sdk.resources.ResourceReading block(String type,int x,int y,int z,String side){return scoreCall(scoreGateway,()->{
                        net.minecraft.core.Direction direction;try{direction=net.minecraft.core.Direction.valueOf(side.toUpperCase(java.util.Locale.ROOT));}catch(IllegalArgumentException bad){throw HostError.invalid("方块面无效");}
                        return resources.block(player("resource.read.block"),type,new net.minecraft.core.BlockPos(x,y,z),direction);
                    });}
                },mechanism->scoreCall(scoreGateway,()->{
                    requireAccess(server,request,null);
                    String ns=com.november.mcphone.core.script.server.store.ScriptKv.namespace(serverId.toString(),"app:"+request.appId(),request.appId(),1);QuotaConfig q=quotas.current();long used,limit;String unit="bytes";
                    switch(mechanism){
                        case "kv"->{used=durableStore.namespaceBytes(request.player().uuid(),ns,false);limit=q.get("kv.per_player_app");}
                        case "sealed"->{used=durableStore.namespaceBytes(request.player().uuid(),ns,true);limit=q.get("sealed.per_player_app");}
                        case "shared"->{used=storageBudget.app(request.appId());limit=q.get("kv.per_app");}
                        case "data"->{used=durableStore.playerBytes(request.player().uuid());limit=q.get("data.per_player");}
                        case "mailbox"->{ScriptHost host=current();used=host==null?0:host.mailbox.used(request.player().uuid());limit=q.get("mailbox.per_player");unit="slots";}
                        case "escrow"->{ScriptHost host=current();used=host==null?0:host.escrow.used(request.player().uuid());limit=q.get("escrow.per_player");unit="slots";}
                        default->throw HostError.invalid("配额查询只接受 kv / sealed / shared / data / mailbox / escrow");
                    }
                    return Map.of("used",used,"limit",limit,"unit",unit);
                })).withReads(new ServerScriptReads(server,scoreGateway,request,itemHandles)).withMailbox(itemHandles.mailbox(request)));
        ServerAuthority authorityView = new ServerAuthority(deployments, authority);
        Map<String, Map<String, ActionGuards>> guardDefinitions = new LinkedHashMap<>();
        for (Deployment deployment : deployments.deployments()) {
            AppPackage pkg = scan.packages().get(deployment.packageDigest());
            if (pkg != null) guardDefinitions.put(deployment.appId(), ActionGuards.parse(
                    new String(pkg.entry("manifest.json"), java.nio.charset.StandardCharsets.UTF_8)));
        }
        IdempotencyLedger ledger = new IdempotencyLedger(System::currentTimeMillis).retention(()->quotas.current().get("ledger.hours")*3600000L);
        GuardController guards = new GuardController(System::currentTimeMillis, (predicate, uuid) -> {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            net.minecraft.resources.ResourceLocation id = net.minecraft.resources.ResourceLocation.tryParse(predicate);
            return player != null && id != null && Boolean.TRUE.equals(com.november.mcphone.platform.Predicates.test(player, id));
        });
        ScriptStateData state = ScriptStateData.get(server);
        state.restore(ledger, guards);
        state.bindShared(sharedState);
        sharedState.quotas(quotas::current,storageBudget);
        sharedState.onWrite(app -> scoreCall(scoreGateway,() -> {
            long now=System.nanoTime();
            storeWrites.entrySet().removeIf(e->e.getValue().isEmpty() || now-e.getValue().peekLast()>=1_000_000_000L);
            var times=storeWrites.computeIfAbsent("global:"+app,k->new java.util.ArrayDeque<>());
            while(!times.isEmpty() && now-times.peekFirst()>=1_000_000_000L) times.removeFirst();
            if(times.size()>=10) throw new com.november.mcphone.core.script.server.store.StoreQuota.QuotaExceeded("全局存储写入超过每秒 10 次");
            times.addLast(now); return null;
        }));
        sharedState.onChanged(() -> scoreCall(scoreGateway, () -> { state.commit(ledger, guards); return null; }));
        ledger.onChanged(() -> state.commit(ledger, guards));
        long warningThreshold = System.currentTimeMillis() + 30L * 86_400_000L;
        guards.cooldowns().forEach((key, until) -> {
            if (until != Long.MAX_VALUE && until > warningThreshold)
                MCphone.LOGGER.warn("[MCphone] 守卫到期时间超出未来 30 天：{} -> {}，请核对服务器时钟", key, until);
        });
        state.commit(ledger, guards);
        ServerMailbox mailbox = new ServerMailbox(server).quotas(quotas::current,uuid->0);
        ServerItemEscrow escrow=new ServerItemEscrow(server,mailbox,quotas::current);mailbox.quotas(quotas::current,escrow::mailboxCount);
        ServerIntentApplier applier = new ServerIntentApplier(uuid -> server.getPlayerList().getPlayer(uuid), mailbox);
        applier.itemHandles(itemHandles);
        applier.escrow(escrow);
        ServerNotifications notifications=new ServerNotifications(server,storageBudget).quotas(quotas::current);
        for(Deployment deployment:deployments.deployments()){AppPackage pkg=scan.packages().get(deployment.packageDigest());if(pkg!=null)notifications.register(pkg);}
        applier.notifications(notifications);
        applier.mailboxEnabled(() -> runtime.get().mailboxOnFull());
        ServerAuthorRevocations authorRevocations;
        try{authorRevocations=new ServerAuthorRevocations(server,runtime::get,()->{ScriptHost active=current();if(active!=null)active.pushRevocations();});}
        catch(java.io.IOException bad){fetch.close();throw new IllegalStateException("作者吊销缓存不可用，脚本宿主拒绝启动",bad);}
        for(Deployment deployment:deployments.deployments()){AppPackage pkg=scan.packages().get(deployment.packageDigest());if(pkg!=null)authorRevocations.register(pkg);}
        ScriptPipeline pipeline = new ScriptPipeline(serverId,
                ledger,
                new ScriptRateLimiter(System::currentTimeMillis),
                new ServerDeployments(deployments, guardDefinitions), authorityView, evaluator,
                applier,
                capabilityPolicy);
        if (scoreGateway == null) throw new IllegalStateException("脚本持久化需要主线程网关");
        pipeline.installGuards(guards, task -> {
            try { scoreGateway.call(() -> { task.run(); return null; }); }
            catch (RuntimeException unavailable) {
                throw HostError.denied(com.november.mcphone.core.script.net.ScriptErrorCode.UNAVAILABLE,
                        "mcphone.script.server_busy", "账本持久化此刻不可用");
            }
        });
        // 登记之后 ScriptRpcHandler.handle 才会把请求交给这条管线（此前一律 NOT_DEPLOYED）
        ScriptRpcHandler.install(pipeline);
        current = new ScriptHost(apps, strikes, evaluator, pipeline, serverId, deployments, authorityView,
                capabilityPolicy, guardDefinitions);
        current.guards = guards; current.ledger = ledger; current.server = server;
        current.itemHandles=itemHandles;
        current.witnesses=witnesses;
        current.mailbox = mailbox;
        current.escrow=escrow;
        current.currencyEscrows=currencyEscrows;
        current.notifications=notifications;
        current.authorRevocations=authorRevocations;
        current.quotas=quotas;current.storageBudget=storageBudget;
        for(Deployment d:deployments.deployments()){AppPackage pkg=scan.packages().get(d.packageDigest());if(pkg!=null)current.backgroundActions.put(d.appId(),com.november.mcphone.core.script.pkg.BackgroundDeclaration.of(pkg).stream().map(t->t.action()).collect(java.util.stream.Collectors.toUnmodifiableSet()));}
        ScriptHost backgroundHost=current;
        pipeline.installBackground((app,action)->backgroundHost.backgroundActions.getOrDefault(app,java.util.Set.of()).contains(action));
        current.runtimeConfig = runtime;
        pipeline.executionEnabled(()->runtime.get().serverScripts());
        current.fetch = fetch;
        current.commands = commands;
        current.resources=resources;
        scan.packages().forEach((digest,pkg)->{current.packageVersions.put(digest,RevocationPolicy.versionOf(pkg));current.packageKeys.put(digest,com.november.mcphone.core.script.pkg.SigManifest.parse(pkg.signature()).pubkey());});
        ScriptHost owner = current;
        pipeline.installRevocations(app -> {
            Deployment d = deployments.deployment(app);
            return d == null ? null : owner.revocations(app).rejected(app,
                    owner.packageVersions.getOrDefault(d.packageDigest(), 0L), d.packageDigest());
        });
        current.gifts = new ServerGifts(server, serverId, runtime::get, guards, ledger, mailbox).audit(quotas.audit());
        try {current.store = new ServerStore(server,runtime::get).quotas(quotas::current);current.store.importIncoming();}
        catch(java.io.IOException bad) {MCphone.LOGGER.error("[MCphone] 商店存档不可用，已关闭商店",bad);}
        QuotaControls quotaControls=new QuotaControls(server,quotas,storageBudget,runtime::get);current.auditExports=new AuditExportControls(server,quotaControls);
        current.administration=new NativeAdminControls(server,quotaControls);
        pipeline.installHostControls(new HostControls(mailbox, current.gifts,durableStore).administration(current.administration).exports(current.auditExports).escrow(escrow).serverStore(current.store).notifications(notifications).quotas(quotaControls).seedStore(uuid -> {
            ServerPlayer player=server.getPlayerList().getPlayer(uuid);
            if(player!=null) durableStore.kv(uuid,com.november.mcphone.core.PhonePlayerData.of(player).scriptKv());
        })::handle);
        current.subscriptions = new GuardSubscriptions(System::nanoTime);
        ScriptHost active = current;
        pipeline.installSubscriptions(active.subscriptions, active::guardSnapshot);
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
            if (h.fetch != null) h.fetch.close();
            if(h.itemHandles!=null)h.itemHandles.clear();
            if(h.authorRevocations!=null)h.authorRevocations.close();
            if(h.store!=null)h.store.close();
            if(h.auditExports!=null)h.auditExports.close();
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
     * <p><b>坏配置不覆盖好配置</b>（S18-E3/E4）：解析不可用时保留上一份生效的快照，
     * 返回 false，命令面用 {@link #capabilities()}{@code .loadError()} 报原因。
     *
     * @return 没开服（脚本后端降级/未装）时 false；配置不可用时也 false
     */
    public static synchronized boolean reloadCapabilities(MinecraftServer server) {
        ScriptHost h = current;
        if (h == null) return false;
        CapabilityConfig fresh = CapabilityConfig.load(server, h.capabilityPolicy.config());
        h.capabilityPolicy.reload(fresh);
        if (!fresh.loadError().isEmpty()) {
            MCphone.LOGGER.error("[MCphone] 能力配置重载被拒：{}（仍按上一份生效）", fresh.loadError());
            return false;
        }
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
            h.authorRevocations.remove(appId);
            h.backgroundActions.remove(appId);
            h.removeApp(appId);           // 撤部署：旧 scope 消失
            return true;
        }
        if(!h.runtime().serverScripts()){h.removeApp(appId);return true;}
        ServerPackageScanner.Scan scan = ServerPackageScanner.scan(server, h.deployments);
        AppPackage pkg = scan.packages().get(d.packageDigest());
        if (pkg == null) {
            MCphone.LOGGER.error("[MCphone] {} 已批准，但包（{}…）不在 incoming，重装配失败：该 App 现在不可执行（NOT_DEPLOYED）。"
                    + "把包装回待审目录后重试，或重启", appId, short8(d.packageDigest()));
            h.removeApp(appId);
            return false;
        }
        AppScope fresh = ServerAppAssembler.assembleOne(d, pkg);
        h.notifications.register(pkg);
        h.authorRevocations.register(pkg);
        if (fresh == null) {
            MCphone.LOGGER.error("[MCphone] {} 的重装配失败：该 App 现在不可执行（NOT_DEPLOYED），修好后重新 approve 或重启", appId);
            h.removeApp(appId);
            return false;
        }
        AppScope old = h.apps.put(appId, fresh);
        h.packageVersions.put(d.packageDigest(), RevocationPolicy.versionOf(pkg));
        h.packageKeys.put(d.packageDigest(),com.november.mcphone.core.script.pkg.SigManifest.parse(pkg.signature()).pubkey());
        h.actionGuards.put(appId, ActionGuards.parse(new String(pkg.entry("manifest.json"), java.nio.charset.StandardCharsets.UTF_8)));
        if (old != null) old.discard();   // 在飞的求值持有旧引用，discard 只是把 AppScope 的缓存置空
        MCphone.LOGGER.info("[MCphone] {} 已重装配（批准轴 {}，包 {}…）", appId, d.approvalRevision(), short8(d.packageDigest()));
        h.backgroundActions.put(appId,com.november.mcphone.core.script.pkg.BackgroundDeclaration.of(scan.packages().get(d.packageDigest())).stream().map(t->t.action()).collect(java.util.stream.Collectors.toUnmodifiableSet()));
        return true;
    }

    public void broadcastHandshake() {
        for(ServerPlayer player:server.getPlayerList().getPlayers())HandshakeService.pushTo(player,epoch(player.getUUID()));
    }

    private void removeApp(String appId) {
        actionGuards.remove(appId);
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
        if (h != null) h.syncPersonalGuards(player);
        return h == null ? 0L : h.pipeline.newEpoch(player.getUUID());
    }

    private void syncPersonalGuards(ServerPlayer player) {
        Map<String, Long> personal = new LinkedHashMap<>(); String suffix = "|" + player.getUUID();
        guards.counters().forEach((k, v) -> { if (k.endsWith(suffix)) personal.put(k, v); });
        guards.cooldowns().forEach((k, v) -> { if (k.endsWith(suffix)) personal.put(k, v); });
        // 玩家附件是同一份事务日志的个人视图；重启后从日志对齐，避免玩家存档与全局配额分两次保存。
        com.november.mcphone.core.PhonePlayerData.of(player).setScriptGuards(
                new com.november.mcphone.core.script.server.store.ScriptGuards(personal));
    }

    private byte[] guardSnapshot(UUID player, String app) {
        Map<String, ActionGuards> visible = new LinkedHashMap<>();
        actionGuards.getOrDefault(app, Map.of()).forEach((action, definition) -> {
            if (authority.allows(player, app, action)) visible.put(action, definition);
        });
        if (visible.isEmpty() || pipeline.epochOf(player) == 0) return null;
        com.google.gson.JsonObject snapshot = guards.describe(player, app, visible);
        snapshot.addProperty("appId", app); snapshot.addProperty("serverId", serverId.toString());
        snapshot.addProperty("epoch", pipeline.epochOf(player));
        snapshot.addProperty("deployRev", deployments.deployment(app).revision());
        return snapshot.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static void tick() {
        ScriptHost h = current(); if (h == null) return;
        long now = System.nanoTime();
        h.authorRevocations.tick();
        if(h.store!=null)h.store.tickUpdates();
        h.itemHandles.sweep();
        if (now - h.mailboxSweep >= 60_000_000_000L) {
            h.mailboxSweep = now;
            try { h.mailbox.expire(); } catch (RuntimeException unavailable) { MCphone.LOGGER.error("[MCphone] 收件箱清理不可用", unavailable); }
            h.escrow.sweep();
            h.notifications.sweep();
            for(ServerPlayer player:h.server.getPlayerList().getPlayers())h.pushNetworkPolicy(player);
        }
        h.subscriptions.flush(h.guards.revision(), uuid -> h.server.getPlayerList().getPlayer(uuid) != null,
                h::guardSnapshot, (uuid, push) -> {
                    ServerPlayer player = h.server.getPlayerList().getPlayer(uuid);
                    if (player != null) com.november.mcphone.core.script.net.ScriptPushHandler.push(player, push);
                });
    }

    /** 管理员核对：绝不由脚本或玩家参数自动判定是否已经发奖。 */
    public void resolve(UUID player, String key, boolean delivered) {
        var entry = ledger.snapshot().getOrDefault(player, Map.of()).get(key);
        if (entry == null || entry.state() != IdempotencyLedger.State.UNKNOWN) throw new IllegalArgumentException("不是待核对请求");
        guards.finish(key, delivered ? com.november.mcphone.core.script.net.ScriptErrorCode.OK : com.november.mcphone.core.script.net.ScriptErrorCode.INTERNAL);
        ledger.resolve(player, key, delivered);
        MCphone.LOGGER.warn("[MCphone] 人工核对脚本结果 player={} request={} delivered={}", player, key, delivered);
    }
    public Map<UUID, Map<String, IdempotencyLedger.Entry>> journal() { return ledger.snapshot(); }
    public ServerMailbox mailbox() { return mailbox; }
    public ServerItemEscrow escrow(){return escrow;}
    public com.november.mcphone.core.script.server.economy.ScriptCurrencyEscrows currencyEscrows(){return currencyEscrows;}
    public long version(String digest){return packageVersions.getOrDefault(digest,0L);}
    public byte[] author(String digest){return packageKeys.getOrDefault(digest,new byte[0]).clone();}
    public VersionWitness witnesses(){return witnesses;}
    public ServerGifts gifts() { return gifts; }
    public ServerStore store() {return store;}
    public CommandRunner commands(){return commands;}
    public QuotaManager quotas(){return quotas;}
    public ServerNotifications notifications(){return notifications;}
    public ScriptRuntimeConfig runtime(){return runtimeConfig.get();}
    public StorageBudget storageBudget(){return storageBudget;}
    public long epoch(UUID player) {return pipeline.epochOf(player);}
    /** 宿主桥只在主线程调用；原请求不能借同 UUID 的新连接继续读写。 */
    public static ServerPlayer requireAccess(MinecraftServer server,ActionEvaluator.Request request,String capability){
        if(!server.isSameThread())throw new IllegalStateException("宿主授权检查必须在服务器主线程执行");
        ScriptHost host=current();
        if(host==null||host.server!=server||host.epoch(request.player().uuid())!=request.connectionEpoch()||request.connectionEpoch()==0)
            throw HostError.denied(ScriptErrorCode.NOT_AUTHORIZED,"mcphone.script.not_authorized","原连接或脚本宿主已失效");
        if(!host.runtime().serverScripts())throw HostError.denied(ScriptErrorCode.UNAVAILABLE,"mcphone.script.capability.disabled","本服未启用服务端脚本");
        ServerPlayer player=server.getPlayerList().getPlayer(request.player().uuid());Deployment live=host.deployments.deployment(request.appId());
        if(player==null||live==null||!live.revision().equals(request.deployRev())||!host.allows(request.player().uuid(),request.appId(),request.actionId()))
            throw HostError.denied(ScriptErrorCode.NOT_AUTHORIZED,"mcphone.script.not_authorized","当前部署或使用许可已失效");
        RevocationPolicy.Rule revoked=host.revokedRule(request.appId());
        if(revoked!=null)throw HostError.denied(ScriptErrorCode.REVOKED,ScriptErrorCode.REVOKED.defaultMessageKey(),revoked.reason());
        if(capability!=null){CapabilityPolicy.Verdict verdict=host.capabilityPolicy.check(capability,java.util.Set.copyOf(live.approvedCapabilities()));
            if(verdict!=CapabilityPolicy.Verdict.OK)throw HostError.denied(verdict==CapabilityPolicy.Verdict.NOT_APPROVED?ScriptErrorCode.NOT_AUTHORIZED:ScriptErrorCode.UNAVAILABLE,CapabilityPolicy.messageKey(verdict),"当前能力被拒绝："+capability);
        }return player;
    }
    public int storeVisibility(String app,UUID player) {return store==null?0:store.visibility(app,player);}
    public ScriptRateLimiter.Decision uploadRate(UUID player,long size) {return pipeline.allowUpload(player,size);}
    public void reloadRuntime() {
        ScriptRuntimeConfig next = ScriptRuntimeConfig.load(server);
        ResourceRegistry.Config nextResources=ResourceRegistry.load(server);
        QuotaConfig nextQuotas;try{nextQuotas=quotas.prepare();}catch(java.io.IOException bad){throw new IllegalStateException("配额无法重载",bad);}
        commands.reload();
        resources.reload(nextResources);
        quotas.publish(nextQuotas);
        applyRuntime(next);
        authorRevocations.reload();
        if(store!=null)store.reloadUpdates();
        pushRevocations();
        if (fetch != null) fetch.clear();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) pushNetworkPolicy(player);
    }
    /** 重载预览保留实际生效值；不能把已经被手工修改的磁盘文件当作旧快照。 */
    public com.google.gson.JsonObject resourceConfiguration(){return resources.snapshot();}
    /** 三份已预检的配置一次发布；管理通道在关闭脚本后仍可用于核对和取回现有物品。 */
    public void publishAdminConfiguration(AdminConfiguration.Snapshot snapshot){
        CapabilityConfig caps=CapabilityConfig.parse(snapshot.file(CapabilityConfig.FILE).toString());
        ScriptRuntimeConfig runtime=ScriptRuntimeConfig.parse(snapshot.file(ScriptRuntimeConfig.FILE).toString());
        QuotaConfig limits=QuotaConfig.parse(snapshot.file(QuotaManager.FILE).toString());
        capabilityPolicy.reload(caps);quotas.publish(limits);applyRuntime(runtime);if(fetch!=null)fetch.clear();if(store!=null)store.reloadUpdates();pushRevocations();broadcastHandshake();
    }
    private void applyRuntime(ScriptRuntimeConfig next){boolean previous=runtime().serverScripts();runtimeConfig.set(next);if(!next.serverScripts())for(String app:java.util.List.copyOf(apps.keySet()))removeApp(app);else if(!previous)for(Deployment deployment:deployments.deployments())reassemble(server,deployment.appId());}

    public void pushNetworkPolicy(ServerPlayer player) {
        com.google.gson.JsonObject data = new com.google.gson.JsonObject();
        data.addProperty("serverId", serverId.toString()); data.addProperty("epoch", Long.toString(pipeline.epochOf(player.getUUID())));
        data.addProperty("allowClientFetch", runtimeConfig.get().net().clientAllowed());
        data.addProperty("appLimit",quotas.current().get("apps.per_player"));
        data.addProperty("storeFileProtocol",com.november.mcphone.core.script.net.StoreFileRequest.PROTOCOL);
        data.addProperty("notificationProtocol",2);
        data.addProperty("onlineMode",server.usesAuthentication());
        data.addProperty("packageCompressed",quotas.current().get("package.compressed"));
        data.addProperty("packageExpanded",quotas.current().get("package.expanded"));
        data.addProperty("admin",player.hasPermissions(3)&&runtimeConfig.get().deploymentApprovers().contains(player.getUUID()));
        com.november.mcphone.core.script.net.ScriptPushHandler.push(player, new com.november.mcphone.core.script.net.ScriptPush(
                com.november.mcphone.core.script.net.ScriptProtocol.HOST_APP_ID, "mcphone:network.policy",
                data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), Seq.next()));
        pushRevocations(player);
        if(store!=null)store.pushUpdates(player);
    }
    public RevocationPolicy revocations(String app){Deployment d=deployments.deployment(app);return revocations(app,d==null?new byte[0]:packageKeys.getOrDefault(d.packageDigest(),new byte[0]));}
    public RevocationPolicy revocations(AppPackage pkg){return revocations(pkg.manifest().id(),com.november.mcphone.core.script.pkg.SigManifest.parse(pkg.signature()).pubkey());}
    public RevocationPolicy revocations(String app,byte[] key){RevocationPolicy policy=runtimeConfig.get().revocations().union(authorRevocations.policy(app,key)).union(witnesses.policy(app,key));if(runtime().blockedAuthors().contains(java.util.Base64.getEncoder().encodeToString(key)))policy=policy.union(new RevocationPolicy(Map.of(app,new RevocationPolicy.Rule(Long.MAX_VALUE,Set.of(Long.MAX_VALUE),Set.of(),"服主已禁用这个作者"))));return policy;}
    public void publishRevocations(){pushRevocations();}
    private void pushRevocations(){for(ServerPlayer player:server.getPlayerList().getPlayers())pushRevocations(player);}
    private void pushRevocations(ServerPlayer player){for(Deployment deployment:deployments.deployments()){
        String app=deployment.appId();RevocationPolicy.Rule rejected=revokedRule(app),rule=revocations(app).rule(app),owner=runtimeConfig.get().revocations().rule(app);com.google.gson.JsonObject data=new com.google.gson.JsonObject();data.addProperty("serverId",serverId.toString());data.addProperty("epoch",Long.toString(epoch(player.getUUID())));data.addProperty("app",app);data.addProperty("digest",deployment.packageDigest());data.addProperty("publicKey",java.util.Base64.getEncoder().encodeToString(packageKeys.getOrDefault(deployment.packageDigest(),new byte[0])));data.addProperty("ownerMinimum",Long.toString(owner==null?0:owner.minVersion()));data.addProperty("ownerRevoked",owner!=null&&owner.rejects(packageVersions.getOrDefault(deployment.packageDigest(),0L),deployment.packageDigest()));data.addProperty("revoked",rejected!=null);data.addProperty("minVersion",Long.toString(rule==null?0:rule.minVersion()));data.addProperty("reason",rejected!=null?rejected.reason():rule==null?"":rule.reason());com.november.mcphone.core.script.net.ScriptPushHandler.push(player,new com.november.mcphone.core.script.net.ScriptPush(com.november.mcphone.core.script.net.ScriptProtocol.HOST_APP_ID,"mcphone:revocation",data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),Seq.next()));
    }}
    private RevocationPolicy.Rule revokedRule(String app) {
        Deployment d = deployments.deployment(app);
        return d == null ? null : revocations(app).rejected(app,packageVersions.getOrDefault(d.packageDigest(),0L),d.packageDigest());
    }

    /** 玩家登出：只忘 epoch，不清账本（账本保留 24 小时，跨重连命中正是它存在的理由）。 */
    public static void forget(UUID player) {
        ScriptHost h = current();
        if (h != null) {h.pipeline.forget(player);h.escrow.forget(player);h.itemHandles.forget(player);h.notifications.forget(player);h.administration.forget(player);if(h.store!=null)h.store.forget(player);}
    }
}
