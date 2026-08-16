package com.november.mcphone.core.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.november.mcphone.MCphone;
import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.app.RequiredMod;
import com.november.mcphone.feature.store.AppPriceRegistry;
import com.november.mcphone.feature.store.net.StoreClientCache;
import com.november.mcphone.util.SpiLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 手机 App 注册表 —— 模拟手机操作系统的 App 目录与安装状态。
 *
 * ================================================================
 * 两层结构
 * ================================================================
 *
 * 这里刻意把两个概念分开，它们的生命周期完全不同：
 *
 *   CATALOG（目录）   这台机器上"存在"哪些 App。
 *                    启动时由 SPI 扫描写入一次，此后永不移除。
 *
 *   INSTALLED（已装） 玩家手机主屏上"装了"哪些 App，只存 id。
 *                    安装/卸载只改这个集合，并持久化到磁盘。
 *                    它【有序】，那个顺序就是图标在主屏上的排列。
 *
 * 卸载不会销毁目录条目，因此卸载是可逆的——应用商店正是靠
 * "目录 - 已装"列出可下载的 App。若像早期版本那样在卸载时直接
 * 从目录中移除实例，App 一旦卸载就再也无法装回。
 *
 * ================================================================
 * App 来源（统一走 SPI）
 * ================================================================
 *
 * 1. MCphone 内建 App   — 注册在 META-INF/services/...IPhoneApp
 * 2. 附属模组 App        — 同样注册在 META-INF/services/...IPhoneApp
 * 3. 运行时动态注册      — 调用 install(IPhoneApp)
 *
 * ================================================================
 * 持久化
 * ================================================================
 *
 * 状态文件: config/mcphone/installed/<存档标识>.json —— 每个存档/服务器一份
 *
 *   {
 *     "installed": ["mcphone:settings", "mcphone:music"],   已装 id，【顺序＝主屏排列】
 *     "known":     ["mcphone:settings", "mcphone:music", "mcphone:camera"]
 *   }
 *
 * installed 那一串是有序的：1.3.8 起玩家能在主屏上拖动图标，摆出来的顺序就存在
 * 这里。手改这个文件也能改主屏顺序。known 则只是个集合，顺序没有意义。
 *
 * 1.0.47 起 id 带命名空间。老文件里是裸串（"settings"），读取时一律按
 * mcphone 补全——详见 parseStoredId，那里说明了为什么不能用
 * ResourceLocation.parse。
 *
 * 记录 known 是为了区分两种"不在 installed 里"的情况：
 *   - 在 known 中但不在 installed → 玩家主动卸载过，保持卸载
 *   - 不在 known 中               → 该 App 首次出现（如新装了附属模组），
 *                                   按 IPhoneApp#isPreinstalled() 决定初始状态
 */
public final class PhoneScreenRegistry {

    /** 目录：SPI 发现的全部 App，启动时写入一次，此后永不移除 */
    private static final Map<ResourceLocation, IPhoneApp> CATALOG = new LinkedHashMap<>();

    /**
     * 前置模组没装、因而没能进目录的 App。
     *
     * ============================================================
     * 为什么留着，而不是像原来那样直接丢掉
     * ============================================================
     *
     * 因为「没装 MCEF 所以没有浏览器」这件事，只有这里知道。丢掉之后玩家再
     * 也无从得知手机里还能多出什么——他甚至不知道有这么个 App 存在过。
     *
     * 应用商店的「联动 App」那一页就是从这儿取数据：把它们标着"未装 XXX"
     * 列出来，玩家才有机会发现装了对应模组能多个什么。
     *
     * ============================================================
     * 它们【不】进目录，这一点不能松
     * ============================================================
     *
     * 这里的 App 不可安装、不可点开、不出现在主屏与商店的普通列表里。原来
     * 那句注释仍然成立：商店里躺着一个点了必然报错的东西，比它压根不出现
     * 更糟。留着只为"说明它需要什么"，不为让它能用。
     *
     * 还有一点：这里的实例是活的 Java 对象，而它依赖的模组正好不在。读它的
     * 名字、图标、简介时必须兜住 Throwable——附属模组完全可能在
     * getDisplayName() 里碰对方的类，那会抛 NoClassDefFoundError。
     */
    private static final Map<ResourceLocation, IPhoneApp> UNAVAILABLE = new LinkedHashMap<>();

