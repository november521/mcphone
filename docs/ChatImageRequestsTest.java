package com.november.mcphone.feature.chat.client;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/** 不启动窗口，模拟会话切换、放大图、限流与重试。 */
public final class ChatImageRequestsTest {
    private static int checks;
    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        var requests = new ChatImageRequests();
        UUID a = new UUID(0, 1), b = new UUID(0, 2);
        UUID old = new UUID(1, 1), current = new UUID(1, 2), viewer = new UUID(1, 3);
        var attempts = new HashMap<UUID, Long>();
        attempts.put(old, 0L);
        attempts.put(current, 0L);
        attempts.put(viewer, 0L);
        requests.beginFrame(a);
        requests.visible(old);
        check(requests.batch(a, 1000, 4, attempts::get).equals(List.of(old)), "会话 A 请求自己的可见图片");
        attempts.put(old, 1000L);
        requests.beginFrame(b);
        requests.visible(current);
        requests.visible(current);
        check(requests.batch(b, 1200, 4, attempts::get).isEmpty(), "切换会话仍遵守全局限流");
        check(requests.batch(a, 1600, 4, attempts::get).isEmpty(), "旧对端不能消费新帧集合");
        var first = requests.batch(b, 1600, 4, attempts::get);
        check(first.equals(List.of(current)), "旧会话待取图不混入 B，重复可见 ID 去重");
        attempts.put(current, 1600L);
        requests.beginFrame(b);
        requests.visible(current);
        requests.visible(viewer);
        check(requests.batch(b, 2200, 4, attempts::get).equals(List.of(viewer)), "查看器登记的图片也可请求，已请求图不立即重试");
        attempts.put(viewer, 2200L);
        check(requests.batch(b, 7599, 4, attempts::get).isEmpty(), "未满六秒不重试");
        check(requests.batch(b, 7600, 4, attempts::get).equals(List.of(current)), "满六秒只重试仍可见的图");
        requests.beginFrame(b);
        requests.visible(viewer);
        attempts.remove(viewer);
        check(requests.batch(b, 9000, 4, attempts::get).isEmpty(), "READY、GONE、BROKEN 均不进入请求");
        attempts.put(viewer, 0L);
        check(requests.batch(b, 9000, 4, attempts::get).equals(List.of(viewer)), "空批次不占用限流额度");
        requests.clear();
        check(requests.batch(b, 10000, 4, attempts::get).isEmpty(), "退出世界清掉对端与可见集合");
        requests.beginFrame(a);
        requests.visible(old);
        requests.visible(current);
        check(requests.batch(a, 10000, 1, attempts::get).equals(List.of(old)), "按可见顺序分批，不超过协议上限");
        check(requests.batch(null, 11000, 4, attempts::get).isEmpty(), "没有对端不能请求");
        System.out.println("全部通过：" + checks + " 条断言");
    }
}
