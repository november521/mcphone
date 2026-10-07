package com.november.mcphone.core.script.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.pkg.AppPackage;
import com.november.mcphone.core.script.pkg.Manifest;
import com.november.mcphone.core.script.pkg.PackageReader;
import com.november.mcphone.core.script.sfc.SfcCompiler;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * 玩家自己放脚本 App 的目录（施工方案 §14.1）：游戏目录下的 {@code mcphone/apps/}。
 *
 * <p>扫到什么就读什么：{@code .vue} 是单文件形态，{@code .zip} 是完整包（§11.2）。
 * <b>坏包一律跳过并记一条日志</b> —— 一个包坏了不许影响商店打开，更不许影响别的包。
 *
 * <p>编译结果按完整文件 SHA-256 缓存：同尺寸、同修改时间的替换也能检测；签名文件也参与检测。
 * 定时扫描在后台进行，商店扫描与它共用锁，避免两条路径写坏缓存。
 * 这里只读取和编译，不上传纹理、不安装 App、不修改信任库。
 */
public final class ScriptAppFolder {

    /** 目录名，挂在游戏目录下。与截图、书库那几处同一个层级，玩家找得到。 */
    public static final String DIR = "mcphone/apps";

    /** 一个文件读出来的东西，外加它的时间戳。 */
    private record Loaded(ScriptApp app, String digest) {
    }

    /** 路径 → 上次读出来的东西。读不出来的不进表，下次还会再试（玩家可能正在往里拷文件）。 */
    private static final Map<Path, Loaded> CACHE = new LinkedHashMap<>();
    /** 同一份坏内容只告警一次；内容变了继续尝试，不把拷贝中的半包当永久失败。 */
    private static final Map<Path, String> FAILED = new LinkedHashMap<>();

    private ScriptAppFolder() {
    }

