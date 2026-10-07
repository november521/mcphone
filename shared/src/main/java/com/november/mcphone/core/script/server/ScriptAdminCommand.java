package com.november.mcphone.core.script.server;

import com.november.mcphone.MCphone;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Set;

/**
 * 脚本后端的 OP 管理命令（施工方案 §14.4）。<b>S17 Stage 1 的审批入口</b>——
 * 管理界面（§14.3 ⑤）到 Stage 2 才做，这条命令届时保留为无界面的等价入口。
 *
 * <pre>
 * /mcphone script identity                              服务器身份（客户端按它分桶）
 * /mcphone script reload                                重扫 incoming 待审目录
 * /mcphone script list                                  候选 + 已批准部署 + 授权范围
 * /mcphone script approve &lt;digest&gt; [动作列表] [能力列表]   批准候选；动作轴省略 = 按声明全批、{@code -} = 一个都不批，
 *                                                      能力轴省略 = 只自动批 plain（声明含 granted 必须显式写，{@code all} = 全批），
 *                                                      否则按逗号拆的逐条勾选（动作与能力两条轴都逐条）
 * /mcphone script remove &lt;app&gt;                          撤掉一个部署
 * /mcphone script authorize &lt;app&gt; all|&lt;玩家名|UUID&gt;      授权
 * /mcphone script revoke &lt;app&gt; &lt;玩家名|UUID&gt;             撤销指定玩家的授权
 * /mcphone script unlicenseAll &lt;app&gt;                    取消"所有人"档（不动指定名单）
 * /mcphone script clearApp &lt;app&gt;                        清掉该 App 的全部授权（所有人档 + 指定名单）
 * </pre>
 *
 * <p>命令面照定向对抗的要求：{@code approve} 回显被丢掉的项与是否覆盖；{@code revoke} 在"所有人"档下
 * <b>拒绝</b>而不是静默空操作；{@code clearApp} 类的全清要报出清掉几人。
 */
public final class ScriptAdminCommand {

