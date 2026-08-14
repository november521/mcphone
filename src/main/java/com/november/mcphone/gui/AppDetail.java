package com.november.mcphone.gui;

import com.november.mcphone.api.client.store.AppInfo;
import com.november.mcphone.api.client.store.IAppSource;
import com.november.mcphone.api.cost.ICost;
import com.november.mcphone.cost.AppPriceRegistry;
import com.november.mcphone.network.store.StoreClientCache;
import com.november.mcphone.store.AppSourceRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;


/**
 * 应用详情页 —— 一个 App 的介绍、价格与那个按钮。
 *
 * ============================================================
 * 按钮只有一个，但它有四种状态
 * ============================================================
 *
 *   购买     付费 App，还没买过，且付得起
 *   买不起   付费 App，还没买过，付不起 —— 灰，且写明要什么
 *   下载     免费 App，或已经买过 —— 装进主屏
 *   已安装   已经在主屏上了 —— 灰
 *
 * 做成一个按钮而不是"购买"与"下载"两个：任一时刻只有一件事可做，两个按钮
 * 里必然有一个是灰的，那只是在教玩家忽略一半界面。
 *
 * ============================================================
 * "买过了"这件事客户端说了不算
 * ============================================================
 *
 * 这里读的 {@link StoreClientCache} 只是服务端那份记录的镜像，用来决定按钮
 * 画成什么样。真正的判断在服务端——把缓存改成"全都买过"也拿不到任何 App，
 * 因为安装前不经过服务端，而付费 App 的购买请求会被服务端按自己的账本驳回。
 *
 * 同步没回来之前按钮显示"加载中"而不是"购买"：那两种状态在玩家眼里差别很
 * 大，把"还不知道"画成"没买过"，会让已经买过的人以为要再花一次钱。
 */
public final class AppDetail {

    private static final int PAD = 6;
    private static final int BIG_ICON = 32;
    private static final int BUTTON_H = 16;

    private AppInfo info;

    /** 按钮的矩形，渲染时算出来，点击时复用。别在两处各算一遍 */
    private int btnX, btnY, btnW;
    private boolean btnHovered;
    private boolean btnEnabled;

    /** 操作结果提示 */
    private Component message = null;

    /** 请求退回商店首页，等 PhoneScreen 来取 */
    private boolean backRequest = false;

    /** 装成功了，商店首页需要刷新列表 */
    private boolean installedRequest = false;

    public void open(AppInfo target) {
        this.info = target;
        this.message = null;
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

    // ============================================================
    //  状态
    // ============================================================

    /** 按钮此刻是哪一种 */
    private enum State { LOADING, BUY, CANT_AFFORD, DOWNLOAD, INSTALLED }

    private State state() {
        if (info == null) return State.LOADING;

        if (PhoneScreenRegistry.isInstalled(info.id())) return State.INSTALLED;

        ICost price = AppPriceRegistry.priceOf(info.id());
        if (price == ICost.FREE) return State.DOWNLOAD;

        // 付费 App：得先知道服务端那边的账本
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

    // ============================================================
    //  渲染
    // ============================================================

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, Font font) {

        int x = phoneLeft + PAD;
        int y = phoneTop + statusH + 4;
        int w = screenW - PAD * 2;
        int bottom = phoneTop + screenH - navH;

        if (info == null) {
            g.drawString(font, Component.translatable("mcphone.store.empty").getString(),
                    x, y, PhoneTheme.FONT_COLOR_SUBTLE, false);
            return;
        }

        // ---- 大图标 + 名称 ----
        if (info.iconTexture() != null) {
            g.blit(info.iconTexture(), x, y, 0, 0, BIG_ICON, BIG_ICON, BIG_ICON, BIG_ICON);
        } else {
            g.fill(x, y, x + BIG_ICON, y + BIG_ICON, PhoneTheme.COLOR_BUTTON_DISABLED);
        }

        int textX = x + BIG_ICON + 5;
        int textW = w - BIG_ICON - 5;
        g.drawString(font, trim(font, info.displayName().getString(), textW),
                textX, y + 2, PhoneTheme.FONT_COLOR_TITLE, false);

        // 作者与版本挤在一行：屏幕只有 120 宽，各占一行不值得
        String meta = info.author() == null || info.author().isBlank()
                ? "v" + info.version()
                : info.author() + " · v" + info.version();
        g.drawString(font, trim(font, meta, textW),
                textX, y + 2 + font.lineHeight + 2, PhoneTheme.FONT_COLOR_SUBTLE, false);

        y += BIG_ICON + 6;

        g.fill(x, y, x + w, y + 1, 0x44FFFFFF);
        y += 4;

        // ---- 简介。按钮要占底部，正文只能用中间这段 ----
        int bodyBottom = bottom - BUTTON_H - font.lineHeight - 8;
        String desc = info.description();
        if (desc == null || desc.isBlank()) {
            desc = Component.translatable("mcphone.store.no_description").getString();
        }
        for (var line : font.split(Component.literal(desc), w)) {
            if (y + font.lineHeight > bodyBottom) break;
            g.drawString(font, line, x, y, PhoneTheme.FONT_COLOR_BODY, false);
            y += font.lineHeight + 1;
        }

        // ---- 提示（购买失败之类） ----
        if (message != null) {
            g.drawString(font, trim(font, message.getString(), w),
                    x, bodyBottom, 0xFFFFAA44, false);
        }

        // ---- 价格 ----
        State s = state();
        ICost price = AppPriceRegistry.priceOf(info.id());
        String priceText = price == ICost.FREE
                ? Component.translatable("mcphone.store.free").getString()
                : price.describe().getString();
        int priceY = bottom - BUTTON_H - font.lineHeight - 3;
        g.drawString(font, trim(font, priceText, w), x, priceY,
                price == ICost.FREE ? PhoneTheme.FONT_COLOR_SUBTLE : PhoneTheme.FONT_COLOR_PRICE,
                false);

        // ---- 按钮 ----
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

    private static String trim(Font font, String text, int maxW) {
        if (font.width(text) <= maxW) return text;
        return font.plainSubstrByWidth(text, maxW - 6) + "…";
    }

    // ============================================================
    //  鼠标
    // ============================================================

    public boolean mouseClicked(double mx, double my, int button) {
        if (info == null || !btnHovered) return false;

        switch (state()) {
            case BUY -> {
                // 只是提出请求。买没买成由服务端说了算，结果会以同步包的
                // 形式回来，按钮届时自己变成"下载"
                StoreClientCache.purchase(info.id());
                message = Component.translatable("mcphone.store.purchasing");
            }
            case DOWNLOAD -> install();
            default -> { }
        }
        return true;
    }

    /**
     * 装进主屏。
     *
     * 这一步纯客户端：App 的实现已经随模组加载进来了，"下载"只是把它加进
     * 已安装集合。服务端不参与，也不需要——它管的是钱，不是主屏摆什么。
     */
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
