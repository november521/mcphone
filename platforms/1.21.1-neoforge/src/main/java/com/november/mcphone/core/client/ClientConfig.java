package com.november.mcphone.core.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.feature.camera.client.CameraFlash;
import com.november.mcphone.feature.music.PlayMode;
import com.november.mcphone.feature.music.client.MusicController;
import com.november.mcphone.feature.music.client.playback.LocalPlayback;
import net.minecraft.util.Mth;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * 客户端配置 —— 只关乎这台机器上的人怎么看这部手机。
 *
 * 为什么是 CLIENT 而不是 COMMON/SERVER
 *
 * 字体颜色是【显示偏好】，和资源包一个性质：你觉得白字刺眼就换成黑的，
 * 这件事跟服务器、跟同服的其他玩家没有半点关系，不该同步、也不该被服主
 * 统一规定。写进 CLIENT 配置意味着它存在玩家自己的 config/ 目录里，
 * 换服务器不会变，也不会有任何一个字节上网。
 *
 * 存档相关的东西（壁纸、设备名、装了哪些 App）走的是另一条路——那些在
 * 服务端，因为它们属于"这个角色"而不是"这台电脑"。
 *
 * 为什么不让渲染直接来问这里
 *
 * ConfigValue.get() 在配置加载完成之前调用会抛 IllegalStateException，
 * 而画一帧手机界面要问上百次颜色。所以值在加载与重载时【推】给
 * {@link FontPalette}，渲染只读那一份静态字段，一次都不碰配置。
 *
 * 模组列表里那个「配置」按钮此前点进去是空的：MCphoneClient 早就注册了
 * IConfigScreenFactory，但没有任何一份配置注册给它，于是它开出一个空壳。
 * 从这里开始它有内容了。
 */
public final class ClientConfig {

    private ClientConfig() {}

    public static final ModConfigSpec SPEC;

    /** 手机界面里画在壁纸上的字用哪套配色 */
    public static final ModConfigSpec.EnumValue<FontPreset> FONT_COLOR;

    /** 音乐 App 的循环模式。记在配置里，重进游戏还是上次那个 */
    public static final ModConfigSpec.EnumValue<PlayMode> MUSIC_MODE;

    /** 音乐 App 自己的音量（0-100）。最终输出还要乘游戏的主音量与唱片音量 */
    public static final ModConfigSpec.IntValue MUSIC_VOLUME;

    /** 每个 App 的快捷键，一条写成 {@code <appId>=<键名>}。解析见 {@link AppHotkeys} */
    public static final ModConfigSpec.ConfigValue<List<? extends String>> APP_HOTKEYS;

    /** 拍照那一下用模糊代替满屏白闪 */
    public static final ModConfigSpec.BooleanValue CAMERA_SOFT_FLASH;

    /** 手机界面开多大，整数百分比。解析与夹取见 {@link PhoneScale} */
    public static final ModConfigSpec.IntValue UI_SCALE;

    /** 界面大小只在"清晰的倍数"上取值。为什么这样更清楚见 {@link PhoneScale#snapPercent} */
    public static final ModConfigSpec.BooleanValue UI_SCALE_SNAP;

    /** 手机放进副手时自动挂到 HUD 上。整套的来龙去脉见 {@link com.november.mcphone.core.client.PhoneHud} */
    public static final ModConfigSpec.BooleanValue HUD_ENABLED;

    /** HUD 上那部手机贴哪个角。九个锚点，解算见 {@link PhoneHudPlacement} */
    public static final ModConfigSpec.EnumValue<PhoneHudPlacement.Anchor> HUD_ANCHOR;

    /** 从锚点再往里挪多少像素，横 */
    public static final ModConfigSpec.IntValue HUD_OFFSET_X;

    /** 从锚点再往里挪多少像素，竖 */
    public static final ModConfigSpec.IntValue HUD_OFFSET_Y;

    /** HUD 上那部手机开多大，整数百分比。与 UI_SCALE 分开的理由见 {@link PhoneHudPlacement} */
    public static final ModConfigSpec.IntValue HUD_SCALE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        FONT_COLOR = builder
                .comment("手机界面的字体颜色。",
                        "WHITE 是默认，与不改这一项时完全一致；",
                        "BLACK 配浅色壁纸用——壁纸浅、字也浅的话就看不清了。",
                        "Font color of the phone UI. WHITE is the default; pick BLACK for light wallpapers.")
                .translation("mcphone.config.font_color")
                .defineEnum("fontColor", FontPreset.WHITE);

        MUSIC_MODE = builder
                .comment("音乐 App 的循环模式。",
                        "Loop mode of the Music app.")
                .translation("mcphone.config.music_mode")
                .defineEnum("musicMode", PlayMode.LIST_LOOP);

