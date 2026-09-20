package com.november.mcphone.core.script.server;

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

/**
 * 脚本后端的 OP 管理命令（施工方案 §14.4）。<b>S17 Stage 1 的审批入口</b>——
 * 管理界面（§14.3 ⑤）到 Stage 2 才做，这条命令届时保留为无界面的等价入口。
 *
 * <pre>
 * /mcphone script identity                              服务器身份（客户端按它分桶）
 * /mcphone script reload                                重扫 incoming 待审目录
 * /mcphone script list                                  候选 + 已批准部署 + 授权范围
 * /mcphone script approve &lt;digest&gt; [动作列表]            批准候选；动作列表省略 = 按声明全批，
 *                                                       给了就是逐条勾选（空串 = 一个都不批）
 * /mcphone script remove &lt;app&gt;                          撤掉一个部署
 * /mcphone script authorize &lt;app&gt; all|&lt;玩家名|UUID&gt;      授权
 * /mcphone script revoke &lt;app&gt; &lt;玩家名|UUID&gt;             撤销指定玩家的授权
 * /mcphone script unlicenseAll &lt;app&gt;                    取消"所有人"档（不动指定名单）
 * </pre>
 *
 * <p>命令面照定向对抗的要求：{@code approve} 回显被丢掉的项与是否覆盖；{@code revoke} 在"所有人"档下
 * <b>拒绝</b>而不是静默空操作；{@code clearApp} 类的全清要报出清掉几人。
 */
public final class ScriptAdminCommand {

    private ScriptAdminCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mcphone")
                .then(Commands.literal("script")
                        .requires(src -> src.hasPermission(3))
                        .then(Commands.literal("identity").executes(ctx -> {
                            ok(ctx.getSource(), "[脚本] 服务器身份 " + ServerIdentity.idOf(ctx.getSource().getServer()));
                            return 1;
                        }))
                        .then(Commands.literal("reload").executes(ctx -> {
                            MinecraftServer server = ctx.getSource().getServer();
                            int n = ServerPackageScanner.scan(server, DeploymentData.get(server));
                            ok(ctx.getSource(), "[脚本] 重扫待审目录完毕，本次进队 " + n + " 条");
                            return n;
                        }))
                        .then(Commands.literal("list").executes(ctx -> list(ctx.getSource())))
                        .then(Commands.literal("approve")
                                .then(Commands.argument("digest", StringArgumentType.word())
                                        .executes(ctx -> approve(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "digest"), null))
                                        .then(Commands.argument("actions", StringArgumentType.greedyString())
                                                .executes(ctx -> approve(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "digest"),
                                                        StringArgumentType.getString(ctx, "actions"))))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("app", StringArgumentType.word())
                                        .executes(ctx -> remove(ctx.getSource(), StringArgumentType.getString(ctx, "app")))))
                        .then(Commands.literal("authorize")
                                .then(Commands.argument("app", StringArgumentType.word())
                                        .then(Commands.argument("target", StringArgumentType.word())
                                                .executes(ctx -> authorize(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "app"),
                                                        StringArgumentType.getString(ctx, "target"))))))
                        .then(Commands.literal("revoke")
                                .then(Commands.argument("app", StringArgumentType.word())
                                        .then(Commands.argument("target", StringArgumentType.word())
                                                .executes(ctx -> revoke(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "app"),
                                                        StringArgumentType.getString(ctx, "target"))))))
                        .then(Commands.literal("unlicenseAll")
                                .then(Commands.argument("app", StringArgumentType.word())
                                        .executes(ctx -> unlicenseAll(ctx.getSource(),
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

    /** {@code actionsArg} 为 null = 按声明全批；空串 = 一个都不批；否则按逗号拆的逐条勾选。 */
    private static int approve(CommandSourceStack src, String digest, String actionsArg) {
        MinecraftServer server = src.getServer();
        DeploymentData dd = DeploymentData.get(server);
        DeploymentData.Candidate candidate = dd.candidate(digest);
        if (candidate == null) {
            fail(src, "[脚本] 没有这个候选：" + digest + "（/mcphone script list 看有哪些）");
            return 0;
        }
        List<String> actions = actionsArg == null ? null : split(actionsArg);
        UUID approver = src.getEntity() instanceof ServerPlayer p ? p.getUUID() : null;
        DeploymentData.Approval ap = dd.approve(candidate, actions, null, approver, System.currentTimeMillis());
        Deployment d = ap.deployment();
        ok(src, "[脚本] 已批准 " + d.appId() + "（版本 " + d.approvalRevision() + "）"
                + "，批准动作 " + d.approvedActions() + " / 声明 " + d.declaredActions()
                + (ap.droppedActions().isEmpty() ? "" : "，丢掉（不在声明里）：" + ap.droppedActions())
                + (ap.replaced() ? "；覆盖了旧部署" : "")
                + (d.approvedActions().isEmpty() ? "【注意：批准动作是空的，这个 App 现在什么都不给】" : ""));
        if (!d.approvedCapabilities().equals(d.declaredCapabilities())) {
            ok(src, "[脚本] 批准能力 " + d.approvedCapabilities() + " / 声明 " + d.declaredCapabilities());
        }
        return 1;
    }

    private static int remove(CommandSourceStack src, String appId) {
        Deployment removed = DeploymentData.get(src.getServer()).remove(appId);
        if (removed == null) {
            fail(src, "[脚本] 没有这个部署：" + appId);
            return 0;
        }
        ok(src, "[脚本] 已撤掉 " + appId + " 的部署（包摘要 " + shortDigest(removed.packageDigest()) + "）");
        return 1;
    }

    private static int authorize(CommandSourceStack src, String appId, String target) {
        AuthorityData ad = AuthorityData.get(src.getServer());
        if ("all".equalsIgnoreCase(target)) {
            ad.licenseAll(appId);
            ok(src, "[脚本] " + appId + " 现在对所有人开放（范围 " + ad.scopeOf(appId) + "）");
            return 1;
        }
        UUID player = resolve(src.getServer(), target);
        if (player == null) {
            fail(src, "[脚本] 找不到玩家 " + target + "（离线玩家请直接给 UUID）");
            return 0;
        }
        ad.license(appId, player);
        ok(src, "[脚本] 已授权 " + player + " 使用 " + appId + "（范围 " + ad.scopeOf(appId) + "）");
        return 1;
    }

    private static int revoke(CommandSourceStack src, String appId, String target) {
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
        if (!ad.revoke(appId, player)) {
            fail(src, "[脚本] " + player + " 本来就不在 " + appId + " 的指定名单里");
            return 0;
        }
        ok(src, "[脚本] 已撤销 " + player + " 对 " + appId + " 的授权（范围 " + ad.scopeOf(appId) + "）");
        return 1;
    }

    private static int unlicenseAll(CommandSourceStack src, String appId) {
        AuthorityData ad = AuthorityData.get(src.getServer());
        boolean changed = ad.unlicenseAll(appId);
        ok(src, "[脚本] " + (changed ? "已取消" : "本来就不在") + " " + appId + " 的【所有人】档（指定名单未动，范围 "
                + ad.scopeOf(appId) + "）");
        return 1;
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

    private static void fail(CommandSourceStack src, String text) {
        src.sendFailure(Component.literal(text));
    }
}
