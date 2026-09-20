package com.november.mcphone.core.script.pkg;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 签名与信任（施工方案 §12.2–§12.8）。
 *
 * <p><b>这里测不了的</b>：安装界面上那几档文案与 INSTALL_NOTE 真的显示出来、
 * 「没有暗示内容安全的图标」——那要 runClient 才能看。判定本身是纯函数，全在这里。
 *
 * <p>跑法：{@code ./gradlew assertTests}（在 {@code platforms/<目标名>/} 下）。
 */
public class PackageSignTest {

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

    @FunctionalInterface
    interface Throwing {
        void run() throws Exception;
    }

    /** 跑一段，把它抛的 PackageError 的码取出来。包在别的异常里也认得出来。 */
    static PackageError.Code codeOf(Throwing body) {
        try {
            body.run();
            return null;
        } catch (Throwable t) {
            for (Throwable e = t; e != null; e = e.getCause()) {
                if (e instanceof PackageError pe) return pe.code();
            }
            return null;
        }
    }

    static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static final String MANIFEST = "{"
            + "\"format\":1,\"id\":\"example:demo\",\"version\":\"1.0.0\","
            + "\"name\":\"demo\",\"author\":\"a\",\"description\":\"d\","
            + "\"icon\":\"icon.png\",\"engine\":\"declarative-1\"}";

    /** 一个最小的合法包，内容部分固定。 */
    static Map<String, byte[]> content() {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("manifest.json", bytes(MANIFEST));
        m.put("app.vue", bytes("<template><text>hi</text></template>"));
        m.put("icon.png", new byte[]{1, 2, 3});
        return m;
    }

    // ================================================================ §12.2 算法与指纹

    static void algorithmAndFingerprint() {
        KeyPair a = Signatures.generate();
        KeyPair b = Signatures.generate();

        eq(a.getPublic().getEncoded().length, 44, "公钥 X.509 44 字节");
        eq(a.getPrivate().getEncoded().length, 48, "私钥 PKCS8 48 字节");
        eq(Signatures.sign(a.getPrivate(), "abc").length, 64, "签名 64 字节");

        String fp = Signatures.fingerprint(a.getPublic());
        check(fp.matches("[0-9a-f]{4}(-[0-9a-f]{4}){3}"), "指纹格式 xxxx-xxxx-xxxx-xxxx，实际 " + fp);
        check(!fp.equals(Signatures.fingerprint(b.getPublic())), "不同密钥指纹不同");
        eq(Signatures.fingerprint(a.getPublic()), fp, "同一密钥指纹稳定");

        check(Signatures.knownAlg("ed25519"), "认得 ed25519");
        check(Signatures.knownAlg("Ed25519"), "大小写不敏感");
        check(!Signatures.knownAlg("rsa-pkcs1"), "不认得 rsa-pkcs1");
        check(!Signatures.knownAlg(null), "null 也不认得");
    }

    static void signAndVerify() {
        KeyPair kp = Signatures.generate();
        byte[] sig = Signatures.sign(kp.getPrivate(), "digest-abc");
        check(Signatures.verify(kp.getPublic(), "digest-abc", sig), "正常验签");
        check(!Signatures.verify(kp.getPublic(), "digest-abd", sig), "换一个摘要验不过");

        byte[] flipped = sig.clone();
        flipped[3] ^= 1;
        check(!Signatures.verify(kp.getPublic(), "digest-abc", flipped), "改签名一位验不过");

        KeyPair other = Signatures.generate();
        check(!Signatures.verify(other.getPublic(), "digest-abc", sig), "换公钥验不过");
    }

