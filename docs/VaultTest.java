package com.november.mcphone.core.script.server.store;

import javax.crypto.AEADBadTagException;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 保险箱的密码学构造（施工方案 §17.4.1、§17.4.2、§17.6）。
 *
 * <p>断言照 §17.6，<b>只能加不能减</b>。加的几条标了"§17.6 之外"。
 *
 * <p><b>这里测不了的</b>：在真存档里 grep 明文、手动换掉一条记录再看客户端报什么 ——
 * 那两条要一台跑着的服务器。不过它们判的东西这里都有对应的纯逻辑断言：
 * "存档里 grep 不到明文"对应下面的"密文里不含明文字节"，
 * "换成别人的密文会报解密失败"对应"换 AAD 必失败"。
 *
 * <p>跑法：{@code ./gradlew assertTests}（在 {@code platforms/<目标名>/} 下）。
 */
public class VaultTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) failures.add(what + "  期望 " + expected + "，实际 " + actual);
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures.add(what);
    }

    /** 跑一段，要求它抛指定类型的异常。 */
    static void rejects(ThrowingRunnable body, Class<? extends Throwable> type, String what) {
        checks++;
        try {
            body.run();
            failures.add(what + "  竟然没抛");
        } catch (Throwable t) {
            if (!type.isInstance(t)) failures.add(what + "  抛的是 " + t.getClass().getSimpleName() + "，期望 " + type.getSimpleName());
        }
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    static byte[] aad(String srv, String player, String app, String key, int schema, long record) {
        return VaultCrypto.aad(srv, player, app, key, schema, record);
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xf, 16)).append(Character.forDigit(x & 0xf, 16));
        return sb.toString();
    }

    static final String TOKEN = "sk-ant-abc";

    // ================================================================ §17.6 的那几条

    static void roundTripAndAad() throws Exception {
        byte[] salt = VaultCrypto.newSalt();
        SecretKey key = VaultCrypto.derive("正确的口令要八个字".toCharArray(), salt);
        byte[] A = aad("srv", "p1", "app", "k1", 1, 1);

        VaultCrypto.Sealed ct = VaultCrypto.seal(key, A, TOKEN.getBytes(StandardCharsets.UTF_8));
        eq(new String(VaultCrypto.unseal(key, A, ct), StandardCharsets.UTF_8), TOKEN, "正常往返");

        // 错口令解不开
        SecretKey wrong = VaultCrypto.derive("错的口令也八个字".toCharArray(), salt);
        rejects(() -> VaultCrypto.unseal(wrong, A, ct), AEADBadTagException.class, "错口令必失败");

        // AAD 六个字段逐一变，每一个都必须解不开 —— 这就是"服主没法张冠李戴"
        List<byte[]> bads = List.of(
                aad("srv2", "p1", "app", "k1", 1, 1),      // 换服务器
                aad("srv", "p2", "app", "k1", 1, 1),       // 换玩家 ← §17.7 那条手动验收对应这里
                aad("srv", "p1", "app2", "k1", 1, 1),      // 换 App
                aad("srv", "p1", "app", "k2", 1, 1),       // 换 key
                aad("srv", "p1", "app", "k1", 2, 1),       // 换 schemaVersion
                aad("srv", "p1", "app", "k1", 1, 2));      // 换 recordVersion
        for (byte[] bad : bads) {
            rejects(() -> VaultCrypto.unseal(key, bad, ct), AEADBadTagException.class, "换 AAD 必失败");
        }

        // 密文改一位必失败
        byte[] flipped = ct.cipher().clone();
        flipped[3] ^= 0x01;
        VaultCrypto.Sealed tampered = new VaultCrypto.Sealed(ct.nonce(), flipped);
        rejects(() -> VaultCrypto.unseal(key, A, tampered), AEADBadTagException.class, "改密文必失败");

        // nonce 改一位也必失败（§17.6 之外）
        byte[] badNonce = ct.nonce().clone();
        badNonce[0] ^= 0x01;
        rejects(() -> VaultCrypto.unseal(key, A, new VaultCrypto.Sealed(badNonce, ct.cipher())),
                AEADBadTagException.class, "改 nonce 必失败");
    }

    /** AAD 用长度前缀拼，不是直接连 —— 否则相邻字段能互相借位（§17.6 之外）。 */
    static void aadNoAmbiguity() {
        check(!hex(aad("ab", "c", "app", "k", 1, 1)).equals(hex(aad("a", "bc", "app", "k", 1, 1))),
                "相邻字段不许互相借位");
        check(!hex(aad("srv", "p", "ab", "c", 1, 1)).equals(hex(aad("srv", "p", "a", "bc", 1, 1))),
                "appId 与 key 之间也不许");
    }

    static void nonceNeverRepeats() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10000; i++) {
            check(seen.add(hex(VaultCrypto.newNonce())), "nonce 不重复（第 " + i + " 个）");
        }
        eq(seen.size(), 10000, "一万个 nonce 全不一样");
        eq(VaultCrypto.newNonce().length, 12, "nonce 是 12 字节");
        eq(VaultCrypto.newSalt().length, 16, "salt 是 16 字节");
    }

    /** 64 字节对齐：不同长度的明文产出相同长度的密文，长度不泄漏"哪家服务商"。 */
    static void paddingHidesLength() {
        byte[] salt = VaultCrypto.newSalt();
        SecretKey key = VaultCrypto.derive("八个字的口令在此".toCharArray(), salt);
        byte[] A = aad("srv", "p1", "app", "k1", 1, 1);

        int a = VaultCrypto.seal(key, A, "short".getBytes(StandardCharsets.UTF_8)).cipher().length;
        int b = VaultCrypto.seal(key, A, "a-much-longer-token".getBytes(StandardCharsets.UTF_8)).cipher().length;
        eq(a, b, "64 字节对齐后长度相同");

        // 填充本身要能无歧义地还原（§17.6 之外）
        for (String s : new String[]{"", "x", "sk-ant-abc", "x".repeat(63), "x".repeat(64), "x".repeat(65)}) {
            byte[] padded = VaultCrypto.pad(s.getBytes(StandardCharsets.UTF_8));
            eq(padded.length % VaultCrypto.PAD_BLOCK, 0, "补齐到 64 的倍数：" + s.length() + " 字节");
            eq(new String(VaultCrypto.unpad(padded), StandardCharsets.UTF_8), s, "填充能还原：" + s.length() + " 字节");
        }
        // 以零字节结尾的明文也要还原得回来 —— 所以填充要带长度前缀
        byte[] withNul = new byte[]{'a', 0, 0};
        eq(hex(VaultCrypto.unpad(VaultCrypto.pad(withNul))), hex(withNul), "结尾是零字节的明文也还原得回来");
    }

    /** §17.4.2 的版本判定，§17.6 直接断言它。 */
    static void rollback() {
        check(!VaultClient.accept(3, 5), "版本倒退拒绝");
        check(VaultClient.accept(7, 5), "版本前进接受");
        check(VaultClient.accept(5, 5), "同版本接受");
        check(VaultClient.accept(1, 0), "本地没见过就接受");
    }

    static void kdfFastEnough() {
        byte[] salt = VaultCrypto.newSalt();
        long t0 = System.nanoTime();
        VaultCrypto.derive("x".repeat(8).toCharArray(), salt);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        check(ms < 2000, "KDF " + VaultCrypto.KDF_ITERATIONS + " 轮 2 秒内完成，实际 " + ms + " 毫秒");
        eq(VaultCrypto.KDF_ITERATIONS, 600_000, "§17.4.1 的轮数");
    }

    // ================================================================ §17.6 之外

    /**
     * §17.7 那条"在服务端存档里 grep 明文 token，搜不到"的纯逻辑对应物：
     * 密文里不含明文的任何一段字节。
     */
    static void cipherHasNoPlaintext() {
        byte[] salt = VaultCrypto.newSalt();
        SecretKey key = VaultCrypto.derive("一个足够长的口令".toCharArray(), salt);
        byte[] A = aad("srv", "p1", "app", "k1", 1, 1);
        String secret = "sk-ant-api03-REAL-LOOKING-TOKEN-1234567890";
        VaultCrypto.Sealed ct = VaultCrypto.seal(key, A, secret.getBytes(StandardCharsets.UTF_8));

        String haystack = hex(ct.cipher()) + hex(ct.nonce()) + hex(salt);
        check(!haystack.contains(hex(secret.getBytes(StandardCharsets.UTF_8))), "整段明文不在密文里");
        // 连四个字符的片段都不该出现
        for (int i = 0; i + 4 <= secret.length(); i += 4) {
            String frag = hex(secret.substring(i, i + 4).getBytes(StandardCharsets.UTF_8));
            check(!haystack.contains(frag), "明文片段 '" + secret.substring(i, i + 4) + "' 不在密文里");
        }
    }

    /** 同一段明文加密两次，密文必须不同 —— nonce 随机的直接后果。 */
    static void sameInputDifferentCipher() {
        byte[] salt = VaultCrypto.newSalt();
        SecretKey key = VaultCrypto.derive("一个足够长的口令".toCharArray(), salt);
        byte[] A = aad("srv", "p1", "app", "k1", 1, 1);
        String a = hex(VaultCrypto.seal(key, A, TOKEN.getBytes(StandardCharsets.UTF_8)).cipher());
        String b = hex(VaultCrypto.seal(key, A, TOKEN.getBytes(StandardCharsets.UTF_8)).cipher());
        check(!a.equals(b), "同一段明文两次加密的密文必须不同");
    }

    /** VaultClient 这一层：版本单调递增、倒退报可读的键而不是 Java 栈。 */
    static void clientFlow() {
        VaultClient v = new VaultClient("srv", "p1");
        byte[] salt = VaultCrypto.newSalt();
        v.unlock("一个足够长的口令".toCharArray(), salt);
        check(v.unlocked(), "开箱了");

        SealedRecord r1 = v.put("app", "k1", TOKEN, salt);
        eq(r1.recordVersion(), 1L, "第一条是第 1 版");
        eq(v.get("app", "k1", r1), TOKEN, "存进去能取出来");

        SealedRecord r2 = v.put("app", "k1", "新的值", salt);
        eq(r2.recordVersion(), 2L, "再写一次版本加一");
        eq(v.get("app", "k1", r2), "新的值", "取到新的");

        // 服主把存档回滚，把第 1 版塞回来
        checks++;
        try {
            v.get("app", "k1", r1);
            failures.add("版本倒退竟然被接受了");
        } catch (VaultClient.VaultException e) {
            eq(e.messageKey(), VaultClient.KEY_ROLLBACK, "报的是版本倒退，且是本地化键不是 Java 栈");
        }

        // 服主把另一个玩家的密文塞进来
        VaultClient other = new VaultClient("srv", "p2");
        other.unlock("一个足够长的口令".toCharArray(), salt);
        SealedRecord stolen = other.put("app", "k1", "别人的 token", salt);
        checks++;
        try {
            VaultClient me = new VaultClient("srv", "p1");
            me.unlock("一个足够长的口令".toCharArray(), salt);
            me.get("app", "k1", stolen);
            failures.add("别人的密文竟然解开了");
        } catch (VaultClient.VaultException e) {
            eq(e.messageKey(), VaultClient.KEY_BAD_TAG, "报的是解密失败，不是静默为空");
        }

        v.lock();
        check(!v.unlocked(), "关手机就丢密钥");
        checks++;
        try {
            v.get("app", "k1", r2);
            failures.add("锁着竟然还能读");
        } catch (VaultClient.VaultException e) {
            eq(e.messageKey(), VaultClient.KEY_LOCKED, "锁着时报 locked");
        }

        // 口令太短要拒（§17.4.4 最少 8 字符）
        checks++;
        try {
            new VaultClient("srv", "p1").unlock("1234567".toCharArray(), salt);
            failures.add("7 个字符的口令竟然收了");
        } catch (VaultClient.VaultException e) {
            eq(e.messageKey(), VaultClient.KEY_LOCKED, "口令太短要拒");
        }
        check(VaultCrypto.passphraseLongEnough("12345678".toCharArray()), "8 个字符够");
        check(!VaultCrypto.passphraseLongEnough("1234567".toCharArray()), "7 个不够");
    }

    /** 版本表能存进 local 档再读回来（§17.4.2：sealed 依赖 local 的唯一一处）。 */
    static void versionTableRoundTrip() {
        VaultClient v = new VaultClient("srv", "p1");
        byte[] salt = VaultCrypto.newSalt();
        v.unlock("一个足够长的口令".toCharArray(), salt);
        SealedRecord r = v.put("app", "k1", TOKEN, salt);

        var saved = v.versionsForLocal();
        eq(saved.get("k1"), 1L, "版本表里记着第 1 版");

        // 换一台设备：从 local 档把版本表读回来，回滚照样挡得住
        VaultClient other = new VaultClient("srv", "p1");
        other.unlock("一个足够长的口令".toCharArray(), salt);
        other.restoreVersions(java.util.Map.of("k1", 5L));
        checks++;
        try {
            other.get("app", "k1", r);
            failures.add("恢复版本表之后竟然还接受旧记录");
        } catch (VaultClient.VaultException e) {
            eq(e.messageKey(), VaultClient.KEY_ROLLBACK, "从 local 恢复的版本表照样挡回滚");
        }
    }

    // ================================================================ 配额（§17.2 / §17.3）

    /** 三个档的超限写入都要【显式拒绝】，不静默截断、不静默丢 key。 */
    static void quotas() {
        // local：单值 4 KiB
        checks++;
        try {
            StoreQuota.checkValue("x".repeat(StoreQuota.LOCAL_PER_VALUE + 1),
                    StoreQuota.LOCAL_PER_VALUE, "local");
            failures.add("local 单值超限竟然放行");
        } catch (StoreQuota.QuotaExceeded e) {
            eq(e.messageKey(), StoreQuota.KEY_QUOTA, "local 单值超限报可读的键");
        }
        // 刚好到顶要放行
        StoreQuota.checkValue("x".repeat(StoreQuota.LOCAL_PER_VALUE), StoreQuota.LOCAL_PER_VALUE, "local");
        checks++;

        // key 数
        checks++;
        try {
            StoreQuota.checkAdd(StoreQuota.LOCAL_KEYS, 0, true, 1,
                    StoreQuota.LOCAL_KEYS, StoreQuota.LOCAL_PER_APP, "local");
            failures.add("local key 数超限竟然放行");
        } catch (StoreQuota.QuotaExceeded ignored) {
        }
        // 已有的 key 再写一次不算新 key
        StoreQuota.checkAdd(StoreQuota.LOCAL_KEYS, 0, false, 1,
                StoreQuota.LOCAL_KEYS, StoreQuota.LOCAL_PER_APP, "local");
        checks++;

        // 总量
        checks++;
        try {
            StoreQuota.checkAdd(0, StoreQuota.LOCAL_PER_APP, false, 1,
                    StoreQuota.LOCAL_KEYS, StoreQuota.LOCAL_PER_APP, "local");
            failures.add("local 总量超限竟然放行");
        } catch (StoreQuota.QuotaExceeded ignored) {
        }

        // shared：ScriptKv 自己带配额
        ScriptKv kv = ScriptKv.DEFAULT;
        String ns = ScriptKv.namespace("srv", "dep1", "app", 1);
        kv = kv.with(ns, "k1", "v1");
        eq(kv.get(ScriptKv.fullKey(ns, "k1")), "v1", "shared 写进去读得出来");
        eq(kv.keyCount(ns), 1, "命名空间里一个 key");

        checks++;
        try {
            kv.with(ns, "big", "x".repeat(StoreQuota.SHARED_PER_VALUE + 1));
            failures.add("shared 单值超限竟然放行");
        } catch (StoreQuota.QuotaExceeded ignored) {
        }

        // 配额按命名空间算：换一个 deploymentId 就是另一块
        String ns2 = ScriptKv.namespace("srv", "dep2", "app", 1);
        eq(kv.keyCount(ns2), 0, "换 deploymentId 是另一个命名空间");

        // 数字与 §17.2/§17.3 的表逐项对上
        eq(StoreQuota.LOCAL_PER_APP, 16 * 1024, "§17.2 每 App 16 KiB");
        eq(StoreQuota.LOCAL_PER_VALUE, 4 * 1024, "§17.2 单值 4 KiB");
        eq(StoreQuota.LOCAL_KEYS, 64, "§17.2 key ≤ 64");
        eq(StoreQuota.SHARED_PER_PLAYER_APP, 8 * 1024, "§17.3 每玩家每 App 8 KiB");
        eq(StoreQuota.SHARED_PER_VALUE, 2 * 1024, "§17.3 单值 2 KiB");
        eq(StoreQuota.SHARED_KEYS, 64, "§17.3 key 64");
        eq(StoreQuota.SHARED_WRITES_PER_SEC, 10, "§17.3 写入 10 次/秒");
        eq(StoreQuota.SHARED_PER_APP_TOTAL, 4L * 1024 * 1024, "§17.3 每 App 全服 4 MiB");
        eq(StoreQuota.SHARED_SERVER_TOTAL, 64L * 1024 * 1024, "§17.3 全服 64 MiB");
    }

    /** 守卫与 KV 是两块：脚本能写的那一块里没有守卫计数（§19.2、§20.2）。 */
    static void guardsAreSeparate() {
        String ns = ScriptKv.namespace("srv", "dep1", "app", 1);
        ScriptGuards g = ScriptGuards.DEFAULT.increment(ScriptGuards.guardKey(ns, "daily", "2026-09-17"));
        eq(g.get(ScriptGuards.guardKey(ns, "daily", "2026-09-17")), 1L, "守卫计数加了一");

        ScriptKv kv = ScriptKv.DEFAULT.with(ns, "k1", "v1");
        check(kv.get(ScriptGuards.guardKey(ns, "daily", "2026-09-17")) == null,
                "守卫计数不在脚本能写的那张表里 —— 在一起脚本就能把「只能领一次」清零");

        // 换周期标签之后旧计数可以清掉，当前那个留着
        ScriptGuards g2 = g.increment(ScriptGuards.guardKey(ns, "daily", "2026-09-18"))
                .pruneOtherCycles(ns, "daily", "2026-09-18");
        eq(g2.get(ScriptGuards.guardKey(ns, "daily", "2026-09-17")), 0L, "旧周期的清掉了");
        eq(g2.get(ScriptGuards.guardKey(ns, "daily", "2026-09-18")), 1L, "当前周期的留着");
    }

    static void localPathsStayInsideRoot() {
        eq(LocalStore.safe("."), "_", "local 路径不接受单点段");
        eq(LocalStore.safe(".."), "_", "local 路径不接受双点段");
        eq(LocalStore.safe("NUL"), "_NUL", "Windows NUL 设备名被改写");
        eq(LocalStore.safe("nul.json"), "_nul.json", "带扩展名的设备名也被改写");
        eq(LocalStore.safe("example:app"), "example_app", "普通 appId 仅替换分隔符");
    }

    public static void main(String[] args) throws Exception {
        quotas();
        localPathsStayInsideRoot();
        guardsAreSeparate();
        roundTripAndAad();
        aadNoAmbiguity();
        nonceNeverRepeats();
        paddingHidesLength();
        rollback();
        kdfFastEnough();
        cipherHasNoPlaintext();
        sameInputDifferentCipher();
        clientFlow();
        versionTableRoundTrip();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