        MUSIC_VOLUME = builder
                .comment("音乐 App 的音量，0-100。",
                        "这是这台手机自己的音量，最终输出还会乘游戏的主音量与唱片音量。",
                        "Music app volume (0-100), multiplied by the game's master and record sliders.")
                .translation("mcphone.config.music_volume")
                .defineInRange("musicVolume", 100, 0, 100);

        APP_HOTKEYS = builder
                .comment("每个 App 的快捷键，一条一个 App，写成 <App id>=<键>。",
                        "键名用原版那套写法（options.txt 里也是这个），例如 key.keyboard.k；",
                        "组合键把修饰键写在前面并用加号连起来，可以叠：",
                        "  mcphone:chat=CONTROL+key.keyboard.k",
                        "  mcphone:notes=CONTROL+SHIFT+key.keyboard.n",
                        "修饰键只认 CONTROL / SHIFT / ALT 三个（Mac 上 CONTROL 就是 Command）。",
                        "正常不用手改这里：设置 → App 管理器 → 点开某个 App → 快捷键。",
                        "Per-app hotkeys, one entry per app, written as <app id>=<key>.",
                        "Vanilla key names (e.g. key.keyboard.k); prefix CONTROL/SHIFT/ALT with",
                        "'+' for combos. Normally set in-game via Settings -> App Manager.")
                .translation("mcphone.config.app_hotkeys")
                // 元素校验只看"是不是字符串"：认不出来的条目由 AppHotkeys.load 逐条丢掉
                // 并留日志。在这里较真的话，一条手改坏了的快捷键会让 NightConfig 把整个
                // 列表退回默认值——玩家丢的就不是那一条，而是全部
                .defineListAllowEmpty("appHotkeys", List.of(),
                        () -> "mcphone:chat=key.keyboard.k",
                        o -> o instanceof String);

        CAMERA_SOFT_FLASH = builder
                .comment("拍照时用『模糊一下』代替满屏白闪。",
                        "false 是默认，与不改这一项时完全一致；",
                        "夜里或在暗处拍照时白闪会把屏幕顶到全白，介意的话开这个。",
                        "在游戏里改：设置 → App 管理器 → 相机 → 快门闪光。",
                        "Replace the white shutter flash with a short blur.",
                        "In-game: Settings -> App Manager -> Camera -> Shutter flash.")
                .translation("mcphone.config.camera_soft_flash")
                .define("cameraSoftFlash", false);

        UI_SCALE = builder
                .comment("手机界面开多大，百分比。100 是原样，150 就是放大一半。",
                        "GUI 缩放是全局的，调大它聊天框和物品栏会跟着变大；这一项只管手机。",
                        "窗口放不下时会自动夹回去，配置里的数不变。",
                        "在游戏里改：设置 → 界面大小。",
                        "Phone UI size in percent (100 = unscaled). Unlike the vanilla GUI scale,",
                        "this only affects the phone. In-game: Settings -> UI size.")
                .translation("mcphone.config.ui_scale")
                .defineInRange("uiScale", PhoneScale.DEFAULT_PERCENT,
                        PhoneScale.MIN_PERCENT, PhoneScale.MAX_PERCENT);

        UI_SCALE_SNAP = builder
                .comment("界面大小只在『清晰的倍数』上取值。",
                        "字是位图，GUI 缩放 × 界面大小 是整数时每个像素才方方正正；",
                        "开着的时候档位对齐到 1/GUI缩放（缩放 2 → 100/150/200…）。",
                        "关掉就能填任意数，代价是非整数倍下细线时粗时细。",
                        "Snap the phone UI size to values that keep text pixel-perfect",
                        "(guiScale x uiScale must be a whole number).")
                .translation("mcphone.config.ui_scale_snap")
                .define("uiScaleSnap", true);

        HUD_ENABLED = builder
                .comment("手机放进副手时，自动把它挂到 HUD 上（一直亮着，停在哪一页就显示哪一页）。",
                        "这一项只管【自动】那条。关掉之后，『唤出/收起手机 HUD』那个键（默认 G）照样叫得出来",
                        "——手机挂在 Curios 饰品栏里时副手是空的，只能靠那个键。",
                        "按一下『操作手机 HUD』那个键（默认左 Alt）唤出鼠标直接点它，再按一下收起。",
                        "位置与大小在游戏里改：设置 → 副手 HUD。",
                        "Show the phone as a HUD overlay while it sits in your off hand.",
                        "This only governs the automatic rule; the 'Toggle Phone HUD' key (default G)",
                        "still summons it from your bag or a Curios slot.",
                        "Tap the 'Use phone HUD' key (default Left Alt) for a cursor; tap again to dismiss.",
                        "In-game: Settings -> Off-hand HUD.")
                .translation("mcphone.config.hud_enabled")
                .define("hudEnabled", true);