    /**
     * §12.8 的域分隔符那一条：<b>把包摘要当作别处的签名输入去签，验不过。</b>
     *
     * <p>包摘要那边的域是 {@code mcphone-pkg-v1\0}，签名这边是 {@code mcphone-sig-v1\0}，
     * 轮换声明又是第三个。三处的哈希互相不能当输入用。
     */
    static void domainSeparation() throws Exception {
        KeyPair kp = Signatures.generate();
        String digest = PackageDigest.of(content());

        // 用轮换声明的域去签同一个东西，拿到包签名这边验 —— 必须验不过
        byte[] rotateStyle = RotateManifest.sign(kp.getPrivate(), digest, new byte[]{1, 2, 3});
        check(!Signatures.verify(kp.getPublic(), digest, rotateStyle),
                "换一个域签出来的签名，在这一边验不过 —— 域分隔符就是为这件事存在的");

        // 反过来也不行
        byte[] sigStyle = Signatures.sign(kp.getPrivate(), digest);
        RotateManifest fake = new RotateManifest(1, "ed25519", Signatures.fingerprint(kp.getPublic()),
                new byte[]{1, 2, 3}, sigStyle);
        check(!fake.verify(kp.getPublic().getEncoded()), "包签名拿到轮换那边也验不过");
    }

    // ================================================================ §12.3 META/ 与摘要

    /** META/ 的内容变化不影响包摘要 —— 否则自引用（摘要含签名，签名又签摘要）。 */
    static void metaNotInDigest() throws Exception {
        Map<String, byte[]> c = content();
        String bare = PackageDigest.of(c);

        AppPackage p1 = PackageReader.read(zip(withMeta(c, bytes("{\"a\":1}"), null)));
        AppPackage p2 = PackageReader.read(zip(withMeta(c, bytes("{\"a\":2}"), null)));
        eq(p1.digest(), bare, "有 sig.json 时摘要不变");
        eq(p2.digest(), bare, "sig.json 内容变了摘要还是不变");

        AppPackage p3 = PackageReader.read(zip(withMeta(c, bytes("{\"a\":1}"), bytes("{\"b\":1}"))));
        eq(p3.digest(), bare, "多一个 rotate.json 摘要也不变");
        check(p3.rotate() != null, "rotate.json 读得出来");
    }

    /** META/ 下只许两个具名文件；只放 rotate.json 是合法的（勘误 E22）。 */
    static void metaWhitelist() throws Exception {
        Map<String, byte[]> c = content();

        // 只有 rotate.json：合法
        AppPackage only = PackageReader.read(zip(withMeta(c, null, bytes("{\"b\":1}"))));
        check(only.signature() == null, "没有 sig.json");
        check(only.rotate() != null, "只放 rotate.json 是合法的 —— §12.3 原文与 §12.6 打架，裁定封闭列举两个");

        // 第三个文件：整包拒绝
        Map<String, byte[]> bad = new LinkedHashMap<>(c);
        bad.put("META/sig.json", bytes("{}"));
        bad.put("META/notes.txt", bytes("hi"));
        eq(codeOf(() -> PackageReader.read(zip(bad))),
                PackageError.Code.E_PKG_META_EXTRA, "META/ 下多放一个文件就整包拒绝");
    }

    static Map<String, byte[]> withMeta(Map<String, byte[]> content, byte[] sig, byte[] rotate) {
        Map<String, byte[]> m = new LinkedHashMap<>(content);
        if (sig != null) m.put("META/sig.json", sig);
        if (rotate != null) m.put("META/rotate.json", rotate);
        return m;
    }

    // ================================================================ sig.json 解析

