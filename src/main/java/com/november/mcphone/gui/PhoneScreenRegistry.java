package com.november.mcphone.gui;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.november.mcphone.MCphone;
import com.november.mcphone.api.IPhoneApp;

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
 * 状态文件: config/mcphone/installed.json
 *
 *   {
 *     "installed": ["settings", "music"],   已装 id
 *     "known":     ["settings", "music", "camera"]   目录中出现过的全部 id
 *   }
 *
 * 记录 known 是为了区分两种"不在 installed 里"的情况：
 *   - 在 known 中但不在 installed → 玩家主动卸载过，保持卸载
 *   - 不在 known 中               → 该 App 首次出现（如新装了附属模组），
 *                                   按 IPhoneApp#isPreinstalled() 决定初始状态
 */
public final class PhoneScreenRegistry {

    /** 目录：SPI 发现的全部 App，启动时写入一次，此后永不移除 */
    private static final Map<String, IPhoneApp> CATALOG = new LinkedHashMap<>();

    /** 已安装 App 的 id 集合，决定主屏显示什么 */
    private static final Set<String> INSTALLED = new LinkedHashSet<>();

    private static final Path STATE_FILE = Path.of("config/mcphone/installed.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static boolean loaded = false;

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
        if (app == null || app.getId() == null || app.getId().isEmpty()) {
            MCphone.LOGGER.warn("[MCphone] App 登记失败: id 为空");
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
    public static boolean install(String id) {
        ensureLoaded();
        if (id == null || id.isEmpty()) return false;

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
    public static boolean uninstall(String id) {
        ensureLoaded();
        if (id == null || id.isEmpty()) return false;

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

    /** 已安装 App（只读列表，保持目录顺序）—— 主屏显示的就是这些 */
    public static List<IPhoneApp> getApps() {
        ensureLoaded();
        List<IPhoneApp> out = new ArrayList<>();
        for (Map.Entry<String, IPhoneApp> e : CATALOG.entrySet()) {
            if (INSTALLED.contains(e.getKey())) out.add(e.getValue());
        }
        return List.copyOf(out);
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
        for (Map.Entry<String, IPhoneApp> e : CATALOG.entrySet()) {
            if (!INSTALLED.contains(e.getKey())) out.add(e.getValue());
        }
        return List.copyOf(out);
    }

    /** 按 id 查找目录中的 App（不论是否已安装） */
    public static IPhoneApp getApp(String id) {
        ensureLoaded();
        return CATALOG.get(id);
    }

    /** 按主屏索引查找已安装 App */
    public static IPhoneApp getApp(int index) {
        List<IPhoneApp> apps = getApps();
        return (index >= 0 && index < apps.size()) ? apps.get(index) : null;
    }

    /** 已安装 App 数量 */
    public static int getAppCount() { return getApps().size(); }

    public static boolean isInstalled(String id) {
        ensureLoaded();
        return INSTALLED.contains(id);
    }

    // ================================================================
    //  延迟初始化（SPI 统一扫描）
    // ================================================================

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;

        // 所有 App 均通过 SPI 发现，包括内建 App
        // ServiceLoader 会扫描所有已加载 jar 包中的
        // META-INF/services/com.november.mcphone.api.IPhoneApp
        int count = 0;
        for (IPhoneApp app : ServiceLoader.load(IPhoneApp.class)) {
            if (register(app)) count++;
        }
        MCphone.LOGGER.info("[MCphone] SPI 扫描完成，目录中共 {} 个 App", count);

        loadState();
    }

    // ================================================================
    //  持久化
    // ================================================================

    /** installed.json 的结构 */
    private static final class State {
        List<String> installed;
        List<String> known;
    }

    private static void loadState() {
        Set<String> known = new HashSet<>();
        Set<String> installed = new HashSet<>();

        if (Files.isRegularFile(STATE_FILE)) {
            try (Reader r = Files.newBufferedReader(STATE_FILE, StandardCharsets.UTF_8)) {
                State s = GSON.fromJson(r, State.class);
                if (s != null) {
                    if (s.installed != null) installed.addAll(s.installed);
                    if (s.known != null) known.addAll(s.known);
                }
            } catch (Exception e) {
                MCphone.LOGGER.warn("[MCphone] 读取 {} 失败，按默认安装状态处理: {}",
                        STATE_FILE, e.toString());
            }
        }

        INSTALLED.clear();
        for (Map.Entry<String, IPhoneApp> e : CATALOG.entrySet()) {
            String id = e.getKey();
            // 目录中出现过 → 沿用玩家的选择；首次出现 → 看是否预装
            boolean on = known.contains(id)
                    ? installed.contains(id)
                    : e.getValue().isPreinstalled();
            if (on) INSTALLED.add(id);
        }

        MCphone.LOGGER.info("[MCphone] 已安装 {} / 目录 {} 个 App",
                INSTALLED.size(), CATALOG.size());

        // 把本次新发现的 App 写回 known，下次启动才能区分"卸载过"和"首次出现"
        saveState();
    }

    private static void saveState() {
        State s = new State();
        s.installed = new ArrayList<>(INSTALLED);
        s.known = new ArrayList<>(CATALOG.keySet());
        try {
            Path parent = STATE_FILE.getParent();
            if (parent != null) Files.createDirectories(parent);
            try (Writer w = Files.newBufferedWriter(STATE_FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(s, w);
            }
        } catch (IOException e) {
            MCphone.LOGGER.warn("[MCphone] 写入 {} 失败: {}", STATE_FILE, e.toString());
        }
    }
}
