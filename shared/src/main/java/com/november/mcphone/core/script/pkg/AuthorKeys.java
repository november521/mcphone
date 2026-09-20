package com.november.mcphone.core.script.pkg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;
import java.util.Set;

/**
 * 作者密钥的生成与保管（施工方案 §12.6）。
 *
 * <pre>
 * config/mcphone/keys/author.key   PKCS8，48 字节
 * config/mcphone/keys/author.pub   X.509，44 字节
 * </pre>
 *
 * <h2>私钥永不上传</h2>
 *
 * 不进任何包、不进任何网络包、不进日志。这个类<b>一次都不 toString 私钥字节</b>，
 * 出错时只说文件路径。
 *
 * <h2>Windows 上没有 chmod 600</h2>
 *
 * POSIX 上设 {@code rw-------}；设不了的时候<b>不假装设上了</b> ——
 * {@link #protectedOnDisk()} 返回 false，界面上要明说"此文件未受系统级保护"。
 * 悄悄失败比没有保护更糟：玩家以为有。
 *
 * <h2>丢了就是丢了</h2>
 *
 * 不提供程序内的恢复入口。所以要有「导出备份」（{@link #exportBackup}）——
 * 备份是<b>一个目录、两个文件</b>（{@code author.key} + {@code author.pub}）：
 * 只备私钥的话，换台机器连公钥都没有，签名照样用不了，那份"备份"等于没备。
 * 恢复是手动的：把两个文件放回 {@code config/mcphone/keys/} 再重进那一页。
 *
 * <h2>一对就是一对</h2>
 *
 * 私钥与公钥<b>必须能互相验过</b>（{@link #pairConsistent}）：混了另一把公钥的目录，
 * 签出来的包收包方一律判 INVALID。私钥里没有公钥，<b>公钥丢了没法从私钥重建</b>，
 * 所以这里宁可当场拒签，也不出一个知道会验不过的包。
 *
 * <h2>签名是命令式操作</h2>
 *
 * <b>不提供自动签名。</b>自动签名会让"我只是改个错别字"与"我发布了一个新版本"
 * 变成同一件事 —— 而后者是要作者自己点头的。
 */
public final class AuthorKeys {

    public static final String DIR = "config/mcphone/keys";
    public static final String PRIVATE_FILE = "author.key";
    public static final String PUBLIC_FILE = "author.pub";

    private final PrivateKey priv;
    private final PublicKey pub;
    private final boolean protectedOnDisk;

    private AuthorKeys(PrivateKey priv, PublicKey pub, boolean protectedOnDisk) {
        this.priv = priv;
        this.pub = pub;
        this.protectedOnDisk = protectedOnDisk;
    }

    /** 指纹 —— 作者的身份就是它。 */
    public String fingerprint() {
        return Signatures.fingerprint(pub);
    }

    public PublicKey publicKey() {
        return pub;
    }

    /** 公钥的 X.509 字节，写进 sig.json。 */
    public byte[] publicKeyBytes() {
        return pub.getEncoded();
    }

    /** 文件在磁盘上有没有受系统级保护。false 时界面要明说。 */
    public boolean protectedOnDisk() {
        return protectedOnDisk;
    }

    /** 签一个包摘要。<b>命令式</b>：调用方是「打包并签名」那个动作，不是自动触发。 */
    public byte[] sign(String packageDigest) {
        return Signatures.sign(priv, packageDigest);
    }

    /** 签一份轮换声明（§12.6）。用<b>旧</b>密钥签，声明里带新公钥。 */
    public byte[] signRotation(String oldFingerprint, byte[] newPubkeyX509) {
        return RotateManifest.sign(priv, oldFingerprint, newPubkeyX509);
    }

    // ---------------------------------------------------------------- 生成 / 读 / 写

    /** 有没有已经生成过。 */
    public static boolean exists(Path gameDir) {
        return Files.isRegularFile(gameDir.resolve(DIR).resolve(PRIVATE_FILE));
    }