    /** 已安装 App 的 id 集合，决定主屏显示什么 */
    private static final Set<ResourceLocation> INSTALLED = new LinkedHashSet<>();

    /**
     * 每个存档 / 每个服务器一份状态文件。
     *
     * 1.1.13 之前是 config/mcphone/installed.json 一个文件走天下——整个游戏
     * 实例共用，于是在存档 A 买了并安装的 App，换到存档 B 主屏上还在。购买
     * 记录本身是按存档记的（服务端附件），漏就漏在这个文件上。
     */
    private static final Path STATE_DIR = Path.of("config/mcphone/installed");

    /** 1.1.13 之前那个全局文件。只用于一次性迁移，见 migrateLegacyIfNeeded */
    private static final Path LEGACY_STATE_FILE = Path.of("config/mcphone/installed.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 目录是否已扫描。与"状态是否已加载"是两件事，前者一辈子只做一次 */
    private static boolean loaded = false;

    /**
     * 当前存档的状态文件。没进世界时为 null——那时候 INSTALLED 是空的，
     * 而手机本来就打不开。
     */
    private static Path stateFile = null;

    private PhoneScreenRegistry() {}

    // ================================================================
    //  目录登记
    // ================================================================

    /**
     * 把一个 App 登记进目录（不改变安装状态）。
     *
     * 同 id 重复登记时保留先注册的那个并告警。早期版本在此静默覆盖，
     * 会让先加载模组的 App 无声消失且无从排查。
     *
     * @return true 表示登记成功
     */
    public static boolean register(IPhoneApp app) {
        if (app == null || app.getId() == null) {
            MCphone.LOGGER.warn("[MCphone] App 登记失败: id 为空");
            return false;
        }

        // App 自己说不该存在（典型是它依赖的模组没装）就到此为止。
        // 这里必须在写入目录【之前】：让它进了目录，应用商店就会把一个
        // 点了必然报错的东西列成"可下载"，比它压根不出现更糟。
        //
        // 但不丢掉——记进 UNAVAILABLE，好让「联动 App」那一页能说出它缺什么。
        // 理由见那个字段的注释。
        if (!app.isAvailable()) {
            UNAVAILABLE.put(app.getId(), app);
            MCphone.LOGGER.info("[MCphone] App 跳过登记: {}（自称当前不可用）", app.getId());
            return false;
        }

        IPhoneApp old = CATALOG.get(app.getId());
        if (old != null) {
            MCphone.LOGGER.warn("[MCphone] App id 冲突: '{}' 已由 {} 注册，忽略 {}",
                    app.getId(), old.getClass().getName(), app.getClass().getName());
            return false;
        }

        CATALOG.put(app.getId(), app);
        MCphone.LOGGER.info("[MCphone] App 已登记: {} v{} by {}",
                app.getId(), app.getVersion(), app.getAuthor());
        return true;
    }

    /**
     * 运行时登记并立即安装 —— 供附属模组动态注册使用。
     * @return true 表示登记且安装成功
     */
    public static boolean install(IPhoneApp app) {
        if (!register(app)) return false;
        return install(app.getId());
    }

    // ================================================================
    //  安装 / 卸载（只改 INSTALLED，目录不动）
    // ================================================================

    /**
     * 安装目录中已有的 App。
     * @return true 表示本次调用真正改变了安装状态
     */
    public static boolean install(ResourceLocation id) {
        ensureLoaded();
        if (id == null) return false;

        IPhoneApp app = CATALOG.get(id);
        if (app == null) {
            MCphone.LOGGER.warn("[MCphone] 安装失败: 目录中没有 App '{}'", id);
            return false;
        }
        if (!INSTALLED.add(id)) return false; // 已经装了

        saveState();
        MCphone.LOGGER.info("[MCphone] App 已安装: {}", id);
        return true;
    }

    /**
     * 卸载一个 App。系统 App 不可卸载。
     * 只从已装集合移除，目录条目保留，可从应用商店重新安装。
     *
     * @return true 表示卸载成功
     */
    public static boolean uninstall(ResourceLocation id) {
        ensureLoaded();
        if (id == null) return false;

        IPhoneApp app = CATALOG.get(id);
        if (app == null || !INSTALLED.contains(id)) {
            MCphone.LOGGER.warn("[MCphone] 卸载失败: App '{}' 未安装", id);
            return false;
        }
        if (app.isSystemApp()) {
            MCphone.LOGGER.warn("[MCphone] 卸载失败: '{}' 是系统App，不可卸载", id);
            return false;
        }

        app.onUninstall();
        INSTALLED.remove(id);
        saveState();
        MCphone.LOGGER.info("[MCphone] App 已卸载: {}", id);
        return true;
    }

    // ================================================================
    //  查询
    // ================================================================

    /**
     * 已安装 App（只读列表）—— 主屏显示的就是这些，**顺序就是主屏上的排列顺序**。
     *
     * 1.3.8 之前这里遍历的是 CATALOG、拿 INSTALLED 当过滤器，于是主屏顺序等于
     * SPI 扫描顺序：玩家摆不动它。现在遍历 INSTALLED 本身——它是 LinkedHashSet，
     * 插入序就是玩家看到的顺序，{@link #moveApp} 改的也是它。
     *
     * 一个集合同时表达"装了哪些"和"怎么排"，而不是另开一个 order 字段：两份数据
     * 就有两份数据不一致的可能，而卸载一个 App 时忘了同步另一份，表现出来是主屏
     * 上留一个点不动的空格。
     *
     * 目录里查不到的 id 会被跳过。这是兜底——install() 与 loadState() 都只往
     * INSTALLED 里放目录中已有的 id，所以正常情况下不会发生。这条不变式也是
     * {@link #moveApp} 能直接用下标的前提。
     */
    public static List<IPhoneApp> getApps() {
        ensureLoaded();
        List<IPhoneApp> out = new ArrayList<>(INSTALLED.size());
        for (ResourceLocation id : INSTALLED) {
            IPhoneApp app = CATALOG.get(id);
            if (app != null) out.add(app);
        }
        // unmodifiableList 而不是 List.copyOf：后者会把刚建好的表再拷一遍数组。
        // out 是本方法里新建的局部变量，出了这里没有第二个人握着它的引用，
        // 包一层只读视图与真拷一份在外部看来完全等价，少一次数组分配。
        // 这是主屏每帧都要走的一条路，见 getAppCount 的注释
        return Collections.unmodifiableList(out);
    }

    /**
     * 把主屏第 from 格的 App 挪到第 to 格。
     *
     * 【插入】而不是交换：把第 1 个拖到第 3 格，中间那些依次前移，就像真手机那样。
     * 交换的话玩家拖一个图标会让另一个莫名其妙地跳到他手指原来的位置上。
     *
     * 下标按 {@link #getApps()} 的下标算——两者一一对应，理由见那个方法的注释。
     *
     * @return 真的改变了顺序才返回 true；下标越界或原地不动返回 false
     */
    public static boolean moveApp(int from, int to) {
        ensureLoaded();

        List<ResourceLocation> order = new ArrayList<>(INSTALLED);
        // 重排的算术走 HomeLayout：界面上的实时预览用的是同一个方法，
        // 两边共用才不会出现"看着会插到这儿、松手却去了那儿"
        if (!HomeLayout.reorder(order, from, to)) return false;

        // 清空再灌回去：LinkedHashSet 没有"就地重排"这回事，它的顺序只由插入
        // 顺序决定。这里的元素总数是个位数，重建的开销可以忽略
        INSTALLED.clear();
        INSTALLED.addAll(order);

        saveState();
        return true;
    }

    /** 目录中的全部 App，含未安装的 */
    public static List<IPhoneApp> getCatalog() {
        ensureLoaded();
        return List.copyOf(CATALOG.values());
    }

    /** 目录中尚未安装的 App —— 应用商店的"可下载"列表 */
    public static List<IPhoneApp> getAvailable() {
        ensureLoaded();
        List<IPhoneApp> out = new ArrayList<>();
        for (Map.Entry<ResourceLocation, IPhoneApp> e : CATALOG.entrySet()) {
            if (!INSTALLED.contains(e.getKey())) out.add(e.getValue());
        }
        return List.copyOf(out);
    }

    /**
     * 全部联动 App —— 声明了外部模组前置的那些，不论前置装没装。
     *
     * 可用的（前置装了，在目录里）排在前面，不可用的排在后面。这个顺序是有
     * 意的：玩家一眼先看到"我已经有的"，再看到"还能有的"，而不是从一堆灰条
     * 里找哪个是亮的。
     *
     * @see #requiredModsOf(IPhoneApp) 读前置要走那个方法，不要直接调
     */
    public static List<IPhoneApp> getCompanionApps() {
        ensureLoaded();
        List<IPhoneApp> out = new ArrayList<>();
        for (IPhoneApp app : CATALOG.values()) {
            if (!requiredModsOf(app).isEmpty()) out.add(app);
        }
        out.addAll(UNAVAILABLE.values());   // 不可用的必定声明了前置，不必再筛
        return List.copyOf(out);
    }

    /** 这个 App 是不是因为前置没装而没能进目录 */
    public static boolean isUnavailable(IPhoneApp app) {
        return app != null && UNAVAILABLE.containsKey(app.getId());
    }

    /**
     * 读一个 App 声明的前置，读不出来就当没有。
     *
     * 兜 Throwable 不是谨慎，是必须：UNAVAILABLE 里的 App 依赖的模组正好不在，
     * 而附属模组完全可能在 requiredMods() 里引用对方的类（比如拿对方的常量拼
     * 显示名）。那抛出来的是 NoClassDefFoundError——属于 Error 不是 Exception，
     * 用后者接不住。
     *
     * 接不住的代价是：玩家点开「联动 App」那一页，整个手机界面崩掉——而这一页
     * 存在的意义只是告诉他缺个模组，这个代价完全不成比例。
     */
    public static List<RequiredMod> requiredModsOf(IPhoneApp app) {
        if (app == null) return List.of();
        try {
            List<RequiredMod> mods = app.requiredMods();
            return mods == null ? List.of() : mods;
        } catch (Throwable t) {
            MCphone.LOGGER.warn("[MCphone] 读取 {} 的前置声明失败，当作没有声明",
                    app.getClass().getName(), t);
            return List.of();
        }
    }

    /** 按 id 查找目录中的 App（不论是否已安装） */
    public static IPhoneApp getApp(ResourceLocation id) {
        ensureLoaded();
        return CATALOG.get(id);
    }

    /**
     * 按主屏索引查找已安装 App。
     *
     * 直接遍历 INSTALLED 数到第 index 个，不再先建整张表再取一个——
     * 理由同 {@link #getAppCount()}。
     */
    public static IPhoneApp getApp(int index) {
        ensureLoaded();
        if (index < 0) return null;

        int i = 0;
        for (ResourceLocation id : INSTALLED) {
            IPhoneApp app = CATALOG.get(id);
            if (app == null) continue;      // 与 getApps 同一条跳过规则，下标才对得上
            if (i++ == index) return app;
        }
        return null;
    }

    /**
     * 已安装 App 数量。
     *
     * 原先是 getApps().size() —— 为了拿一个整数，先建一个 ArrayList，再让
     * List.copyOf 把它拷成第二个数组，然后只读 size。
     *
     * 这不是理论上的浪费：主屏一帧里它至少被调四次（renderAppGrid 的边缘
     * 翻页结算、pageCount、goToPage 的夹取、updateAppHover），拖动时还要
     * 再加一次。每帧十次数组分配，只为几个个位数的计数。
     *
     * 现在只数不建表。跳过规则与 getApps 保持一致（目录里没有的 id 不算），
     * 否则"主屏画了几个"与"一共几个"会对不上，表现是最后一页多出一格空白。
     */
    public static int getAppCount() {
        ensureLoaded();
        int n = 0;
        for (ResourceLocation id : INSTALLED) {
            if (CATALOG.containsKey(id)) n++;
        }
        return n;
    }

    public static boolean isInstalled(ResourceLocation id) {
        ensureLoaded();
        return INSTALLED.contains(id);
    }

    // ================================================================
    //  延迟初始化（SPI 统一扫描）
    // ================================================================

    /**
     * 扫描 App 目录。一辈子只做一次。
     *
     * 【不】在这里读安装状态：状态是按存档分的，而这个方法在客户端启动时
     * 就会被触发，那会儿还不知道玩家要进哪个世界。读状态改由
     * {@link #loadForCurrentWorld()} 在进世界时做。
     */
    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;

        // 所有 App 均通过 SPI 发现，包括内建 App
        // ServiceLoader 会扫描所有已加载 jar 包中的
        // META-INF/services/com.november.mcphone.api.client.app.IPhoneApp
        //
        // 走 SpiLoader 而不是直接 for-each ServiceLoader：一个附属的 App 构造失败
        // 会让整个扫描中断，内建 App 一个都登记不上，玩家的手机变成空的。理由
        // 详见那个类的注释
        int count = 0;
        for (IPhoneApp app : SpiLoader.loadSafely(IPhoneApp.class, "App")) {
            // register 会调 getId()、isAvailable()——都是第三方代码，同样要兜。
            // 一个 App 的 isAvailable() 抛异常，不该让它后面那些登记不上
            try {
                if (register(app)) count++;
            } catch (Throwable t) {
                MCphone.LOGGER.error("[MCphone] App {} 登记时抛异常，已跳过",
                        app.getClass().getName(), t);
            }
        }
        MCphone.LOGGER.info("[MCphone] SPI 扫描完成，目录中共 {} 个 App", count);
    }

