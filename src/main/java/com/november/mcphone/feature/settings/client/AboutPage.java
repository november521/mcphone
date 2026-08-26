package com.november.mcphone.feature.settings.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.app.RequiredMod;
import com.november.mcphone.compat.CuriosCompat;
import com.november.mcphone.compat.NetMusicCompat;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.core.client.PhoneTheme;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 关于页 —— 这部手机是什么、哪一版、跟谁配合得上。
 *
 * 版本号一个字都不许写死
 *
 * 这一页取代的是原来那句
 *
 *     Component.literal("§eMCphone v1.0.0 §7by november")
 *
 * ——它从第一版起就没跟着改过，玩家在 1.1.13 上看到的仍然是 v1.0.0。物品
 * tooltip 那边一直是对的（运行时读 ModContainer），只有这里漏了。
 *
 * 所以这里的版本号来自 {@link MCphone#getVersion()}，游戏版本来自
 * SharedConstants。往这一页加东西时守住这条：凡是"会随构建变化"的值，
 * 一律运行时取，别写进字面量，也别写进语言文件。
 *
 * 为什么是一页，不是聊天里的一行字
 *
 * 聊天框是公共区域。查个版本号要往里塞一行，划走就没了、想再看得重新点，
 * 还会把别人的对话顶上去。而这些信息恰恰是要拿去报 bug 的——得能停在
 * 屏幕上让人抄。
 *
 * 兼容状态也列在这里：玩家最常问的两个问题是"我的手机怎么挂不到腰上"和
 * "怎么没有传送石"，答案九成是对应模组没装。让他自己看一眼就知道。
 */
public final class AboutPage {

    private AboutPage() {}

    private static final int PAD = 6;

    public static void render(GuiGraphics g, int phoneLeft, int phoneTop,
                              int screenW, int screenH, int statusH, int navH, Font font) {

        int x = phoneLeft + PAD;
        int y = phoneTop + statusH + 4;
        int w = screenW - PAD * 2;

        g.drawString(font, Component.translatable("mcphone.gui.about").getString(),
                x, y, FontPalette.title(), true);
        y += font.lineHeight + 4;

        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 5;

        // ---- 名字与版本 ----
        g.drawString(font, "MCphone", x, y, FontPalette.title(), false);
        y += font.lineHeight + 1;

        // 版本运行时取，不写死——这一页存在的理由就是原来那句写死了
        g.drawString(font, "v" + MCphone.getVersion(), x, y, FontPalette.price(), false);
        y += font.lineHeight + 4;

        y = row(g, font, x, y, w, "mcphone.about.author", "november521");
        y = row(g, font, x, y, w, "mcphone.about.game",
                SharedConstants.getCurrentVersion().getName());
        y = row(g, font, x, y, w, "mcphone.about.apps",
                String.valueOf(PhoneScreenRegistry.getAppCount()));

        y += 3;
        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER_FAINT);
        y += 4;

        // ---- 联动模组 ----
        g.drawString(font, Component.translatable("mcphone.about.compat").getString(),
                x, y, FontPalette.subtle(), false);
        y += font.lineHeight + 2;

        // Curios 与 NetMusic 都是"能力型"联动：装了多点东西，但都不对应任何
        // 一个 App，所以从 App 的前置声明里汇总不出来，只能单独写在这儿。
        // 这一页存在的理由就是回答玩家"我怎么没有这个"，漏一条他就会把
        // "功能不见了"当成 bug 来报
        y = compatRow(g, font, x, y, w,
                Component.translatable("mcphone.compat.curios").getString(),
                CuriosCompat.isLoaded());
        y = compatRow(g, font, x, y, w,
                Component.translatable("mcphone.compat.netmusic").getString(),
                NetMusicCompat.isLoaded());

        // 其余从各 App 声明的前置汇总。这一段【不能】改回手写清单：
        // v1.2.0 加了浏览器 App 却忘了往这儿加 MCEF，玩家看不到自己缺什么，
        // 只会把"App 不见了"当成 bug 来报。汇总出来就漏不掉了。
        int bottom = phoneTop + screenH - navH - 2;
        for (RequiredMod mod : companionMods()) {
            if (y + font.lineHeight > bottom) {
                g.drawString(font, "…", x, y, FontPalette.subtle(), false);
                break;
            }
            y = compatRow(g, font, x, y, w, mod.displayName(),
                    ModList.get().isLoaded(mod.modId()));
        }
    }

    /**
     * 所有 App 声明过的前置模组，按 modId 去重。
     *
     * 去重是必须的：两个 App 依赖同一个模组时（比如将来又来一个用 Waystones
     * 的），这一页不该把它列两遍。用 LinkedHashMap 而不是 HashMap，是为了让
     * 顺序稳定——每次进这一页顺序都不一样的话，玩家会以为自己看错了。
     */
    private static List<RequiredMod> companionMods() {
        Map<String, RequiredMod> byId = new LinkedHashMap<>();

        // 硬前置：声明了它的 App 缺了对方就整个不可用
        for (IPhoneApp app : PhoneScreenRegistry.getCompanionApps()) {
            for (RequiredMod mod : PhoneScreenRegistry.requiredModsOf(app)) {
                byId.putIfAbsent(mod.modId(), mod);
            }
        }

        // 软联动：装了多一块内容，没装 App 照常在。这一类【不】出现在
        // getCompanionApps() 里（那份名单按前置筛），所以要走整个目录
        for (IPhoneApp app : PhoneScreenRegistry.getApps()) {
            for (RequiredMod mod : PhoneScreenRegistry.companionModsOf(app)) {
                byId.putIfAbsent(mod.modId(), mod);
            }
        }
        for (IPhoneApp app : PhoneScreenRegistry.getAvailable()) {
            for (RequiredMod mod : PhoneScreenRegistry.companionModsOf(app)) {
                byId.putIfAbsent(mod.modId(), mod);
            }
        }

        return List.copyOf(byId.values());
    }

    /** 一行"标签 …… 值"，值靠右。排法与截断规则见 GuiUtil.drawLabelValueRow */
    private static int row(GuiGraphics g, Font font, int x, int y, int w,
                           String labelKey, String value) {
        GuiUtil.drawLabelValueRow(g, font, x, y, w,
                Component.translatable(labelKey).getString(), FontPalette.subtle(),
                value, FontPalette.body());
        return y + font.lineHeight + 1;
    }

    /**
     * 一行兼容状态。
     *
     * 用文字"已装/未装"而不是 ✓ ✗ 符号：那两个符号在部分字体下会掉成
     * 方框，而这一页多半是玩家截图发出来问问题的时候在看。
     */
    private static int compatRow(GuiGraphics g, Font font, int x, int y, int w,
                                 String modName, boolean loaded) {
        String value = Component.translatable(
                loaded ? "mcphone.about.installed" : "mcphone.about.missing").getString();
        // 模组名会很长（"Waystones（传送石碑）"就快占满整行），必须给右边
        // 那两个字让路——否则"已装"正压在名字上，这一页恰恰是玩家截图来
        // 问问题时在看的
        GuiUtil.drawLabelValueRow(g, font, x, y, w,
                modName, FontPalette.subtle(),
                value, loaded ? FontPalette.confirm() : FontPalette.subtle());
        return y + font.lineHeight + 1;
    }
}
