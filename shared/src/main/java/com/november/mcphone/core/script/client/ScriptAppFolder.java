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
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 玩家自己放脚本 App 的目录（施工方案 §14.1）：游戏目录下的 {@code mcphone/apps/}。
 *
 * <p>扫到什么就读什么：{@code .vue} 是单文件形态，{@code .zip} 是完整包（§11.2）。
 * <b>坏包一律跳过并记一条日志</b> —— 一个包坏了不许影响商店打开，更不许影响别的包。
 *
 * <p><b>编译一次常驻</b>（§9.9）：按文件路径缓存，文件的大小与修改时间都没变就不再读、不再编。
 * 玩家在游戏里换掉一个包时，下次扫描会看见时间变了，重新编。
 *
 * <p>修改时间在有些文件系统上是秒级：同一秒里改两次、而且字节数正好没变的那一次看不出来。
 * 代价是作者那一下改动要到下次改动才生效；换成内容摘要的话，每次开商店都要把目录里每个包整份读一遍。
 *
 * <p>全在客户端主线程：读盘量很小（整包 ≤ 256 KiB，至多几十个），而回调契约要求在主线程，
 * 换到后台线程再弹回来只会多一层。
 */
public final class ScriptAppFolder {

    /** 目录名，挂在游戏目录下。与截图、书库那几处同一个层级，玩家找得到。 */
    public static final String DIR = "mcphone/apps";

    /** 一个文件读出来的东西，外加它的时间戳。 */
    private record Loaded(ScriptApp app, long size, long modified) {
    }

    /** 路径 → 上次读出来的东西。读不出来的不进表，下次还会再试（玩家可能正在往里拷文件）。 */
    private static final Map<Path, Loaded> CACHE = new LinkedHashMap<>();

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

    /** 读一个文件，读不出来返回 null 并记日志。时间戳没变就走缓存。 */
    private static ScriptApp load(Path file) {
        String name = file.getFileName().toString();
        boolean vue = name.endsWith(".vue");
        boolean zip = name.endsWith(".zip");
        if (!vue && !zip) return null;

        long size;
        long modified;
        try {
            size = Files.size(file);
            modified = Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            MCphone.LOGGER.warn("[MCphone] 读不到 {} 的属性，跳过: {}", name, e.toString());
            return null;
        }

        Loaded known = CACHE.get(file);
        if (known != null && known.size() == size && known.modified() == modified) return known.app();

        ScriptApp app;
        try {
            app = read(name, Files.readAllBytes(file));
        } catch (IOException | RuntimeException e) {
            // 坏 ZIP / 坏清单 / 坏模板：跳过这一个，别的照常（§14.1）
            MCphone.LOGGER.warn("[MCphone] 脚本 App {} 读不了，已跳过: {}", name, e.getMessage());
            CACHE.remove(file);
            return null;
        }
        CACHE.put(file, new Loaded(app, size, modified));
        MCphone.LOGGER.info("[MCphone] 脚本 App 已编译: {} v{}（{}）", app.id(), app.manifest().version(), name);
        return app;
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