    // ================================================================
    //  持久化
    // ================================================================

    /** installed.json 的结构 */
    private static final class State {
        List<String> installed;
        List<String> known;
    }

    /**
     * 把状态文件里的一条 id 解析成 ResourceLocation。
     *
     * 1.0.47 之前 id 是不带命名空间的裸串（"settings"、"music"），
     * 而那个时候目录里只可能有内建 App，所以裸串一律归到 mcphone 名下。
     *
     * 这里【不能】直接用 ResourceLocation.parse：它会把裸串补成
     * minecraft:settings，与新写法的 mcphone:settings 对不上，结果是
     * 老玩家的安装/卸载选择全部作废、主屏一夜回到默认状态。这种数据丢失
     * 不会报错，只会让人觉得"更新完 App 全乱了"。
     *
     * @return 解析不了时返回 null，调用方跳过该条
     */
    private static ResourceLocation parseStoredId(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        ResourceLocation id = ResourceLocation.tryParse(
                s.indexOf(':') < 0 ? MCphone.MODID + ":" + s : s);
        if (id == null) {
            MCphone.LOGGER.warn("[MCphone] 状态文件中有无法解析的 App id '{}'，已忽略", raw);
        }
        return id;
    }

    /**
     * 把状态文件里的一串 id 解析进目标集合，跳过解析不了的。
     *
     * 收 Collection 而不是 Set：installed 那一串的【顺序】就是主屏排列，得用
     * LinkedHashSet 接着；known 只关心有没有，用 HashSet 就够。
     */
    private static void parseStoredIds(List<String> raw, Collection<ResourceLocation> out) {
        if (raw == null) return;
        for (String s : raw) {
            ResourceLocation id = parseStoredId(s);
            if (id != null) out.add(id);
        }
    }

