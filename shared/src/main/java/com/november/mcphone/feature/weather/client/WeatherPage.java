package com.november.mcphone.feature.weather.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.feature.clock.WorldClock;
import com.november.mcphone.feature.weather.Weather;
import com.november.mcphone.core.client.GuiUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.biome.Biome;

import java.util.List;

/**
 * 天气那一页：一个大字说是什么天，下面一段说这种天适合干什么。
 *
 * 三个字段拼出一种天
 *
 * Minecraft 的天气是全世界一个开关，但落下来的东西按当地生物群系算，
 * 而下界末地压根没有天气。所以要问三处：
 *
 *   level.dimensionType().hasSkyLight()   这个维度有没有天
 *   level.isRaining() / isThundering()    世界的两个开关
 *   biome.getPrecipitationAt(pos)         脚下这里落什么
 *
 * 怎么把这三个字段拼成一种天，见 {@link Weather#classify}——那里有断言。
 * 这里只负责把值取出来递过去。
 */
public final class WeatherPage {

    private WeatherPage() {}

    private static final int PAD = 6;

    /** 天气名放大到这个倍数 */
    private static final float BIG_SCALE = 2.0f;

    /**
     * 天气图标画多大。贴图本身是 32×32，这里按原尺寸画。
     *
     * 手机内宽 120，32 占四分之一强，是这一页当之无愧的主角，
     * 又不至于把下面那段建议挤没。
     */
    private static final int ICON_SIZE = 32;

    public static void render(GuiGraphics g, int phoneLeft, int phoneTop,
                              int screenW, int screenH, int statusH, int navH, Font font) {

        final int x = phoneLeft + PAD;
        final int w = screenW - PAD * 2;
        int y = phoneTop + statusH + 4;

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;

        // ---- 标题 ----
        g.drawString(font, Component.translatable("mcphone.app.weather").getString(),
                x, y, FontPalette.title(), true);
        y += font.lineHeight + 4;
        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 10;

        if (level == null || player == null) {
            // 理论上进不来：没有世界就没有手机界面。写着是为了不在断线的
            // 那一帧抛空指针
            g.drawString(font, Component.translatable("mcphone.clock.no_world").getString(),
                    x, y, FontPalette.subtle(), false);
            return;
        }

        Weather.Kind kind = currentKind(level, player);
        boolean night = WorldClock.isNight(level.getDayTime());

        // ---- 天气图标，居中 ----
        int iconX = phoneLeft + (screenW - ICON_SIZE) / 2;
        GuiUtil.drawTexture(g, ResourceLocation.fromNamespaceAndPath(
                        MCphone.MODID, "textures/" + kind.iconPath() + ".png"),
                iconX, y, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
        y += ICON_SIZE + 4;

        // ---- 天气名，大字居中 ----
        drawBigCentered(g, font, Component.translatable(kind.nameKey()).getString(),
                phoneLeft, screenW, y);
        y += (int) (font.lineHeight * BIG_SCALE) + 8;

        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 6;

        // ---- 适合做什么 ----
        g.drawString(font, Component.translatable("mcphone.weather.advice_title").getString(),
                x, y, FontPalette.subtle(), false);
        y += font.lineHeight + 3;

        // 走 font.split 而不是自己按字数断行：中文一字一格、英文按词断，
        // 两套规则自己写必错一套。split 认得出这个区别
        List<FormattedCharSequence> lines =
                font.split(Component.translatable(kind.adviceKey(night)), w);

        int bottom = phoneTop + screenH - navH - 2;
        for (FormattedCharSequence line : lines) {
            if (y + font.lineHeight > bottom) break;   // 排不下就截住，不画到导航栏上
            g.drawString(font, line, x, y, FontPalette.body(), false);
            y += font.lineHeight + 1;
        }
    }

    /** 把三处字段取出来，交给有断言的那一边判 */
    private static Weather.Kind currentKind(ClientLevel level, LocalPlayer player) {
        BlockPos pos = player.blockPosition();

        Weather.Precip local;
        try {
            local = toPrecip(level.getBiome(pos).value().getPrecipitationAt(pos));
        } catch (Throwable t) {
            // 生物群系拿不到（区块还没到、别的模组的自定义群系抛了异常）时
            // 按"什么都不落"算。为了一行天气预报崩掉整个手机界面不值得
            local = Weather.Precip.NONE;
        }

        return Weather.classify(
                level.dimensionType().hasSkyLight(),
                level.isRaining(),
                level.isThundering(),
                local);
    }

    /**
     * 把原版的降水类型换成我们自己的。
     *
     * 隔这一层是为了让 Weather 那个类不 import 任何 Minecraft 类型——
     * 带上 Biome.Precipitation 的话它就编不成单独的了，那些断言也就跑不了。
     * 代价只是这一句 switch，而它漏了分支编译当场不过。
     */
    private static Weather.Precip toPrecip(Biome.Precipitation p) {
        return switch (p) {
            case NONE -> Weather.Precip.NONE;
            case RAIN -> Weather.Precip.RAIN;
            case SNOW -> Weather.Precip.SNOW;
        };
    }

    /**
     * 放大居中画一行字。
     *
     * 先 translate 再 scale，最后在原点画——理由见 HomeGrid.drawAppName，
     * 反过来写会让实际中心随字数偏移。
     */
    private static void drawBigCentered(GuiGraphics g, Font font, String text,
                                        int phoneLeft, int screenW, int y) {
        float bigW = font.width(text) * BIG_SCALE;
        g.pose().pushPose();
        g.pose().translate(phoneLeft + (screenW - bigW) / 2f, y, 0);
        g.pose().scale(BIG_SCALE, BIG_SCALE, 1f);
        g.drawString(font, text, 0, 0, FontPalette.title(), false);
        g.pose().popPose();
    }
}
