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
 * 手机 App 注册表：CATALOG（SPI 扫描到的全部 App，永不移除）与 INSTALLED（主屏装了哪些，有序＝主屏排列）。
 * 状态按存档/服务器各存一份到 config/mcphone/installed/<存档标识>.json；
 * known 记录见过哪些 App，用来区分"玩家卸载过"与"首次出现（按 isPreinstalled 决定）"。
 */
public final class PhoneScreenRegistry {

    /** 目录：SPI 发现的全部 App，启动时写入一次，此后永不移除 */
    private static final Map<ResourceLocation, IPhoneApp> CATALOG = new LinkedHashMap<>();

    /**
     * 前置模组没装、没能进目录的 App，只供商店「联动 App」页说明它缺什么；不可安装、不可点开。
     * 这些实例依赖的类不在，读名字/图标/简介必须兜 Throwable（NoClassDefFoundError）。
     */
    private static final Map<ResourceLocation, IPhoneApp> UNAVAILABLE = new LinkedHashMap<>();

    /** 已安装 App 的 id，有序：顺序就是主屏排列 */
    private static final Set<ResourceLocation> INSTALLED = new LinkedHashSet<>();

    /** 每个存档 / 每个服务器一份状态文件 */
    private static final Path STATE_DIR = Path.of("config/mcphone/installed");

    /** 旧的全局状态文件，只用于一次性迁移 */
    private static final Path LEGACY_STATE_FILE = Path.of("config/mcphone/installed.json");

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 目录是否已扫描。与"状态是否已加载"是两件事，前者一辈子只做一次 */
    private static boolean loaded = false;

    /** 当前存档的状态文件，没进世界时为 null */
    private static Path stateFile = null;

    private PhoneScreenRegistry() {}

