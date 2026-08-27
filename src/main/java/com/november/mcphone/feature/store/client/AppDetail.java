package com.november.mcphone.feature.store.client;

import com.november.mcphone.api.client.store.AppInfo;
import com.november.mcphone.api.client.store.IAppSource;
import com.november.mcphone.api.cost.ICost;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.feature.store.AppPriceRegistry;
import com.november.mcphone.feature.store.client.AppSourceRegistry;
import com.november.mcphone.feature.store.net.StoreClientCache;
import com.november.mcphone.core.client.GuiUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * 应用详情页：一个 App 的介绍、价格与唯一的按钮（购买/买不起/下载/已安装 四态）。
 * "买过了"以服务端账本为准，{@link StoreClientCache} 只是画按钮用的镜像；
 * 同步没回来之前按钮是"加载中"，别把"还不知道"画成"没买过"。
 */
public final class AppDetail {

    private static final int PAD = 6;
    private static final int BIG_ICON = 32;
    private static final int BUTTON_H = 16;

    private AppInfo info;

    /** 渲染时算出来，点击时复用 */
    private int btnX, btnY, btnW;
    private boolean btnHovered;
    private boolean btnEnabled;

    private Component message = null;

    /** 上一帧的按钮状态：状态一变就清提示，没有计时器 */
    private State lastState = null;

    /** 请求退回商店首页，等 PhoneScreen 来取 */
    private boolean backRequest = false;

    /** 装成功了，商店首页需要刷新列表 */
    private boolean installedRequest = false;

    public void open(AppInfo target) {
        this.info = target;
        this.message = null;
        this.lastState = null;
        this.backRequest = false;
        this.installedRequest = false;
    }

    public boolean consumeBackRequest() {
        boolean r = backRequest;
        backRequest = false;
        return r;
    }

    public boolean consumeInstalledRequest() {
        boolean r = installedRequest;
        installedRequest = false;
        return r;
    }

    private enum State { LOADING, BUY, CANT_AFFORD, DOWNLOAD, INSTALLED }

    private State state() {
        if (info == null) return State.LOADING;

        if (PhoneScreenRegistry.isInstalled(info.id())) return State.INSTALLED;

        ICost price = AppPriceRegistry.priceOf(info.id());
        if (price == ICost.FREE) return State.DOWNLOAD;

        if (!StoreClientCache.isSynced()) return State.LOADING;
        if (StoreClientCache.has(info.id())) return State.DOWNLOAD;

        var player = Minecraft.getInstance().player;
        if (player != null && !price.canAfford(player)) return State.CANT_AFFORD;
        return State.BUY;
    }

    private static String labelOf(State s) {
        return switch (s) {
            case LOADING -> Component.translatable("mcphone.store.loading").getString();
            case BUY -> Component.translatable("mcphone.store.buy").getString();
            case CANT_AFFORD -> Component.translatable("mcphone.store.cant_afford").getString();
            case DOWNLOAD -> Component.translatable("mcphone.store.install").getString();
            case INSTALLED -> Component.translatable("mcphone.store.installed_label").getString();
        };
    }

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font) {

        int x = phoneLeft + PAD;
        int y = phoneTop + statusH + 4;
        int w = screenW - PAD * 2;
        int bottom = phoneTop + screenH - navH;

        if (info == null) {
            g.drawString(font, Component.translatable("mcphone.store.empty").getString(),
                    x, y, FontPalette.subtle(), false);
            return;
        }

        // 状态一变说明上一次操作有结果了，撤掉"正在购买…"
        State s = state();
        if (lastState != null && s != lastState) message = null;
        lastState = s;

        if (info.iconTexture() != null) {
            GuiUtil.drawTexture(g, info.iconTexture(), x, y, BIG_ICON, BIG_ICON, BIG_ICON, BIG_ICON);
        } else {
            g.fill(x, y, x + BIG_ICON, y + BIG_ICON, PhoneTheme.COLOR_BUTTON_DISABLED);
        }

        int textX = x + BIG_ICON + 5;
        int textW = w - BIG_ICON - 5;
        g.drawString(font, GuiUtil.truncate(font, info.displayName().getString(), textW),
                textX, y + 2, FontPalette.title(), false);

        String meta = info.author() == null || info.author().isBlank()
                ? "v" + info.version()
                : info.author() + " · v" + info.version();
        g.drawString(font, GuiUtil.truncate(font, meta, textW),
                textX, y + 2 + font.lineHeight + 2, FontPalette.subtle(), false);

        y += BIG_ICON + 6;

        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 4;

        int bodyBottom = bottom - BUTTON_H - font.lineHeight - 8;
        String desc = info.description();
        if (desc == null || desc.isBlank()) {
            desc = Component.translatable("mcphone.store.no_description").getString();
        }
        for (var line : font.split(Component.literal(desc), w)) {
            if (y + font.lineHeight > bodyBottom) break;
            g.drawString(font, line, x, y, FontPalette.body(), false);
            y += font.lineHeight + 1;
        }

        if (message != null) {
            g.drawString(font, GuiUtil.truncate(font, message.getString(), w),
                    x, bodyBottom, FontPalette.notice(), false);
        }

        ICost price = AppPriceRegistry.priceOf(info.id());
        String priceText = price == ICost.FREE
                ? Component.translatable("mcphone.store.free").getString()
                : price.describe().getString();
        int priceY = bottom - BUTTON_H - font.lineHeight - 3;
        g.drawString(font, GuiUtil.truncate(font, priceText, w), x, priceY,
                price == ICost.FREE ? FontPalette.subtle() : FontPalette.price(),
                false);

        btnX = x;
        btnY = bottom - BUTTON_H - 1;
        btnW = w;
        btnEnabled = s == State.BUY || s == State.DOWNLOAD;
        btnHovered = btnEnabled && mouseX >= btnX && mouseX <= btnX + btnW
                && mouseY >= btnY && mouseY <= btnY + BUTTON_H;

        if (btnEnabled) {
            PhoneSkin.drawOrFill(g, PhoneSkin.Element.STORE_BUTTON, btnX, btnY, btnW, BUTTON_H,
                    btnHovered ? PhoneTheme.COLOR_BUTTON_HOVER : PhoneTheme.COLOR_BUTTON);
        } else {
            PhoneSkin.drawOrFill(g, PhoneSkin.Element.STORE_BUTTON_DISABLED,
                    btnX, btnY, btnW, BUTTON_H, PhoneTheme.COLOR_BUTTON_DISABLED);
        }

        String label = labelOf(s);
        g.drawString(font, label,
                btnX + (btnW - font.width(label)) / 2,
                btnY + (BUTTON_H - font.lineHeight) / 2 + 1,
                btnEnabled ? PhoneTheme.FONT_COLOR_BUTTON : PhoneTheme.FONT_COLOR_BUTTON_DISABLED,
                false);
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (info == null || !btnHovered) return false;

        switch (state()) {
            case BUY -> {
                // 只是提出请求：结果随同步包回来，按钮届时自己变成"下载"
                StoreClientCache.purchase(info.id());
                message = Component.translatable("mcphone.store.purchasing");
            }
            case DOWNLOAD -> install();
            default -> { }
        }
        return true;
    }

    /** 纯客户端：实现已随模组加载，"下载"只是把它加进已安装集合 */
    private void install() {
        IAppSource source = AppSourceRegistry.getSource(info.sourceId());
        if (source == null) {
            message = Component.translatable("mcphone.store.error.no_source",
                    info.sourceId().toString());
            return;
        }
        source.install(info,
                app -> {
                    installedRequest = true;
                    backRequest = true;
                },
                err -> message = err);
    }

}