    /**
     * 生成一对并落盘。已经有了就抛 —— <b>不覆盖</b>：覆盖等于把作者的身份弄丢。
     *
     * <p><b>发布是原子的</b>：两个文件都先写 {@code .tmp}、自检一对能签能验，再按
     * "先公钥、后私钥"的顺序改名。中间崩了只会留下"有公钥没私钥"（{@link #exists} 仍为
     * false，可以重新生成）；反过来则会留下"有私钥没公钥"的死局，而公钥<b>无法</b>从私钥重建。
     */
    public static AuthorKeys generate(Path gameDir) {
        Path dir = gameDir.resolve(DIR);
        if (exists(gameDir)) {
            throw PackageError.of(PackageError.Code.E_SIG_BAD_KEY,
                    "已经有一对密钥了，不覆盖 —— 覆盖等于把作者身份弄丢");
        }
        KeyPair kp = Signatures.generate();
        Path key = dir.resolve(PRIVATE_FILE);
        Path pub = dir.resolve(PUBLIC_FILE);
        Path keyTmp = dir.resolve(PRIVATE_FILE + ".tmp");
        Path pubTmp = dir.resolve(PUBLIC_FILE + ".tmp");
        boolean prot;
        try {
            Files.createDirectories(dir);
            Files.write(pubTmp, kp.getPublic().getEncoded());
            Files.write(keyTmp, kp.getPrivate().getEncoded());
            if (!pairConsistent(kp.getPrivate(), kp.getPublic())) {
                // 概率为零；真发生了说明这个 JRE 的 Ed25519 实现坏了，宁可不出这一对
                throw new IOException("新生成的密钥对自检不过");
            }
            // 先公钥、后私钥：见方法注释里那条"半成品方向"的取舍
            moveInto(pubTmp, pub);
            moveInto(keyTmp, key);
            prot = restrict(key);
        } catch (IOException e) {
            deleteQuietly(keyTmp);
            deleteQuietly(pubTmp);
            deleteQuietly(pub);   // 私钥没改成，公钥就不该单独留着
            throw PackageError.of(PackageError.Code.E_SIG_BAD_KEY, "密钥写不进去：" + e.getMessage());
        }
        return new AuthorKeys(kp.getPrivate(), kp.getPublic(), prot);
    }

