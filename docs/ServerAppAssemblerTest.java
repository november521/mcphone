package com.november.mcphone.core.script.server;

import com.november.mcphone.core.script.engine.AppScope;
import com.november.mcphone.core.script.pkg.AppPackage;
import com.november.mcphone.core.script.pkg.PackageReader;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * S18 断言：{@link ServerAppAssembler} 的装配期预检是<b>静态</b>的。
 *
 * <p>核心两条：① 带动态 require / 缺模块 / 语法错的包不装配；② <b>顶层直接 throw 的包照样装配</b> ——
 * 真执行过的话它必然失败，这条就是"预检零副作用、不执行 App 代码"的行为证据。
 */
public class ServerAppAssemblerTest {

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

    static final String MANIFEST = "{\n"
            + "  \"format\": 1,\n"
            + "  \"id\": \"example:asm\",\n"
            + "  \"version\": \"1.0.0\",\n"
            + "  \"name\": \"asm\",\n"
            + "  \"author\": \"tester\",\n"
            + "  \"description\": \"assembler test\",\n"
            + "  \"engine\": \"declarative-1\",\n"
            + "  \"icon\": \"icon.png\"\n"
            + "}";

    static Deployment deploymentFor(AppPackage pkg) {
        return new Deployment("example:asm", "example:asm@" + pkg.digest().substring(0, 12),
                pkg.digest(), 1L, pkg.digest(), "a".repeat(64),
                List.of("ping"), List.of("ping"), List.of("item.give"), List.of("item.give"),
                UUID.nameUUIDFromBytes("approver".getBytes()), 1L);
    }

    /** 造一个真 zip 再走 PackageReader —— 不绕过白名单/路径判据。 */
    static AppPackage pack(String serverJs, Map<String, String> extraModules) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("manifest.json", bytes(MANIFEST));
        entries.put("app.vue", bytes("<template>\n<box/>\n</template>\n<script>\nstate = { }\n</script>\n"));
        entries.put("icon.png", new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        entries.put("server.js", bytes(serverJs));
        for (Map.Entry<String, String> e : extraModules.entrySet()) entries.put(e.getKey(), bytes(e.getValue()));
        return PackageReader.read(zip(entries));
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
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

    static void staticPreflight() throws Exception {
        // 合法包 + 顶层 throw：真执行会炸，静态预检必须通过
        AppPackage ok = pack("throw new Error('顶层就炸');\n"
                + "var u = require('./server/util.js');\n"
                + "actions.ping = function () { return ctx.ok({}); };",
                Map.of("server/util.js", "exports.n = 1;"));
        AppScope app = ServerAppAssembler.assembleOne(deploymentFor(ok), ok);
        check(app != null, "顶层 throw 的包照样装配（预检不执行代码）");
        eq(app == null ? null : app.appId(), "example:asm", "装出来的 appId 对");

        // 语法错
        AppPackage syntax = pack("function (", Map.of());
        check(ServerAppAssembler.assembleOne(deploymentFor(syntax), syntax) == null, "语法错不装配");

        // 动态 require
        AppPackage dynamic = pack("var n = 'util'; require('./server/' + n + '.js');", Map.of());
        check(ServerAppAssembler.assembleOne(deploymentFor(dynamic), dynamic) == null, "动态 require 不装配");

        // 缺模块
        AppPackage missing = pack("require('./server/nope.js');", Map.of());
        check(ServerAppAssembler.assembleOne(deploymentFor(missing), missing) == null, "require 找不到不装配");

        // 循环依赖
        AppPackage cycle = pack("require('./server/a.js');",
                Map.of("server/a.js", "require('./b.js');", "server/b.js", "require('./a.js');"));
        check(ServerAppAssembler.assembleOne(deploymentFor(cycle), cycle) == null, "循环依赖不装配");

        // 没有 server.js 的包（不可能走到这里：Scanner 只收带 server.js 的，但装配点自己也要拒）
        Map<String, byte[]> noEntry = new LinkedHashMap<>();
        noEntry.put("manifest.json", bytes(MANIFEST));
        noEntry.put("app.vue", bytes("<template>\n<box/>\n</template>\n<script>\nstate = { }\n</script>\n"));
        noEntry.put("icon.png", new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        AppPackage bare = PackageReader.read(zip(noEntry));
        check(ServerAppAssembler.assembleOne(deploymentFor(bare), bare) == null, "没有 server.js 不装配");
    }

    public static void main(String[] args) throws Exception {
        staticPreflight();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
