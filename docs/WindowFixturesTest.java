import com.november.mcphone.core.script.engine.ScriptStaticCheck;
import com.november.mcphone.core.script.pkg.AppPackage;
import com.november.mcphone.core.script.pkg.PackageReader;
import com.november.mcphone.core.script.server.economy.EconomyConfig;
import com.november.mcphone.core.script.sfc.SfcCompiler;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 真服窗口夹具的机械闸（对抗 W1 的防回归）：夹具必须能被<b>真链路</b>吃下去 ——
 * {@code PackageReader} → {@code Manifest} → {@code SfcCompiler} → {@code ScriptStaticCheck}，
 * 数据集/货币配置也要过真解析器。夹具坏了 CI 当场红，不用等现场空跑。
 */
public class WindowFixturesTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!Objects.equals(actual, expected)) failures.add(what + "  期望 " + expected + "，实际 " + actual);
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures.add(what);
    }

    /** 从平台目录往上找到仓库根（有 demo-apps 的那一层）。 */
    static Path repoRoot() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("demo-apps"))) return p;
        }
        return null;
    }

    static byte[] zipOf(Path dir) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Path f : Files.walk(dir).filter(Files::isRegularFile).sorted().toList()) {
                zip.putNextEntry(new ZipEntry(dir.relativize(f).toString().replace('\\', '/')));
                zip.write(Files.readAllBytes(f));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    static void appPackage(Path root, String name, boolean hasServer) throws Exception {
        Path dir = root.resolve("demo-apps/window/" + name);
        check(Files.isDirectory(dir), name + " 目录在：" + dir);
        if (!Files.isDirectory(dir)) return;

        AppPackage pkg;
        try {
            pkg = PackageReader.read(zipOf(dir));
        } catch (Throwable t) {
            check(false, name + "：PackageReader 读过（" + t + "）");
            return;
        }
        check(pkg.manifest() != null, name + "：manifest 解析出来");
        check(pkg.entry("app.vue") != null, name + "：app.vue 在包里");
        check(pkg.entry("icon.png") != null, name + "：icon.png 在包里");
        eq(pkg.entry("server.js") != null, hasServer, name + "：server.js 有无符合预期");

        try {
            SfcCompiler.compilePage("app.vue", new String(pkg.entry("app.vue"), StandardCharsets.UTF_8));
        } catch (Throwable t) {
            check(false, name + "：app.vue 过 SFC 编译（" + t + "）");
        }

        if (hasServer) {
            Map<String, String> sources = new LinkedHashMap<>();
            sources.put("server.js", new String(pkg.entry("server.js"), StandardCharsets.UTF_8));
            String why = ScriptStaticCheck.check(sources);
            check(why == null, name + "：server.js 过静态预检（" + why + "）");
        }
    }

    static void economy(Path root) throws Exception {
        EconomyConfig.Result a = EconomyConfig.parse(Files.readString(
                root.resolve("demo-apps/window/economy/economy-server-a.json"), StandardCharsets.UTF_8));
        EconomyConfig.Result b = EconomyConfig.parse(Files.readString(
                root.resolve("demo-apps/window/economy/economy-server-b.json"), StandardCharsets.UTF_8));
        check(a.clean() && a.specs().size() == 1, "A 配置干净且一条：" + a.problems());
        check(b.clean() && b.specs().size() == 2, "B 配置干净且两条：" + b.problems());
        if (a.specs().isEmpty() || b.specs().isEmpty()) return;
        eq(a.specs().get(0).id(), "win:coin", "A 的货币 id");
        eq(a.specs().get(0).isDefault(), true, "A 默认 = true");
        eq(b.specs().get(0).id(), "win:crown", "B 的货币 id");
        check(!a.specs().get(0).id().equals(b.specs().get(0).id()), "两台服的货币 id 不同（#6）");
        check(b.specs().stream().anyMatch(s -> "scoreboard".equals(s.provider())),
                "B 带 scoreboard 档（审计分档用）");
    }

    static void datapacks(Path root) throws Exception {
        Path v121 = root.resolve("demo-apps/window/datapack-1.21.1");
        Path v120 = root.resolve("demo-apps/window/datapack-1.20.1");
        check(Files.isRegularFile(v121.resolve("pack.mcmeta")), "1.21.1 pack.mcmeta 在");
        check(Files.isRegularFile(v120.resolve("pack.mcmeta")), "1.20.1 pack.mcmeta 在");
        check(Files.isRegularFile(v121.resolve("data/myserver/loot_table/daily_gift.json")),
                "1.21.1 用 loot_table/");
        check(Files.isRegularFile(v121.resolve("data/myserver/predicate/is_vip.json")),
                "1.21.1 用 predicate/");
        check(Files.isRegularFile(v120.resolve("data/myserver/loot_tables/daily_gift.json")),
                "1.20.1 用 loot_tables/");
        check(Files.isRegularFile(v120.resolve("data/myserver/predicates/is_vip.json")),
                "1.20.1 用 predicates/");
        check(Files.isRegularFile(v121.resolve("data/mcphone/tags/item/giftable.json")), "1.21.1 giftable 标签");
        check(Files.isRegularFile(v120.resolve("data/mcphone/tags/item/giftable.json")), "1.20.1 giftable 标签");
        check(Files.readString(v121.resolve("pack.mcmeta"), StandardCharsets.UTF_8).contains("48"),
                "1.21.1 pack_format = 48");
        check(Files.readString(v120.resolve("pack.mcmeta"), StandardCharsets.UTF_8).contains("15"),
                "1.20.1 pack_format = 15");
    }

    public static void main(String[] args) throws Exception {
        Path root = repoRoot();
        check(root != null, "找得到仓库根（有 demo-apps 那一层）");
        if (root != null) {
            appPackage(root, "s15window-server", true);
            appPackage(root, "s15window-frontend", false);
            economy(root);
            datapacks(root);
        }

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