        HUD_ANCHOR = builder
                .comment("HUD 上那部手机贴哪个角。换分辨率、改 GUI 缩放之后靠它留在原处。",
                        "在游戏里改：唤出鼠标后直接拖手机的边框，或者 设置 → 副手 HUD → 位置。",
                        "Which corner the HUD phone sticks to; keeps it in place across resolutions.",
                        "In-game: drag the phone's edge with the cursor up, or Settings -> Off-hand HUD.")
                .translation("mcphone.config.hud_anchor")
                .defineEnum("hudAnchor", PhoneHudPlacement.DEFAULT_ANCHOR);

        HUD_OFFSET_X = builder
                .comment("从锚点再往里挪多少像素（横）。贴着角落多半会压到物品栏，挪几像素才好看。",
                        "Pixel offset from the anchor, horizontal.")
                .translation("mcphone.config.hud_offset_x")
                .defineInRange("hudOffsetX", 0,
                        -PhoneHudPlacement.MAX_OFFSET, PhoneHudPlacement.MAX_OFFSET);

        HUD_OFFSET_Y = builder
                .comment("从锚点再往里挪多少像素（竖）。",
                        "Pixel offset from the anchor, vertical.")
                .translation("mcphone.config.hud_offset_y")
                .defineInRange("hudOffsetY", 0,
                        -PhoneHudPlacement.MAX_OFFSET, PhoneHudPlacement.MAX_OFFSET);

        HUD_SCALE = builder
                .comment("HUD 上那部手机开多大，百分比。与『手机界面大小』是两个数：",
                        "那一档管全屏打开时多大，这一档管挂在角落里多大，后者必然小得多。",
                        "在游戏里改：唤出鼠标后 Ctrl+滚轮，或者 设置 → 副手 HUD → 大小。",
                        "Size of the HUD phone in percent. Separate from the full-screen UI size.",
                        "In-game: Ctrl+scroll with the cursor up, or Settings -> Off-hand HUD.")
                .translation("mcphone.config.hud_scale")
                .defineInRange("hudScale", PhoneHudPlacement.DEFAULT_PERCENT,
                        PhoneHudPlacement.MIN_PERCENT, PhoneHudPlacement.MAX_PERCENT);

