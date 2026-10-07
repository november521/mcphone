package com.november.mcphone.core.script.client.tex;

import com.mojang.blaze3d.platform.NativeImage;
import com.november.mcphone.MCphone;
import com.november.mcphone.core.client.ImageCodec;
import com.november.mcphone.core.script.layout.ImageSizes;
import com.november.mcphone.core.script.pkg.AppPackage;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 包内图片对应的贴图（施工方案 §5.2 image、§5.4、§28.2 #13）。
 *
 * <p>两道判定上限：单张 ≤ {@link #MAX_BYTES}、边长 ≤ {@link #MAX_SIDE}。超限、坏文件、不在包里的
 * 一律【拒绝加载】返回 null，由 §8.4 的占位分支画占位图 —— 不抛，不许一张坏图把商店带崩。
 *
 * <p><b>尺寸与上传分两步</b>：measure 只要宽高（读 PNG 头，不碰显存），画的时候才真的上传。
 * 一页里三十张图只露出两张时，另外二十八张一个字节的显存都不占。宽高不限量 —— 一条 8 个字节，
 * 限它没有意义；限的是显存。
 *
 * <p><b>每个 App 同时至多 {@link #MAX_PER_APP} 张贴图在显存里</b>（§28.2 #13）。这道闸按帧分两种行为：
 * <ul>
 *   <li><b>一帧之内</b>照 §28.2 #13 写的办：满了就拒绝加载，多出来的画占位图。每帧至多传 16 张，
 *       而且每帧拒的是同一批（画的顺序是树序，稳定），不闪。</li>
 *   <li><b>跨帧</b>按最久没画的淘汰，与聊天图片那套（ChatImageCache）同一个办法。</li>
 * </ul>
 * 少了前一半，一个装了二十张图的 {@code scroll} 就会每帧把刚画过的换出去再换回来 —— {@code scroll}
 * 不剔除滚出可见区的子节点（见 Renderer.childRange），裁掉的照样调 {@link #of}，于是那是每帧二十次
 * ImageIO 解码加建删纹理，而那个包完全合规（§28.2 #13 限的是显存里 16 张，不是包里只能有 16 张）。
 * 少了后一半，先被问到的 16 张会【永久】占住名额：换个 tab 再回来，后来的那几张在关页面之前一直是
 * 占位图，而显存里躺着的是玩家早就不看的图。
 *
 * <p><b>没有调用方 = 泄漏</b>：{@link #release} 现在全仓没人调（宿主页是 S9/S10 的事）。
 * 接宿主页的人必须在关页面、卸包时调它，否则开关两百次就是两百份贴图挂在 TextureManager 上（§8.8）。
 *
 * <p><b>线程</b>：全在渲染线程。{@link #of} 第一次被问到时就地解码再上传 —— 一张 128×128 是一万六千个
 * 像素，一页十六张压在开页面那一帧里是几毫秒。要更平滑就得像聊天图片那样搬去后台线程，P0 不做。
 */
public final class AppTextures {

    /** 单张字节上限（§11.2 / §28.2 #13）。 */
    public static final int MAX_BYTES = 64 * 1024;
    /** 边长上限，宽高各自算（§5.2）。 */
    public static final int MAX_SIDE = 128;
    /** 远程 PNG 的独立尺寸上限；包内素材仍保持原有 128 上限。 */
    public static final int MAX_REMOTE_SIDE = 512;
    /**
     * 每个 App 同时能有多少张贴图在显存里（§28.2 #13）。满了淘汰最久没画的那张。
     *
     * <p>必须 ≥ 1：调成 0 不会变成"一张都不加载"，而是退化成 1 —— 表空的时候腾位置那一步无事可做，
     * 照样传得进去。要关掉图片得在别处关，不是把这个数调成 0。
     */
    public static final int MAX_PER_APP = 16;
    /**
     * 一个 App 最多留多少条【被拒】的判定。
     *
     * <p>模板里的 {@code :src} 可以是表达式，每次重排都能造出一批没见过的路径；不封顶的话判定表
     * 随重排次数一直涨，一条最长 512 字节（S1 的路径上限）。
     *
     * <p>只封被拒的：收下的那些条数已经被包里的文件数钉死了（S1 的 {@code MAX_ENTRIES} 是 64）。
     * 连 OK 一起封的话，一个装满图的大 App 只要先被问到几条失效路径，后面【真实存在的图】就会被误伤。
     */
    static final int MAX_REJECTED = 32;
    /**
     * 一次打开最多报多少条"这张图用不了"。同一个坏 src 每次重排都报一遍的话，日志就没法看了。
     *
     * <p>计数挂在 App 上，而 {@link #release} 把 App 整个删掉 —— 也就是每次打开页面重新计数。
     * 一个稳定的坏图开关两百次仍然是两百行，那是两百次"打开时告诉作者一次"，不是刷屏。
     */
    static final int MAX_WARNINGS = 32;

    private AppTextures() {
    }

    /** 一条素材的判定结果。测试按它断言，比"返回了 null"说得清楚。 */
    enum Result {
        OK,
        /** 包里没有这条路径 */
        MISSING,
        /** 后缀不是 .png */
        BAD_PATH,
        /** 字节数超 {@link #MAX_BYTES} */
        TOO_LARGE,
        /** 宽或高超 {@link #MAX_SIDE} */
        TOO_BIG,
        /** 不是 PNG：假扩展名，或者头就坏了 */
        NOT_PNG,
        /** 头没问题，像素解不开或者传不上去 */
        BROKEN,
        /** 这个 App 用不了的图片路径太多了（多半是 {@code :src} 绑了个每次都变的表达式），判定表封顶了 */
        TOO_MANY
    }

    private static final class Entry {
        Result result;
        int width;
        int height;
        byte[] remotePng;
        /** 画过才有。测试里的假上传口给的 location 是 null。 */
        ImageCodec.Texture texture;
        /** 最后一次被画是哪一帧。淘汰不许动这一帧已经画过的图，见 {@link #hasRoom}。 */
        int lastFrame = -1;
    }

    private static final class App {
        /** src → 判定结果。插入序；被拒的那些封顶在 {@link #MAX_REJECTED}。 */
        final Map<String, Entry> entries = new LinkedHashMap<>();
        /** 显存里那几张，访问序：满了淘汰迭代器给出的第一条，也就是最久没画的那张。 */
        final Map<String, Entry> live = new LinkedHashMap<>(16, 0.75f, true);
        /** 画到第几帧了，由 {@link #beginFrame} 推。 */
        int frame;
        int rejected;
        int warned;
    }

    /** key 是包摘要：同一个包换个 AppPackage 实例装进来，贴图不必重传。 */
    private static final Map<String, App> APPS = new HashMap<>();

    /**
     * App 图标，key 是 App id。值为 null 表示判过了、用不了（商店画占位方块）。
     *
     * <p>单独一张表，不进上面那套每帧 16 张的名额：图标是主屏与商店画的，那条路上没有
     * {@code Frame}，也就没人调 {@link #beginFrame}，混进去会被页面里的图挤掉、然后每帧重传。
     *
     * <p><b>这张表不淘汰</b>，只由 {@link #releaseIcon} 与 {@link #clearCache} 清。试过按访问序封顶，
     * 但那是错的：{@code AppInfo} 会把 {@code getIconTexture()} 的返回值<b>存进对象</b>，商店画的是存住的
     * 那个 ResourceLocation、不会再问一次 —— 在它背后把贴图还掉，画出来就是紫黑格。
     * 条数的上限是"目录里有几个包"，每张 ≤ {@link #MAX_BYTES}；真要限量得先让 AppInfo 改成画的时候再问。
     */
    private static final Map<String, ImageCodec.Texture> ICONS = new LinkedHashMap<>();

    private static int epoch;

    /**
     * §7.6 的四个 epoch 之一。资源重载、以及一张图被判成 {@link Result#BROKEN} 时它会变。
     *
     * <p><b>现在没有消费者</b>：读它的是宿主页（S9/S10），眼下还不存在。接上的人要知道两件事 ——
     * 一是这个数变了就得重排（贴图全丢了、原始尺寸要重新读、布局跟着变），二是它可能在
     * {@link #of} 里前进，而那是画到一半的时候：重排要推到下一帧，不能在遍历当前这棵树的中途做。
     */
    public static int epoch() {
        return epoch;
    }

    /**
     * 这一帧开始了。由 {@code Frame} 的构造函数调 —— 那是"画这一页"的必经之路（§8.6：Frame 要在这一帧开头新建）。
     *
     * <p>帧号只有一个用处：淘汰不许动这一帧已经画过的图（{@link #hasRoom}）。不分帧的话，
     * 一页同屏十七张图时被淘汰的永远是刚画过的那张，每帧换进换出 —— 那是几十次解码加建删纹理。
     */
    public static void beginFrame(AppPackage pkg) {
        App app = pkg == null ? null : APPS.get(pkg.digest());
        if (app != null) app.frame++;
    }

    /** 这张图的原始尺寸 {宽, 高}；用不了返回 null（§5.2：image 不写 w / h 时按原始尺寸算）。 */
    public static int[] size(AppPackage pkg, String src) {
        Entry e = entry(pkg, src);
        return e == null || e.result != Result.OK ? null : new int[]{e.width, e.height};
    }

    /**
     * 把这个包绑给布局（§7.4 的 measure 要原始尺寸）。
     *
     * <p>{@link com.november.mcphone.core.script.layout.LayoutEngine#layout} 建完树就问一遍，
     * 之后整趟排版用那一份答案。问尺寸不占显存，也不限量 —— 一页里三十张图，三十个宽高都给得出来，
     * 显存里同时只会有 {@link #MAX_PER_APP} 张。
     */
    public static ImageSizes sizes(AppPackage pkg) {
        return src -> size(pkg, src);
    }

    /**
     * 可以画的贴图，拿不到返回 null（占位图的事交给 §8.4 的调用方）。
     *
     * <p>第一次问到某张图时才上传，之后走缓存。必须在渲染线程调。
     */
    public static ResourceLocation of(AppPackage pkg, String src) {
        Entry e = entry(pkg, src);
        if (e == null || e.result != Result.OK) return null;
        App app = APPS.get(pkg.digest());
        if (e.texture != null) {
            app.live.get(src);              // 访问序：刷一下，淘汰的时候它就不是最老的那个
            e.lastFrame = app.frame;
            return e.texture.location();
        }
        if (!hasRoom(app)) return null;     // 这一帧的图已经把显存占满了，多出来的画占位图
        // 先传，传成了才真的去还别人的：顺序反过来的话，一张坏图会白白顶掉一张好图
        ImageCodec.Texture tex = uploader.upload(e.remotePng == null ? pkg.entry(src) : e.remotePng,
                e.remotePng == null ? MAX_SIDE : MAX_REMOTE_SIDE);
        if (tex == null) {
            // 头过了像素没过：判定就地改成 BROKEN，否则每一帧都要重解一遍这张坏图。
            // epoch 跟着前进：measure 是按头里那个宽高留的位置，现在这张图没了，得按占位尺寸重排一次。
            // BROKEN 是终态：解码那头偶发的内存不足（ImageCodec 会吞掉并返回 null）也会落进这里，
            // 那张图在资源重载之前不会再试第二次
            e.result = Result.BROKEN;
            epoch++;
            warn(pkg, src, Result.BROKEN);
            return null;
        }
        evict(app);
        e.texture = tex;
        e.lastFrame = app.frame;
        app.live.put(src, e);
        return e.texture.location();
    }

    /**
     * 显存里还放得下一张吗：没满，或者最久没画的那张不是这一帧画的。
     *
     * <p>这一帧已经画过的那些不许淘汰 —— 淘汰它等于承认"这一页同屏的图比预算多"，而那时每帧都会把
     * 刚画的换出去再换回来：几十次 ImageIO 解码加建删纹理，一秒六十轮。放不下就画占位图
     * （§28.2 #13 写的处置就是这个），下一页、下一个 tab 自然就腾出来了。
     */
    private static boolean hasRoom(App app) {
        if (app.live.size() < MAX_PER_APP) return true;
        var it = app.live.values().iterator();      // 迭代不算访问，不会把顺序搅乱
        return it.hasNext() && it.next().lastFrame != app.frame;
    }

    /** 把最久没画的那张还回去，它下次被画到时重传。调之前先问过 {@link #hasRoom}。 */
    private static void evict(App app) {
        var it = app.live.entrySet().iterator();
        while (app.live.size() >= MAX_PER_APP && it.hasNext()) {
            Entry eldest = it.next().getValue();
            if (eldest.lastFrame == app.frame) return;
            uploader.release(eldest.texture);
            eldest.texture = null;
            it.remove();
        }
    }

    /**
     * App 图标的贴图，用不了返回 null（商店与主屏画占位方块）。
     *
     * <p>{@code png} 是原始字节：zip 形态是包里那张图，单文件形态是清单里 data URI 解出来的
     * （§11.2 的 8 KiB 上限由 {@code Manifest} 把着）。判据与包内图片同一套 —— 得是真 PNG、
     * 边长不超 {@link #MAX_SIDE}、字节数不超 {@link #MAX_BYTES}。
     *
     * <p>判完就记住，包括"用不了"：主屏每帧问一次图标，不记的话每帧重判一次。
     */
    public static ResourceLocation icon(String appId, byte[] png) {
        if (appId == null) return null;
        if (ICONS.containsKey(appId)) {
            ImageCodec.Texture known = ICONS.get(appId);
            return known == null ? null : known.location();
        }

        ImageCodec.Texture tex = null;
        int[] size = png == null ? null : PngHeader.size(png);
        if (png != null && png.length <= MAX_BYTES && size != null
                && size[0] <= MAX_SIDE && size[1] <= MAX_SIDE) {
            tex = uploader.upload(png);
        }
        ICONS.put(appId, tex);
        if (tex == null) {
            MCphone.LOGGER.warn("[MCphone] App {} 的图标用不了，商店画占位方块", appId);
        }
        return tex == null ? null : tex.location();
    }

    /** 还回这个 App 图标占的显存。卸载、覆盖安装时调。 */
    public static void releaseIcon(String appId) {
        if (appId == null) return;
        if (!ICONS.containsKey(appId)) return;
        uploader.release(ICONS.remove(appId));
    }

    /**
     * 还回这个包占的显存。关页面、卸包时调 —— 不调的话贴图会一直挂在 TextureManager 上，
     * 开关两百次就是两百份（§8.8）。
     *
     * <p>没有引用计数：表按包摘要分组，而摘要相同就意味着内容逐字节相同，两个 AppPackage 实例
     * 共用一份贴图。同一个包同时被两处打开时，先关的那一方把另一方的也还了 —— 另一方下一帧重传，
     * 表现是闪一下。眼下不会发生（一次只开一页），真要同时开两页就得在这儿加计数。
     */
    public static void release(AppPackage pkg) {
        if (pkg == null) return;
        App app = APPS.remove(pkg.digest());
        if (app == null) return;
        for (Entry e : app.live.values()) {
            uploader.release(e.texture);
            e.texture = null;
        }
        app.live.clear();
    }

    /** 先检查字节数和 IHDR，再交给按需解码。返回的路径只属于当前包，不含 URL 或凭证。 */
    public static Map<String, Object> remotePng(AppPackage pkg, byte[] bytes) {
        if (pkg == null || bytes == null || bytes.length > MAX_BYTES) return Map.of("status", "INVALID");
        int[] size = PngHeader.size(bytes);
        if (size == null || size[0] > MAX_REMOTE_SIDE || size[1] > MAX_REMOTE_SIDE
                || (long) size[0] * size[1] > 262144) return Map.of("status", "INVALID");
        App app = APPS.computeIfAbsent(pkg.digest(), key -> new App());
        int count = 0;
        for (var entry : app.entries.entrySet()) if (entry.getValue().remotePng != null) {
            count++;
            if (java.util.Arrays.equals(entry.getValue().remotePng, bytes))
                return remoteResult(entry.getKey(), entry.getValue());
        }
        if (count >= MAX_PER_APP) return Map.of("status", "QUOTA_EXCEEDED");
        String src = "private/" + java.util.UUID.randomUUID().toString().replace("-", "") + ".png";
        Entry entry = new Entry(); entry.result = Result.OK; entry.width = size[0]; entry.height = size[1];
        entry.remotePng = bytes.clone(); app.entries.put(src, entry); epoch++;
        return remoteResult(src, entry);
    }

    private static Map<String, Object> remoteResult(String src, Entry entry) {
        return entry.result == Result.OK ? Map.of("status", "READY", "src", src, "width", entry.width, "height", entry.height)
                : Map.of("status", "INVALID");
    }

    /** 玩家撤回许可或换服时立即清除远程原始字节和纹理；包内素材继续由原有生命周期管理。 */
    public static void clearRemote() {
        for (App app : APPS.values()) {
            var iterator = app.entries.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next(); Entry value = entry.getValue();
                if (value.remotePng == null) continue;
                app.live.remove(entry.getKey()); uploader.release(value.texture); value.texture = null; iterator.remove();
            }
        }
        epoch++;
    }

    /**
     * 全部还回去并让 {@link #epoch()} 前进。挂在客户端资源重载上（与 PhoneSkin.clearCache 同一处）。
     *
     * <p>重载之后原来那批 DynamicTexture 未必还在；与其赌它在不在，不如全丢掉重来 ——
     * 一个包至多 16 张 64 KiB 的图，重传的代价是一帧里几毫秒，而赌输的代价是整页白图。
     */
    public static void clearCache() {
        for (ImageCodec.Texture tex : ICONS.values()) uploader.release(tex);
        ICONS.clear();
        for (App app : APPS.values()) {
            for (Entry e : app.live.values()) {
                uploader.release(e.texture);
                e.texture = null;
            }
            app.live.clear();
        }
        APPS.clear();
        epoch++;
    }

    /** 这条素材的判定结果，只给测试用。和 {@link #size} 一样会当场判一次，判完就留在表里。 */
    static Result resultOf(AppPackage pkg, String src) {
        Entry e = entry(pkg, src);
        return e == null ? null : e.result;
    }

    /**
     * 这张图的贴图在不在显存里，只给测试用：假上传口给的 location 是 null，{@link #of} 的返回值分不出来。
     *
     * <p>【别在这儿用 {@code live.get}】：{@code live} 是访问序的，{@code get} 会把这一条刷成最新，
     * 于是"看一眼"就改掉了谁该被淘汰。{@code containsKey} 不动顺序。
     */
    static boolean uploaded(AppPackage pkg, String src) {
        App app = pkg == null ? null : APPS.get(pkg.digest());
        Entry e = app == null ? null : app.entries.get(src);
        return e != null && e.texture != null && app.live.containsKey(src);
    }

    /** 这个包在显存里有几张，只给测试用。它必须永远 ≤ {@link #MAX_PER_APP}。 */
    static int liveCount(AppPackage pkg) {
        App app = pkg == null ? null : APPS.get(pkg.digest());
        return app == null ? 0 : app.live.size();
    }

    /** 图标表里有几条，只给测试用。 */
    static int cachedIcons() {
        return ICONS.size();
    }

    /** 表里攒了多少条判定，只给测试用：装卸两百次之后它必须回到装之前的数（§8.8）。 */
    static int cachedEntries() {
        int n = 0;
        for (App app : APPS.values()) n += app.entries.size();
        return n;
    }

    /** 查表，没有就当场判一次。判定结果（含拒绝）一律留下：每帧重判一次坏图等于每帧解一次码。 */
    private static Entry entry(AppPackage pkg, String src) {
        if (pkg == null || src == null) return null;
        App app = APPS.computeIfAbsent(pkg.digest(), k -> new App());
        Entry known = app.entries.get(src);
        if (known != null) return known;

        Entry e = new Entry();
        e.result = judge(pkg, src, e);
        if (e.result != Result.OK) {
            if (app.rejected >= MAX_REJECTED) {
                // 这条不进表：用不了的路径记满了还往里塞，:src 绑个每次都变的表达式就能让表一直涨
                e.result = Result.TOO_MANY;
                warn(app, pkg, src, e.result);
                return e;
            }
            app.rejected++;
            warn(app, pkg, src, e.result);
        }
        app.entries.put(src, e);
        return e;
    }

    /**
     * 判一条素材能不能用。宽高在这里填进 {@code out}。
     *
     * <p>判据只有"是不是 .png"与"在不在包里"两条形状上的 —— §11.2 还写了素材只能放在 {@code assets/} 下，
     * 那条这里【不查】：查 src 形状的地方是 S2 的 {@code NodeParser.imageSrc} 与 S7 的 {@code PropRules}，
     * 它们只要求 {@code .png}。在这儿单独多一条，等于同一件事两处判据，装得进、校验全过的包到画的时候整批变占位图。
     * 要收紧就收紧在那两处（作者当场拿到错误码），或者装包时按 §11.2 拒——不在这儿。
     */
    private static Result judge(AppPackage pkg, String src, Entry out) {
        // 后缀与 NodeParser.imageSrc 逐字一致，不走 PathRules.extensionOf 的小写化：
        // 那边收不了 .PNG，这边收了也没用，反倒是两处判据说了两件事
        if (!src.endsWith(".png")) return Result.BAD_PATH;
        byte[] png = pkg.entry(src);
        if (png == null) return Result.MISSING;

        // 先判格式再判字节数：反过来的话，一个 100 KiB 的假 PNG 报的是"超过 64 KiB"，把人往错的方向指
        int[] size = PngHeader.size(png);
        if (size == null) return Result.NOT_PNG;
        if (png.length > MAX_BYTES) return Result.TOO_LARGE;
        if (size[0] > MAX_SIDE || size[1] > MAX_SIDE) return Result.TOO_BIG;

        out.width = size[0];
        out.height = size[1];
        return Result.OK;
    }

    private static void warn(App app, AppPackage pkg, String src, Result why) {
        if (app.warned >= MAX_WARNINGS) return;
        app.warned++;
        MCphone.LOGGER.warn("[MCphone] App {} 的图片 {} 用不了：{}", pkg.manifest().id(), src, reason(why));
        if (app.warned == MAX_WARNINGS) {
            MCphone.LOGGER.warn("[MCphone] App {} 用不了的图片已经报了 {} 条，后面的不再报",
                    pkg.manifest().id(), MAX_WARNINGS);
        }
    }

    private static void warn(AppPackage pkg, String src, Result why) {
        App app = APPS.get(pkg.digest());
        if (app != null) warn(app, pkg, src, why);
    }

    private static String reason(Result why) {
        return switch (why) {
            case MISSING -> "包里没有这个文件";
            case BAD_PATH -> "只能是 .png";
            case TOO_LARGE -> "超过 " + (MAX_BYTES / 1024) + " KiB";
            case TOO_BIG -> "超过 " + MAX_SIDE + "×" + MAX_SIDE;
            case NOT_PNG -> "不是 PNG";
            case BROKEN -> "PNG 头没问题，像素解不开";
            case TOO_MANY -> "这个 App 用不了的图片路径已经有 " + MAX_REJECTED + " 条了";
            case OK -> "";
        };
    }

    // ============================================================
    //  上传口
    // ============================================================

    /**
     * 贴图怎么进显存。游戏里走 {@link ImageCodec}；docs 的断言测试换一个假的 ——
     * 那里没有 Minecraft 的运行环境，而要测的是判定、名额与释放，不是像素。
     */
    interface Uploader {
        /** 上传一张已经过判定的 PNG，传不上返回 null。 */
        ImageCodec.Texture upload(byte[] png);
        default ImageCodec.Texture upload(byte[] png, int maxSide) { return upload(png); }

        /** 还回去。传 null 是合法的（这张图还没画过）。 */
        void release(ImageCodec.Texture texture);
    }

    private static Uploader uploader = new GameUploader();

    /** 换上传口，只给测试用。换的时候表里不能留着别人的贴图。 */
    static void uploader(Uploader replacement) {
        clearCache();
        uploader = replacement == null ? new GameUploader() : replacement;
    }

    private static final class GameUploader implements Uploader {
        @Override
        public ImageCodec.Texture upload(byte[] png) {
            return upload(png, MAX_SIDE);
        }

        @Override
        public ImageCodec.Texture upload(byte[] png, int maxSide) {
            // MAX_SIDE 在这儿是"不缩放"的保证：判定已经挡掉了更大的，缩放路径走不到
            NativeImage image = ImageCodec.decodeAndScale(png, maxSide);
            return image == null ? null : ImageCodec.upload(image, "script_app_");
        }

        @Override
        public void release(ImageCodec.Texture texture) {
            ImageCodec.release(texture);
        }
    }
}
