package com.november.mcphone.core.script.server;

import net.minecraft.nbt.CompoundTag;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * S17 Stage 1 的断言：部署表 / 授权表（世界级存档）与两个生产视图。
 *
 * <p>起不了服务器，所以 {@code get(MinecraftServer)} 那一层不在 docs 里跑（由三平台开服接线覆盖）；
 * 这里测的是表本身、NBT 往返、以及"声明 / 批准 / 许可"三层判定 —— 也就是攻击剧本第 4/5 行的服务端那一半。
 */
public class DeploymentAuthorityTest {

    static int checks = 0;
    static final java.util.List<String> failures = new java.util.ArrayList<>();

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!Objects.equals(actual, expected)) failures.add(what + "  期望 " + expected + "，实际 " + actual);
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures.add(what);
    }

    static final String APP = "example:shop";
    static final UUID P1 = UUID.nameUUIDFromBytes("p1".getBytes());
    static final UUID P2 = UUID.nameUUIDFromBytes("p2".getBytes());

    static DeploymentData.Candidate candidate() {
        return new DeploymentData.Candidate(APP, "d1gest", "f1front",
                List.of("buy", "sell", "grant"), List.of("economy.pay"));
    }

    /** 候选 → 批准 → 视图：声明 vs 批准 vs 许可三层各判各的。 */
    static void deploymentAndAuthority() {
        DeploymentData dd = new DeploymentData();
        AuthorityData ad = new AuthorityData();

        check(dd.putCandidate(candidate()), "候选进队列");
        check(!dd.putCandidate(candidate()), "同一摘要重复放不标脏（幂等）");
        eq(dd.candidates().size(), 1, "候选表一条");
        check(dd.deployment(APP) == null, "候选阶段还没有部署 —— 候选不给任何特权");

        // OP 只批 3 个动作里的 2 个：buy、sell；grant 声明了但没批
        Deployment d = dd.approve(dd.candidate("d1gest"), List.of("buy", "sell", "not-declared"),
                List.of(), P1, 123L);
        eq(d.approvedActions(), List.of("buy", "sell"), "批准集合是声明集合的子集，多余的丢掉");
        eq(d.declaredActions(), List.of("buy", "sell", "grant"), "声明集合原样留着");
        check(d.approves("buy") && d.approves("sell"), "批过的动作");
        check(!d.approves("grant"), "声明了但没批的动作不算批过");
        check(!d.approves("not-declared"), "凭空写的动作更不算");
        eq(dd.candidates().size(), 0, "批准后候选出队");
        eq(dd.deployment(APP).appId(), APP, "部署表里能按 appId 查到");

        DeploymentView view = new ServerDeployments(dd);
        check(view.deployed(APP), "有已批准部署");
        check(view.hasAction(APP, "buy"), "声明的动作 → 过部署闸");
        check(view.hasAction(APP, "grant"), "声明了但没批的动作也过部署闸（由授权层拒）");
        check(!view.hasAction(APP, "forged"), "没声明的动作 → NOT_DEPLOYED");
        eq(view.deployRev(APP), "d1gest", "部署版本 = 包摘要");
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
        ad.revoke(APP, P1);
        check(auth.allows(P1, APP, "sell"), "所有人档下单独撤一个玩家不生效（他仍在所有人档里）");
        ad.unlicenseAll(APP);
        check(!auth.allows(P2, APP, "sell"), "撤掉所有人档之后不再放行");

        // 撤部署：动作与版本一起没了
        check(dd.remove(APP) != null, "撤部署");
        check(!view.deployed(APP) && view.deployRev(APP) == null, "撤了之后 deployed=false、rev=null");
        check(!auth.allows(P1, APP, "buy"), "撤了之后授权也不放行");
    }

    /** 两张表都要经得起存档往返。 */
    static void persistenceRoundTrip() {
        DeploymentData dd = new DeploymentData();
        dd.putCandidate(candidate());
        AuthorityData ad = new AuthorityData();
        ad.license(APP, P1);

        DeploymentData dd2 = DeploymentData.load(dd.write(new CompoundTag()));
        eq(dd2.candidates().size(), 1, "候选往返还在");
        eq(dd2.candidate("d1gest").declaredActions(), List.of("buy", "sell", "grant"), "声明集合往返不变");
        Deployment d2 = dd2.approve(dd2.candidate("d1gest"), List.of("buy"), List.of(), P1, 7L);
        eq(d2.approvedActions(), List.of("buy"), "批准集合往返后仍是子集");

        DeploymentData dd3 = DeploymentData.load(dd2.write(new CompoundTag()));
        Deployment d3 = dd3.deployment(APP);
        check(d3 != null && d3.approves("buy") && !d3.approves("sell"), "部署往返后批准集合不丢");
        check(d3.approver().equals(P1) && d3.approvedAt() == 7L, "批准人与时刻往返");

        AuthorityData ad2 = AuthorityData.load(ad.write(new CompoundTag()));
        check(new ServerAuthority(dd3, ad2).allows(P1, APP, "buy"), "许可往返后仍放行");
        check(!ad2.isLicensed(APP, P2), "没授过的不被放行");
    }

    public static void main(String[] args) {
        deploymentAndAuthority();
        persistenceRoundTrip();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
