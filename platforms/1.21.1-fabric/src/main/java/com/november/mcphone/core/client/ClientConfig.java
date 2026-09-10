package com.november.mcphone.core.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.november.mcphone.MCphone;
import com.november.mcphone.feature.camera.client.CameraFlash;
import com.november.mcphone.feature.camera.client.CameraStamp;
import com.november.mcphone.feature.music.PlayMode;
import com.november.mcphone.feature.music.client.MusicController;
import com.november.mcphone.feature.music.client.playback.LocalPlayback;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户端配置 —— 只关乎这台机器上的人怎么看这部手机。
 *
 * 原 NeoForge 版用 ModConfigSpec（TOML，写在 config/ 下，由 NeoForge 管理）。
 * Fabric 没有那套东西，这里改成自管的一份 JSON：{@code config/mcphone-client.json}。
 * 键名与语义与原来一致。
 *
 * 为什么不让渲染直接来问这里
 *
 * 画一帧手机界面要问上百次颜色，而读文件每次都是 IO。所以值在加载时【推】
 * 给 {@link FontPalette}，渲染只读那一份静态字段，一次都不碰配置。
 *
 * 原来的「模组列表 → 配置」按钮（NeoForge 的 ConfigurationScreen）在 Fabric
 * 上不复存在：客户端配置改由游戏内的「设置 → 字体颜色 / App 管理器 / 音乐
 * 音量 / 界面大小 / 副手 HUD」等入口写回，路径与原来一致。
 */
public final class ClientConfig {

