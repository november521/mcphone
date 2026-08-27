package com.november.mcphone.core.client;

import net.minecraft.util.Mth;
import com.november.mcphone.MCphone;
import com.november.mcphone.feature.music.PlayMode;
import com.november.mcphone.feature.music.client.MusicController;
import com.november.mcphone.feature.music.client.playback.LocalPlayback;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.common.ForgeConfigSpec;

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

    public static final ForgeConfigSpec SPEC;

    /** 手机界面里画在壁纸上的字用哪套配色 */
    public static final ForgeConfigSpec.EnumValue<FontPreset> FONT_COLOR;

    /** 音乐 App 的循环模式。记在配置里，重进游戏还是上次那个 */
    public static final ForgeConfigSpec.EnumValue<PlayMode> MUSIC_MODE;

    /** 音乐 App 自己的音量（0-100）。最终输出还要乘游戏的主音量与唱片音量 */
    public static final ForgeConfigSpec.IntValue MUSIC_VOLUME;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

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