        SPEC = builder.build();
    }

    //  配置 → FontPalette

    /** 配置文件首次读进来 */
    public static void onLoad(ModConfigEvent.Loading event) {
        apply(event);
    }

    /**
     * 配置被改了 —— 玩家在模组列表的配置界面里改的，或者我们自己
     * {@link #selectFontColor} 存盘触发的，两条路都走这里。
     */
    public static void onReload(ModConfigEvent.Reloading event) {
        apply(event);
    }

    private static void apply(ModConfigEvent event) {
        // 本模组可能不止一份配置（日后加 COMMON/SERVER），事件对每一份都发。
        // 不认一下就会拿别份配置的加载去读这份，那时 FONT_COLOR 还没值
        if (event.getConfig().getSpec() != SPEC) return;

        FontPalette.set(FONT_COLOR.get());

        // 音乐的两项也在这里落地：配置读进来之后，播放器才知道上次
        // 玩家把音量拧到了哪儿、用的是哪种循环
        MusicController.setMode(MUSIC_MODE.get());
        LocalPlayback.setVolume(MUSIC_VOLUME.get() / 100.0F);

        // 快捷键同理：按下时要在一帧之内答出"这个键是哪个 App"，不能去问配置
        AppHotkeys.load(APP_HOTKEYS.get());

        // 快门闪光也一样：闪光那 220 毫秒里每帧都要问一次用哪种
        CameraFlash.setSoft(CAMERA_SOFT_FLASH.get());

        // 界面倍数更甚：每一帧、每一次鼠标换算都要用
        PhoneScale.load(UI_SCALE.get());
        PhoneScale.loadSnap(UI_SCALE_SNAP.get());

        // HUD 同理：它每帧都要问位置与倍数，那是渲染路径上最热的地方
        PhoneHudPlacement.load(HUD_ENABLED.get(), HUD_ANCHOR.get(),
                HUD_OFFSET_X.get(), HUD_OFFSET_Y.get(), HUD_SCALE.get());
    }

    //  手机界面 → 配置

    /**
     * 玩家在手机的「设置 → 字体颜色」里选了一个。
     *
     * 三件事的顺序是有讲究的：先让界面立刻变（玩家松开鼠标就该看见结果），
     * 再写值，最后存盘。存盘会触发 Reloading，绕回 {@link #apply} 再设一次
     * 同样的值——重复但无害，而且省掉了"界面和配置各存一份、迟早对不上"的
     * 那类问题。
     */
    public static void selectFontColor(FontPreset preset) {
        FontPalette.set(preset);

        // 配置没加载完就点得到设置页是不可能的，但真出了那种事，
        // 宁可颜色只在本次游戏里生效，也不要在这儿把界面崩掉
        if (!SPEC.isLoaded()) {
            MCphone.LOGGER.warn("字体颜色改成了 {}，但配置尚未加载，这次不落盘", preset.id());
            return;
        }

        FONT_COLOR.set(preset);
        SPEC.save();
    }

    /**
     * 玩家在音乐 App 里切了循环模式。
     *
     * 与字体颜色同一套路数：先让播放器立刻用上，再写值存盘。存盘会绕回
     * apply 再设一次同样的值，重复但无害。
     */
    public static void saveMusicMode(PlayMode mode) {
        if (!SPEC.isLoaded()) return;
        MUSIC_MODE.set(mode);
        SPEC.save();
    }

    /**
     * 快捷键表变了 —— 玩家在 App 管理器里绑了一个键，或者清掉了一个。
     *
     * 与上面几项同一套路数：{@link AppHotkeys} 那张表已经改好了，这里只负责
     * 落盘。存盘会触发 Reloading 绕回 {@link #apply}，把刚写下去的同一份再
     * load 一遍——重复但无害，而且省掉了"界面一份、配置一份，迟早对不上"。
     */
    public static void saveAppHotkeys(List<String> entries) {
        if (!SPEC.isLoaded()) return;
        APP_HOTKEYS.set(entries);
        SPEC.save();
    }

    /**
     * 玩家在 App 管理器的相机那一页上换了快门闪光。
     *
     * 与上面几项同一套路数：{@link CameraFlash} 那边已经用上新值了，这里只负责落盘。
     */
    public static void saveCameraSoftFlash(boolean soft) {
        if (!SPEC.isLoaded()) return;
        CAMERA_SOFT_FLASH.set(soft);
        SPEC.save();
    }

    /**
     * 玩家在「设置 → 界面大小」里改了倍数。
     *
     * 与上面几项同一套路数：{@link PhoneScale} 那边已经用上新值了（下一帧就变），
     * 这里只负责落盘。
     */
    public static void saveUiScale(int percent) {
        if (!SPEC.isLoaded()) return;
        UI_SCALE.set(PhoneScale.clamp(percent));
        SPEC.save();
    }

    /** 「贴合清晰倍数」那个开关翻了面 */
    public static void saveUiScaleSnap(boolean value) {
        if (!SPEC.isLoaded()) return;
        UI_SCALE_SNAP.set(value);
        SPEC.save();
    }

    /**
     * 副手 HUD 的三组值。与上面几项同一套路数：{@link PhoneHudPlacement} 那边
     * 已经用上新值了（下一帧就变），这里只负责落盘。
     *
     * 位置分成一个方法而不是三个：锚点与两个偏移是【一起】算出来的一份结果
     * （见 {@link PhoneHudPlacement#derive}），分三次写会在中途存出一个
     * 「新锚点配旧偏移」的组合——那一瞬间落盘失败的话，手机就摆到了谁也没要的地方。
     */
    public static void saveHudEnabled(boolean value) {
        if (!SPEC.isLoaded()) return;
        HUD_ENABLED.set(value);
        SPEC.save();
    }

    public static void saveHudPlacement(PhoneHudPlacement.Anchor anchor, int offsetX, int offsetY) {
        if (!SPEC.isLoaded()) return;
        HUD_ANCHOR.set(anchor);
        HUD_OFFSET_X.set(PhoneHudPlacement.clampOffset(offsetX));
        HUD_OFFSET_Y.set(PhoneHudPlacement.clampOffset(offsetY));
        SPEC.save();
    }

    public static void saveHudScale(int percent) {
        if (!SPEC.isLoaded()) return;
        HUD_SCALE.set(PhoneHudPlacement.clampPercent(percent));
        SPEC.save();
    }

    /**
     * 玩家在音乐 App 里调了音量。
     *
     * 存的是 0-100 的整数而不是浮点：配置文件里 "musicVolume = 80" 比
     * "0.8000000119" 好读，玩家手改配置的时候尤其。
     */
    public static void saveMusicVolume(float volume) {
        if (!SPEC.isLoaded()) return;
        MUSIC_VOLUME.set(Math.round(Mth.clamp(volume, 0.0F, 1.0F) * 100));
        SPEC.save();
    }
}