    // ================================================================
    //  按存档加载 / 卸载
    // ================================================================

    /**
     * 把没买过的付费 App 从主屏摘掉。收到服务端的购买记录后调用。
     *
     * ============================================================
     * 不做这一步会造出一个死胡同
     * ============================================================
     *
     * 服务端现在会拦住"没买过却要用"的请求（见 AppAccess）。而 1.1.9 之前
     * 根本没有购买这回事，老玩家主屏上装着末影箱 App、却从没买过——点开被
     * 拒，转身去应用商店买，商店里却没有它：商店列的是"目录减去已安装"，
     * 而它已安装。买不到、又用不了，卡死。
     *
     * 所以立一条规则：没买过的付费 App 不该待在主屏上。摘掉之后它自然回到
     * 商店的可下载列表里，玩家买完再装回来，路就通了。
     *
     * 顺带也让客户端状态与服务端那本账对齐：换台电脑登录、或在别处退了款，
     * 主屏都会跟着修正。
     */
    public static void enforcePurchases() {
        if (stateFile == null) return;   // 还没进世界，此时的 INSTALLED 不代表任何存档

        List<ResourceLocation> revoked = new ArrayList<>();
        for (ResourceLocation id : INSTALLED) {
            if (!AppPriceRegistry.isPaid(id)) continue;
            if (StoreClientCache.has(id)) continue;
            revoked.add(id);
        }
        if (revoked.isEmpty()) return;

        INSTALLED.removeAll(revoked);
        saveState();
        MCphone.LOGGER.info("[MCphone] 有 {} 个付费 App 未购买，已从主屏移除: {}",
                revoked.size(), revoked);
    }