    private ClientConfig() {}

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("mcphone-client.json");
    }

    private static boolean loaded = false;
    private static FontPreset fontColor = FontPreset.WHITE;
    private static PlayMode musicMode = PlayMode.LIST_LOOP;
    private static int musicVolume = 100;
    private static List<String> appHotkeys = new ArrayList<>();
    private static boolean cameraSoftFlash = false;
    /** 照片右下角印拍摄坐标。默认开着——见上游 cameraCoordStamp 的说明 */
    private static boolean cameraCoordStamp = true;
    private static int uiScale = PhoneScale.DEFAULT_PERCENT;
    private static boolean uiScaleSnap = true;
    private static boolean hudEnabled = true;
    private static PhoneHudPlacement.Anchor hudAnchor = PhoneHudPlacement.DEFAULT_ANCHOR;
    private static int hudOffsetX = 0;
    private static int hudOffsetY = 0;
    private static int hudScale = PhoneHudPlacement.DEFAULT_PERCENT;

    /** 客户端启动时读一次；文件不存在就写默认值 */
    public static void load() {
        Path path = file();
        try {
            if (Files.isRegularFile(path)) {
                JsonObject obj = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
                fontColor = parsePreset(getString(obj, "fontColor", "white"));
                musicMode = parseMode(getString(obj, "musicMode", "LIST_LOOP"));
                musicVolume = Math.max(0, Math.min(100, getInt(obj, "musicVolume", 100)));
                appHotkeys = new ArrayList<>(getStrings(obj, "appHotkeys"));
                cameraSoftFlash = getBool(obj, "cameraSoftFlash", false);
                cameraCoordStamp = getBool(obj, "cameraCoordStamp", true);
                uiScale = PhoneScale.clamp(getInt(obj, "uiScale", PhoneScale.DEFAULT_PERCENT));
                uiScaleSnap = getBool(obj, "uiScaleSnap", true);
                hudEnabled = getBool(obj, "hudEnabled", true);
                hudAnchor = parseAnchor(getString(obj, "hudAnchor", PhoneHudPlacement.DEFAULT_ANCHOR.name()));
                hudOffsetX = PhoneHudPlacement.clampOffset(getInt(obj, "hudOffsetX", 0));
                hudOffsetY = PhoneHudPlacement.clampOffset(getInt(obj, "hudOffsetY", 0));
                hudScale = PhoneHudPlacement.clampPercent(getInt(obj, "hudScale", PhoneHudPlacement.DEFAULT_PERCENT));
            } else {
                Files.createDirectories(path.getParent());
                save();
            }
            loaded = true;
            apply();
        } catch (Throwable t) {
            MCphone.LOGGER.error("读取客户端配置失败，使用默认值", t);
            fontColor = FontPreset.WHITE;
            musicMode = PlayMode.LIST_LOOP;
            musicVolume = 100;
            appHotkeys = new ArrayList<>();
            cameraSoftFlash = false;
            cameraCoordStamp = true;
            uiScale = PhoneScale.DEFAULT_PERCENT;
            uiScaleSnap = true;
            hudEnabled = true;
            hudAnchor = PhoneHudPlacement.DEFAULT_ANCHOR;
            hudOffsetX = 0;
            hudOffsetY = 0;
            hudScale = PhoneHudPlacement.DEFAULT_PERCENT;
            loaded = true;
            apply();
        }
    }

    private static void apply() {
        // 配置 → FontPalette
        FontPalette.set(fontColor);

        // 音乐的两项也在这里落地：配置读进来之后，播放器才知道上次
        // 玩家把音量拧到了哪儿、用的是哪种循环
        MusicController.setMode(musicMode);
        LocalPlayback.setVolume(musicVolume / 100.0F);

        // 快捷键同理：按下时要在一帧之内答出"这个键是哪个 App"，不能去问配置
        AppHotkeys.load(appHotkeys);

        // 快门闪光也一样：闪光那 220 毫秒里每帧都要问一次用哪种
        CameraFlash.setSoft(cameraSoftFlash);

        // 坐标水印更是每一帧都要问：相机模式下它一直画着（取景看到的就是照片上的样子）
        CameraStamp.setEnabled(cameraCoordStamp);

        // 界面倍数更甚：每一帧、每一次鼠标换算都要用
        PhoneScale.load(uiScale);
        PhoneScale.loadSnap(uiScaleSnap);

        // HUD 同理：它每帧都要问位置与倍数，那是渲染路径上最热的地方
        PhoneHudPlacement.load(hudEnabled, hudAnchor, hudOffsetX, hudOffsetY, hudScale);
    }

    private static void save() {
        JsonObject obj = new JsonObject();
        obj.addProperty("fontColor", fontColor.id());
        obj.addProperty("musicMode", musicMode.name());
        obj.addProperty("musicVolume", musicVolume);
        JsonArray arr = new JsonArray();
        for (String s : appHotkeys) arr.add(s);
        obj.add("appHotkeys", arr);
        obj.addProperty("cameraSoftFlash", cameraSoftFlash);
        obj.addProperty("cameraCoordStamp", cameraCoordStamp);
        obj.addProperty("uiScale", uiScale);
        obj.addProperty("uiScaleSnap", uiScaleSnap);
        obj.addProperty("hudEnabled", hudEnabled);
        obj.addProperty("hudAnchor", hudAnchor.name());
        obj.addProperty("hudOffsetX", hudOffsetX);
        obj.addProperty("hudOffsetY", hudOffsetY);
        obj.addProperty("hudScale", hudScale);
        try {
            Files.writeString(file(), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(obj),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            MCphone.LOGGER.error("写客户端配置文件失败", e);
        }
    }

    private static String getString(JsonObject obj, String key, String def) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : def;
    }

    private static int getInt(JsonObject obj, String key, int def) {
        return obj.has(key) ? obj.get(key).getAsInt() : def;
    }

    private static boolean getBool(JsonObject obj, String key, boolean def) {
        return obj.has(key) ? obj.get(key).getAsBoolean() : def;
    }

    private static List<String> getStrings(JsonObject obj, String key) {
        List<String> out = new ArrayList<>();
        if (obj.has(key) && obj.get(key).isJsonArray()) {
            for (var el : obj.getAsJsonArray(key)) {
                if (el.isJsonPrimitive()) out.add(el.getAsString());
            }
        }
        return out;
    }

    private static FontPreset parsePreset(String id) {
        for (FontPreset p : FontPreset.values()) {
            if (p.id().equalsIgnoreCase(id)) return p;
        }
        return FontPreset.WHITE;
    }

    private static PlayMode parseMode(String name) {
        try {
            return PlayMode.valueOf(name);
        } catch (IllegalArgumentException e) {
            return PlayMode.LIST_LOOP;
        }
    }

    private static PhoneHudPlacement.Anchor parseAnchor(String name) {
        try {
            return PhoneHudPlacement.Anchor.valueOf(name);
        } catch (IllegalArgumentException e) {
            return PhoneHudPlacement.DEFAULT_ANCHOR;
        }
    }

    //  手机界面 → 配置

    /**
     * 玩家在手机的「设置 → 字体颜色」里选了一个。
     *
     * 三件事的顺序是有讲究的：先让界面立刻变（玩家松开鼠标就该看见结果），
     * 再写值，最后存盘。
     */
    public static void selectFontColor(FontPreset preset) {
        FontPalette.set(preset);
        if (!loaded) {
            MCphone.LOGGER.warn("字体颜色改成了 {}，但配置尚未加载，这次不落盘", preset.id());
            return;
        }
        fontColor = preset;
        save();
    }

    /** 玩家在音乐 App 里切了循环模式。 */
    public static void saveMusicMode(PlayMode mode) {
        if (!loaded) return;
        musicMode = mode;
        save();
    }

    /** 快捷键表变了 —— 玩家在 App 管理器里绑了一个键，或者清掉了一个。 */
    public static void saveAppHotkeys(List<String> entries) {
        if (!loaded) return;
        appHotkeys = new ArrayList<>(entries);
        save();
    }

    /** 玩家在 App 管理器的相机那一页上换了快门闪光。 */
    public static void saveCameraSoftFlash(boolean soft) {
        if (!loaded) return;
        cameraSoftFlash = soft;
        save();
    }

    /**
     * 玩家在相机那一页上开关了坐标水印。与上面几项同一套路数：{@link CameraStamp}
     * 那边已经用上新值了（下一帧就变），这里只负责落盘。
     */
    public static void saveCameraCoordStamp(boolean value) {
        cameraCoordStamp = value;
        CameraStamp.setEnabled(value);
        save();
    }

    /**
     * 玩家在「设置 → 界面大小」里改了倍数。
     *
     * 与上面几项同一套路数：{@link PhoneScale} 那边已经用上新值了（下一帧就变），
     * 这里只负责落盘。
     */
    public static void saveUiScale(int percent) {
        if (!loaded) return;
        uiScale = PhoneScale.clamp(percent);
        save();
    }

    /** 「贴合清晰倍数」那个开关翻了面 */
    public static void saveUiScaleSnap(boolean value) {
        if (!loaded) return;
        uiScaleSnap = value;
        save();
    }

    /**
     * 副手 HUD 的开关。与上面几项同一套路数：{@link PhoneHudPlacement} 那边
     * 已经用上新值了（下一帧就变），这里只负责落盘。
     */
    public static void saveHudEnabled(boolean value) {
        if (!loaded) return;
        hudEnabled = value;
        save();
    }

    /**
     * 位置分成一个方法而不是三个：锚点与两个偏移是【一起】算出来的一份结果
     * （见 {@link PhoneHudPlacement#derive}），分三次写会在中途存出一个
     * 「新锚点配旧偏移」的组合——那一瞬间落盘失败的话，手机就摆到了谁也没要的地方。
     */
    public static void saveHudPlacement(PhoneHudPlacement.Anchor anchor, int offsetX, int offsetY) {
        if (!loaded) return;
        hudAnchor = anchor;
        hudOffsetX = PhoneHudPlacement.clampOffset(offsetX);
        hudOffsetY = PhoneHudPlacement.clampOffset(offsetY);
        save();
    }

    public static void saveHudScale(int percent) {
        if (!loaded) return;
        hudScale = PhoneHudPlacement.clampPercent(percent);
        save();
    }

    /** 玩家在音乐 App 里调了音量。存 0-100 的整数，配置文件里可读性好。 */
    public static void saveMusicVolume(float volume) {
        if (!loaded) return;
        musicVolume = Math.round(Math.max(0.0F, Math.min(1.0F, volume)) * 100);
        save();
    }
}