    private ScriptAdminCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mcphone").then(Commands.literal("quota").requires(ScriptAdminCommand::quotaAllowed)
            .then(Commands.literal("show").executes(ctx->{ScriptHost h=ScriptHost.current();if(h==null)return fail(ctx.getSource(),"后端未启用");for(var s:QuotaConfig.SPECS)ok(ctx.getSource(),s.key()+" = "+h.quotas().current().get(s.key())+" "+s.unit()+" ["+s.minimum()+".."+s.maximum()+"]");ok(ctx.getSource(),"全服数据占用 "+h.storageBudget().total()+" B");return 1;}))
            .then(Commands.literal("set").then(Commands.argument("key",StringArgumentType.word()).suggests((ctx,builder)->{QuotaConfig.SPECS.forEach(s->builder.suggest(s.key()));return builder.buildFuture();})
                .then(Commands.argument("value",com.mojang.brigadier.arguments.LongArgumentType.longArg(0)).executes(ctx->{ScriptHost h=ScriptHost.current();if(h==null)return fail(ctx.getSource(),"后端未启用");try{String key=StringArgumentType.getString(ctx,"key");long value=com.mojang.brigadier.arguments.LongArgumentType.getLong(ctx,"value");h.quotas().set(ctx.getSource().getEntity() instanceof ServerPlayer p?p.getUUID():null,key,value);for(var p:ctx.getSource().getServer().getPlayerList().getPlayers())h.pushNetworkPolicy(p);ok(ctx.getSource(),"配额已保存："+key+" = "+value);return 1;}catch(java.io.IOException|RuntimeException bad){return fail(ctx.getSource(),"配额未更改："+bad.getMessage());}}))))));
        dispatcher.register(Commands.literal("mcphone")
                .then(Commands.literal("script")
                        .requires(src -> src.hasPermission(3))
                        .then(Commands.literal("identity").executes(ctx -> {
                            ok(ctx.getSource(), "[脚本] 服务器身份 " + ServerIdentity.idOf(ctx.getSource().getServer()));
                            return 1;
                        }))
                        .then(Commands.literal("reload").executes(ctx -> {
                            MinecraftServer server = ctx.getSource().getServer();
                            int n = ServerPackageScanner.scan(server, DeploymentData.get(server)).changed();
                            ScriptHost host=ScriptHost.current();if(host!=null&&host.store()!=null)host.store().importIncoming();
                            ok(ctx.getSource(), "[脚本] 重扫待审目录完毕，本次进队 " + n + " 条");
                            return n;
                        }))
                        .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                        .then(Commands.literal("pending").executes(ctx -> pending(ctx.getSource())))
                        .then(Commands.literal("currencyEscrowPending").requires(ScriptAdminCommand::quotaAllowed).executes(ctx->{ScriptHost h=ScriptHost.current();if(h==null)return fail(ctx.getSource(),"后端未启用");for(var e:h.currencyEscrows().entries())if(e.state()==com.november.mcphone.core.script.server.economy.ScriptCurrencyEscrows.State.UNKNOWN)ok(ctx.getSource(),e.id()+" "+e.currency()+" 玩家="+e.scope().owner()+" App="+e.scope().app()+" 原始版本="+e.scope().version());return 1;}))
                        .then(Commands.literal("currencyEscrowResolve").requires(ScriptAdminCommand::quotaAllowed).then(Commands.argument("id",StringArgumentType.word()).then(Commands.argument("resolution",StringArgumentType.word()).executes(ctx->{
                            ScriptHost h=ScriptHost.current();if(h==null)return fail(ctx.getSource(),"后端未启用");String resolution=StringArgumentType.getString(ctx,"resolution");if(!Set.of("occurred","retry").contains(resolution))return fail(ctx.getSource(),"必须选 occurred（已结算）或 retry（已核对未执行）");
                            try{UUID id=UUID.fromString(StringArgumentType.getString(ctx,"id"));var e=h.currencyEscrows().entries().stream().filter(row->row.id().equals(id)).findFirst().orElseThrow();com.google.gson.JsonObject data=new com.google.gson.Gson().toJsonTree(e).getAsJsonObject();data.addProperty("version",Long.toString(e.scope().version()));data.addProperty("resolution",resolution);h.quotas().audit().append(ctx.getSource().getEntity() instanceof ServerPlayer p?p.getUUID():null,"currency.escrow.resolve",e.scope().app(),null,data);h.currencyEscrows().resolve(id,resolution.equals("occurred"));ok(ctx.getSource(),"货币托管核对已保存");return 1;}catch(Exception bad){return fail(ctx.getSource(),"核对未保存："+bad.getMessage());}
                        }))))
                        .then(Commands.literal("runtimeReload").requires(ScriptAdminCommand::quotaAllowed).executes(ctx -> {
                            ScriptHost host = ScriptHost.current();
                            if (host == null) return fail(ctx.getSource(), "[脚本] 后端未启用");
                            try {var preview=new AdminConfiguration(ctx.getSource().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT),host.quotas().audit()).read();if(!auditCommand(ctx.getSource(),"config.runtime.reload","mcphone:configuration",new com.google.gson.JsonPrimitive(host.runtime().toString()),new com.google.gson.Gson().toJsonTree(preview.files())))return 0;host.reloadRuntime(); ok(ctx.getSource(), "[脚本] 运行期配置已重载，吊销立即生效"); return 1; }
                            catch (java.io.IOException|RuntimeException error) { return fail(ctx.getSource(), "[脚本] 配置被拒：" + error.getMessage()); }
                        }))
                        .then(Commands.literal("escrowPending").requires(ScriptAdminCommand::quotaAllowed).executes(ctx->{ScriptHost h=ScriptHost.current();if(h==null)return fail(ctx.getSource(),"后端未启用");for(var e:h.escrow().entries())if(e.state()==ItemEscrowLedger.State.UNKNOWN_CAPTURE||e.state()==ItemEscrowLedger.State.UNKNOWN_DELIVERY)ok(ctx.getSource(),e.id()+" "+e.state()+" 原主="+e.owner()+" 受益人="+e.recipient());return 1;}))
                        .then(Commands.literal("escrowResolve").requires(ScriptAdminCommand::quotaAllowed).then(Commands.argument("id",StringArgumentType.word()).then(Commands.argument("resolution",StringArgumentType.word()).executes(ctx->{ScriptHost h=ScriptHost.current();if(h==null)return fail(ctx.getSource(),"后端未启用");String resolution=StringArgumentType.getString(ctx,"resolution");if(!Set.of("occurred","retry").contains(resolution))return fail(ctx.getSource(),"必须明确选择 occurred（已扣除/已交付）或 retry（未发生）");try{h.escrow().resolve(ctx.getSource().getEntity() instanceof ServerPlayer p?p.getUUID():null,StringArgumentType.getString(ctx,"id"),resolution.equals("occurred"));ok(ctx.getSource(),"托管人工核对已保存");return 1;}catch(Exception bad){return fail(ctx.getSource(),"核对未保存："+bad.getMessage());}}))))
                        .then(Commands.literal("mailboxResolve").requires(ScriptAdminCommand::quotaAllowed)
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .then(Commands.argument("item", StringArgumentType.word())
                                                .then(Commands.argument("resolution", StringArgumentType.word()).executes(ctx -> {
                                                    ScriptHost host = ScriptHost.current();
                                                    if (host == null) return fail(ctx.getSource(), "[脚本] 后端未启用");
                                                    String resolution = StringArgumentType.getString(ctx, "resolution");
                                                    if (!resolution.equals("delivered") && !resolution.equals("retry")) return fail(ctx.getSource(), "必须选 delivered 或 retry");
                                                    try {
                                                        UUID player = UUID.fromString(StringArgumentType.getString(ctx, "player"));
                                                        String item = StringArgumentType.getString(ctx, "item");
                                                        host.mailbox().resolve(ctx.getSource().getEntity() instanceof ServerPlayer p?p.getUUID():null,player, item, resolution.equals("delivered"));
                                                        MCphone.LOGGER.warn("[MCphone] 人工核对收件箱 operator={} player={} item={} resolution={}", ctx.getSource().getTextName(), player, item, resolution);
                                                        ok(ctx.getSource(), "[脚本] 收件箱核对结果已保存"); return 1;
                                                    } catch (RuntimeException invalid) { return fail(ctx.getSource(), "[脚本] 核对失败：" + invalid.getMessage()); }
                                                })))))
                        .then(Commands.literal("resolve").requires(ScriptAdminCommand::quotaAllowed)
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .then(Commands.argument("key", StringArgumentType.word())
                                                .then(Commands.argument("resolution", StringArgumentType.word()).executes(ctx ->
                                                        resolve(ctx.getSource(), StringArgumentType.getString(ctx, "player"),
                                                                StringArgumentType.getString(ctx, "key"), StringArgumentType.getString(ctx, "resolution")))))))
                        .then(Commands.literal("capabilities")
                                .executes(ctx -> capabilities(ctx.getSource()))
                                .then(Commands.literal("reload").requires(ScriptAdminCommand::quotaAllowed).executes(ctx -> reloadCapabilities(ctx.getSource()))))
                        .then(Commands.literal("approve")
                                .then(Commands.argument("digest", StringArgumentType.word())
                                        .executes(ctx -> approve(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "digest"), null, null))
                                        .then(Commands.argument("actions", StringArgumentType.string())
                                                .executes(ctx -> approve(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "digest"),
                                                        StringArgumentType.getString(ctx, "actions"), null))
                                                .then(Commands.argument("caps", StringArgumentType.string())
                                                        .executes(ctx -> approve(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "digest"),
                                                                StringArgumentType.getString(ctx, "actions"),
                                                                StringArgumentType.getString(ctx, "caps")))))))
                        .then(Commands.literal("remove").requires(ScriptAdminCommand::quotaAllowed)
                                .then(Commands.argument("app", StringArgumentType.string())
                                        .executes(ctx -> remove(ctx.getSource(), StringArgumentType.getString(ctx, "app")))))
                        .then(Commands.literal("authorize").requires(ScriptAdminCommand::quotaAllowed)
                                .then(Commands.argument("app", StringArgumentType.string())
                                        .then(Commands.argument("target", StringArgumentType.string())
                                                .executes(ctx -> authorize(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "app"),
                                                        StringArgumentType.getString(ctx, "target"))))))
                        .then(Commands.literal("revoke").requires(ScriptAdminCommand::quotaAllowed)
                                .then(Commands.argument("app", StringArgumentType.string())
                                        .then(Commands.argument("target", StringArgumentType.string())
                                                .executes(ctx -> revoke(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "app"),
                                                        StringArgumentType.getString(ctx, "target"))))))
                        .then(Commands.literal("unlicenseAll").requires(ScriptAdminCommand::quotaAllowed)
                                .then(Commands.argument("app", StringArgumentType.string())
                                        .executes(ctx -> unlicenseAll(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "app")))))
                        .then(Commands.literal("clearApp").requires(ScriptAdminCommand::quotaAllowed)
                                .then(Commands.argument("app", StringArgumentType.string())
                                        .executes(ctx -> clearApp(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "app")))))));
    }

    private static int list(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        DeploymentData dd = DeploymentData.get(server);
        AuthorityData ad = AuthorityData.get(server);
        ok(src, "[脚本] 候选 " + dd.candidates().size() + "，已批准 " + dd.deployments().size());
        for (DeploymentData.Candidate c : dd.candidates()) {
            ok(src, "  候选 " + c.appId() + " [" + shortDigest(c.packageDigest()) + "] 动作 "
                    + c.declaredActions().size() + "、能力 " + c.declaredCapabilities().size()
                    + "，用 /mcphone script approve " + c.packageDigest() + " 批准");
        }
        for (Deployment d : dd.deployments()) {
            ok(src, "  已批准 " + d.appId() + " 版本 " + d.approvalRevision() + " [" + shortDigest(d.packageDigest())
                    + "] 动作 " + d.approvedActions() + " / 声明 " + d.declaredActions()
                    + "，范围 " + ad.scopeOf(d.appId()));
        }
        return 1;
    }
    private static boolean quotaAllowed(CommandSourceStack source){ScriptHost host=ScriptHost.current();return source.hasPermission(3)&&(source.getEntity()==null||source.getEntity() instanceof ServerPlayer p&&host!=null&&host.runtime().deploymentApprovers().contains(p.getUUID()));}

    /** 能力目录 + 当前生效的开关（S18）。服主据此知道哪些 id 能批、哪些被全服关了。 */
    private static int capabilities(CommandSourceStack src) {
        ScriptHost host = ScriptHost.current();
        CapabilityConfig cfg = host == null ? null : host.capabilities();
        ok(src, "[脚本] 能力目录（" + CapabilityCatalog.all().size() + " 条，其中首版开放 "
                + CapabilityCatalog.open().size() + " 条）：");
        for (CapabilityCatalog.Entry e : CapabilityCatalog.all()) {
            boolean off = cfg != null && cfg.isDisabled(e.id());
            ok(src, "  " + (e.open() ? "开放" : "不开放") + "  " + e.tier() + "  " + e.id()
                    + (CapabilityCatalog.enforced(e.id()) ? "  [可关]" : (e.open() ? "  [本步无调用点]" : ""))
                    + (off ? "  【本服已关闭】" : ""));
        }
        if (cfg == null) {
            ok(src, "[脚本] 脚本后端未启用，能力配置读不到");
            return 1;
        }
        if (!cfg.loadError().isEmpty()) {
            fail(src, "[脚本] ⚠ 配置文件这次没生效（仍按上一份跑）：" + cfg.loadError());
        }
        ok(src, "[脚本] 预设 " + cfg.preset() + "，全服关闭 " + cfg.disabled().size() + " 项"
                + (cfg.disabled().isEmpty() ? "" : "：" + String.join("、", cfg.disabled())));
        return 1;
    }

    /** 重读能力配置文件。只换配置快照：不动部署、不动 epoch、不重建 scope。坏配置保留上一份。 */
    private static int reloadCapabilities(CommandSourceStack src) {
        ScriptHost active=ScriptHost.current();if(active==null)return fail(src,"脚本后端未启用");
        try{var next=new AdminConfiguration(src.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT),active.quotas().audit()).read();if(!auditCommand(src,"config.capabilities.reload","mcphone:configuration",new com.google.gson.Gson().toJsonTree(active.capabilities()),next.file(CapabilityConfig.FILE)))return 0;}
        catch(java.io.IOException|RuntimeException bad){return fail(src,"配置预检失败，未重载："+bad.getMessage());}
        if (!ScriptHost.reloadCapabilities(src.getServer())) {
            ScriptHost host = ScriptHost.current();
            if (host == null) {
                fail(src, "[脚本] 脚本后端未启用，能力配置要等开服后才会读");
            } else {
                fail(src, "[脚本] 能力配置被拒（仍按上一份生效）：" + host.capabilities().loadError());
            }
            return 0;
        }
        ScriptHost host = ScriptHost.current();
        CapabilityConfig cfg = host == null ? null : host.capabilities();
        if (cfg != null) {
            for (String w : cfg.warnings()) ok(src, "[脚本] 能力配置警告：" + w);
            ok(src, "[脚本] 能力配置已重载：预设 " + cfg.preset() + "，全服关闭 " + cfg.disabled().size() + " 项");
        }
        return 1;
    }

    /**
     * 动作轴：{@code null} = 按声明全批；{@code -} = 一个都不批；否则按逗号拆的逐条勾选。
     * 能力轴（S18）：{@code null} = <b>只自动批 plain</b>（声明里有 granted/restricted 就报错，
     * 要全批必须显式 {@code all}）；{@code -} = 一个都不批；{@code all}/{@code *} = 按声明全批；
     * 否则逐条勾选。定向对抗 S18-A1。
     */
    private static int approve(CommandSourceStack src, String digest, String actionsArg, String capsArg) {
        MinecraftServer server = src.getServer();
        if (src.getEntity() instanceof ServerPlayer player) {
            try { if (!ScriptRuntimeConfig.load(server).deploymentApprovers().contains(player.getUUID()))
                return fail(src, "[脚本] 需要服主在 admin.deployment_approvers 中显式列出你的 UUID；OP 等级不代替此许可"); }
            catch (RuntimeException bad) { return fail(src, "[脚本] 管理权限配置不可用，批准被拒"); }
        }
        DeploymentData dd = DeploymentData.get(server);
        DeploymentData.Candidate candidate = dd.candidate(digest);
        if (candidate == null) {
            fail(src, "[脚本] 没有这个候选：" + digest + "（/mcphone script list 看有哪些）");
            return 0;
        }
        com.november.mcphone.core.script.pkg.AppPackage pkg;
        try {
            pkg = ServerPackageScanner.scan(server, dd).packages().get(digest);
            if (pkg == null) return fail(src, "[脚本] 待审包已不存在或未通过检查");
            ScriptHost host=ScriptHost.current();var policy=host==null?ScriptRuntimeConfig.load(server).revocations():host.revocations(pkg);
            var rule = policy.rejected(candidate.appId(), RevocationPolicy.versionOf(pkg), digest);
            if (rule != null) return fail(src, "[脚本] 被吊销的版本不能批准：" + rule.reason());
        } catch (RuntimeException badConfig) { return fail(src, "[脚本] 版本策略不可用，批准被拒：" + badConfig.getMessage()); }
        List<String> actions = parseList(actionsArg);
        List<String> caps = isAll(capsArg) ? List.copyOf(candidate.declaredCapabilities()) : parseList(capsArg);
        UUID approver = src.getEntity() instanceof ServerPlayer p ? p.getUUID() : null;
        // 覆盖前先看一眼旧的批准集：重新批准是"整条轴替换"，两轴都要重列 —— 拍在这里好回显（对完 S18-A1 的丑话）。
        Deployment before = dd.deployment(candidate.appId());
        DeploymentData.Approval ap;
        try {
            // 先完成轴校验与静态装配，再持久化批准下限，最后替换部署。
            Deployment preview=dd.preview(candidate,actions,caps,approver,System.currentTimeMillis()).deployment();
            var scope=ServerAppAssembler.assembleOne(preview,pkg);if(scope==null)throw new IllegalArgumentException("脚本静态装配失败");scope.discard();
            ScriptHost host=ScriptHost.current();
            VersionWitness witnesses=host==null?new VersionWitness(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("mcphone/version-witness.json")):host.witnesses();
            com.google.gson.JsonObject after=new com.google.gson.Gson().toJsonTree(preview).getAsJsonObject();after.addProperty("version",Long.toString(RevocationPolicy.versionOf(pkg)));
            if(!auditCommand(src,"deployment.approve",candidate.appId(),before==null?null:new com.google.gson.Gson().toJsonTree(before),after))return 0;
            witnesses.require(pkg,true);
            ap = dd.approve(candidate, actions, caps, approver, System.currentTimeMillis());
        } catch (IllegalArgumentException|java.io.IOException e) {
            // 装不进握手的部署（ADV-S2b-5）：批准了客户端也永远收不到，在这里拒掉并说清字节数
            fail(src, "[脚本] 批准被拒：" + e.getMessage());
            return 0;
        } finally {ScriptHost host=ScriptHost.current();if(host!=null)host.publishRevocations();}
        Deployment d = ap.deployment();
        // 批准之后立刻在主线程重装配（S17 Stage 2 约束 1）：成功即生效，不用重启
        boolean degraded = ScriptHost.current() == null;
        boolean live = ScriptHost.reassemble(server, d.appId());
        ScriptHost active=ScriptHost.current();
        if(active!=null){if(live&&active.store()!=null)active.store().importIncoming();active.broadcastHandshake();}
        String effect = degraded ? "（脚本后端未启用，重开服生效）"
                : live ? "（已重装配，立即生效；在飞的请求仍用旧 scope）"
                : "（⚠ 重装配失败，该 App 现在不可执行 NOT_DEPLOYED；修好后重新 approve 或重启）";
        boolean actionsShrank = before != null && !before.approvedActions().equals(d.approvedActions())
                && !d.approvedActions().containsAll(before.approvedActions());
        boolean capsShrank = before != null && !before.approvedCapabilities().equals(d.approvedCapabilities())
                && !d.approvedCapabilities().containsAll(before.approvedCapabilities());
        ok(src, "[脚本] 已批准 " + d.appId() + "（版本 " + d.approvalRevision() + "）"
                + "，批准动作 " + d.approvedActions() + " / 声明 " + d.declaredActions()
                + "；批准能力 " + d.approvedCapabilities() + " / 声明 " + d.declaredCapabilities()
                + (ap.droppedActions().isEmpty() && ap.droppedCapabilities().isEmpty() ? ""
                        : "，丢掉（不在声明里）：动作 " + ap.droppedActions() + "、能力 " + ap.droppedCapabilities())
                + (ap.replaced() ? "；覆盖了旧部署" : "")
                + (d.approvedActions().isEmpty() ? "【注意：批准动作是空的，这个 App 现在什么都不给】" : "")
                + (actionsShrank ? "【注意：动作轴每次都要重列，`-` 会清空动作轴；这次动作从 "
                        + before.approvedActions() + " 变成 " + d.approvedActions() + "】" : "")
                + (capsShrank ? "【注意：能力轴同理，这次能力从 " + before.approvedCapabilities()
                        + " 变成 " + d.approvedCapabilities() + "】" : "")
                + effect);
        return 1;
    }

    private static int remove(CommandSourceStack src, String appId) {
        if (badApp(src, appId)) return 0;
        Deployment removed = DeploymentData.get(src.getServer()).deployment(appId);
        if (removed == null) {
            fail(src, "[脚本] 没有这个部署：" + appId);
            return 0;
        }
        if(!auditCommand(src,"deployment.remove",appId,new com.google.gson.Gson().toJsonTree(removed),new com.google.gson.JsonPrimitive("撤掉部署")))return 0;
        DeploymentData.get(src.getServer()).remove(appId);
        // 撤部署之后同样重装配：表里没有这个 App 了，reassemble 会把 scope 摘掉（在飞的求值继续用旧引用）
        boolean degraded = ScriptHost.current() == null;
        boolean live = ScriptHost.reassemble(src.getServer(), appId);
        refreshClients();
        ok(src, "[脚本] 已撤掉 " + appId + " 的部署（包摘要 " + shortDigest(removed.packageDigest()) + "）"
                + (degraded ? "（脚本后端未启用，重开服生效）"
                        : live ? "（已重装配：新请求 NOT_DEPLOYED；在飞的请求仍用旧 scope）"
                        : "（⚠ 重装配未完成，该 App 现在不可执行 NOT_DEPLOYED）"));
        return 1;
    }

    private static int authorize(CommandSourceStack src, String appId, String target) {
        if (badApp(src, appId)) return 0;
        AuthorityData ad = AuthorityData.get(src.getServer());
        if ("all".equalsIgnoreCase(target)) {
            if(!auditCommand(src,"license.all",appId,authoritySnapshot(ad,appId),new com.google.gson.JsonPrimitive("所有玩家")))return 0;
            ad.licenseAll(appId);
            refreshClients();
            ok(src, "[脚本] " + appId + " 现在对所有人开放（范围 " + ad.scopeOf(appId) + "）");
            return 1;
        }
        UUID player = resolve(src.getServer(), target);
        if (player == null) {
            fail(src, "[脚本] 找不到玩家 " + target + "（离线玩家请直接给 UUID）");
            return 0;
        }
        if(!auditCommand(src,"license.specific",appId,authoritySnapshot(ad,appId),new com.google.gson.JsonPrimitive(player.toString())))return 0;
        ad.license(appId, player);
        refreshClients();
        ok(src, "[脚本] 已授权 " + player + " 使用 " + appId + "（范围 " + ad.scopeOf(appId) + "）");
        return 1;
    }

    private static int revoke(CommandSourceStack src, String appId, String target) {
        if (badApp(src, appId)) return 0;
        AuthorityData ad = AuthorityData.get(src.getServer());
        if (ad.isEveryone(appId)) {
            // 对抗组 Q6：所有人档下 revoke 是静默空操作，命令面必须拒绝并说清怎么做
            fail(src, "[脚本] " + appId + " 现在对【所有人】开放，单独撤一个人不生效；先 /mcphone script unlicenseAll "
                    + appId + " 收紧到指定名单，再撤具体玩家");
            return 0;
        }
        UUID player = resolve(src.getServer(), target);
        if (player == null) {
            fail(src, "[脚本] 找不到玩家 " + target + "（离线玩家请直接给 UUID）");
            return 0;
        }
        if(!auditCommand(src,"license.revoke",appId,authoritySnapshot(ad,appId),new com.google.gson.JsonPrimitive(player.toString())))return 0;
        if (!ad.revoke(appId, player)) {
            fail(src, "[脚本] " + player + " 本来就不在 " + appId + " 的指定名单里");
            return 0;
        }
        ok(src, "[脚本] 已撤销 " + player + " 对 " + appId + " 的授权（范围 " + ad.scopeOf(appId) + "）");
        refreshClients();
        return 1;
    }

    private static int unlicenseAll(CommandSourceStack src, String appId) {
        if (badApp(src, appId)) return 0;
        AuthorityData ad = AuthorityData.get(src.getServer());
        if(!auditCommand(src,"license.unlicenseAll",appId,authoritySnapshot(ad,appId),new com.google.gson.JsonPrimitive("取消所有人档")))return 0;
        boolean changed = ad.unlicenseAll(appId);
        refreshClients();
        ok(src, "[脚本] " + (changed ? "已取消" : "本来就不在") + " " + appId + " 的【所有人】档（指定名单未动，范围 "
                + ad.scopeOf(appId) + "）");
        return 1;
    }

    private static void refreshClients() {ScriptHost host=ScriptHost.current();if(host!=null)host.broadcastHandshake();}

    private static int clearApp(CommandSourceStack src, String appId) {
        if (badApp(src, appId)) return 0;
        if(!auditCommand(src,"license.clear",appId,authoritySnapshot(AuthorityData.get(src.getServer()),appId),new com.google.gson.JsonPrimitive("清空全部许可")))return 0;
        int n = AuthorityData.get(src.getServer()).clearApp(appId);
        refreshClients();
        ok(src, "[脚本] 已清掉 " + appId + " 的全部授权（所有人档 + 指定名单 " + n + " 人）");
        return 1;
    }

    /** appId 与候选用同一套校验：非空、≤64、无控制字符（定向对抗 C4）。 */
    private static boolean badApp(CommandSourceStack src, String appId) {
        if (!Deployment.validId(appId)) {
            fail(src, "[脚本] appId 不合法（非空、≤" + Deployment.MAX_ID_LEN + "、无控制字符）：" + appId);
            return true;
        }
        return false;
    }

    /** null = 全批；{@code -} = 一个都不批；否则按逗号拆（空 = 空集，与 approve 的 fail-closed 一致）。 */
    static List<String> parseList(String arg) {
        if (arg == null) return null;
        if (arg.equals("-")) return List.of();
        return split(arg);
    }

    /** 能力轴的显式全批写法（只作用于能力轴，避免与动作 id 撞名）。 */
    private static boolean isAll(String arg) {
        return "all".equals(arg) || "*".equals(arg);
    }

    /** 在线玩家名或 UUID；离线玩家必须给 UUID（授权表按 UUID 存）。 */
    static UUID resolve(MinecraftServer server, String target) {
        if (target.length() == 36) {
            try {
                return UUID.fromString(target);
            } catch (IllegalArgumentException ignored) {
                // 落到按名字找
            }
        }
        ServerPlayer p = server.getPlayerList().getPlayerByName(target);
        return p == null ? null : p.getUUID();
    }

    static List<String> split(String commaSeparated) {
        List<String> out = new ArrayList<>();
        for (String part : commaSeparated.split(",")) {
            String s = part.trim();
            if (!s.isEmpty() && !out.contains(s)) out.add(s);
        }
        return out;
    }

    static String shortDigest(String digest) {
        return digest.substring(0, Math.min(8, digest.length()));
    }

    private static void ok(CommandSourceStack src, String text) {
        src.sendSuccess(() -> Component.literal(text), false);
    }

    private static int pending(CommandSourceStack src) {
        ScriptHost host = ScriptHost.current(); if (host == null) return fail(src, "脚本宿主未启用");
        int[] count = {0};
        host.journal().forEach((player, box) -> box.forEach((key, entry) -> {
            if (entry.state() == IdempotencyLedger.State.UNKNOWN) {
                ok(src, "[待核对] 玩家 " + player + " 请求 " + key + "；先核对物品/流水，再 resolve"); count[0]++;
            }
        }));
        ok(src, "[脚本] 待核对 " + count[0] + " 条"); return count[0];
    }
    private static int resolve(CommandSourceStack src, String player, String key, String resolution) {
        ScriptHost host = ScriptHost.current(); if (host == null) return fail(src, "脚本宿主未启用");
        if (!resolution.equals("delivered") && !resolution.equals("retry")) return fail(src, "resolution 只能是 delivered 或 retry");
        try {
            UUID owner=UUID.fromString(player);var before=host.journal().getOrDefault(owner,java.util.Map.of()).get(key);if(before==null)return fail(src,"待核对记录不存在");
            com.google.gson.JsonObject after=new com.google.gson.JsonObject();after.addProperty("owner",owner.toString());after.addProperty("resolution",resolution);
            if(!auditCommand(src,"guard.resolve",key,new com.google.gson.Gson().toJsonTree(before),after))return 0;
            host.resolve(owner, key, resolution.equals("delivered"));
            ok(src, "[脚本] 人工核对已记录：" + resolution); return 1;
        } catch (RuntimeException e) { return fail(src, "核对未提交：" + e.getMessage()); }
    }

    private static int fail(CommandSourceStack src, String text) {
        src.sendFailure(Component.literal(text));
        return 0;
    }
    private static com.google.gson.JsonObject authoritySnapshot(AuthorityData authority,String app){com.google.gson.JsonObject out=new com.google.gson.JsonObject();out.addProperty("everyone",authority.isEveryone(app));out.add("players",new com.google.gson.Gson().toJsonTree(authority.licensedPlayers(app)));return out;}
    private static boolean auditCommand(CommandSourceStack source,String action,String app,com.google.gson.JsonElement before,com.google.gson.JsonElement after){
        try{ScriptHost host=ScriptHost.current();com.google.gson.JsonObject detail=after!=null&&after.isJsonObject()?after.getAsJsonObject().deepCopy():new com.google.gson.JsonObject();if(after!=null&&!after.isJsonObject())detail.add("change",after);if(!detail.has("version")){Deployment deployment=DeploymentData.get(source.getServer()).deployment(app);detail.addProperty("version",Long.toString(host==null||deployment==null?0:host.version(deployment.packageDigest())));}AuditLog audit=host==null?new AuditLog(source.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("mcphone/audit"),()->QuotaConfig.DEFAULT):host.quotas().audit();audit.append(source.getEntity() instanceof ServerPlayer p?p.getUUID():null,action,app,before,detail);return true;}
        catch(Exception bad){fail(source,"审计未保存，更改被拒绝："+bad.getMessage());return false;}
    }
}