    /**
     * 进世界时调用：算出当前存档的标识，读它自己那份状态。
     *
     * 客户端登录事件触发，见 MCphoneClient。
     */
    public static void loadForCurrentWorld() {
        ensureLoaded();

        String key = currentWorldKey();
        stateFile = STATE_DIR.resolve(key + ".json");
        migrateLegacyIfNeeded();
        loadState();

        MCphone.LOGGER.info("[MCphone] 已加载存档 '{}' 的 App 安装状态", key);
    }

    /**
     * 退出世界时调用：把安装状态清空。
     *
     * 不清的话，下一个存档在自己的状态读进来之前会先显示上一个存档的主屏。
     * 那一瞬间玩家看到的是别处的数据，而且如果他恰好在这时点了什么，还会
     * 被写进新存档的文件里。
     */
    public static void unloadWorld() {
        INSTALLED.clear();
        stateFile = null;
    }

    /**
     * 当前存档/服务器的标识。
     *
     * 联机用服务器地址，单机用存档目录名——两者都是玩家心里"这一局"的
     * 天然边界。不用世界显示名：两个存档可以重名，那样会让它们共用一份
     * 状态，等于没隔离。
     */
    private static String currentWorldKey() {
        Minecraft mc = Minecraft.getInstance();

        ServerData server = mc.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            return "server_" + sanitize(server.ip);
        }

