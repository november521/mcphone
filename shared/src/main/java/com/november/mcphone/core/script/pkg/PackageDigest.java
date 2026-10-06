package com.november.mcphone.core.script.pkg;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 包摘要，字节格式见施工方案 §3.3。签名（§12）与自动更新（§22）都拿它当身份，动一个字节
 * 两边全对不上。
 *
 * <p>哈希的是规范化后的内容清单，不是 ZIP 字节：时间戳、压缩级别、条目顺序都会变，
 * 同一个包重打一次就换了身份。
 */
public final class PackageDigest {

    /** 域分隔符。换算法要换它，否则新旧两种算法算出的值会静默地混在一起比。 */
    private static final byte[] DOMAIN = "mcphone-pkg-v1\0".getBytes(StandardCharsets.UTF_8);

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private PackageDigest() {
    }

    /** entries 的 key 是规范化路径，value 是文件内容。 */
    public static String of(Map<String, byte[]> entries) {
        Map<String,byte[]> leaves = new java.util.LinkedHashMap<>();
        entries.forEach((path,content) -> leaves.put(path,leaf(path,content)));
        return ofLeaves(leaves);
    }

    /** 与包摘要原有字节格式完全一致；前端证明只搬运被剔除的后端叶子哈希。 */
    public static byte[] leaf(String path,byte[] content) {
        byte[] pb=path.getBytes(StandardCharsets.UTF_8);
        if(pb.length>PackageError.PathRules.MAX_PATH_BYTES) throw PackageError.of(PackageError.Code.E_PKG_BAD_PATH,path,PackageError.PathRules.Reason.PATH_TOO_LONG.text());
        MessageDigest inner=sha256(); inner.update(u16be(pb.length)); inner.update(pb); inner.update(u64be(content.length)); inner.update(content);
        return inner.digest();
    }

    public static String ofLeaves(Map<String,byte[]> leaves) {
        List<String> paths = new ArrayList<>(leaves.keySet());
        paths.sort(PackageDigest::compareUtf8Bytes);

        MessageDigest outer = sha256();
        outer.update(DOMAIN);
        outer.update(u32be(paths.size()));

        for (String p : paths) {
            byte[] pb = p.getBytes(StandardCharsets.UTF_8);
            // 判据跟着字节格式走，不跟着调用者走：长度前缀是 u16be，路径一旦超过 64 KiB
            // 就回绕，而「长度前缀消掉拼接歧义」这个保证正是建立在它不回绕上的。
            if (pb.length > PackageError.PathRules.MAX_PATH_BYTES) {
                throw PackageError.of(PackageError.Code.E_PKG_BAD_PATH, p,
                        PackageError.PathRules.Reason.PATH_TOO_LONG.text());
            }
            byte[] leaf=leaves.get(p);
            if(leaf==null || leaf.length!=32) throw new IllegalArgumentException("包摘要叶子必须是 SHA-256");
            // 长度前缀不是装饰：没有它，("ab","c") 与 ("a","bc") 算出同一个摘要。
            outer.update(u16be(pb.length));
            outer.update(pb);
            outer.update(leaf);
        }
        return hex(outer.digest());
    }

    /**
     * 按 UTF-8 字节升序比。
     *
     * <p>{@code & 0xFF} 不是可选的：Java 的 byte 有符号，非 ASCII 路径不做无符号化就会排错序，
     * 于是照 §3.3 写的另一个实现算不出同一个值。
     */
    static int compareUtf8Bytes(String a, String b) {
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        int n = Math.min(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int d = (x[i] & 0xFF) - (y[i] & 0xFF);
            if (d != 0) return d;
        }
        return x.length - y.length;
    }

    static byte[] u16be(int v) {
        return new byte[]{(byte) (v >>> 8), (byte) v};
    }

    static byte[] u32be(int v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    static byte[] u64be(long v) {
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) out[i] = (byte) (v >>> (56 - 8 * i));
        return out;
    }

    static String hex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            out[i * 2] = HEX[(bytes[i] >>> 4) & 0xF];
            out[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }
        return new String(out);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，取不到说明运行环境已经不是 Java 了
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
