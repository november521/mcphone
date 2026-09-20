package com.november.mcphone.core.script.pkg;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 包入口链路的全部拒绝理由。App 包是全模组唯一接受陌生人输入的入口，这里每一条都是硬失败。
 *
 * <p>文案写在枚举上而不是抛出点：同一条判据在两处抛过，文案就会长出两个版本，而用户看到
 * 哪一个取决于走哪条分支。
 */
public final class PackageError extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 错误码与文案。一码一条判据，判据表见施工方案 §3.2（manifest）与 §3.4（路径与 ZIP）。 */
    public enum Code {
        // ── manifest.json（§3.2）
        E_PKG_MANIFEST_SYNTAX("manifest.json 不是合法 JSON：%s"),
        E_PKG_MANIFEST_NOT_OBJECT("manifest.json 的顶层必须是一个 JSON 对象"),
        E_PKG_MANIFEST_DUP_KEY("manifest.json 里的键 '%s' 出现了两次 —— 同一份清单会被读出两种结果"),
        E_PKG_BAD_FORMAT("manifest.json 的 format 必须是 1，收到 %s"),
        E_PKG_MISSING_FIELD("manifest.json 缺少必填字段 '%s'"),
        E_PKG_BAD_TYPE("manifest.json 的字段 '%s' 要 %s，给的是 %s"),
        E_PKG_BAD_ID("manifest.json 的 id 要写成 namespace:path，两段都匹配 [a-z0-9_.-]{1,64}，收到 '%s'"),
        E_PKG_ID_TOO_LONG("manifest.json 的 id 是整条 namespace:path，最长 %d 个字符，收到 %d 个（'%s'）—— 线格式与部署表都按整条算"),
        E_PKG_RESERVED_NAMESPACE("manifest.json 的 id 不许用 mcphone 命名空间：那是内建 App 的，占了会把内建 App 挡在注册表外"),
        E_PKG_BAD_VERSION("manifest.json 的 version 要写成 x.y.z 三段数字，收到 '%s'"),
        E_PKG_TEXT_TOO_LONG("manifest.json 的字段 '%s' 超长：上限 %d，收到 %d"),
        E_PKG_TEXT_CONTROL_CHAR("manifest.json 的字段 '%s' 含控制字符、换行或不可见的格式字符"),
        E_PKG_BAD_ENGINE("manifest.json 的 engine 只认 declarative-1，收到 '%s'"),
        E_PKG_BAD_SDK("manifest.json 的 sdk 段不合法：%s"),
        E_PKG_MISSING_ENTRY("manifest.json 的 '%s' 指向 '%s'，包里没有这个文件"),
        E_PKG_NO_MANIFEST("包里没有 manifest.json —— 它必须在包根"),
        E_PKG_BAD_ICON("<manifest> 的 icon 要写成 data:image/png;base64,…，base64 部分最多 %d 字符，收到 %s"),

        // ── 路径（§3.4）
        E_PKG_BAD_PATH("路径 '%s' 不合法：%s"),
        E_PKG_BAD_ENTRY_NAME("条目名不是合法的 UTF-8"),
        E_PKG_BANNED_EXT("路径 '%s' 的扩展名在禁止清单里：%s"),
        E_PKG_BAD_EXT("路径 '%s' 的扩展名不在允许清单里，允许的是：%s"),
        E_PKG_DUP_PATH("路径 '%s' 与 '%s' 撞车 —— 规范化并忽略大小写后是同一个"),
        E_PKG_TOO_DEEP("路径 '%s' 的目录深度 %d 超过上限 %d"),

        // ── ZIP（§3.4）
        E_PKG_BAD_ZIP("ZIP 结构不合法：%s"),
        E_PKG_TOO_LARGE("包压缩后 %d 字节，超过上限 %d"),
        E_PKG_TOO_MANY_ENTRIES("包内条目 %d 个，超过上限 %d"),
        E_PKG_ENTRY_TOO_LARGE("条目 '%s' 解压后超过上限 %d 字节"),
        E_PKG_TOTAL_TOO_LARGE("包解压后超过上限 %d 字节"),
        E_PKG_RATIO("条目 '%s' 的压缩比 %d:1 超过上限 %d:1"),
        E_PKG_SYMLINK("条目 '%s' 是符号链接"),
        E_PKG_CENTRAL_MISMATCH("ZIP 的中央目录与本地头对不上：%s"),

        // ── META/（§12.4）
        E_PKG_META_EXTRA("META/ 下只许有 sig.json 与 rotate.json，还出现了 '%s'"),

        // ---- §12 签名与信任。只增不减 ----
        E_SIG_BAD_JSON("META/sig.json 不是合法 JSON：%s"),
        E_SIG_MISSING_FIELD("META/sig.json 缺少必填字段 '%s'"),
        E_SIG_BAD_FORMAT("META/sig.json 的 format 必须是 1，收到 %s"),
        E_SIG_UNKNOWN_ALG("不认识的签名算法 '%s' —— 拒绝，【不回退成「未签名」】：那是两档不同的状态"),
        E_SIG_BAD_KEY("密钥不合法：%s"),
        E_SIG_BAD_BASE64("字段 '%s' 不是合法的 base64"),
        E_SIG_DIGEST_MISMATCH("签名里写的摘要与包内容算出来的不符：声称 %s，实际 %s"),
        E_SIG_ROTATE_BAD("密钥轮换声明无效：%s");

        private final String text;

        Code(String text) {
            this.text = text;
        }

        /** 这个码的文案模板。测试拿它对着断言，不必把字符串抄第二遍。 */
        public String text() {
            return text;
        }
    }

    private final Code code;
    private final Object[] args;

    private PackageError(Code code, String message, Object[] args) {
        super(message);
        this.code = code;
        this.args = args;
    }

    public Code code() {
        return code;
    }

    /** 模板的实参，顺序与模板一致。.vue 的 {@code <manifest>} 报错时拿字段名去找它在第几行。 */
    public List<Object> args() {
        return List.of(args);
    }

    /** 按 {@link Code} 自带的模板成文。调用点只给参数，给不出文案。 */
    public static PackageError of(Code code, Object... args) {
        Object[] kept = new Object[args.length];
        for (int i = 0; i < args.length; i++) kept[i] = String.valueOf(args[i]);
        return new PackageError(code, code.name() + "：" + String.format(Locale.ROOT, code.text(), args), kept);
    }

    /**
     * 路径规范化的七条判据（§3.4）。
     *
     * <p>与错误文案同住一个文件：分开放两处时，加判据的人看不见文案表，于是就地编一句。
     */
    public static final class PathRules {

        /** 单段字节上限。 */
        public static final int MAX_SEGMENT_BYTES = 128;
        /** 整条路径的字节上限。 */
        public static final int MAX_PATH_BYTES = 512;
        /** 目录深度上限：a/b/c/d/file.json 恰好到顶。 */
        public static final int MAX_DEPTH = 4;

        /**
         * 允许的扩展名。
         *
         * <p>{@code vue} 是作者真正写的那个格式（§11.1）：zip 形态里入口是 {@code app.vue}、
         * 别的页在 {@code pages/} 下（§11.2）。不收它的话，§11.2 的 zip 形态一个都装不进来。
         *
         * <p>{@code js} 是后端模块（{@code server.js} 与 {@code server/*.js}，§11.2）
         * 与将来客户端脚本的扩展名。少了它 {@code signApp} 与收包两侧都会拒
         * {@code server.js}（PR #44 实跑抓到；旧断言还把"拒 .js"写成了预期，见
         * {@code docs/ScriptPackageTest.java} 的 {@code serverJsPasses}）。
         */
        public static final Set<String> ALLOWED_EXT = Set.of("json", "mss", "png", "txt", "vue", "js");

        /**
         * 禁止的扩展名。它是「不在允许清单里」的真子集，留着只为报得准：收到 evil.class 时
         * 说「在禁止清单里」比说「不在允许清单里」更快指出问题。
         */
        public static final Set<String> BANNED_EXT = Set.of(
                "class", "jar", "zip", "so", "dylib", "dll", "exe", "sh", "bat", "cmd", "jnilib");

        /** Windows 保留设备名。带不带扩展名都算，不分大小写。 */
        private static final Set<String> RESERVED_NAMES = Set.of(
                "con", "prn", "aux", "nul",
                "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
                "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

        /** 拒绝理由。文案与判据一一对应，和 {@link Code} 同一个道理。 */
        public enum Reason {
            EMPTY("空路径"),
            ABSOLUTE("以 / 开头"),
            BACKSLASH("含反斜杠"),
            UNC("以 // 开头"),
            COLON("含冒号 —— 盘符前缀与 Windows 数据流都长这样"),
            CONTROL_CHAR("含 ASCII 控制字符"),
            DOT_SEGMENT("含 . 段"),
            DOTDOT_SEGMENT("含 .. 段"),
            EMPTY_SEGMENT("含空段"),
            SEGMENT_EDGE("有一段以空格或点收尾 —— Windows 会把它们剥掉，于是这个名字在那儿是另一个文件"),
            RESERVED_NAME("含 Windows 保留设备名段"),
            SEGMENT_TOO_LONG("单段超过 " + MAX_SEGMENT_BYTES + " 字节"),
            PATH_TOO_LONG("整条超过 " + MAX_PATH_BYTES + " 字节"),
            NOT_NFC("不是 NFC 规范形式 —— 同一个名字有两种字节写法，摘要会对不上");

            private final String text;

            Reason(String text) {
                this.text = text;
            }

            public String text() {
                return text;
            }
        }

        private PathRules() {
        }

        /** 形状合法即 true。深度与扩展名不在这里判：那两条各报各的码，见 {@link #require}。 */
        public static boolean accept(String path) {
            return reject(path) == null;
        }

        /** 形状不合法时给出理由，合法返回 null。 */
        public static Reason reject(String path) {
            if (path == null || path.isEmpty()) return Reason.EMPTY;
            if (path.startsWith("//")) return Reason.UNC;
            if (path.charAt(0) == '/') return Reason.ABSOLUTE;
            if (path.indexOf('\\') >= 0) return Reason.BACKSLASH;
            if (path.indexOf(':') >= 0) return Reason.COLON;

            for (int i = 0; i < path.length(); i++) {
                char c = path.charAt(i);
                if (c < 0x20 || c == 0x7F) return Reason.CONTROL_CHAR;
            }

            // NFC 之外的写法一律拒：macOS 存 NFD，同一个文件名会算出两个摘要。
            if (!Normalizer.isNormalized(path, Normalizer.Form.NFC)) return Reason.NOT_NFC;

            if (utf8Length(path) > MAX_PATH_BYTES) return Reason.PATH_TOO_LONG;

            for (String seg : path.split("/", -1)) {
                if (seg.isEmpty()) return Reason.EMPTY_SEGMENT;
                if (seg.equals(".")) return Reason.DOT_SEGMENT;
                if (seg.equals("..")) return Reason.DOTDOT_SEGMENT;
                char first = seg.charAt(0);
                char last = seg.charAt(seg.length() - 1);
                if (first == ' ' || last == ' ' || last == '.') return Reason.SEGMENT_EDGE;
                if (utf8Length(seg) > MAX_SEGMENT_BYTES) return Reason.SEGMENT_TOO_LONG;
                if (RESERVED_NAMES.contains(asciiLower(deviceStem(seg)))) return Reason.RESERVED_NAME;
            }
            return null;
        }

        /** 目录深度 = 分隔符个数。{@code lang/zh_cn.json} 是 1。 */
        public static int depth(String path) {
            int n = 0;
            for (int i = 0; i < path.length(); i++) {
                if (path.charAt(i) == '/') n++;
            }
            return n;
        }

        /** 深度超限就抛。目录条目只走到这一步，扩展名判据对它不成立。 */
        public static void requireDepth(String path) {
            int d = depth(path);
            if (d > MAX_DEPTH) throw PackageError.of(Code.E_PKG_TOO_DEEP, path, d, MAX_DEPTH);
        }

        /** 小写扩展名，取最后一个点之后那一段；没有扩展名时返回空串。 */
        public static String extensionOf(String path) {
            String last = path.substring(path.lastIndexOf('/') + 1);
            int dot = last.lastIndexOf('.');
            if (dot <= 0 || dot == last.length() - 1) return "";
            return asciiLower(last.substring(dot + 1));
        }

        /**
         * 整组路径两两不撞车即 true。
         *
         * <p>判据是「NFC 之后按 ASCII 小写」：大小写不敏感的文件系统上 Icon.png 与 icon.PNG
         * 是同一个文件，而摘要会把它们算成两条 —— 装出来的包与算摘要时的包不是同一个。
         */
        public static boolean duplicateSafe(List<String> paths) {
            return firstCollision(paths) == null;
        }

        /** 撞车的那一对（后出现的在前），没有则 null。 */
        public static String[] firstCollision(List<String> paths) {
            Map<String, String> seen = new HashMap<>();
            for (String p : paths) {
                String key = asciiLower(Normalizer.normalize(p, Normalizer.Form.NFC));
                String prev = seen.putIfAbsent(key, p);
                if (prev != null) return new String[]{p, prev};
            }
            return null;
        }

        /** 跑完一条文件路径的全部判据。形状、深度、扩展名各报各的码。 */
        public static void require(String path) {
            Reason why = reject(path);
            if (why != null) throw PackageError.of(Code.E_PKG_BAD_PATH, String.valueOf(path), why.text());

            requireDepth(path);

            String ext = extensionOf(path);
            if (BANNED_EXT.contains(ext)) {
                throw PackageError.of(Code.E_PKG_BANNED_EXT, path, sorted(BANNED_EXT));
            }
            if (!ALLOWED_EXT.contains(ext)) {
                throw PackageError.of(Code.E_PKG_BAD_EXT, path, sorted(ALLOWED_EXT));
            }
        }

        /** 整组一起查，含撞车。 */
        public static void requireAll(List<String> paths) {
            for (String p : paths) require(p);
            String[] hit = firstCollision(paths);
            if (hit != null) throw PackageError.of(Code.E_PKG_DUP_PATH, hit[0], hit[1]);
        }

        /**
         * 拿去比设备名的那一段：第一个点之前，再剥掉尾部的空格与点。
         *
         * <p>Windows 认设备名认的是这一段，{@code con.txt.json} 与 {@code nul } 都落到设备上。
         */
        private static String deviceStem(String segment) {
            int dot = segment.indexOf('.');
            String stem = dot < 0 ? segment : segment.substring(0, dot);
            int end = stem.length();
            while (end > 0 && (stem.charAt(end - 1) == ' ' || stem.charAt(end - 1) == '.')) end--;
            return stem.substring(0, end);
        }

        /** 只折叠 A-Z。§3.4 写的就是 ASCII 小写，用全 Unicode 小写会把开尔文符号折成 k，误杀合法路径。 */
        private static String asciiLower(String s) {
            char[] out = s.toCharArray();
            for (int i = 0; i < out.length; i++) {
                if (out[i] >= 'A' && out[i] <= 'Z') out[i] += 32;
            }
            return new String(out);
        }

        private static int utf8Length(String s) {
            return s.getBytes(StandardCharsets.UTF_8).length;
        }

        private static String sorted(Set<String> exts) {
            List<String> out = new ArrayList<>();
            for (String e : exts) out.add("." + e);
            out.sort(null);
            return String.join(" ", out);
        }
    }
}
