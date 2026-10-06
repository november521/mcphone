package com.november.mcphone.core.script.server;

import com.google.gson.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 不访问真实公网：去重、分片、默认关闭、速率、过期与撤销采用可控传输。 */
public final class FetchCacheTest {
    private static int checks;
    private static void check(boolean ok, String message) { checks++; if (!ok) throw new AssertionError(message); }
    private static String status(String json) { return JsonParser.parseString(json).getAsJsonObject().get("status").getAsString(); }
    public static void main(String[] args) throws Exception {
        AtomicLong time = new AtomicLong(100000); AtomicInteger calls = new AtomicInteger();
        var policy = new AtomicReference<>(FetchCache.Policy.DEFAULT);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (FetchCache cache = new FetchCache(policy::get, time::get, (url, p) -> {
            calls.incrementAndGet(); entered.countDown(); release.await(10, TimeUnit.SECONDS);
            return new SafeFetch.Response(SafeFetch.Type.TEXT, new byte[5000]);
        })) {
            check(status(cache.read("https://example.com/a", 0)).equals("DISABLED"), "服务端默认关闭");
            policy.set(new FetchCache.Policy(true, Set.of("example.com"), 262144, 1000, true));
            check(status(cache.read("https://example.com/a", 0)).equals("PENDING"), "首次非阻塞");
            check(entered.await(5, TimeUnit.SECONDS), "独立网络任务运行");
            for (int i = 0; i < 100; i++) check(status(cache.read("https://example.com/a", 0)).equals("PENDING"), "同时读共用一个任务");
            check(calls.get() == 1, "一百个读者只出网一次"); release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); String ready;
            do { ready = cache.read("https://example.com/a", 0); if (status(ready).equals("READY")) break; Thread.sleep(1); }
            while (System.nanoTime() < deadline);
            JsonObject first = JsonParser.parseString(ready).getAsJsonObject();
            check(first.get("nextOffset").getAsInt() == 2048 && !first.get("eof").getAsBoolean(), "首段严格 2 KiB");
            check(Base64.getDecoder().decode(first.get("data").getAsString()).length == 2048, "分片原文尺寸");
            var last = JsonParser.parseString(cache.read("https://example.com/a", 4096)).getAsJsonObject();
            check(last.get("eof").getAsBoolean() && last.get("size").getAsInt() == 5000, "尾段明确长度与结束标记");
            boolean denied = false; try { cache.read("https://evil.example/a", 0); } catch (IllegalArgumentException e) { denied = true; }
            check(denied, "未授权域名不读缓存也不出网");
            for (int i = 0; i < 5; i++) cache.read("https://example.com/" + i, 0);
            check(status(cache.read("https://example.com/seventh", 0)).equals("RATE_LIMITED"), "每域名 6 次每分钟");
            policy.set(FetchCache.Policy.DEFAULT);
            check(status(cache.read("https://example.com/a", 0)).equals("DISABLED"), "关闭后已缓存内容也不交付");
            policy.set(new FetchCache.Policy(true, Set.of("example.com"), 262144, 1000, true));
            time.addAndGet(60001); check(status(cache.read("https://example.com/a", 0)).equals("PENDING"), "缓存过期重新拉取");
        } finally { release.countDown(); }
        System.out.println("FetchCacheTest: " + checks + " passed");
    }
}