    static byte[] sigJson(KeyPair kp, String digest, String author) {
        byte[] sig = Signatures.sign(kp.getPrivate(), digest);
        return bytes("{\"format\":1,\"alg\":\"ed25519\","
                + "\"digest\":\"sha256:" + digest + "\","
                + "\"pubkey\":\"" + Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()) + "\","
                + "\"sig\":\"" + Base64.getEncoder().encodeToString(sig) + "\","
                + "\"author\":\"" + author + "\",\"signedAt\":1757203200}");
    }

    static void sigManifestParsing() throws Exception {
        KeyPair kp = Signatures.generate();
        String digest = PackageDigest.of(content());

        SigManifest m = SigManifest.parse(sigJson(kp, digest, "yumeka"));
        eq(m.author(), "yumeka", "author 读得出来（但它只是装饰）");
        eq(m.fingerprint(), Signatures.fingerprint(kp.getPublic()), "指纹来自公钥，不是 author");
        check(m.verify(digest), "验签通过");
        check(!m.verify("别的摘要"), "摘要对不上就不通过");

        // alg 不认识 → 拒绝，【不回退成未签名】
        byte[] rsa = bytes(new String(sigJson(kp, digest, "x"), StandardCharsets.UTF_8)
                .replace("\"ed25519\"", "\"rsa-pkcs1\""));
        eq(codeOf(() -> SigManifest.parse(rsa)), PackageError.Code.E_SIG_UNKNOWN_ALG,
                "不认识的 alg 要拒，不许当成未签名 —— 那会把硬拒绝降级成二次确认");

        eq(codeOf(() -> SigManifest.parse(bytes("{\"format\":2,\"alg\":\"ed25519\"}"))),
                PackageError.Code.E_SIG_BAD_FORMAT, "format 不对");
        eq(codeOf(() -> SigManifest.parse(bytes("{\"format\":1}"))),
                PackageError.Code.E_SIG_MISSING_FIELD, "缺字段");
        eq(codeOf(() -> SigManifest.parse(bytes("不是 json"))),
                PackageError.Code.E_SIG_BAD_JSON, "坏 JSON");
        eq(codeOf(() -> SigManifest.parse(bytes("{\"format\":1,\"alg\":\"ed25519\",\"digest\":\"x\","
                + "\"pubkey\":\"!!!\",\"sig\":\"aaaa\"}"))),
                PackageError.Code.E_SIG_BAD_BASE64, "坏 base64");
    }

    /** §12.8：把已签名的包改一个字节，验签必败。 */
    static void tamperedPackage() throws Exception {
        KeyPair kp = Signatures.generate();
        Map<String, byte[]> c = content();
        String digest = PackageDigest.of(c);

        AppPackage good = PackageReader.read(zip(withMeta(c, sigJson(kp, digest, "yumeka"), null)));
        check(SigManifest.parse(good.signature()).verify(good.digest()), "没动过的包验得过");

        // 改内容一个字节，签名不动
        Map<String, byte[]> tampered = new LinkedHashMap<>(c);
        byte[] vue = tampered.get("app.vue").clone();
        vue[0] ^= 1;
        tampered.put("app.vue", vue);
        AppPackage bad = PackageReader.read(zip(withMeta(tampered, sigJson(kp, digest, "yumeka"), null)));
        check(!bad.digest().equals(digest), "改一个字节摘要就变了");
        check(!SigManifest.parse(bad.signature()).verify(bad.digest()), "改一字节验签必败");
    }

    // ================================================================ §12.4 档位判定

    static AppPackage pkg(Map<String, byte[]> content, byte[] sig) throws Exception {
        return PackageReader.read(zip(withMeta(content, sig, null)));
    }

    /** 五种输入全覆盖。 */
    static void trustStates() throws Exception {
        KeyPair author = Signatures.generate();
        KeyPair other = Signatures.generate();
        Map<String, byte[]> c = content();
        String digest = PackageDigest.of(c);
        String fpA = Signatures.fingerprint(author.getPublic());
        String appId = "example:demo";

        // ① 未签名
        TrustStore t1 = new TrustStore();
        var v1 = TrustState.of(pkg(c, null), appId, t1);
        eq(v1.state(), TrustState.State.UNSIGNED, "没有 sig.json → 未签名");
        eq(v1.fingerprint(), null, "未签名时指纹是 null，UI 上写「无」");
        check(v1.state().installable, "未签名不是硬拒绝");
        check(v1.state().needsConfirm(), "但要二次确认");

        // ② 已签名·陌生作者
        TrustStore t2 = new TrustStore();
        var v2 = TrustState.of(pkg(c, sigJson(author, digest, "yumeka")), appId, t2);
        eq(v2.state(), TrustState.State.UNKNOWN_AUTHOR, "首次见到 → 陌生作者");
        eq(v2.fingerprint(), fpA, "带出指纹");
        check(v2.state().needsConfirm(), "二次确认");

        // ③ 已签名·已信任
        TrustStore t3 = new TrustStore();
        t3.record(fpA, "yumeka", author.getPublic().getEncoded(), 1000);
        t3.trust(fpA, appId);
        var v3 = TrustState.of(pkg(c, sigJson(author, digest, "yumeka")), appId, t3);
        eq(v3.state(), TrustState.State.TRUSTED, "记过且信任 → 直接安装");
        check(!v3.state().needsConfirm(), "不用再确认");

        // ④ 作者密钥变了（同 appId 装过，指纹不同）
        var v4 = TrustState.of(pkg(c, sigJson(other, digest, "yumeka")), appId, t3);
        eq(v4.state(), TrustState.State.KEY_CHANGED, "同 App 换了签名密钥");
        eq(v4.knownFingerprint(), fpA, "带出上次那个指纹，UI 要把新旧都显示");
        check(v4.state().needsPhrase(), "要输入确认短语，不是点一下");
        check(v4.state().installable, "但不是硬拒绝");

        // ⑤ 签名无效
        Map<String, byte[]> tampered = new LinkedHashMap<>(c);
        tampered.put("app.vue", bytes("被改过了"));
        var v5 = TrustState.of(pkg(tampered, sigJson(author, digest, "yumeka")), appId, t3);
        eq(v5.state(), TrustState.State.INVALID, "内容改过 → 签名无效");
        check(!v5.state().installable, "硬拒绝：不给「仍然继续」");

        // alg 不认识也是「签名无效」，不是「未签名」
        byte[] rsa = bytes(new String(sigJson(author, digest, "x"), StandardCharsets.UTF_8)
                .replace("\"ed25519\"", "\"rsa-pkcs1\""));
        eq(TrustState.of(pkg(c, rsa), appId, new TrustStore()).state(), TrustState.State.INVALID,
                "alg 不认识 → 签名无效，不是未签名");

        // 每一档都有自己的文案键。「不许写安全」那条盯的是 lang 里的文本，在 SignCopyTest
        for (TrustState.State s : TrustState.State.values()) {
            check(!s.messageKey.isEmpty(), s + " 要有文案键");
            check(s.messageKey.startsWith("mcphone.sig."), s + " 的键有前缀");
        }
        eq(TrustState.State.values().length, 6, "六档");
        long hard = 0;
        for (TrustState.State s : TrustState.State.values()) if (!s.installable) hard++;
        eq(hard, 2L, "硬拒绝两档：签名无效、签名被摘掉了");
        check(TrustState.State.UNSIGNED.installable, "「未签名」本身仍然可装 —— "
                + "把它也做成硬拒绝会逼所有人去找绕过办法");
    }

    /** §12.7：被封禁的作者，验签通过也拒绝。 */
    static void blocked() throws Exception {
        KeyPair author = Signatures.generate();
        Map<String, byte[]> c = content();
        String digest = PackageDigest.of(c);
        String fp = Signatures.fingerprint(author.getPublic());

        TrustStore t = new TrustStore();
        t.record(fp, "yumeka", author.getPublic().getEncoded(), 1000);
        t.trust(fp, "example:demo");
        eq(TrustState.of(pkg(c, sigJson(author, digest, "y")), "example:demo", t).state(),
                TrustState.State.TRUSTED, "封禁之前是信任的");

        t.setBlocked(fp, true);
        eq(TrustState.of(pkg(c, sigJson(author, digest, "y")), "example:demo", t).state(),
                TrustState.State.INVALID, "封禁之后验签通过也拒绝");
        check(!t.trusted(fp), "封了就不算信任了");
    }

    // ================================================================ §12.6 轮换

    static void rotation() {
        KeyPair oldKey = Signatures.generate();
        KeyPair newKey = Signatures.generate();
        String oldFp = Signatures.fingerprint(oldKey.getPublic());
        String newFp = Signatures.fingerprint(newKey.getPublic());

        TrustStore t = new TrustStore();
        t.record(oldFp, "yumeka", oldKey.getPublic().getEncoded(), 1000);
        t.trust(oldFp, "example:demo");

        byte[] sig = RotateManifest.sign(oldKey.getPrivate(), oldFp, newKey.getPublic().getEncoded());
        RotateManifest r = new RotateManifest(1, "ed25519", oldFp, newKey.getPublic().getEncoded(), sig);
        check(r.verify(oldKey.getPublic().getEncoded()), "旧公钥验得过这份声明");
        eq(r.newFingerprint(), newFp, "新指纹");

        eq(t.applyRotation(r, 2000), newFp, "旧的被信任 + 旧公钥验过 → 接受新指纹");
        check(t.trusted(newFp), "新指纹继承了信任");
        eq(t.fingerprintFor("example:demo"), newFp, "这个 App 的作者记录也转过去了");

        // 旧公钥验不过 → 拒绝
        KeyPair impostor = Signatures.generate();
        byte[] fakeSig = RotateManifest.sign(impostor.getPrivate(), oldFp, impostor.getPublic().getEncoded());
        RotateManifest fake = new RotateManifest(1, "ed25519", oldFp,
                impostor.getPublic().getEncoded(), fakeSig);
        check(!fake.verify(oldKey.getPublic().getEncoded()),
                "别人签的声明，旧公钥验不过 —— 不然谁都能把自己接到别人的信任上");

        TrustStore t2 = new TrustStore();
        t2.record(oldFp, "yumeka", oldKey.getPublic().getEncoded(), 1000);
        t2.trust(oldFp, "example:demo");
        eq(t2.applyRotation(fake, 2000), null, "拒绝轮换");

        // 旧指纹没被信任过也不接受
        TrustStore t3 = new TrustStore();
        eq(t3.applyRotation(r, 2000), null, "旧指纹根本没记过，不接受");
    }

    // ================================================================ 信任库落盘

    static void trustStoreRoundTrip() throws Exception {
        KeyPair kp = Signatures.generate();
        String fp = Signatures.fingerprint(kp.getPublic());
        Path dir = Files.createTempDirectory("mcphone-trust");
        dir.toFile().deleteOnExit();
        Path f = dir.resolve("authors.json");

        TrustStore t = new TrustStore();
        t.record(fp, "yumeka", kp.getPublic().getEncoded(), 1700000000L);
        t.trust(fp, "example:demo");
        t.trust(fp, "example:other");
        t.save(f);

        TrustStore back = TrustStore.load(f);
        check(back.trusted(fp), "信任状态读回来了");
        eq(back.displayName(fp), "yumeka", "显示名");
        eq(back.fingerprintFor("example:demo"), fp, "App 与作者的对应读回来了");
        eq(back.get(fp).apps().size(), 2, "两个 App");
        eq(back.get(fp).firstSeen(), 1700000000L, "首次见到时间");

        // 文件不在时不该炸
        check(!TrustStore.load(dir.resolve("nope.json")).trusted(fp), "文件不在就从空的开始，不抛");
    }

    /** §12.6：密钥生成、保管、备份。<b>私钥不进日志</b>。 */
    static void authorKeys() throws Exception {
        Path game = Files.createTempDirectory("mcphone-keys");
        game.toFile().deleteOnExit();

        check(!AuthorKeys.exists(game), "一开始没有");
        AuthorKeys k = AuthorKeys.generate(game);
        check(AuthorKeys.exists(game), "生成之后有了");
        check(k.fingerprint().matches("[0-9a-f]{4}(-[0-9a-f]{4}){3}"), "指纹格式");
        eq(Files.readAllBytes(game.resolve(AuthorKeys.DIR).resolve(AuthorKeys.PRIVATE_FILE)).length,
                48, "私钥 PKCS8 48 字节");
        eq(Files.readAllBytes(game.resolve(AuthorKeys.DIR).resolve(AuthorKeys.PUBLIC_FILE)).length,
                44, "公钥 X.509 44 字节");

        // 不覆盖：覆盖等于把作者身份弄丢
        check(codeOf(() -> AuthorKeys.generate(game)) == PackageError.Code.E_SIG_BAD_KEY,
                "已经有了就不覆盖");

        AuthorKeys loaded = AuthorKeys.load(game);
        eq(loaded.fingerprint(), k.fingerprint(), "读回来是同一把");

        // 签出来的 sig.json 能被自己的解析器读懂、并验得过
        String digest = PackageDigest.of(content());
        SigManifest m = SigManifest.parse(k.buildSigJson(digest, "yumeka", 1757203200L));
        check(m.verify(digest), "自己签的自己验得过");
        eq(m.fingerprint(), k.fingerprint(), "指纹对得上");
        eq(m.author(), "yumeka", "author 写进去了");

        // 备份：两个文件都要有（只备私钥的"备份"换台机器恢复不了）
        Path backup = game.resolve("backup");
        AuthorKeys.exportBackup(game, backup);
        eq(Files.readAllBytes(backup.resolve(AuthorKeys.PRIVATE_FILE)).length, 48, "备份里有私钥 48 字节");
        eq(Files.readAllBytes(backup.resolve(AuthorKeys.PUBLIC_FILE)).length, 44, "备份里有公钥 44 字节");

        // 恢复演练：一个全新目录，只靠备份里的两个文件就能读回同一把
        Path restored = Files.createTempDirectory("mcphone-keys-restore");
        Files.createDirectories(restored.resolve(AuthorKeys.DIR));
        Files.copy(backup.resolve(AuthorKeys.PRIVATE_FILE),
                restored.resolve(AuthorKeys.DIR).resolve(AuthorKeys.PRIVATE_FILE));
        Files.copy(backup.resolve(AuthorKeys.PUBLIC_FILE),
                restored.resolve(AuthorKeys.DIR).resolve(AuthorKeys.PUBLIC_FILE));
        eq(AuthorKeys.load(restored).fingerprint(), k.fingerprint(), "用备份恢复出同一把密钥");

        // 半成品：缺公钥 / 公钥被换成另一把 → 读失败且报的是"密钥坏"，不冒充"没有密钥"
        Path mixed = Files.createTempDirectory("mcphone-keys-mixed");
        Files.createDirectories(mixed.resolve(AuthorKeys.DIR));
        Files.copy(backup.resolve(AuthorKeys.PRIVATE_FILE),
                mixed.resolve(AuthorKeys.DIR).resolve(AuthorKeys.PRIVATE_FILE));
        check(codeOf(() -> AuthorKeys.load(mixed)) == PackageError.Code.E_SIG_BAD_KEY,
                "缺公钥：读失败（不是「没有密钥」）");

        Path other = Files.createTempDirectory("mcphone-keys-other");
        AuthorKeys otherKeys = AuthorKeys.generate(other);
        Files.copy(other.resolve(AuthorKeys.DIR).resolve(AuthorKeys.PUBLIC_FILE),
                mixed.resolve(AuthorKeys.DIR).resolve(AuthorKeys.PUBLIC_FILE));
        check(codeOf(() -> AuthorKeys.load(mixed)) == PackageError.Code.E_SIG_BAD_KEY,
                "author.key 与 author.pub 不是一对：读失败");
        check(!otherKeys.fingerprint().equals(k.fingerprint()), "两把不同密钥的指纹不同");

        // 生成是原子的：改名发布，不留 .tmp 半成品
        try (var walk = Files.walk(game.resolve(AuthorKeys.DIR))) {
            check(walk.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),
                    "生成之后没有 .tmp 残留");
        }
    }

    /** §12.7：服务端的作者名单。 */
    static void serverPolicy() {
        AuthorPolicy p = new AuthorPolicy();
        check(!p.whitelistMode(), "什么都没配时不是白名单模式");
        check(p.permits("9579-ce63-18c2-0b3d"), "没配就都放行");
        check(p.permits(null), "未签名的包也放行 —— 未签名不比签名更危险（§12.1）");

        p.block("9579-ce63-18c2-0b3d");
        check(!p.permits("9579-ce63-18c2-0b3d"), "封了就不放行");
        eq(p.denyKey("9579-ce63-18c2-0b3d"), "mcphone.sig.server_blocked", "拒绝理由分得开");
        check(p.permits("c3a1-0000-0000-0000"), "别人不受影响");

        AuthorPolicy w = new AuthorPolicy().allow("c3a1-0000-0000-0000");
        check(w.whitelistMode(), "allowed 非空 = 白名单模式");
        check(w.permits("c3a1-0000-0000-0000"), "名单内放行");
        check(!w.permits("9579-ce63-18c2-0b3d"), "名单外拒绝");
        check(!w.permits(null), "白名单模式下未签名的包过不去");
        eq(w.denyKey("9579-ce63-18c2-0b3d"), "mcphone.sig.server_not_allowed", "白名单的拒绝理由");

        // 空表不是"谁都不许" —— 反过来写会让服主第一次配置就把自己锁死
        check(new AuthorPolicy().permits("任何人"), "空的 allowed 表示不设白名单，不是全拒");
    }


    /**
     * 小阶公钥：不持任何私钥就能造出「验签通过」的包。
     *
     * <p>JDK 的 Ed25519 不查公钥落在哪个子群。拿恒等点当公钥、签名取 {@code 0x01||0x00*63}，
     * 对<b>任意</b>消息验签恒为 true。少了这一层，§12.4 的「INVALID 是唯一硬拒绝」
     * 对这类包永远不成立：玩家确认一次之后，那个谁都能签的指纹就成了 TRUSTED。
     */
    static void smallOrderForgery() throws Exception {
        // 恒等点的 X.509：12 字节外壳 + 32 字节点，点是 0x01 后面全 0
        byte[] hdr = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
        byte[] x509 = new byte[44];
        System.arraycopy(hdr, 0, x509, 0, 12);
        x509[12] = 1;
        byte[] forgedSig = new byte[64];
        forgedSig[0] = 1;

        check(Signatures.smallOrder(java.security.KeyFactory.getInstance("Ed25519")
                        .generatePublic(new java.security.spec.X509EncodedKeySpec(x509))),
                "恒等点要判成小阶");

        boolean threw = false;
        try {
            Signatures.publicKey(x509);
        } catch (PackageError e) {
            threw = true;
        }
        check(threw, "publicKey() 要拒小阶公钥");

        // 端到端：伪造的包必须是 INVALID，而且换多少种内容都一样
        Map<String, byte[]> c = content();
        String digest = PackageDigest.of(c);
        byte[] forged = bytes("{\"format\":1,\"alg\":\"ed25519\","
                + "\"digest\":\"sha256:" + digest + "\","
                + "\"pubkey\":\"" + Base64.getEncoder().encodeToString(x509) + "\","
                + "\"sig\":\"" + Base64.getEncoder().encodeToString(forgedSig) + "\","
                + "\"author\":\"Mojang Studios\",\"signedAt\":1757203200}");
        eq(TrustState.of(pkg(c, forged), "example:demo", new TrustStore()).state(),
                TrustState.State.INVALID, "伪造的包是 INVALID，不是「验签通过·陌生作者」");

        // 真密钥不受影响
        KeyPair real = Signatures.generate();
        eq(TrustState.of(pkg(c, sigJson(real, digest, "yumeka")), "example:demo", new TrustStore()).state(),
                TrustState.State.UNKNOWN_AUTHOR, "真密钥照常走陌生作者那一档");
    }

    /**
     * 摘掉 {@code META/sig.json} 是降级，不是「未签名」。
     *
     * <p>不这么判就有一条通吃的路：{@code zip -d pkg.zip META/sig.json}。没有签名就没有指纹，
     * 查不了封禁也查不了钉扎，于是 KEY_CHANGED（要抄指纹）与 §12.7 的吊销
     * 一起变成 UNSIGNED（点一下就装）。
     */
    static void signatureRemoved() throws Exception {
        KeyPair author = Signatures.generate();
        Map<String, byte[]> c = content();
        String digest = PackageDigest.of(c);
        String fpA = Signatures.fingerprint(author.getPublic());
        String appId = "example:demo";

        TrustStore t = new TrustStore();
        t.record(fpA, "yumeka", author.getPublic().getEncoded(), 1000);
        t.trust(fpA, appId);

        eq(TrustState.of(pkg(c, null), appId, t).state(), TrustState.State.SIGNATURE_REMOVED,
                "钉扎过的 App 突然没签名 → 降级，硬拒绝");
        check(!TrustState.State.SIGNATURE_REMOVED.installable, "这一档不许「仍然继续」");

        // 没钉扎过的 App 没签名，仍然是普通的「未签名」
        eq(TrustState.of(pkg(c, null), "example:never-installed", t).state(),
                TrustState.State.UNSIGNED, "没装过的包没签名 → 还是 UNSIGNED");

        // 封了之后摘签名也绕不过去
        TrustStore b = new TrustStore();
        b.record(fpA, "yumeka", author.getPublic().getEncoded(), 1000);
        b.trust(fpA, appId);
        b.setBlocked(fpA, true);
        eq(TrustState.of(pkg(c, sigJson(author, digest, "yumeka")), appId, b).state(),
                TrustState.State.INVALID, "封了 → 验签通过也拒");
        eq(TrustState.of(pkg(c, null), appId, b).state(), TrustState.State.SIGNATURE_REMOVED,
                "封了之后把签名摘掉 → 照样拒，不会掉回 UNSIGNED");
    }

    /**
     * 钉扎不受「一个作者最多记 64 个 App」那张列表约束。
     *
     * <p>从那张列表重建钉扎的话，装到第 65 个就会把第 1 个的钉扎挤掉 ——
     * 那个 App 换一把密钥重签就从 KEY_CHANGED 掉回 UNKNOWN_AUTHOR。
     */
    static void pinsSurviveAppListCap() throws Exception {
        KeyPair author = Signatures.generate();
        String fpA = Signatures.fingerprint(author.getPublic());
        TrustStore t = new TrustStore();
        t.record(fpA, "yumeka", author.getPublic().getEncoded(), 1000);

        t.trust(fpA, "example:first");
        for (int i = 0; i < TrustStore.MAX_APPS_PER_AUTHOR + 4; i++) t.trust(fpA, "example:app" + i);

        check(!t.get(fpA).apps().contains("example:first"), "第一个已经被挤出那张列表了");
        eq(t.fingerprintFor("example:first"), fpA, "但钉扎还在（内存）");

        Path f = Files.createTempDirectory("mcphone-pins").resolve("authors.json");
        t.save(f);
        TrustStore back = TrustStore.load(f);
        eq(back.fingerprintFor("example:first"), fpA, "存盘再读回，钉扎仍然在");

        KeyPair other = Signatures.generate();
        Map<String, byte[]> c = content();
        String digest = PackageDigest.of(c);
        eq(TrustState.of(pkg(c, sigJson(other, digest, "y")), "example:first", back).state(),
                TrustState.State.KEY_CHANGED, "换钥重签仍然要抄指纹，没掉回 UNKNOWN_AUTHOR");
    }

    public static void main(String[] args) throws Exception {
        serverPolicy();
        algorithmAndFingerprint();
        signAndVerify();
        domainSeparation();
        metaNotInDigest();
        metaWhitelist();
        sigManifestParsing();
        tamperedPackage();
        trustStates();
        blocked();
        rotation();
        trustStoreRoundTrip();
        authorKeys();
        smallOrderForgery();
        signatureRemoved();
        pinsSurviveAppListCap();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
