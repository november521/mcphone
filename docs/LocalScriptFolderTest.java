package com.november.mcphone.core.script.client;

import com.november.mcphone.core.script.pkg.PackageReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** 用真实临时文件验证内容检测、半包重试和读前大小限制，无需 Minecraft 窗口。 */
public final class LocalScriptFolderTest {
    private static int checks;
    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("shared"))) root = root.getParent();
        if (root == null) throw new AssertionError("找不到样例所在仓库");
        for (String version : new String[]{"1.0.0", "1.0.1"}) {
            Path fixture = root.resolve("docs/local-update-fixtures/v" + version + "/demo.vue");
            ScriptApp sample = ScriptAppFolder.read("demo.vue", Files.readAllBytes(fixture));
            check(sample.manifest().version().equals(version), "交付样例能通过真实页面编译：" + version);
        }
        Path dir = Files.createTempDirectory("mcphone-local-update-");
        Path file = dir.resolve("demo.vue");
        try {
            String one = ScriptAppLoadTest.vue("");
            Files.writeString(file, one, StandardCharsets.UTF_8);
            var stamp = Files.getLastModifiedTime(file);
            ScriptApp old = ScriptAppFolder.scan(dir).get(0);
            check(ScriptAppFolder.scan(dir).get(0) == old, "不变内容复用编译对象");
            Files.writeString(file, one.replace("1.0.0", "1.0.1"), StandardCharsets.UTF_8);
            Files.setLastModifiedTime(file, stamp);
            ScriptApp fresh = ScriptAppFolder.scan(dir).get(0);
            check(fresh != old && fresh.manifest().version().equals("1.0.1"), "同尺寸同时间仍发现新版");
            Files.writeString(file, "拷贝中的半包", StandardCharsets.UTF_8);
            check(ScriptAppFolder.scan(dir).isEmpty(), "半包不作为更新候选");
            check(ScriptAppFolder.scan(dir).isEmpty(), "相同坏内容不会伪装成功");
            Files.writeString(file, one.replace("1.0.0", "1.0.2"), StandardCharsets.UTF_8);
            check(ScriptAppFolder.scan(dir).get(0).manifest().version().equals("1.0.2"), "坏包修好后重试");
            Files.write(file, new byte[PackageReader.MAX_COMPRESSED + 1]);
            check(ScriptAppFolder.scan(dir).isEmpty(), "超大文件在解析前拒绝");
            Files.writeString(file, one, StandardCharsets.UTF_8);
            check(ScriptAppFolder.scan(dir).get(0).manifest().version().equals("1.0.0"), "超限恢复后能重新读取");
            Files.delete(file);
            check(ScriptAppFolder.scan(dir).isEmpty(), "删除文件清理扫描缓存");
            Files.writeString(file, one, StandardCharsets.UTF_8);
            check(ScriptAppFolder.scan(dir).get(0) != old, "重新出现的文件重新编译");
        } finally {
            Files.deleteIfExists(file);
            ScriptAppFolder.scan(dir);
            Files.deleteIfExists(dir);
        }
        System.out.println("本地扫描断言 " + checks + " 条通过");
    }
}
