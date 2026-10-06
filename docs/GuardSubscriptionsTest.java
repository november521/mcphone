package com.november.mcphone.core.script.server;

import com.november.mcphone.core.script.net.ScriptPush;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

public final class GuardSubscriptionsTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        AtomicLong nanos = new AtomicLong(-9_000_000_000L);
        GuardSubscriptions subs = new GuardSubscriptions(nanos::get);
        List<ScriptPush> pushes = new ArrayList<>();
        Set<UUID> online = new HashSet<>();
        for (int i = 0; i < 500; i++) online.add(new UUID(0, i));
        // 500 人在线，其中 20 人正在看 App；物理发包数与逻辑广播数分别统计。
        for (int i = 0; i < 20; i++) check(subs.watch(new UUID(0, i), "test:gift", 0), "订阅成功");
        subs.flush(0, online::contains, (p, a) -> new byte[]{1}, (p, push) -> pushes.add(push));
        for (int revision = 1; revision <= 20; revision++)
            subs.flush(revision, online::contains, (p, a) -> new byte[]{1}, (p, push) -> pushes.add(push));
        check(pushes.isEmpty(), "同一秒内 20 次变化尚不推送");
        nanos.addAndGet(1_000_000_000L);
        subs.flush(20, online::contains, (p, a) -> new byte[]{1}, (p, push) -> pushes.add(push));
        check(pushes.size() == 20, "只给 20 个订阅者各一条最新快照");
        check(pushes.stream().allMatch(p -> p.revision() == 20 && p.isHost()), "所有推送带最新 revision 且为宿主消息");
        subs.unwatch(new UUID(0, 0), "test:gift"); online.remove(new UUID(0, 1));
        nanos.addAndGet(1_000_000_000L);
        subs.flush(21, online::contains, (p, a) -> new byte[]{1}, (p, push) -> pushes.add(push));
        check(pushes.size() == 38, "关页与离线两人不再收包");
        UUID player = new UUID(1, 1);
        for (int i = 0; i < 4; i++) check(subs.watch(player, "test:app" + i, 0), "最多四个页面订阅");
        check(!subs.watch(player, "test:overflow", 0), "第五个拒绝");
        subs.forget(player); check(subs.watch(player, "test:new", 0), "断线回收全部订阅槽");
        nanos.addAndGet(1_000_000_000L);
        int before = pushes.size();
        subs.flush(22, online::contains, (p, a) -> null, (p, push) -> pushes.add(push));
        check(pushes.size() == before, "撤销授权的快照不可用时取消订阅，不能继续泄漏");
        System.out.println("GuardSubscriptionsTest: " + checks + " checks passed; first burst physical packets=20");
    }
}
