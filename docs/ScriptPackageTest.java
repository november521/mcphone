package com.november.mcphone.core.script.pkg;

import com.november.mcphone.core.script.pkg.PackageError.Code;
import com.november.mcphone.core.script.pkg.PackageError.PathRules;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 包入口链路的断言测试（施工方案 §3.5），用 javac 单独编，不需要 Minecraft。
 *
 * <p>恶意样本的构造代码就在 {@link #maliciousZips()} 与 {@link #forgedZips()} 里，长期留档：
 * 改判据的人能照着再跑一遍，不必去翻当时的会话记录。
 */
public class ScriptPackageTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) {
            failures.add(what + "  期望 " + expected + "，实际 " + actual);
        }
    }

    static void check(boolean cond, String what) {
        checks++;
        if (!cond) failures.add(what);
    }

    static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static void report() {
        System.out.println();
        if (failures.isEmpty()) {
            System.out.println("全部通过：" + checks + " 条断言");
        } else {
            System.out.println("失败 " + failures.size() + " / " + checks + " 条：");
            for (String f : failures) System.out.println("  ✗ " + f);
            System.exit(1);
        }
    }

    public static void main(String[] a) throws Exception {
        // 摘要确定性
        eq(PackageDigest.of(Map.of("a.json", b("x"), "b.mss", b("y"))),
           PackageDigest.of(Map.of("b.mss", b("y"), "a.json", b("x"))),
           "摘要不受插入顺序影响");

        // 长度前缀有效：拼接歧义不能撞
        check(!PackageDigest.of(Map.of("ab", b("c"))).equals(
               PackageDigest.of(Map.of("a", b("bc")))),
              "路径/内容的拼接歧义不产生同一摘要");

        // 内容变一个字节 → 摘要变
        check(!PackageDigest.of(Map.of("a", b("x"))).equals(
               PackageDigest.of(Map.of("a", b("y")))),
              "内容变化改变摘要");

        // 非 ASCII 路径排序（无符号比较）
        eq(PackageDigest.of(Map.of("中.json", b("1"), "a.json", b("2"))),
           PackageDigest.of(Map.of("a.json", b("2"), "中.json", b("1"))),
           "非 ASCII 路径排序稳定");

        // 路径规范化
        for (String bad : new String[]{ "../x", "/x", "a\\b", "C:/x", "a/./b",
                                        "con.json", "a:b", "x\u0001" })
            check(!PathRules.accept(bad), "拒绝非法路径: " + bad);

        check(PathRules.accept("lang/zh_cn.json"), "接受正常路径");
        check(!PathRules.duplicateSafe(List.of("Icon.png", "icon.PNG")), "拒绝大小写撞车");

        digestDetails();
        pathRuleDetails();
        manifestRules();
        happyPath();
        serverJsPasses();
        maliciousZips();
        forgedZips();

        report();
    }

    // ============================================================
    //  摘要
    // ============================================================

    static void digestDetails() {
        eq(PackageDigest.of(Map.of()).length(), 64, "SHA-256 十六进制是 64 个字符");

        check(!PackageDigest.of(Map.of()).equals(
               PackageDigest.of(Map.of("", b("")))),
              "零条目与一条空条目不是同一个摘要");

        check(!PackageDigest.of(Map.of("a.json", b("x"))).equals(
               PackageDigest.of(Map.of("b.json", b("x")))),
              "路径变化改变摘要");

        check(!PackageDigest.of(Map.of("a", b("xy"))).equals(
               PackageDigest.of(Map.of("a", b("x"), "b", b("y")))),
              "条目数进了摘要");

        // 无符号字节序：U+4E2D 的首字节是 0xE4，有符号比较会把它排到 'a'(0x61) 前面
        eq(PackageDigest.compareUtf8Bytes("中", "a") > 0, true, "非 ASCII 按无符号字节排在 ASCII 之后");
        eq(PackageDigest.compareUtf8Bytes("a", "ab") < 0, true, "前缀短的排前面");
        eq(PackageDigest.compareUtf8Bytes("a", "a"), 0, "相同路径比出 0");

        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("ui.json", b("{}"));
        m.put("ui.mss", b(".a { }"));
        eq(PackageDigest.of(m), PackageDigest.of(m), "同一份输入算两次结果相同");

        // 长度前缀是 u16be，路径超过 64 KiB 就回绕 —— 判据得跟着字节格式走，不能指望调用者
        String huge = "x".repeat(PathRules.MAX_PATH_BYTES + 1);
        eq(codeOf(() -> PackageDigest.of(Map.of(huge, b("x")))), Code.E_PKG_BAD_PATH,
           "摘要自己挡住超长路径，不指望调用者先查过");
    }

    // ============================================================
    //  路径判据
    // ============================================================

    static void pathRuleDetails() {
        check(!PathRules.accept(""), "拒绝空路径");
        check(!PathRules.accept("//host/share"), "拒绝 UNC");
        check(!PathRules.accept("a//b"), "拒绝空段");
        check(!PathRules.accept(".."), "拒绝单独的 ..");
        check(!PathRules.accept("a/../b"), "拒绝中间的 ..");
        check(!PathRules.accept("NUL"), "拒绝保留名 NUL");
        check(!PathRules.accept("lpt9.txt"), "拒绝保留名 lpt9.txt");
        check(!PathRules.accept("COM1.json"), "拒绝保留名 COM1.json（不分大小写）");
        check(!PathRules.accept("x\u007F"), "拒绝 DEL");
        check(!PathRules.accept("a/" + "x".repeat(129) + ".json"), "拒绝超长单段");

        // Windows 会剥掉段尾的空格与点，剥完就是另一个文件
        check(!PathRules.accept("nul .json"), "拒绝 'nul .json'：Windows 剥掉尾空格就是 NUL 设备");
        check(!PathRules.accept("com1 .txt"), "拒绝 'com1 .txt'");
        check(!PathRules.accept("lang./zh_cn.json"), "拒绝以点收尾的目录段");
        check(!PathRules.accept("lang /zh_cn.json"), "拒绝以空格收尾的目录段");
        check(!PathRules.accept(" lang/zh_cn.json"), "拒绝以空格开头的段");
        check(!PathRules.accept(".../zh_cn.json"), "拒绝 '...' 段");
        check(!PathRules.accept("a/b.json "), "拒绝以空格收尾的文件段");

        // 整条超长：单段上限 128，五段刚好越过 512
        String longPath = String.join("/", "x".repeat(128), "y".repeat(128),
                "z".repeat(128), "w".repeat(128), "v".repeat(20) + ".json");
        check(!PathRules.accept(longPath), "拒绝整条超过 512 字节的路径");

        // NFC：café 的两种写法不能同时算数
        check(!PathRules.accept("cafe\u0301.json"), "拒绝 NFD 写法的路径");
        check(PathRules.accept("caf\u00E9.json"), "接受 NFC 写法的路径");

        check(PathRules.accept("manifest.json"), "接受包根的 manifest.json");
        check(PathRules.accept("中文/文件.json"), "接受非 ASCII 路径");
        check(PathRules.accept("community.json"), "com 开头但不是保留名的照收");
        check(!PathRules.accept("\u212A.png"), "开尔文符号不是 NFC 形式，按第七条拒");
        // 撞车判据按 ASCII 折叠，不按全量小写：İ 的全量小写是 i+U+0307，会和一个真的
        // i+U+0307 撞上，而那是两个不同的文件名
        check(PathRules.accept("\u0130.png"), "土耳其 İ 是 NFC 形式，照收");
        check(PathRules.duplicateSafe(List.of("\u0130.png", "i\u0307.png")),
              "ASCII 折叠不把 İ 折成 i+U+0307，合法包不误杀");

        eq(PathRules.depth("lang/zh_cn.json"), 1, "一层目录深度为 1");
        eq(PathRules.depth("manifest.json"), 0, "包根深度为 0");
        eq(PathRules.depth("a/b/c/d/e.json"), 4, "四层目录深度为 4");
        eq(PathRules.extensionOf("icon.PNG"), "png", "扩展名按小写取");
        eq(PathRules.extensionOf("noext"), "", "没有扩展名时给空串");
        eq(PathRules.extensionOf(".hidden"), "", "点开头的不算扩展名");

        check(PathRules.duplicateSafe(List.of("a.json", "b.json")), "不撞的一组放行");
        check(!PathRules.duplicateSafe(List.of("a.json", "a.json")), "完全相同也是撞车");

        // accept 只管形状；深度与扩展名由 require 判，各报各的码
        check(PathRules.accept("a/b/c/d/e/f.json"), "accept 只管形状，不管深度");
        check(PathRules.accept("evil.class"), "accept 只管形状，不管扩展名");
        eq(codeOf(() -> PathRules.require("a/b/c/d/e.json")), null, "深度正好到顶的放行");
        eq(codeOf(() -> PathRules.require("a/b/c/d/e/f.json")), Code.E_PKG_TOO_DEEP, "深度超限报 TOO_DEEP");
        eq(codeOf(() -> PathRules.require("evil.class")), Code.E_PKG_BANNED_EXT, "禁止扩展名报 BANNED_EXT");
        eq(codeOf(() -> PathRules.require("a.yaml")), Code.E_PKG_BAD_EXT, "不在白名单报 BAD_EXT");
        // .vue 是作者真正写的那个格式（§11.1）：不收它的话 §11.2 的 zip 形态一个都装不进来
        eq(codeOf(() -> PathRules.require("app.vue")), null, "收 .vue");
        eq(codeOf(() -> PathRules.require("pages/detail.vue")), null, "pages/ 下的 .vue 也收");
        // PR #44 实跑抓到的洞：原先这里断言"P0 还不收 .js"，而设计侧全程用 server.js ——
        // 白名单少一个 js，signApp 与收包两侧都会拒掉后端模块。预期不许再写反
        eq(codeOf(() -> PathRules.require("server.js")), null, "收 .js（后端入口 server.js）");
        eq(codeOf(() -> PathRules.require("server/util.js")), null, "server/ 下的 .js 模块也收");
        eq(codeOf(() -> PathRules.require("evil.yaml")), Code.E_PKG_BAD_EXT, "白名单外的扩展名照拒");
        eq(codeOf(() -> PathRules.require("../x.json")), Code.E_PKG_BAD_PATH, "形状不对报 BAD_PATH");
        eq(codeOf(() -> PathRules.requireAll(List.of("Icon.png", "icon.PNG"))), Code.E_PKG_DUP_PATH,
           "撞车报 DUP_PATH");
    }

    // ============================================================
    //  manifest.json
    // ============================================================

    /** 一份合规的 manifest，字段值是原始 JSON 片段 —— 下面按字段改它，不必每次抄一整份。 */
    static Map<String, String> goodManifestFields() {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("format", "1");
        f.put("id", "\"example:notice\"");
        f.put("version", "\"1.0.0\"");
        f.put("name", "\"服务器公告\"");
        f.put("author", "\"yumeka\"");
        f.put("description", "\"看看今天有什么事\"");
        f.put("icon", "\"icon.png\"");
        f.put("ui", "{\"tree\":\"ui.json\",\"style\":\"ui.mss\"}");
        f.put("engine", "\"declarative-1\"");
        return f;
    }

    static String manifestJson(Map<String, String> fields) {
        StringBuilder sb = new StringBuilder("{");
        for (Map.Entry<String, String> e : fields.entrySet()) {
            if (sb.length() > 1) sb.append(',');
            sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
        }
        return sb.append('}').toString();
    }

    static String with(String field, String rawJsonValue) {
        Map<String, String> f = goodManifestFields();
        f.put(field, rawJsonValue);
        return manifestJson(f);
    }

    static String without(String field) {
        Map<String, String> f = goodManifestFields();
        f.remove(field);
        return manifestJson(f);
    }

    static final String GOOD_MANIFEST = manifestJson(goodManifestFields());

    static void manifestRules() {
        Manifest m = Manifest.parse(GOOD_MANIFEST);
        eq(m.id(), "example:notice", "id 拼得回去");
        eq(m.namespace(), "example", "namespace 拆出来了");
        eq(m.path(), "notice", "path 拆出来了");
        eq(m.uiTree(), "ui.json", "ui.tree 拆出来了");

        eq(codeOf(() -> Manifest.parse("{")), Code.E_PKG_MANIFEST_SYNTAX, "坏 JSON 报 MANIFEST_SYNTAX");
        eq(codeOf(() -> Manifest.parse("[]")), Code.E_PKG_MANIFEST_NOT_OBJECT, "顶层不是对象就拒");
        eq(codeOf(() -> Manifest.parse(without("format"))), Code.E_PKG_MISSING_FIELD, "缺 format");
        eq(codeOf(() -> Manifest.parse(with("format", "2"))), Code.E_PKG_BAD_FORMAT, "format 不是 1 就拒整包");
        eq(codeOf(() -> Manifest.parse(with("format", "\"1\""))), Code.E_PKG_BAD_TYPE, "format 是字符串也拒");
        eq(codeOf(() -> Manifest.parse(with("format", "1.5"))), Code.E_PKG_BAD_TYPE, "format 是小数也拒");
        eq(codeOf(() -> Manifest.parse(with("format", "1.0"))), Code.E_PKG_BAD_TYPE, "1.0 不算整数 1");
        eq(codeOf(() -> Manifest.parse(with("format", "1e0"))), Code.E_PKG_BAD_TYPE, "1e0 不算整数 1");

        eq(codeOf(() -> Manifest.parse(with("id", "\"notice\""))), Code.E_PKG_BAD_ID, "id 缺 namespace");
        eq(codeOf(() -> Manifest.parse(with("id", "\"a:b:c\""))), Code.E_PKG_BAD_ID, "id 多一个冒号");
        eq(codeOf(() -> Manifest.parse(with("id", "\"Example:notice\""))), Code.E_PKG_BAD_ID, "id 不许大写");
        eq(codeOf(() -> Manifest.parse(with("id", "\"example:\""))), Code.E_PKG_BAD_ID, "id 的 path 不许空");
        eq(codeOf(() -> Manifest.parse(with("id", "\"mcphone:notice\""))), Code.E_PKG_RESERVED_NAMESPACE,
           "mcphone 命名空间是内建 App 的");

        // ADV-S2b-3：ID_SEGMENT 的 64 是【每段】上限，整条 id 另有 64 的上限
        // （与 ScriptProtocol.ID_MAX / Deployment.MAX_ID_LEN 同一个数）。不限整条的话，
        // 两段各 64 能拼出 129 字符的 id：客户端装得下、ScriptRpc 编码不出来、服务端永远批不了
        String seg64 = "x".repeat(64);
        String total64 = "a:" + "b".repeat(62);
        eq(Manifest.parse(with("id", "\"" + total64 + "\"")).id(), total64, "整条 id 恰好 64 字符：通过");
        eq(codeOf(() -> Manifest.parse(with("id", "\"a:" + seg64 + "\""))), Code.E_PKG_ID_TOO_LONG,
           "整条 66 字符：拒绝（每段都合法也不行）");
        eq(codeOf(() -> Manifest.parse(with("id", "\"" + seg64 + ":" + seg64 + "\""))),
           Code.E_PKG_ID_TOO_LONG, "两段各 64 拼出 129 字符：拒绝");
        eq(Manifest.MAX_ID, 64, "整条 id 上限与线格式的 64 是同一个数（改一处必须三处一起改）");

        eq(codeOf(() -> Manifest.parse(with("version", "\"1.0\""))), Code.E_PKG_BAD_VERSION, "version 要三段");
        eq(codeOf(() -> Manifest.parse(with("version", "\"1.0.0-beta\""))), Code.E_PKG_BAD_VERSION,
           "version 不收后缀");

        eq(codeOf(() -> Manifest.parse(with("name", "\"" + "x".repeat(65) + "\""))),
           Code.E_PKG_TEXT_TOO_LONG, "name 上限 64");
        eq(codeOf(() -> Manifest.parse(with("author", "\"" + "x".repeat(33) + "\""))),
           Code.E_PKG_TEXT_TOO_LONG, "author 上限 32");
        eq(codeOf(() -> Manifest.parse(with("description", "\"" + "x".repeat(257) + "\""))),
           Code.E_PKG_TEXT_TOO_LONG, "description 上限 256");
        eq(codeOf(() -> Manifest.parse(with("name", "\"a\\nb\""))), Code.E_PKG_TEXT_CONTROL_CHAR, "name 禁换行");
        eq(codeOf(() -> Manifest.parse(with("name", "\"a\\u0007b\""))), Code.E_PKG_TEXT_CONTROL_CHAR,
           "name 禁控制字符");

        // 只判 ASCII 控制字符不够：这几个都是看不见的，而名字要显示给人看
        eq(codeOf(() -> Manifest.parse(with("name", "\"\\u202Ecute\""))), Code.E_PKG_TEXT_CONTROL_CHAR,
           "name 禁方向覆盖字符 U+202E");
        eq(codeOf(() -> Manifest.parse(with("author", "\"Mojang\\u200B\""))), Code.E_PKG_TEXT_CONTROL_CHAR,
           "author 禁零宽空格 —— 否则能一字不差地伪装成别人");
        eq(codeOf(() -> Manifest.parse(with("name", "\"a\\u0085b\""))), Code.E_PKG_TEXT_CONTROL_CHAR,
           "name 禁 Unicode 换行 U+0085");
        eq(codeOf(() -> Manifest.parse(with("name", "\"a\\u2028b\""))), Code.E_PKG_TEXT_CONTROL_CHAR,
           "name 禁行分隔符 U+2028");

        eq(codeOf(() -> Manifest.parse(with("engine", "\"declarative-2\""))), Code.E_PKG_BAD_ENGINE,
           "engine 只认 declarative-1");
        eq(codeOf(() -> Manifest.parse(with("icon", "\"../icon.png\""))), Code.E_PKG_BAD_PATH,
           "icon 走的是同一套路径判据");
        eq(codeOf(() -> Manifest.parse(with("icon", "\"icon.exe\""))), Code.E_PKG_BANNED_EXT,
           "icon 的扩展名也在管");

        // §3.2 的表头是「全部硬失败，不宽容」—— 表里的字段一个都不能缺
        eq(codeOf(() -> Manifest.parse(without("description"))), Code.E_PKG_MISSING_FIELD, "description 必填");
        eq(codeOf(() -> Manifest.parse(without("name"))), Code.E_PKG_MISSING_FIELD, "name 必填");
        eq(codeOf(() -> Manifest.parse(without("engine"))), Code.E_PKG_MISSING_FIELD, "engine 必填");

        // ui 是那一列里唯一可省的：省了就是 §11.2 的 zip 形态，入口是 app.vue、样式在它的 <style> 块里。
        // 原来这里断言「ui 必填」，那是照 §3.2 的 ui.json 写的；§11.2 之后作者写的是 .vue，包里根本没有 ui.json
        Manifest noUi = Manifest.parse(without("ui"));
        eq(noUi.uiTree(), Manifest.DEFAULT_ENTRY, "不写 ui 时入口是 app.vue");
        eq(noUi.uiStyle(), null, "不写 ui 时没有单独的样式文件");
        eq(Manifest.parse(GOOD_MANIFEST).uiStyle(), "ui.mss", "写了 ui 的照旧");

        // Gson 默认是宽容的，清单不能跟着宽容
        eq(codeOf(() -> Manifest.parse("{format:1}")), Code.E_PKG_MANIFEST_SYNTAX, "拒绝无引号的键");
        eq(codeOf(() -> Manifest.parse("{'format':1}")), Code.E_PKG_MANIFEST_SYNTAX, "拒绝单引号");
        eq(codeOf(() -> Manifest.parse("// hi\n" + GOOD_MANIFEST)), Code.E_PKG_MANIFEST_SYNTAX, "拒绝行注释");
        eq(codeOf(() -> Manifest.parse(GOOD_MANIFEST + GOOD_MANIFEST)), Code.E_PKG_MANIFEST_SYNTAX,
           "拒绝末尾多出来的内容");

        // 重复键：后者静默覆盖前者，于是同一份清单有两种读法
        eq(codeOf(() -> Manifest.parse(
                "{\"id\":\"safe:app\",\"id\":\"attacker:app\",\"format\":1}")),
           Code.E_PKG_MANIFEST_DUP_KEY, "拒绝重复的 id 键");
        eq(codeOf(() -> Manifest.parse(with("ui", "{\"tree\":\"a.json\",\"tree\":\"b.json\"}"))),
           Code.E_PKG_MANIFEST_DUP_KEY, "子对象里的重复键一样拒");

        // 名字按代码点数，不按 char：表情占两个 char
        Manifest emoji = Manifest.parse(with("name", "\"" + "🙂".repeat(64) + "\""));
        eq(emoji.name().codePointCount(0, emoji.name().length()), 64, "64 个表情不算超长");
    }

    // ============================================================
    //  一个能读进来的包
    // ============================================================

    static Map<String, byte[]> goodEntries() {
        Map<String, byte[]> m = new LinkedHashMap<>();
        m.put("manifest.json", b(GOOD_MANIFEST));
        m.put("ui.json", b("{\"type\":\"column\"}"));
        m.put("ui.mss", b(".title { color: $title; }"));
        m.put("icon.png", new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        m.put("lang/zh_cn.json", b("{\"a\":\"b\"}"));
        return m;
    }

    static void happyPath() throws Exception {
        AppPackage pkg = PackageReader.read(zip(goodEntries()));
        eq(pkg.manifest().id(), "example:notice", "读出来的 id 对");
        eq(pkg.paths().size(), 5, "五条内容");
        eq(new String(pkg.entry("ui.json"), StandardCharsets.UTF_8), "{\"type\":\"column\"}",
           "内容原样读出");
        eq(pkg.digest(), PackageDigest.of(goodEntries()), "包摘要与直接按条目算的一致");
        eq(pkg.signed(), false, "没有 META/sig.json 就是没签名");

        // 重打一次 zip（条目顺序反过来）→ 摘要必须一样
        Map<String, byte[]> reversed = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(goodEntries().keySet());
        for (int i = keys.size() - 1; i >= 0; i--) reversed.put(keys.get(i), goodEntries().get(keys.get(i)));
        eq(PackageReader.read(zip(reversed)).digest(), pkg.digest(), "条目顺序不改变摘要");

        // STORED 的条目也读得出来
        eq(PackageReader.read(zipStored(goodEntries())).digest(), pkg.digest(),
           "不压缩的包与压缩的包算出同一个摘要");

        // 拿到的是副本，改了不影响包
        byte[] stolen = pkg.entry("ui.json");
        stolen[0] = 'X';
        eq(pkg.entry("ui.json")[0], (byte) '{', "entry() 给的是副本");

        // 造包时手里那份也不能再影响它
        Map<String, byte[]> mutable = goodEntries();
        AppPackage snapshot = PackageReader.read(zip(mutable));
        String before = snapshot.digest();
        eq(snapshot.entries().get("ui.json")[0], (byte) '{', "entries() 给的是副本");
        eq(snapshot.digest(), before, "摘要不随外部数组变化");

        // META/sig.json 不进摘要（§12.4）
        Map<String, byte[]> signed = goodEntries();
        signed.put("META/sig.json", b("{\"alg\":\"ed25519\"}"));
        AppPackage s1 = PackageReader.read(zip(signed));
        eq(s1.digest(), pkg.digest(), "META/ 不进摘要条目集");
        eq(s1.signed(), true, "带 sig.json 就是签了名");
        eq(s1.paths().contains("META/sig.json"), false, "META/ 不出现在内容条目里");

        Map<String, byte[]> signedAgain = goodEntries();
        signedAgain.put("META/sig.json", b("{\"alg\":\"ed25519\",\"sig\":\"另一份\"}"));
        eq(PackageReader.read(zip(signedAgain)).digest(), pkg.digest(), "只改 sig.json 不改变摘要");

        // 目录条目照收，但不进内容
        eq(PackageReader.read(zipWithDirectories(goodEntries())).digest(), pkg.digest(),
           "zip 里的目录条目不影响摘要");

        // manifest 指到的文件必须真在包里
        Map<String, byte[]> noIcon = goodEntries();
        noIcon.remove("icon.png");
        byte[] noIconZip = zip(noIcon);
        eq(codeOf(() -> PackageReader.read(noIconZip)), Code.E_PKG_MISSING_ENTRY, "icon 不在包里就拒");

        Map<String, byte[]> noManifest = goodEntries();
        noManifest.remove("manifest.json");
        byte[] noManifestZip = zip(noManifest);
        eq(codeOf(() -> PackageReader.read(noManifestZip)), Code.E_PKG_NO_MANIFEST,
           "没有 manifest.json 报自己的码");

        Map<String, byte[]> metaExtra = goodEntries();
        metaExtra.put("META/notes.txt", b("hi"));
        byte[] metaExtraZip = zip(metaExtra);
        eq(codeOf(() -> PackageReader.read(metaExtraZip)), Code.E_PKG_META_EXTRA,
           "META/ 下多一个文件就拒整包");
    }

    // ============================================================
    //  server.js 必须过得去（PR #44 实跑抓到）
    // ============================================================

    /**
     * 白名单漏了 {@code js}：设计侧全程按 {@code server.js} / {@code server/*.js} 取后端
     * （{@code FrontendDigest.SERVER_ENTRY}、{@code PackageReader}、{@code ServerPackageScanner}），
     * 而 {@link PathRules#ALLOWED_EXT} 里没有它 —— {@code signApp} 第一步就拒真包。
     *
     * <p>这条用例**真走读包路径**（{@link PackageReader#read(byte[])}）与**签名路径**
     * （{@link AppSigner#readDir(Path)}），不像旧用例那样把条目直接塞进 Map 绕开白名单。
     */
    static void serverJsPasses() throws Exception {
        Map<String, byte[]> entries = goodEntries();
        entries.put("server.js", b("actions.ping = function (ctx) { return ctx.ok({}); };\n"));
        entries.put("server/util.js", b("var util = {};\n"));

        AppPackage pkg = PackageReader.read(zip(entries));
        check(pkg.entry("server.js") != null, "带 server.js 的包能过读包白名单");
        check(pkg.entry("server/util.js") != null, "server/ 下的 .js 模块也能过");

        // 签名侧：AppSigner.readDir 先跑 PathRules.require，对同一批文件要给出同一份条目表
        Path dir = Files.createTempDirectory("mcphone-sign-readdir");
        try {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                Path f = dir.resolve(e.getKey());
                Files.createDirectories(f.getParent());
                Files.write(f, e.getValue());
            }
            Map<String, byte[]> read = AppSigner.readDir(dir);
            check(read.containsKey("server.js"), "signApp 的读目录能收 server.js");
            check(read.containsKey("server/util.js"), "signApp 的读目录能收 server/util.js");
        } finally {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    // ============================================================
    //  7 个恶意样本
    // ============================================================

    static void maliciousZips() throws Exception {
        // ① 穿越：条目名指到包外
        Map<String, byte[]> traversal = goodEntries();
        traversal.put("../evil.json", b("{}"));
        rejects(zip(traversal), Code.E_PKG_BAD_PATH, "① 穿越：../evil.json");

        Map<String, byte[]> absolute = goodEntries();
        absolute.put("/etc/passwd.json", b("{}"));
        rejects(zip(absolute), Code.E_PKG_BAD_PATH, "① 穿越：绝对路径");

        // ② 炸弹：一条目解压出 1 MiB
        Map<String, byte[]> bomb = goodEntries();
        bomb.put("bomb.txt", new byte[1024 * 1024]);
        rejects(zip(bomb), Code.E_PKG_ENTRY_TOO_LARGE, "② 炸弹：单条目解压超 256 KiB");

        // ②' 压缩比：200 KiB 全零压成几百字节，没到单条上限但比值离谱
        Map<String, byte[]> ratio = goodEntries();
        ratio.put("pad.txt", new byte[200 * 1024]);
        rejects(zip(ratio), Code.E_PKG_RATIO, "②' 压缩比超 100:1");

        // ③ 重名：大小写不敏感的文件系统上是同一个文件
        Map<String, byte[]> dup = goodEntries();
        dup.put("Icon.png", b("另一张"));
        rejects(zip(dup), Code.E_PKG_DUP_PATH, "③ 重名：Icon.png 撞 icon.png");

        // ④ 超大：每条都在上限内、压缩比也正常，加起来超 1 MiB
        Map<String, byte[]> huge = goodEntries();
        for (int i = 0; i < 6; i++) huge.put("pad" + i + ".txt", lowRatioBlob(200 * 1024, i));
        rejects(zip(huge), Code.E_PKG_TOTAL_TOO_LARGE, "④ 超大：解压后总量超 1 MiB");

        // ④' 压缩后就超限：连读都不该读进来
        Map<String, byte[]> fat = goodEntries();
        fat.put("fat.txt", randomBytes(300 * 1024, 42));
        rejects(zip(fat), Code.E_PKG_TOO_LARGE, "④' 压缩后超 256 KiB");

        // ⑤ 保留名：Windows 上 con.json 打不开
        Map<String, byte[]> reserved = goodEntries();
        reserved.put("con.json", b("{}"));
        rejects(zip(reserved), Code.E_PKG_BAD_PATH, "⑤ 保留名：con.json");

        Map<String, byte[]> reservedSpace = goodEntries();
        reservedSpace.put("nul .json", b("{}"));
        rejects(zip(reservedSpace), Code.E_PKG_BAD_PATH, "⑤ 保留名：'nul .json'（尾空格会被剥掉）");

        // ⑥ 含 .class：包里不许有可执行的东西
        Map<String, byte[]> clazz = goodEntries();
        clazz.put("Evil.class", new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
        rejects(zip(clazz), Code.E_PKG_BANNED_EXT, "⑥ 含 .class");

        // ⑦ 声明长度造假：头里的两个长度是攻击者写的，所以只拿来对，不拿来用
        Map<String, byte[]> liar = goodEntries();
        liar.put("bomb.txt", new byte[300 * 1024]);
        rejects(patchCentral(zip(liar), "bomb.txt", 24, 10),
                Code.E_PKG_ENTRY_TOO_LARGE,
                "⑦ 声明长度造假：声明 10 字节、实际 300 KiB —— 按数出来的字节拦");

        byte[] good = zip(goodEntries());
        rejects(patchCentral(good, "ui.json", 24, 10),
                Code.E_PKG_CENTRAL_MISMATCH, "⑦ 声明长度造假：解压长度写小");
        rejects(patchCentral(good, "ui.json", 20, 999999),
                Code.E_PKG_CENTRAL_MISMATCH, "⑦ 声明长度造假：压缩长度写大");
        rejects(patchCentral(good, "ui.json", 24, 0x7FFFFFFFL),
                Code.E_PKG_CENTRAL_MISMATCH, "⑦ 声明长度造假：小条目声明 2 GiB");
        rejects(patchCentral(zipStored(goodEntries()), "ui.json", 20, 60),
                Code.E_PKG_CENTRAL_MISMATCH, "⑦ 声明长度造假：STORED 条目想多吃 52 字节");

        // 符号链接：external attributes 高位标记 S_IFLNK
        rejects(markAsSymlink(zip(goodEntries()), "ui.json"), Code.E_PKG_SYMLINK, "符号链接条目直接拒包");

        // 条目数
        Map<String, byte[]> many = new LinkedHashMap<>();
        many.put("manifest.json", b(GOOD_MANIFEST));
        for (int i = 0; i < 70; i++) many.put("f" + i + ".txt", b("x"));
        rejects(zip(many), Code.E_PKG_TOO_MANY_ENTRIES, "条目数超 64");

        // 深度
        Map<String, byte[]> deep = goodEntries();
        deep.put("a/b/c/d/e/f.json", b("{}"));
        rejects(zip(deep), Code.E_PKG_TOO_DEEP, "目录深度超 4");

        // 不是 zip
        rejects(b("这不是一个 zip"), Code.E_PKG_BAD_ZIP, "不是 zip 的字节");
    }

    // ============================================================
    //  形状被改过的 zip
    // ============================================================

    /**
     * 这一组打的是同一件事：让这个读取器和 {@code ZipFile} 看见两套不同的条目。
     *
     * <p>摘要只覆盖我们看见的那套，所以一旦两边能分叉，签名就签在一个「里面还有别的东西」
     * 的文件上，而自动更新拿摘要比对时也发现不了内容被换过。
     */
    static void forgedZips() throws Exception {
        byte[] good = zip(goodEntries());

        // EOCD 少数一条：我们照 records 走，ZipFile 照 cdSize 走，于是它多看见一条
        byte[] undercount = good.clone();
        int eocd = eocdOf(undercount);
        writeU16(undercount, eocd + 10, readU16(undercount, eocd + 10) - 1);
        rejects(undercount, Code.E_PKG_BAD_ZIP, "EOCD 少数了一条中央目录记录");

        // 中央目录之前塞一段不属于任何条目的字节：ZipInputStream 会把它当成一条
        rejects(insertBeforeCentral(good, new byte[64]), Code.E_PKG_BAD_ZIP,
                "中央目录之前有不属于任何条目的字节");

        // EOCD 后面再多一个字节：它就不是文件的最后一个结构了
        byte[] trailing = new byte[good.length + 1];
        System.arraycopy(good, 0, trailing, 0, good.length);
        rejects(trailing, Code.E_PKG_BAD_ZIP, "ZIP 尾后面还有字节");

        // 本地头与中央目录写着两个名字
        rejects(renameInLocalHeader(good, "ui.json", "ui.jsoX"), Code.E_PKG_CENTRAL_MISMATCH,
                "本地头与中央目录的文件名不一致");

        // CRC 造假
        rejects(patchCentral(good, "ui.json", 16, 0xDEADBEEFL), Code.E_PKG_BAD_ZIP, "CRC 与内容对不上");

        // 条目名不是合法 UTF-8：坏字节会被替换成 U+FFFD，不同的包会算出同一个摘要
        rejects(corruptName(good, "ui.json"), Code.E_PKG_BAD_ENTRY_NAME, "条目名不是合法 UTF-8");
    }

    // ============================================================
    //  样本构造
    // ============================================================

    static byte[] lowRatioBlob(int size, int seed) {
        byte[] block = randomBytes(4096, seed);
        byte[] out = new byte[size];
        for (int i = 0; i < size; i += block.length) {
            System.arraycopy(block, 0, out, i, Math.min(block.length, size - i));
        }
        return out;
    }

    static byte[] randomBytes(int size, int seed) {
        byte[] out = new byte[size];
        new Random(seed).nextBytes(out);
        return out;
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

    /** 不压缩的 zip。STORED 要自己把长度与 CRC 填进条目，否则 ZipOutputStream 不收。 */
    static byte[] zipStored(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            zos.setMethod(ZipOutputStream.STORED);
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                ZipEntry entry = new ZipEntry(e.getKey());
                CRC32 crc = new CRC32();
                crc.update(e.getValue(), 0, e.getValue().length);
                entry.setMethod(ZipEntry.STORED);
                entry.setSize(e.getValue().length);
                entry.setCompressedSize(e.getValue().length);
                entry.setCrc(crc.getValue());
                zos.putNextEntry(entry);
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** 带目录条目的 zip，模拟 {@code zip -r} 的产物。 */
    static byte[] zipWithDirectories(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            List<String> dirs = new ArrayList<>();
            for (String name : entries.keySet()) {
                int slash = name.lastIndexOf('/');
                if (slash > 0) {
                    String dir = name.substring(0, slash + 1);
                    if (!dirs.contains(dir)) dirs.add(dir);
                }
            }
            for (String dir : dirs) {
                zos.putNextEntry(new ZipEntry(dir));
                zos.closeEntry();
            }
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /** 改中央目录里某条记录的一个 u32 字段。偏移 16=CRC、20=压缩长度、24=解压长度。 */
    static byte[] patchCentral(byte[] zip, String name, int fieldOffset, long value) {
        byte[] copy = zip.clone();
        writeU32(copy, centralRecordOf(copy, name) + fieldOffset, value);
        return copy;
    }

    /** 把某条的 external attributes 改成 unix 符号链接（S_IFLNK = 0xA000）。 */
    static byte[] markAsSymlink(byte[] zip, String name) {
        byte[] copy = zip.clone();
        int p = centralRecordOf(copy, name);
        copy[p + 5] = 3;                                  // versionMadeBy 高字节 = UNIX
        writeU32(copy, p + 38, 0xA1FF0000L);              // 高 16 位是文件模式
        return copy;
    }

    /** 只改本地头里的名字，中央目录不动。 */
    static byte[] renameInLocalHeader(byte[] zip, String name, String fake) {
        byte[] copy = zip.clone();
        int local = (int) readU32(copy, centralRecordOf(copy, name) + 42);
        byte[] raw = fake.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(raw, 0, copy, local + 30, raw.length);
        return copy;
    }

    /** 把条目名的首字节换成 0xFF，中央目录与本地头都换 —— 长度不变，只是不再是合法 UTF-8。 */
    static byte[] corruptName(byte[] zip, String name) {
        byte[] copy = zip.clone();
        int p = centralRecordOf(copy, name);
        int local = (int) readU32(copy, p + 42);
        copy[p + 46] = (byte) 0xFF;
        copy[local + 30] = (byte) 0xFF;
        return copy;
    }

    /** 在中央目录之前插一段字节，并把 EOCD 里的偏移挪过去。 */
    static byte[] insertBeforeCentral(byte[] zip, byte[] junk) {
        int eocd = eocdOf(zip);
        int cdOffset = (int) readU32(zip, eocd + 16);
        byte[] out = new byte[zip.length + junk.length];
        System.arraycopy(zip, 0, out, 0, cdOffset);
        System.arraycopy(junk, 0, out, cdOffset, junk.length);
        System.arraycopy(zip, cdOffset, out, cdOffset + junk.length, zip.length - cdOffset);
        writeU32(out, eocdOf(out) + 16, cdOffset + junk.length);
        return out;
    }

    static int eocdOf(byte[] zip) {
        for (int i = zip.length - 22; i >= 0; i--) {
            if (readU32(zip, i) == 0x06054b50L) return i;
        }
        throw new IllegalStateException("样本里找不到 ZIP 尾");
    }

    /** 中央目录里某条记录的起点。 */
    static int centralRecordOf(byte[] zip, String name) {
        int eocd = eocdOf(zip);
        int records = readU16(zip, eocd + 10);
        int p = (int) readU32(zip, eocd + 16);
        for (int i = 0; i < records; i++) {
            int nameLen = readU16(zip, p + 28);
            int extraLen = readU16(zip, p + 30);
            int commentLen = readU16(zip, p + 32);
            if (new String(zip, p + 46, nameLen, StandardCharsets.UTF_8).equals(name)) return p;
            p += 46 + nameLen + extraLen + commentLen;
        }
        throw new IllegalStateException("样本里找不到条目 " + name);
    }

    static int readU16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    static long readU32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    static void writeU16(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
    }

    static void writeU32(byte[] b, int off, long v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }

    // ============================================================
    //  断言辅助
    // ============================================================

    /** 恶意样本必须被拒，从字节读和从磁盘读拒的是同一条。 */
    static void rejects(byte[] zip, Code expected, String what) throws Exception {
        Code got = codeOf(() -> PackageReader.read(zip));
        eq(got, expected, what);
        // 逐条印出来：这份日志就是"哪个样本被哪条判据拦下"的留档，翻 CI 日志能看到
        System.out.println("  拒绝[" + got + "]  " + what);

        Path dir = Files.createTempDirectory("mcphone-pkg-test");
        try {
            Path file = dir.resolve("sample.zip");
            Files.write(file, zip);
            Code fromDisk = codeOf(() -> {
                try {
                    PackageReader.readFile(file);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            eq(fromDisk, expected, what + "（从磁盘读也一样）");
        } finally {
            try (var walk = Files.walk(dir)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(p);
                }
            }
        }
    }

    /** 跑一段，把它抛出的 PackageError 的码取出来；没抛就返回 null。 */
    static Code codeOf(Runnable body) {
        try {
            body.run();
            return null;
        } catch (PackageError e) {
            return e.code();
        }
    }
}