    /** 同目录内的改名优先用原子改名；文件系统不支持时退回普通改名，不把"建不了密钥"栽在它头上。 */
    private static void moveInto(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 清理失败不改主错误
        }
    }

    /**
     * 读已有的。
     *
     * <p>三条失败路径要分开报，玩家才知道该放回哪个文件：私钥丢/坏、公钥丢/坏、
     * 两个文件不是一对。第三种是这里新增的 —— 以前混了另一把公钥也能"读成功"，
     * 直到收包方判 INVALID 才发现。
     */
    public static AuthorKeys load(Path gameDir) {
        Path dir = gameDir.resolve(DIR);
        Path key = dir.resolve(PRIVATE_FILE);
        Path pub = dir.resolve(PUBLIC_FILE);

        PrivateKey priv;
        try {
            priv = Signatures.privateKey(Files.readAllBytes(key));
        } catch (IOException | RuntimeException e) {
            // 只说路径，【不说内容】
            throw PackageError.of(PackageError.Code.E_SIG_BAD_KEY, "私钥读不出来：" + key);
        }
        PublicKey publicKey;
        try {
            publicKey = Signatures.publicKey(Files.readAllBytes(pub));
        } catch (IOException | RuntimeException e) {
            // 公钥丢了不能从私钥重建（PKCS8 里没有公钥），只能靠备份里的 author.pub
            throw PackageError.of(PackageError.Code.E_SIG_BAD_KEY,
                    "公钥读不出来：" + pub + "（私钥还在，但公钥没法从私钥重建 —— 把备份里的 author.pub 放回原处）");
        }
        if (!pairConsistent(priv, publicKey)) {
            throw PackageError.of(PackageError.Code.E_SIG_BAD_KEY,
                    "author.key 与 author.pub 不是一对（签名互相验不过）—— 用备份恢复这两个文件，别拿它签包");
        }
        return new AuthorKeys(priv, publicKey, isRestricted(key));
    }

    /**
     * 这对密钥能不能互签互验。Ed25519 私钥里<b>没有</b>公钥，所以判据只能是
     * "拿私钥签一个探针、拿公钥验它"，而不是比字节。
     */
    private static boolean pairConsistent(PrivateKey priv, PublicKey pub) {
        try {
            byte[] sig = Signatures.sign(priv, PAIR_PROBE);
            return Signatures.verify(pub, PAIR_PROBE, sig);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 自检用的探针。<b>不是包摘要</b>，只是让"签一笔、验一笔"跑起来。 */
    private static final String PAIR_PROBE = "mcphone-key-pair-check";

    /**
     * 导出备份。<b>丢了就是丢了</b>，所以这个按钮必须有，而且界面上要写明这句话。
     *
     * <p>导出到<b>一个目录</b>，里面是 {@code author.key} 与 {@code author.pub} 两个文件 ——
     * 只导私钥的话收不到包方，恢复时缺公钥照样用不了。导出前先 {@link #load} 一遍：
     * 坏密钥不往外备份。
     */
    public static void exportBackup(Path gameDir, Path dir) {
        load(gameDir);   // 先确认这一对是好的；坏了就抛，不把坏文件当"备份"
        Path src = gameDir.resolve(DIR);
        try {
            Files.createDirectories(dir);
            Files.copy(src.resolve(PRIVATE_FILE), dir.resolve(PRIVATE_FILE),
                    StandardCopyOption.REPLACE_EXISTING);
            Files.copy(src.resolve(PUBLIC_FILE), dir.resolve(PUBLIC_FILE),
                    StandardCopyOption.REPLACE_EXISTING);
            restrict(dir.resolve(PRIVATE_FILE));
        } catch (IOException e) {
            throw PackageError.of(PackageError.Code.E_SIG_BAD_KEY, "备份写不出去：" + dir);
        }
    }

    /** POSIX 上设成 {@code rw-------}。设不了返回 false，<b>不假装设上了</b>。 */
    private static boolean restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file,
                    Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // Windows / 沙箱安全策略走到这里。真正的 ACL 要平台代码，本步只如实回答"没设上"
            return false;
        }
    }

    /**
     * 私钥文件的权限是不是"只有自己能读写"。
     *
     * <p>判据是<b>白名单</b>：所有权限位都必须落在 {@code {OWNER_READ, OWNER_WRITE}} 里，
     * 且至少有 OWNER_READ。老的"位数为 2 且含 OWNER_READ"会把 {@code {OWNER_READ, GROUP_READ}}
     * 误判成已保护 —— 组可读在共享机器上就是别人能读你的私钥。
     */
    private static boolean isRestricted(Path file) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            if (perms.isEmpty() || !perms.contains(PosixFilePermission.OWNER_READ)) return false;
            for (PosixFilePermission p : perms) {
                if (p != PosixFilePermission.OWNER_READ && p != PosixFilePermission.OWNER_WRITE) {
                    return false;
                }
            }
            return true;
        } catch (IOException | UnsupportedOperationException e) {
            return false;
        }
    }

    /** 给「打包并签名」用：拼出 {@code META/sig.json} 的字节。 */
    public byte[] buildSigJson(String packageDigest, String author, long signedAtEpochSeconds) {
        byte[] sig = sign(packageDigest);
        // 出包前自己验一遍：混了公钥的目录签出来的东西收包方一律判 INVALID，
        // 与其让作者事后被玩家问，不如在这里当场拒
        if (!Signatures.verify(pub, packageDigest, sig)) {
            throw PackageError.of(PackageError.Code.E_SIG_BAD_KEY,
                    "公私钥不是一对，拒绝出包（签名用私钥、包里带的是 author.pub）");
        }
        String json = "{"
                + "\"format\":" + SigManifest.FORMAT + ","
                + "\"alg\":\"" + Signatures.ALG + "\","
                + "\"digest\":\"" + SigManifest.DIGEST_PREFIX + packageDigest + "\","
                + "\"pubkey\":\"" + Base64.getEncoder().encodeToString(publicKeyBytes()) + "\","
                + "\"sig\":\"" + Base64.getEncoder().encodeToString(sig) + "\","
                + "\"author\":\"" + escape(author) + "\","
                + "\"signedAt\":" + signedAtEpochSeconds
                + "}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : (s == null ? "" : s).toCharArray()) {
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c >= ' ') sb.append(c);
        }
        return sb.toString();
    }
}
