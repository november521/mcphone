package com.november.mcphone.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.november.mcphone.MCphone;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 服主的开关 —— 这台服务器允许手机做什么。
 *
 * 为什么必须是服务端配置，不能是客户端配置
 *
 * 客户端配置在每个玩家自己电脑上，他想改就改。用它来管"能不能传送"，
 * 等于把规则交给被管的人——改一行配置就绕过去了。
 *
 * 服务端配置由服主一份说了算。Fabric 不像 NeoForge 那样自动把服务端配置
 * 同步给客户端，所以这里补了一个同步包：玩家连上来时服务端把它自己的
 * 那份值推过来（{@link com.november.mcphone.core.net.SyncServerConfigPacket}），
 * 界面据此提前藏按钮；真正的拦截仍在服务端——界面只是不给入口，伪造客户端
 * 照样发得出包，那一层不能省。
 *
 * 读它必须容忍"还没加载"
 *
 * 主菜单里、以及连上服务器之前，这份配置根本没有值。所以一律走下面那几个
 * 包装方法，拿不到值时返回默认——默认是开着的，与"不配置就保持原样"一致。
 *
 * 配置文件位置：<世界目录>/serverconfig/mcphone-server.json
 * （单人游戏在存档目录下，每个存档一份；专用服务器在 serverconfig/ 下）。
 * 原 NeoForge 版是 TOML（ModConfigSpec），Fabric 版改成 JSON，键名不变。
 */
public final class ServerConfig {

    private ServerConfig() {}

    private static final int DEFAULT_IMAGE_MAX_KB = 512;
    private static final int IMAGE_MIN_KB = 64;
    private static final int IMAGE_MAX_KB = 768;

    //  服务端本地那份（从文件读的）
    private static boolean loaded = false;
    private static boolean allowFriendTeleport = true;
    private static boolean allowChatImages = true;
    private static int chatImageMaxKb = DEFAULT_IMAGE_MAX_KB;
    private static boolean terminalKeepPowered = true;

    //  客户端那份（从同步包收的；收到之后优先于本地）
    private static boolean synced = false;
    private static boolean syncedAllowFriendTeleport = true;
    private static boolean syncedAllowChatImages = true;
    private static int syncedChatImageMaxKb = DEFAULT_IMAGE_MAX_KB;

    /**
     * 允不允许好友传送。
     *
     * 配置没加载时返回 true：那只发生在主菜单或连上服务器之前，而那时
     * 谁也传送不了。返回 false 反而会让界面在刚进世界的一瞬间闪一下
     * ——图标先没有、配置到了又冒出来。
     */
    public static boolean allowFriendTeleport() {
        if (synced) return syncedAllowFriendTeleport;
        return !loaded || allowFriendTeleport;
    }

    /** 允不允许在美西螈里发图片。没加载时返回 true，理由同 {@link #allowFriendTeleport()} */
    public static boolean allowChatImages() {
        if (synced) return syncedAllowChatImages;
        return !loaded || allowChatImages;
    }

    /**
     * 一张图的字节上限。客户端压到这个数以内，服务端也按它收。
     *
     * 没加载时返回默认值：那只发生在主菜单，那时也没人在发图。真正进了世界
     * 之后，客户端拿到的是【服主那一份】——同步包连上来时就推过来了。
     */
    public static int chatImageMaxBytes() {
        int kb = synced ? syncedChatImageMaxKb : (loaded ? chatImageMaxKb : DEFAULT_IMAGE_MAX_KB);
        return kb * 1024;
    }

    /**
     * 手机替卡槽里的终端供电吗。没加载时返回 true，理由同 {@link #allowFriendTeleport()}
     * ——那只发生在还没进世界的时候，那时卡槽里的东西也不会被 tick 到。
     *
     * 只在服务端读（TerminalCharger 在服务端 tick），不进 SyncServerConfigPacket。
     */
    public static boolean terminalKeepPowered() {
        return !loaded || terminalKeepPowered;
    }

    /** 客户端收到同步包后写入 */
    public static void applySync(boolean allowFriendTeleport, boolean allowChatImages, int chatImageMaxKb) {
        synced = true;
        syncedAllowFriendTeleport = allowFriendTeleport;
        syncedAllowChatImages = allowChatImages;
        syncedChatImageMaxKb = clampKb(chatImageMaxKb);
    }

    /** 断线/退出世界时清掉同步态（不能带着上一个服务器的配置） */
    public static void clearSync() {
        synced = false;
    }

    /**
     * 服务器启动时读一次。文件不存在就写默认值；读坏了用默认值并且不崩服——
     * 一个坏配置文件不该让整个服务器起不来。
     */
    public static void load(MinecraftServer server) {
        Path file = server.getWorldPath(LevelResource.ROOT).resolve("serverconfig/mcphone-server.json");
        try {
            if (Files.isRegularFile(file)) {
                JsonObject obj = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                allowFriendTeleport = getBool(obj, "allowFriendTeleport", true);
                allowChatImages = getBool(obj, "allowChatImages", true);
                chatImageMaxKb = clampKb(getInt(obj, "chatImageMaxKb", DEFAULT_IMAGE_MAX_KB));
                terminalKeepPowered = getBool(obj, "terminalKeepPowered", true);
            } else {
                Files.createDirectories(file.getParent());
                save(file);
            }
            loaded = true;
            MCphone.LOGGER.info("服务端配置已加载：{}（允许好友传送={}, 允许发图={}, 图片上限={}KB, 终端由手机供电={}）",
                    file, allowFriendTeleport, allowChatImages, chatImageMaxKb, terminalKeepPowered);
        } catch (Throwable t) {
            MCphone.LOGGER.error("读取服务端配置失败，使用默认值", t);
            allowFriendTeleport = true;
            allowChatImages = true;
            chatImageMaxKb = DEFAULT_IMAGE_MAX_KB;
            terminalKeepPowered = true;
            loaded = true;
        }
    }

    private static void save(Path file) {
        JsonObject obj = new JsonObject();
        obj.addProperty("allowFriendTeleport", allowFriendTeleport);
        obj.addProperty("allowChatImages", allowChatImages);
        obj.addProperty("chatImageMaxKb", chatImageMaxKb);
        obj.addProperty("terminalKeepPowered", terminalKeepPowered);
        try {
            Files.writeString(file, new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(obj),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            MCphone.LOGGER.error("写服务端配置文件失败：{}", file, e);
        }
    }

    private static boolean getBool(JsonObject obj, String key, boolean def) {
        return obj.has(key) ? obj.get(key).getAsBoolean() : def;
    }

    private static int getInt(JsonObject obj, String key, int def) {
        return obj.has(key) ? obj.get(key).getAsInt() : def;
    }

    private static int clampKb(int kb) {
        return Math.max(IMAGE_MIN_KB, Math.min(IMAGE_MAX_KB, kb));
    }
}