    /** 登记进目录，不改安装状态。同 id 重复登记保留先注册的并告警 */
    public static boolean register(IPhoneApp app) {
        if (app == null || app.getId() == null) {
            MCphone.LOGGER.warn("[MCphone] App 登记失败: id 为空");
            return false;
        }

        // 不可用的不进目录（否则商店会把它列成可下载），但记进 UNAVAILABLE 让联动页能说出它缺什么
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

    /** 运行时登记并立即安装，供附属模组动态注册 */
    public static boolean install(IPhoneApp app) {
        if (!register(app)) return false;
        return install(app.getId());
    }

    /** 安装目录中已有的 App，真正改变了安装状态才 true */
    public static boolean install(ResourceLocation id) {
        ensureLoaded();
        if (id == null) return false;

        IPhoneApp app = CATALOG.get(id);
        if (app == null) {
            MCphone.LOGGER.warn("[MCphone] 安装失败: 目录中没有 App '{}'", id);
            return false;
        }
        if (!INSTALLED.add(id)) return false;

        saveState();
        MCphone.LOGGER.info("[MCphone] App 已安装: {}", id);
        return true;
    }

    /** 卸载。系统 App 不可卸载；只从已装集合移除，目录条目保留 */
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

    /** 已安装 App 的只读列表，顺序就是主屏排列；目录里查不到的 id 跳过，getApp(int)/getAppCount 同一条规则，下标才对得上 */
    public static List<IPhoneApp> getApps() {
        ensureLoaded();
        List<IPhoneApp> out = new ArrayList<>(INSTALLED.size());
        for (ResourceLocation id : INSTALLED) {
            IPhoneApp app = CATALOG.get(id);
            if (app != null) out.add(app);
        }
        // unmodifiableList 而不是 List.copyOf：省一次拷贝，主屏每帧都走这条路
        return Collections.unmodifiableList(out);
    }

    /** 把主屏第 from 格挪到第 to 格，是插入不是交换。下标按 getApps() 算；真的变了才 true */
    public static boolean moveApp(int from, int to) {
        ensureLoaded();

        List<ResourceLocation> order = new ArrayList<>(INSTALLED);
        // 与界面预览共用 HomeLayout.reorder，预览与落定才一致
        if (!HomeLayout.reorder(order, from, to)) return false;

        // LinkedHashSet 没有就地重排，清空再灌回去
        INSTALLED.clear();
        INSTALLED.addAll(order);

        saveState();
        return true;
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
     * 全部靠别的模组撑着的 App，可用的在前、不可用的在后。
     *
     * 两种声明都算：{@link #requiredModsOf} 与 {@link #companionModsOf}。只认前者
     * 是不行的——内建的 App 现在一个都不用前置了（MCphone 自己没有前置，缺了谁都
     * 照常开机），只认前置的话这份名单里就只剩"当前不可用"的那一批，模组都装齐的
     * 玩家进商店的「联动App」页会看到一张空页。
     */
    public static List<IPhoneApp> getCompanionApps() {
        ensureLoaded();
        List<IPhoneApp> out = new ArrayList<>();
        for (IPhoneApp app : CATALOG.values()) {
            if (!requiredModsOf(app).isEmpty() || !companionModsOf(app).isEmpty()) out.add(app);
        }
        out.addAll(UNAVAILABLE.values());   // 不可用的必定是靠谁撑着才不可用，不必再筛
        return List.copyOf(out);
    }

    /**
     * 这个 App 现在可不可用 —— 在目录里就是可用，{@link #register} 把不可用的挡在
     * 门外并记进 UNAVAILABLE，两边互斥。
     *
     * 兜 Throwable 的理由与 {@link #requiredModsOf} 一样：UNAVAILABLE 里的 App
     * 引用着没装的模组，读它任何一样东西都可能抛 NoClassDefFoundError。
     */
    public static boolean isRegistered(IPhoneApp app) {
        if (app == null) return false;
        ensureLoaded();
        try {
            return CATALOG.containsKey(app.getId());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 读一个 App 声明的前置，读不出来就当没有。必须兜 Throwable：UNAVAILABLE 里的 App 会抛 NoClassDefFoundError */
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

    /**
     * 读一个 App 声明的【联动】模组（软的：装了更好用，没装照样能用），读不出来就当没有。
     *
     * 与 {@link #requiredModsOf} 同样兜 Throwable，理由一样：UNAVAILABLE 里的 App
     * 引用着没装的模组，读它任何一样东西都可能抛 NoClassDefFoundError。
     */
    public static List<RequiredMod> companionModsOf(IPhoneApp app) {
        if (app == null) return List.of();
        try {
            List<RequiredMod> mods = app.companionMods();
            return mods == null ? List.of() : mods;
        } catch (Throwable t) {
            MCphone.LOGGER.warn("[MCphone] 读取 {} 的联动声明失败，当作没有声明",
                    app.getClass().getName(), t);
            return List.of();
        }
    }

    /** 按 id 查找目录中的 App（不论是否已安装） */
    public static IPhoneApp getApp(ResourceLocation id) {
        ensureLoaded();
        return CATALOG.get(id);
    }

    /** 按主屏下标查找已安装 App */
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

    /** 已安装 App 数量。只数不建表，主屏一帧要调多次；跳过规则与 getApps 一致 */
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

    /**
     * 扫描一次 App 目录，正常情况下一辈子一次。不在这里读安装状态：
     * 状态按存档分，由 {@link #loadForCurrentWorld()} 进世界时读。
     *
     * 【扫出空目录时不落锁，留一次重试的余地】
     *
     * 原来是进门就 loaded = true 再开扫。那样只要有一轮扫空了，整局就再也
     * 不会重扫 —— 玩家看到的是"新建世界开手机一片空白"，而且重进世界也
     * 治不好，只能重启游戏。空目录本身的成因已经在 SpiLoader 里治了
     * （类加载器），这里是第二道：万一还有别的路子让扫描落空，下次调用
     * 还能自己救回来。
     *
     * 本模组自带的 services 文件里始终有内建 App，所以"扫完还是空"必然是
     * 出了问题，重试是划算的。但仍然设了次数上限：这个方法在渲染路径上
     * 被调，真扫不出来时不能让它每帧都扫一遍。
     */
    private static final int MAX_SCAN_ATTEMPTS = 3;
    private static int scanAttempts = 0;

    private static void ensureLoaded() {
        if (loaded) return;

        scanAttempts++;

        // 走 SpiLoader 而不是直接 for-each ServiceLoader：一个附属构造失败不该中断整个扫描
        int count = 0;
        for (IPhoneApp app : SpiLoader.loadSafely(IPhoneApp.class, "App")) {
            // register 会调第三方的 getId()/isAvailable()，同样要兜
            try {
                if (register(app)) count++;
            } catch (Throwable t) {
                MCphone.LOGGER.error("[MCphone] App {} 登记时抛异常，已跳过",
                        app.getClass().getName(), t);
            }
        }

        // 扫出东西了才落锁；一个都没有就留给下次，除非已经试到上限
        if (!CATALOG.isEmpty() || scanAttempts >= MAX_SCAN_ATTEMPTS) {
            loaded = true;
        }

        if (CATALOG.isEmpty()) {
            MCphone.LOGGER.error("[MCphone] SPI 扫描一个 App 都没找到（第 {} 次）。"
                    + "本模组自带的内建 App 也没扫到，说明扫描本身出了问题；"
                    + "{}", scanAttempts,
                    loaded ? "已达重试上限，本局不再重试" : "下次调用会再试一次");
        } else {
            MCphone.LOGGER.info("[MCphone] SPI 扫描完成，目录中共 {} 个 App", count);
        }
    }

    /** installed.json 的结构 */
    private static final class State {
        List<String> installed;
        List<String> known;
    }

    /** 解析状态文件里的一条 id，解析不了返回 null。裸串（老文件）补成 mcphone: —— 不能用 ResourceLocation.parse，它补成 minecraft: 会作废老玩家的状态 */
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

    /** 解析一串 id 进目标集合，跳过解析不了的。installed 要用 LinkedHashSet 接，顺序就是主屏排列 */
    private static void parseStoredIds(List<String> raw, Collection<ResourceLocation> out) {
        if (raw == null) return;
        for (String s : raw) {
            ResourceLocation id = parseStoredId(s);
            if (id != null) out.add(id);
        }
    }

    /** 把没买过的付费 App 从主屏摘掉，收到服务端购买记录后调用。不摘的话它既用不了也不在商店里，买不回来 */
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

    /** 进世界时调用（客户端登录事件）：读当前存档自己那份状态 */
    public static void loadForCurrentWorld() {
        ensureLoaded();

        String key = currentWorldKey();
        stateFile = STATE_DIR.resolve(key + ".json");
        migrateLegacyIfNeeded();
        loadState();

        MCphone.LOGGER.info("[MCphone] 已加载存档 '{}' 的 App 安装状态", key);
    }

    /** 退出世界时调用，清空安装状态，否则下一个存档读进来之前会先显示上一个的主屏 */
    public static void unloadWorld() {
        INSTALLED.clear();
        stateFile = null;
    }

    /** 当前存档/服务器的标识：联机用服务器地址，单机用存档目录名（显示名可重名，不用） */
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

        // 取不到时用固定名字而不是抛异常
        return "unknown";
    }

    /** 只留文件名安全的字符 */
    private static String sanitize(String raw) {
        String s = raw.replaceAll("[^a-zA-Z0-9._-]", "_");
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    /** 一次性迁移：把旧的全局文件搬给第一个进入的存档，旧文件改名不删 */
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
        // 文件里记着装了的按文件顺序装回来，这决定主屏排列
        for (ResourceLocation id : installed) {
            if (CATALOG.containsKey(id)) INSTALLED.add(id);
        }

        // 目录里有而玩家没见过的按该不该预装决定，一律追加到末尾，不挤乱已摆好的位置
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

    /** 首次出现的 App 该不该直接躺在主屏上：付费的一律不预装（硬约束，不看意愿），免费的听 App 自己的 isPreinstalled() */
    private static boolean shouldPreinstall(ResourceLocation id, IPhoneApp app) {
        if (AppPriceRegistry.isPaid(id)) return false;
        return app.isPreinstalled();
    }

    private static void saveState() {
        // 没进世界时 INSTALLED 不代表任何存档，写下去只会污染上一个存档
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