    /** 目录的绝对路径。取不到游戏目录（没有客户端）时返回 null。 */
    public static Path dir() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gameDirectory == null) return null;
        return mc.gameDirectory.toPath().resolve(DIR);
    }

    /**
     * 扫一遍目录，返回读得出来的全部 App。目录不存在就建一个空的（玩家得看得见往哪儿放）。
     *
     * <p>同一个 id 出现在两个文件里时，按文件名排序取第一个 —— 不是随机取一个。
     */
    public static List<ScriptApp> scan() {
        Path dir = dir();
        if (dir == null) return List.of();
        return scan(dir);
    }

    /** 不访问 Minecraft，允许后台扫描和无窗口真实文件测试。 */
    static synchronized List<ScriptApp> scan(Path dir) {

        List<Path> files = new ArrayList<>();
        try {
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir);
                return List.of();
            }
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(Files::isRegularFile).sorted().forEach(files::add);
            }
        } catch (IOException e) {
            MCphone.LOGGER.warn("[MCphone] 读 {} 失败: {}", dir, e.toString());
            return List.of();
        }

        CACHE.keySet().retainAll(files);   // 文件删了，缓存跟着走
        FAILED.keySet().retainAll(files);

        Map<ResourceLocation, ScriptApp> byId = new LinkedHashMap<>();
        for (Path file : files) {
            ScriptApp app = load(file);
            if (app == null) continue;
            ScriptApp old = byId.putIfAbsent(app.id(), app);
            if (old != null) {
                MCphone.LOGGER.warn("[MCphone] 脚本 App id 撞车: '{}' 已经由 {} 占了，跳过 {}",
                        app.id(), old.file(), app.file());
            }
        }
        return List.copyOf(byId.values());
    }

    /** 读取有界字节后比较内容摘要，避免先 readAllBytes 再拒绝超大文件。 */
    private static ScriptApp load(Path file) {
        String name = file.getFileName().toString();
        boolean vue = name.endsWith(".vue");
        boolean zip = name.endsWith(".zip");
        if (!vue && !zip) return null;

        byte[] content;
        try {
            if (Files.size(file) > PackageReader.MAX_COMPRESSED) {
                if (!"oversize".equals(FAILED.put(file, "oversize"))) {
                    MCphone.LOGGER.warn("[MCphone] {} 超过本地 App 文件上限，保留已启用版本", name);
                }
                return null;
            }
            try (var in = Files.newInputStream(file)) {
                content = in.readNBytes(PackageReader.MAX_COMPRESSED + 1);
            }
            if (content.length > PackageReader.MAX_COMPRESSED) return null;
        } catch (IOException e) {
            MCphone.LOGGER.warn("[MCphone] 读不到 {} 的属性，跳过: {}", name, e.toString());
            return null;
        }

        Loaded known = CACHE.get(file);
        String digest = digest(content);
        if (known != null && known.digest().equals(digest)) return known.app();
        if (digest.equals(FAILED.get(file))) return null;

        ScriptApp app;
        try {
            app = read(name, content);
        } catch (RuntimeException e) {
            // 坏 ZIP / 坏清单 / 坏模板：跳过这一个，别的照常（§14.1）
            MCphone.LOGGER.warn("[MCphone] 脚本 App {} 读不了，已跳过: {}", name, e.getMessage());
            FAILED.put(file, digest);
            return null;
        }
        FAILED.remove(file);
        CACHE.put(file, new Loaded(app, digest));
        MCphone.LOGGER.info("[MCphone] 脚本 App 已编译: {} v{}（{}）", app.id(), app.manifest().version(), name);
        return app;
    }

    private static String digest(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }

    /**
     * 按文件名决定怎么读这段字节：{@code .vue} 是单文件形态，{@code .zip} 是完整包（§11.2）。
     * 读不出来就抛 —— 调用方负责跳过并记日志。
     */
    static ScriptApp read(String name, byte[] content) {
        return name.endsWith(".vue")
                ? single(name, new String(content, StandardCharsets.UTF_8))
                : packaged(name, content);
    }

    /** 单个 .vue：清单在文件里，没有包，也就没有素材。 */
    private static ScriptApp single(String name, String src) {
        SfcCompiler.App compiled = SfcCompiler.compile(name, src);
        Manifest manifest = compiled.manifest();
        return ScriptApp.of(idOf(manifest), manifest, null,
                new SfcCompiler.Page(compiled.stylesheet(), compiled.template()), Map.of(),
                inlineIcon(manifest), name);
    }

    /** zip：清单独立成文件，入口是 manifest 指的那个（不写 ui 就是 app.vue），pages/*.vue 是别的页。 */
    private static ScriptApp packaged(String name, byte[] zip) {
        AppPackage pkg = PackageReader.read(zip);
        Manifest manifest = pkg.manifest();

        String entryPath = manifest.uiTree();
        if (!entryPath.endsWith(".vue")) {
            // §11.1：作者写的永远是 .vue。ui.json 那条路已经退场，装进来也画不出东西
            throw new IllegalStateException("入口 " + entryPath + " 不是 .vue（§11.2：zip 的前端入口是 app.vue）");
        }

        Map<String, SfcCompiler.Page> pages = new LinkedHashMap<>();
        for (String path : pkg.paths()) {
            if (!path.startsWith("pages/") || !path.endsWith(".vue")) continue;
            String page = path.substring("pages/".length(), path.length() - ".vue".length());
            if (page.isEmpty() || page.indexOf('/') >= 0) continue;   // pages/ 下再套目录的不认，nav 也写不出来
            pages.put(page, SfcCompiler.compilePage(path, text(pkg, path)));
        }
        SfcCompiler.Page entry = SfcCompiler.compilePage(entryPath, text(pkg, entryPath));
        return ScriptApp.of(idOf(manifest), manifest, pkg, entry, Map.copyOf(pages),
                pkg.entry(manifest.icon()), name);
    }

    private static String text(AppPackage pkg, String path) {
        byte[] bytes = pkg.entry(path);
        if (bytes == null) throw new IllegalStateException("包里没有 " + path);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** 内联清单的 data URI 图标 → 原始字节，没写就是 null。 */
    private static byte[] inlineIcon(Manifest manifest) {
        String icon = manifest.icon();
        if (icon == null || !icon.startsWith(Manifest.INLINE_ICON_PREFIX)) return null;
        try {
            return Base64.getDecoder().decode(icon.substring(Manifest.INLINE_ICON_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return null;   // Manifest 已经解过一遍，走不到这儿；真走到了就当没图标
        }
    }

    /**
     * 清单 id → ResourceLocation。两边的字符集是对得上的：{@code Manifest} 的两段都限在
     * {@code [a-z0-9_.-]}，而那是 ResourceLocation 命名空间与路径都收的子集。
     */
    private static ResourceLocation idOf(Manifest manifest) {
        ResourceLocation id = ResourceLocation.tryParse(manifest.id());
        if (id == null) throw new IllegalStateException("id '" + manifest.id() + "' 不是合法的 ResourceLocation");
        return id;
    }
}