        MinecraftServer single = mc.getSingleplayerServer();
        if (single != null) {
            Path dir = single.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            Path name = dir.getFileName();
            if (name != null) return "world_" + sanitize(name.toString());
        }

        // 两种都取不到（理论上不该发生）时用一个固定名字，而不是抛异常：
        // 手机是个玩具，取不到存档名的代价应该是"状态存到了一个共用文件"，
        // 不是"游戏崩了"
        return "unknown";
    }

    /** 只留文件名安全的字符。冒号、斜杠这些在各平台上的下场不一样，一律换掉 */
    private static String sanitize(String raw) {
        String s = raw.replaceAll("[^a-zA-Z0-9._-]", "_");
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    /**
     * 一次性迁移：把 1.1.13 之前那个全局文件搬给当前存档。
     *
     * 不迁移的话，所有老玩家进游戏会发现主屏被清空了——而末影箱与传送石
     * 现在不预装，等于连买过的东西一起没收，还得再花一次钱。
     *
     * 只搬给【第一个进入的存档】，搬完把旧文件改名。不删是留条后路；不复制
     * 给每个存档是因为那就等于把要修的 bug 换个形式保留下来。
     */
    private static void migrateLegacyIfNeeded() {
        if (stateFile == null || Files.exists(stateFile)) return;
        if (!Files.isRegularFile(LEGACY_STATE_FILE)) return;

        try {
            Path parent = stateFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.copy(LEGACY_STATE_FILE, stateFile);
            Files.move(LEGACY_STATE_FILE,
                    LEGACY_STATE_FILE.resolveSibling("installed.json.migrated"));
            MCphone.LOGGER.info("[MCphone] 已把旧的全局安装状态迁移给当前存档，"
                    + "旧文件改名为 installed.json.migrated");
        } catch (IOException e) {
            // 迁移失败不是中断的理由：大不了这个存档按默认安装状态开局
            MCphone.LOGGER.warn("[MCphone] 迁移旧安装状态失败: {}", e.toString());
        }
    }

    private static void loadState() {
        Set<ResourceLocation> known = new HashSet<>();
        // 有序：这一串的顺序就是玩家上次摆好的主屏顺序
        Set<ResourceLocation> installed = new LinkedHashSet<>();

        if (stateFile != null && Files.isRegularFile(stateFile)) {
            try (Reader r = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8)) {
                State s = GSON.fromJson(r, State.class);
                if (s != null) {
                    parseStoredIds(s.installed, installed);
                    parseStoredIds(s.known, known);
                }
            } catch (Exception e) {
                MCphone.LOGGER.warn("[MCphone] 读取 {} 失败，按默认安装状态处理: {}",
                        stateFile, e.toString());
            }
        }

        INSTALLED.clear();

        // 第一段：文件里记着装了的，按【文件里的先后】装回来。
        // 这一段决定主屏的排列——1.3.8 之前这里是遍历 CATALOG 的，于是玩家怎么
        // 摆都会在下次进世界时被摆回目录顺序
        for (ResourceLocation id : installed) {
            if (CATALOG.containsKey(id)) INSTALLED.add(id);
        }

        // 第二段：目录里有、而玩家从没见过的（known 里没有），按该不该预装决定，
        // 一律【追加到末尾】。新装个附属模组不该把已经摆好的位置挤乱，
        // 真手机装了新 App 也是落在最后一格
        for (Map.Entry<ResourceLocation, IPhoneApp> e : CATALOG.entrySet()) {
            ResourceLocation id = e.getKey();
            if (known.contains(id) || INSTALLED.contains(id)) continue;
            if (shouldPreinstall(id, e.getValue())) INSTALLED.add(id);
        }

        MCphone.LOGGER.info("[MCphone] 已安装 {} / 目录 {} 个 App",
                INSTALLED.size(), CATALOG.size());

        // 把本次新发现的 App 写回 known，下次启动才能区分"卸载过"和"首次出现"
        saveState();
    }


    /**
     * 一个首次出现的 App 该不该直接躺在主屏上。
     *
     * ============================================================
     * 两个条件，问的是两件事
     * ============================================================
     *
     * isPreinstalled() 是【App 自己的意愿】——"我想不想默认出现在主屏"。
     * 附属完全可能有个免费 App 却希望它从商店里开始，那是它的选择，我们不越权。
     *
     * 价格是【硬约束】——付费 App 不能白送。这一条不看谁的意愿：一个 App 声明了
     * 价格却忘了把 isPreinstalled 改成 false，玩家就会白得一个本该花钱的东西。
     * 内建的两个付费 App 各自也覆盖了 isPreinstalled，两道都在，是刻意的：这里
     * 出错的代价是"付费内容白送"，值得多一层。
     *
     * ============================================================
     * 为什么免费的一律预装
     * ============================================================
     *
     * 新玩家第一次打开手机，看到的应该是一部能用的手机，而不是一个几乎空的
     * 主屏加一句"去商店下载"。免费的东西让他先去商店走一趟，这一趟没有任何
     * 意义——他不需要做决定，也没有别的选项。
     *
     * 相机与相册在 1.3.3 之前就卡在这儿：明明免费，却要先进商店装一次。
     *
     * 玩家自己卸载过的不受影响：那属于"目录中出现过"，走的是上面 known 那条
     * 分支，沿用他的选择。
     */
    private static boolean shouldPreinstall(ResourceLocation id, IPhoneApp app) {
        if (AppPriceRegistry.isPaid(id)) return false;
        return app.isPreinstalled();
    }

    private static void saveState() {
        // 没进世界就没有归属的文件，此时的 INSTALLED 也不代表任何存档。
        // 写下去只会污染上一个存档的状态
        if (stateFile == null) return;

        State s = new State();
        s.installed = INSTALLED.stream().map(ResourceLocation::toString).toList();
        s.known = CATALOG.keySet().stream().map(ResourceLocation::toString).toList();
        try {
            Path parent = stateFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (Writer w = Files.newBufferedWriter(stateFile, StandardCharsets.UTF_8)) {
                GSON.toJson(s, w);
            }
        } catch (IOException e) {
            MCphone.LOGGER.warn("[MCphone] 写入 {} 失败: {}", stateFile, e.toString());
        }
    }
}
