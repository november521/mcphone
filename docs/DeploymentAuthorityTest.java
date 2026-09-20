package com.november.mcphone.core.script.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * S17 Stage 1 的断言：部署表 / 授权表（世界级存档）与两个生产视图。
 *
 * <p>起不了服务器，所以 {@code get(MinecraftServer)} 那一层不在 docs 里跑；这里测表本身、NBT 往返、
 * "声明 / 批准 / 许可"三层判定，以及定向对抗（Q2/Q3/Q6/Q7）要求的口径。
 */
public class DeploymentAuthorityTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!Objects.equals(actual, expected)) failures.add(what + "  期望 " + expected + "，实际 " + actual);
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures.add(what);
    }

    static final String APP = "example:shop";
    static final String PKG = "a".repeat(64);
    static final String PKG2 = "c".repeat(64);
    static final String FRONT = "b".repeat(64);
    static final UUID P1 = UUID.nameUUIDFromBytes("p1".getBytes());
    static final UUID P2 = UUID.nameUUIDFromBytes("p2".getBytes());

    static DeploymentData.Candidate candidate() {
        return new DeploymentData.Candidate(APP, PKG, FRONT, 1L,
                List.of("buy", "sell", "grant"), List.of("currency.mint"));
    }

    /** 候选 → 批准 → 视图：声明 vs 批准 vs 许可三层各判各的。 */
    static void deploymentAndAuthority() {
        DeploymentData dd = new DeploymentData();
        AuthorityData ad = new AuthorityData();

        check(dd.putCandidate(candidate()), "候选进队列");
        check(!dd.putCandidate(candidate()), "同一摘要重复放不标脏（幂等）");
        eq(dd.candidates().size(), 1, "候选表一条");
        check(dd.deployment(APP) == null, "候选阶段还没有部署 —— 候选不给任何特权");

        // OP 只批 3 个动作里的 2 个：buy、sell；grant 声明了但没批；另有一个根本没声明的
        DeploymentData.Approval ap = dd.approve(dd.candidate(PKG), List.of("buy", "sell", "not-declared"),
                List.of(), P1, 123L);
        Deployment d = ap.deployment();
        eq(d.approvedActions(), List.of("buy", "sell"), "批准集合是声明集合的子集，多余的丢掉");
        eq(ap.droppedActions(), List.of("not-declared"), "丢掉的那些要回显（不静默吞）");
        eq(d.declaredActions(), List.of("buy", "sell", "grant"), "声明集合原样留着");
        eq(d.approvalRevision(), 1L, "首次批准：批准轴 = 1");
        check(d.approves("buy") && d.approves("sell"), "批过的动作");
        check(!d.approves("grant"), "声明了但没批的动作不算批过");
        eq(dd.candidates().size(), 0, "批准后候选出队");
        eq(dd.deployment(APP).appId(), APP, "部署表里能按 appId 查到");

        DeploymentView view = new ServerDeployments(dd);
        check(view.deployed(APP), "有已批准部署");
        check(view.hasAction(APP, "buy"), "声明的动作 → 过部署闸");
        check(view.hasAction(APP, "grant"), "声明了但没批的动作也过部署闸（由授权层拒）");
        check(!view.hasAction(APP, "forged"), "没声明的动作 → NOT_DEPLOYED");
        eq(view.deployRev(APP), PKG, "部署版本 = 包摘要（包轴）");
        check(!view.deployed("example:other"), "别的 App 没部署");

        AuthorityView auth = new ServerAuthority(dd, ad);
        check(!auth.allows(P1, APP, "buy"), "部署批了、但还没给这个玩家许可 → 拒");
        ad.license(APP, P1);
        check(auth.allows(P1, APP, "buy"), "指定玩家 + 已批动作 → 放行");
        check(!auth.allows(P2, APP, "buy"), "没在名单里的玩家 → 拒");
        check(!auth.allows(P1, APP, "grant"), "已许可、但动作没批 → 拒（NOT_AUTHORIZED 那一路）");
        check(!auth.allows(P1, APP, "forged"), "没声明的动作 → 拒");

        ad.licenseAll(APP);
        check(auth.allows(P2, APP, "sell"), "所有人档：谁都可以");
        eq(ad.scopeOf(APP), "所有人；另指定 1 人", "范围同时报两档，不拿所有人盖住指定名单");
        check(ad.revoke(APP, P1), "所有人档下也允许从指定名单里撤（它不改变放行结果）");
        check(auth.allows(P1, APP, "sell"), "他仍在所有人档里，所以照旧放行");
        check(ad.unlicenseAll(APP), "取消所有人档");
        check(!ad.isLicensed(APP, P1), "上一步已把指定名单撤空 → 取消所有人档后不再放行");
        ad.license(APP, P1);
        check(ad.isLicensed(APP, P1), "重新指定 P1");
        check(!ad.unlicenseAll(APP), "已经不在所有人档：再取消返回 false（幂等）");
        check(ad.isLicensed(APP, P1), "unlicenseAll 不顺手清指定名单（与 licenseAll 对称）");
        eq(ad.playerCount(APP), 1, "指定名单一条");
        eq(ad.clearApp(APP), 1, "clearApp 全清并报出清掉几人");
        check(!ad.isLicensed(APP, P1) && !ad.isEveryone(APP), "全清之后什么都不剩");

        // 撤部署：动作与版本一起没了
        check(dd.remove(APP) != null, "撤部署");
        check(!view.deployed(APP) && view.deployRev(APP) == null, "撤了之后 deployed=false、rev=null");
        check(!auth.allows(P1, APP, "buy"), "撤了之后授权也不放行");
    }

    /** Q3：批准集合的默认值必须 fail-closed；null 才是显式全批。 */
    static void approvalDefaultsAreFailClosed() {
        DeploymentData none = new DeploymentData();
        none.putCandidate(candidate());
        Deployment dNone = none.approve(none.candidate(PKG), List.of(), List.of(), P1, 1L).deployment();
        eq(dNone.approvedActions(), List.of(), "空列表 = 一个都不批（fail-closed）");

        DeploymentData all = new DeploymentData();
        all.putCandidate(candidate());
        Deployment dAll = all.approve(all.candidate(PKG), null, null, P1, 1L).deployment();
        eq(dAll.approvedActions(), List.of("buy", "sell", "grant"), "null = 显式按声明全批");
        eq(dAll.approvedCapabilities(), List.of("currency.mint"), "能力同理 null = 全批");

        // 换包重新批准：批准轴单调 +1，且回显"这是覆盖"
        DeploymentData.Approval second = all.approve(
                new DeploymentData.Candidate(APP, PKG2, FRONT, 2L, List.of("buy"), List.of()),
                List.of("buy"), List.of(), P2, 200L);
        eq(second.deployment().approvalRevision(), 2L, "同一 App 再批准：批准轴 +1");
        check(second.replaced(), "换包是覆盖旧部署（命令面要回显）");
        eq(second.deployment().approver(), P2, "批准人换成新的");
        eq(second.deployment().revision(), PKG2, "包轴跟着新包走");
    }

    /** Q4：上限在入队就挡住；候选条数按 LRU 淘汰。 */
    static void limitsAndEviction() {
        DeploymentData dd = new DeploymentData();
        try {
            dd.putCandidate(new DeploymentData.Candidate(APP, "short", FRONT, 1L, List.of(), List.of()));
            check(false, "坏摘要应当拒");
        } catch (IllegalArgumentException e) {
            check(true, "坏摘要入队就拒：" + e.getMessage());
        }
        try {
            dd.putCandidate(new DeploymentData.Candidate(APP, PKG, FRONT, 1L,
                    java.util.Collections.nCopies(40, "a"), List.of()));
            check(false, "超 32 个动作应当拒");
        } catch (IllegalArgumentException e) {
            check(true, "声明动作超限入队就拒");
        }

        for (int i = 0; i < DeploymentData.MAX_CANDIDATES + 5; i++) {
            String digest = String.format("%064x", i);
            dd.putCandidate(new DeploymentData.Candidate(APP, digest, FRONT, i, List.of("a"), List.of()));
        }
        eq(dd.candidates().size(), DeploymentData.MAX_CANDIDATES, "候选条数封顶（LRU 淘汰最早的）");
    }

    /** Q7：坏档不许让 load 抛，也不许让请求路径抛。 */
    static void malformedDataIsRejectedNotThrown() {
        DeploymentData dd = new DeploymentData();
        dd.putCandidate(candidate());
        Deployment d = dd.approve(dd.candidate(PKG), null, null, P1, 7L).deployment();

        // 批准人坏了：不丢整条部署，只丢批准人
        CompoundTag dt = d.toTag();
        dt.putString("approver", "not-a-uuid");
        Deployment tolerated = Deployment.fromTag(dt);
        check(tolerated != null, "批准人坏了不丢整条部署");
        check(tolerated.approver() == null, "批准人按无处理");

        // 授权表里的坏 UUID：load 不抛，坏项跳过
        CompoundTag bad = new CompoundTag();
        ListTag rows = new ListTag();
        CompoundTag row = new CompoundTag();
        row.putString("appId", APP);
        ListTag ids = new ListTag();
        ids.add(StringTag.valueOf("not-a-uuid"));
        row.put("uuids", ids);
        rows.add(row);
        bad.put("players", rows);
        AuthorityData loaded = AuthorityData.load(bad);
        check(!loaded.isLicensed(APP, P1), "坏 UUID 被跳过，不放行");

        // appId 都不合法的部署条目：load 跳过
        CompoundTag junk = new CompoundTag();
        ListTag deployRows = new ListTag();
        deployRows.add(new CompoundTag());
        junk.put("deployments", deployRows);
        eq(DeploymentData.load(junk).deployments().size(), 0, "空 appId 的部署条目被跳过");
    }

    /** 两张表都要经得起存档往返。 */
    static void persistenceRoundTrip() {
        DeploymentData dd = new DeploymentData();
        dd.putCandidate(candidate());
        AuthorityData ad = new AuthorityData();
        ad.license(APP, P1);

        DeploymentData dd2 = DeploymentData.load(dd.write(new CompoundTag()));
        eq(dd2.candidates().size(), 1, "候选往返还在");
        eq(dd2.candidate(PKG).declaredActions(), List.of("buy", "sell", "grant"), "声明集合往返不变");
        Deployment d2 = dd2.approve(dd2.candidate(PKG), List.of("buy"), List.of(), P1, 7L).deployment();
        eq(d2.approvedActions(), List.of("buy"), "批准集合往返后仍是子集");

        DeploymentData dd3 = DeploymentData.load(dd2.write(new CompoundTag()));
        Deployment d3 = dd3.deployment(APP);
        check(d3 != null && d3.approves("buy") && !d3.approves("sell"), "部署往返后批准集合不丢");
        check(d3.approver().equals(P1) && d3.approvedAt() == 7L && d3.approvalRevision() == 1L,
                "批准人、时刻、批准轴往返");

        AuthorityData ad2 = AuthorityData.load(ad.write(new CompoundTag()));
        check(new ServerAuthority(dd3, ad2).allows(P1, APP, "buy"), "许可往返后仍放行");
        check(!ad2.isLicensed(APP, P2), "没授过的不被放行");
    }

    /** 扫描器与命令面的纯函数：前端摘要的谓词、manifest 扩展字段解析、动作列表拆分。 */
    static void scannerAndCommandHelpers() {
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        entries.put("app.vue", "UI".getBytes());
        entries.put("lang/zh_cn.json", "{}".getBytes());
        entries.put("server.js", "backend".getBytes());
        entries.put("server/util.js", "util".getBytes());
        Map<String, byte[]> front = new java.util.LinkedHashMap<>();
        front.put("app.vue", "UI".getBytes());
        front.put("lang/zh_cn.json", "{}".getBytes());
        eq(ServerPackageScanner.frontendDigest(entries), com.november.mcphone.core.script.pkg.PackageDigest.of(front),
                "前端摘要排除 server.js 与 server/**");
        check(!ServerPackageScanner.frontendDigest(entries)
                        .equals(com.november.mcphone.core.script.pkg.PackageDigest.of(entries)),
                "前端摘要与整包摘要不同（谓词不同）");
        // 客户端回显与"界面被改过"的对比走同一份谓词（FrontendDigest），两份实现迟早对不上
        eq(com.november.mcphone.core.script.pkg.FrontendDigest.of(entries),
                ServerPackageScanner.frontendDigest(entries), "FrontendDigest 与服务端谓词同一份实现");
        check(com.november.mcphone.core.script.pkg.FrontendDigest.isServerSide("server.js")
                        && com.november.mcphone.core.script.pkg.FrontendDigest.isServerSide("server/util.js")
                        && !com.november.mcphone.core.script.pkg.FrontendDigest.isServerSide("app.vue"),
                "后端谓词：server.js 与 server/** 是，app.vue 不是");

        var root = com.google.gson.JsonParser.parseString(
                "{\"actions\":[\"buy\",\"sell\"],\"capabilities\":[]}").getAsJsonObject();
        eq(ServerPackageScanner.stringList(root, "actions"), List.of("buy", "sell"), "manifest.actions 解析");
        eq(ServerPackageScanner.stringList(root, "capabilities"), List.of(), "空数组");
        eq(ServerPackageScanner.stringList(root, "missing"), List.of(), "缺字段 = 空");
        boolean threw = false;
        try {
            ServerPackageScanner.stringList(
                    com.google.gson.JsonParser.parseString("{\"actions\":[1]}").getAsJsonObject(), "actions");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "非字符串元素被拒");

        eq(ScriptAdminCommand.split(" buy , sell ,, buy "), List.of("buy", "sell"), "动作列表按逗号拆、去空去重");
        eq(ScriptAdminCommand.split(""), List.of(), "空串 = 空列表（一个都不批，与 approve 的语义一致）");
        eq(ScriptAdminCommand.shortDigest("abcdef123456"), "abcdef12", "短摘要取 8 位");
        eq(ScriptAdminCommand.parseList(null), null, "approve 不传参数 = 按声明全批");
        eq(ScriptAdminCommand.parseList("-"), List.of(), "approve 传 - = 一个都不批（显式 fail-closed）");
        eq(ScriptAdminCommand.parseList("buy,sell"), List.of("buy", "sell"), "逗号拆的逐条勾选");
    }

    /** S18：能力名必须对得上能力目录 —— 目录外/重复在入队就拒（fail-closed）。 */
    static void capabilityNamesAreValidated() {
        boolean threw = false;
        try {
            new DeploymentData.Candidate(APP, PKG, FRONT, 1L, List.of("buy"), List.of("economy.pay"));
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "目录外的能力名在候选构造期就被拒");

        threw = false;
        try {
            new DeploymentData.Candidate(APP, PKG, FRONT, 1L, List.of("buy"), List.of("item.give", "item.give"));
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "重复声明同一能力被拒");

        // 参数化的模板名是认得的（command.template:<id>）
        new DeploymentData.Candidate(APP, PKG, FRONT, 1L, List.of("buy"),
                List.of("item.give", "command.template:daily"));
        checks++;
        check(CapabilityCatalog.knownDeclared("command.template:daily"), "参数化模板名在目录里");
    }

    /** C8/#4：命令解析要吃得下真实的 appId（`example:shop`）与含点的动作/能力名。 */
    static void commandParsesRealIds() {
        var dispatcher = new com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack>();
        ScriptAdminCommand.register(dispatcher);
        net.minecraft.commands.CommandSourceStack src = new net.minecraft.commands.CommandSourceStack(
                net.minecraft.commands.CommandSource.NULL, net.minecraft.world.phys.Vec3.ZERO,
                net.minecraft.world.phys.Vec2.ZERO, null, 3, "t",
                net.minecraft.network.chat.Component.literal("t"), null, null);
        for (String cmd : new String[]{
                "mcphone script identity",
                "mcphone script remove example:shop",
                "mcphone script approve " + PKG + " buy,sell currency.mint",
                "mcphone script approve " + PKG + " - -",
                "mcphone script authorize example:shop all",
                "mcphone script revoke example:shop 00000000-0000-0000-0000-000000000001",
                "mcphone script unlicenseAll example:shop",
                "mcphone script clearApp example:shop"}) {
            boolean parsed;
            try {
                dispatcher.parse(cmd, src);
                parsed = true;
            } catch (Exception e) {
                failures.add("命令解析失败（" + e.getMessage() + "）：" + cmd);
                checks++;
                parsed = false;
            }
            check(parsed, "命令能解析：" + cmd);
        }
    }

    /**
     * ADV-S2b-5：装不下握手的部署在<b>批准期</b>就拒，并报出字节数。
     *
     * <p>32 条动作本身合法（≤ MAX_ACTIONS），但每条 64 个中文数字混排的名字会让编码超
     * {@code DATA_MAX} —— 这种部署推不到客户端，批准它只会制造"本服没部署"的假象。
     */
    static void oversizedApprovalIsRejected() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < Deployment.MAX_ACTIONS; i++) {
            many.add("动".repeat(60) + String.format("%04d", i));   // 64 字符，编码约 184 字节
        }
        String bigDigest = "e".repeat(64);
        DeploymentData.Candidate big = new DeploymentData.Candidate("example:big", bigDigest, FRONT, 1L,
                many, List.of());
        DeploymentData dd = new DeploymentData();
        check(dd.putCandidate(big), "超长动作集合的候选进队（单条合法，只是装不下握手）");

        boolean threw = false;
        String message = "";
        try {
            dd.approve(big, null, List.of(), P1, 1L);
        } catch (IllegalArgumentException e) {
            threw = true;
            message = String.valueOf(e.getMessage());
        }
        check(threw, "批准被拒：这条部署超出单条握手上限");
        check(message.contains(String.valueOf(com.november.mcphone.core.script.net.ScriptProtocol.DATA_MAX)),
                "错误里带字节数上限（实际文案：" + message + "）");
        check(dd.deployment("example:big") == null, "被拒之后没有写进部署表（无假象）");
        check(dd.candidate(bigDigest) != null, "候选仍在待审队列里，缩短动作名后可以再批");

        // 同一份表里的正常部署照常能批，并且编码得下
        DeploymentData ok = new DeploymentData();
        ok.putCandidate(candidate());
        Deployment small = ok.approve(ok.candidate(PKG), List.of("buy"), List.of(), P1, 1L).deployment();
        check(com.november.mcphone.core.script.net.Handshake.wireSize(small) <=
                        com.november.mcphone.core.script.net.ScriptProtocol.DATA_MAX,
                "普通部署的握手表示在 4096 以内");

        // 三处 id 上限必须是同一个数（ADV-S2b-3 的教训：两个 64 的含义不同，改一处要三处一起改）
        check(com.november.mcphone.core.script.pkg.Manifest.MAX_ID == Deployment.MAX_ID_LEN
                        && Deployment.MAX_ID_LEN == com.november.mcphone.core.script.net.ScriptProtocol.ID_MAX,
                "Manifest.MAX_ID / Deployment.MAX_ID_LEN / ScriptProtocol.ID_MAX 三处一致");
    }

    public static void main(String[] args) {
        deploymentAndAuthority();
        approvalDefaultsAreFailClosed();
        limitsAndEviction();
        malformedDataIsRejectedNotThrown();
        persistenceRoundTrip();
        scannerAndCommandHelpers();
        capabilityNamesAreValidated();
        commandParsesRealIds();
        oversizedApprovalIsRejected();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
