package com.november.mcphone.core.script.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.november.mcphone.core.script.net.ScriptRpcResult;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 客户端脚本诊断命令（S17 Stage 2）：看握手状态、按指定字段发一条原始 {@code ScriptRpc}。
 *
 * <pre>
 * /mcphoneclient handshake                                   看 serverId / epoch / 部署表
 * /mcphoneclient rpc &lt;app&gt; &lt;动作&gt; [deployRev] [frontendDigest]  发一条原始调用并打印返回码
 * </pre>
 *
 * <h2>它是验收与对抗用的，不是给作者/玩家的门面</h2>
 *
 * {@code deployRev}/{@code frontendDigest} 传 {@code -} 表示空串；省略则用握手下发的那份。
 * 这条命令存在的理由正是<b>生产路径不许伪造</b>（{@link ScriptCall#call} 的字段由宿主回填），
 * 而剧本 3/4/5 要的是"伪造这些字段会怎样" —— 只能有一个绕过回填的口子，且它必须是显式的、
 * 看得见的调试命令，而不是藏在 App 能调的 API 里。
 *
 * <p>平台无关的部分在这里，反馈方式（Forge/NeoForge 的 {@code sendSuccess} 与 Fabric 的
 * {@code sendFeedback}）由各平台注册时传进来 —— 源码类型不同，命令树是同一棵。
 */
public final class ScriptClientCommand {

    /** 各平台把"往聊天栏说一句"装进来。 */
    @FunctionalInterface
    public interface Feedback<S> {
        void send(S source, Component message);
    }

    private ScriptClientCommand() {
    }

    public static <S> void register(CommandDispatcher<S> dispatcher, Feedback<S> feedback) {
        // 用 Brigadier 自己的泛型构造器，不用 net.minecraft.commands.Commands —— 后者的
        // literal() 返回的是写死 CommandSourceStack 的那一支，Fabric 的源类型挂不上
        dispatcher.register(LiteralArgumentBuilder.<S>literal("mcphoneclient")
                .then(LiteralArgumentBuilder.<S>literal("handshake")
                        .executes(ctx -> handshake(ctx.getSource(), feedback)))
                .then(LiteralArgumentBuilder.<S>literal("rpc")
                        .then(RequiredArgumentBuilder.<S, String>argument("app", StringArgumentType.string())
                                .then(RequiredArgumentBuilder.<S, String>argument("action", StringArgumentType.string())
                                        .executes(ctx -> rpc(ctx, feedback, null, null))
                                        .then(RequiredArgumentBuilder.<S, String>argument("deployRev", StringArgumentType.string())
                                                .executes(ctx -> rpc(ctx, feedback, str(ctx, "deployRev"), null))
                                                .then(RequiredArgumentBuilder.<S, String>argument("frontendDigest", StringArgumentType.string())
                                                        .executes(ctx -> rpc(ctx, feedback,
                                                                str(ctx, "deployRev"), str(ctx, "frontendDigest")))))))));
    }

    private static <S> String str(CommandContext<S> ctx, String name) {
        return StringArgumentType.getString(ctx, name);
    }

    private static <S> int handshake(S source, Feedback<S> feedback) {
        feedback.send(source, Component.literal("[脚本] 握手 "
                + (ClientHandshake.complete() ? "完整" : "不完整/未收到")
                + "  serverId=" + ClientHandshake.serverId()
                + "  serverName='" + ClientHandshake.serverName() + "'"
                + "  epoch=" + ClientHandshake.connectionEpoch()
                + "  批次修订=" + ClientHandshake.revision()));

        Map<String, ClientHandshake.Entry> deployments = ClientHandshake.deployments();
        if (deployments.isEmpty()) {
            feedback.send(source, Component.literal("[脚本] 本服没有已批准部署（或握手还没收齐）"));
            return 0;
        }
        for (Map.Entry<String, ClientHandshake.Entry> e : deployments.entrySet()) {
            ClientHandshake.Entry d = e.getValue();
            feedback.send(source, Component.literal("[脚本]   " + e.getKey()
                    + "  rev=" + shortDigest(d.deployRev())
                    + "  front=" + shortDigest(d.frontendDigest())
                    + "  版本=" + d.approvalRevision()
                    + "  批准于=" + date(d.approvedAt())
                    + "  actions=" + d.actions()));
        }
        return deployments.size();
    }

    private static <S> int rpc(CommandContext<S> ctx, Feedback<S> feedback,
                               String deployRevArg, String frontendDigestArg) {
        S source = ctx.getSource();
        String app = str(ctx, "app");
        String action = str(ctx, "action");
        String deployRev = "-".equals(deployRevArg) ? "" : deployRevArg;
        String frontendDigest = "-".equals(frontendDigestArg) ? "" : frontendDigestArg;

        if (deployRev == null || frontendDigest == null) {
            ClientHandshake.Entry e = ClientHandshake.deployment(app);
            if (deployRev == null) deployRev = e == null ? "" : e.deployRev();
            if (frontendDigest == null) frontendDigest = e == null ? "" : e.frontendDigest();
        }

        String revShown = shortDigest(deployRev);
        String frontShown = shortDigest(frontendDigest);
        long requestId = ScriptCall.sendRaw(app, action, deployRev, frontendDigest,
                result -> feedback.send(source, describe(result)));
        feedback.send(source, Component.literal("[脚本] 已发 #" + requestId
                + "  " + app + " / " + action
                + "  rev=" + revShown + "  front=" + frontShown
                + "  epoch=" + ClientHandshake.connectionEpoch()));
        return 1;
    }

    private static Component describe(ScriptRpcResult result) {
        return Component.literal("[脚本] #" + result.requestId() + " → " + result.code()
                + (result.messageKey() == null || result.messageKey().isEmpty()
                        ? "" : "  " + result.messageKey()));
    }

    private static String shortDigest(String digest) {
        if (digest == null || digest.isEmpty()) return "（空）";
        return digest.length() <= 12 ? digest : digest.substring(0, 12) + "…";
    }

    private static String date(long epochMillis) {
        if (epochMillis <= 0L) return "未记录";
        return DateTimeFormatter.ISO_LOCAL_DATE
                .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }
}
