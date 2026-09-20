package com.november.mcphone.feature.store.client;

import com.november.mcphone.api.client.store.AppInfo;
import com.november.mcphone.api.client.store.IAppSource;
import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.cost.ICost;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.core.client.PhoneSkin;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.core.script.client.ClientHandshake;
import com.november.mcphone.core.script.client.LocalScriptSource;
import com.november.mcphone.core.script.client.ScriptApp;
import com.november.mcphone.core.script.client.ScriptAppAdapter;
import com.november.mcphone.core.script.pkg.FrontendDigest;
import com.november.mcphone.core.script.pkg.SigCopy;
import com.november.mcphone.feature.store.AppPriceRegistry;
import com.november.mcphone.feature.store.client.AppSourceRegistry;
import com.november.mcphone.feature.store.net.StoreClientCache;
import com.november.mcphone.core.client.GuiUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

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

    /** 二次确认框的位置；没画出来时 {@code confirmBoxY} 是 -1。 */
    private int confirmBoxX, confirmBoxY = -1;

    /**
     * 「申请」按钮的位置与状态（§14.5）。没画出来时 {@code applyW} 是 0。
     *
     * <p>按钮本身只做一件事：把"申请通道还没接通"如实告诉玩家。真正发通知给 OP 的链路
     * 属于审批界面那一批（Stage 3 之后），现在不该画一个点了像发出去、其实什么都没发的按钮。
     */
    private int applyX, applyY, applyW, applyH;
    private boolean applyHovered;

    /** 打开时查一次的本机脚本 App（装没装都查得到）；不是脚本 App 就是 null。 */
    private ScriptApp script;

    private Component message = null;

    /** 上一帧的按钮状态：状态一变就清提示，没有计时器 */
    private State lastState = null;

    /** 请求退回商店首页，等 PhoneScreen 来取 */
    private boolean backRequest = false;

    /** 装成功了，商店首页需要刷新列表 */
    private boolean installedRequest = false;

    /**
     * 「作者密钥变了」那一档要输入的东西（§12.4：<b>要输入确认短语，不是点一下</b>）。
     * 用 {@code char[]} 没必要 —— 这不是口令，是一串公开的指纹。
     */
    private String typedPhrase = "";

    /** 二次确认那个框勾了没有。换一个 App 就清掉。 */
    private boolean confirmTicked;

    public void open(AppInfo target) {
        this.info = target;
        this.message = null;
        this.lastState = null;
        this.backRequest = false;
        this.installedRequest = false;
        this.typedPhrase = "";
        this.confirmTicked = false;
        this.applyW = 0;
        this.script = target == null ? null : scriptOf(target);
    }

    /**
     * 这个 App 是不是脚本 App，是就把它的包拿在手里（详情页三行与横幅要读包里的 server.js）。
     *
     * <p>已装过的走目录（{@code PhoneScreenRegistry}），没装的问本地脚本来源（会扫一次
     * {@code mcphone/apps/}）—— 两处都只在这页打开时问一次，不每帧问。
     */
    private static ScriptApp scriptOf(AppInfo info) {
        IPhoneApp installed = PhoneScreenRegistry.getApp(info.id());
        if (installed instanceof ScriptAppAdapter adapter) return adapter.script();
        if (!LocalScriptSource.ID.equals(info.sourceId())) return null;
        return LocalScriptSource.scriptOf(info.id());
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

    private enum State { LOADING, BLOCKED, BUY, CANT_AFFORD, DOWNLOAD, INSTALLED }

    private State state() {
        if (info == null) return State.LOADING;

        if (PhoneScreenRegistry.isInstalled(info.id())) return State.INSTALLED;

        // 排在价钱之前：装不了的东西不该先问玩家买不买得起（§23.4）
        if (info.blockedReason() != null) return State.BLOCKED;

        ICost price = AppPriceRegistry.priceOf(info.id());
        if (price == ICost.FREE) return State.DOWNLOAD;

        if (!StoreClientCache.isSynced()) return State.LOADING;
        if (StoreClientCache.has(info.id())) return State.DOWNLOAD;

        var player = Minecraft.getInstance().player;
        if (player != null && !price.canAfford(player)) return State.CANT_AFFORD;
        return State.BUY;
    }

    private String labelOf(State s) {
        return switch (s) {
            case LOADING -> Component.translatable("mcphone.store.loading").getString();
            case BLOCKED -> info.blockedReason().getString();
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

        // 空壳横幅（§13.7 的宿主兜底）：作者没写 v-if="!backend.available" 那一支时，
        // 宿主在最上面插一条，别让玩家对着点不动的界面发呆。它是 UX，不参与任何判定
        y = renderBackendBanner(g, font, x, y, w);

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

        // §14.5 的三行（部署 / 授权 / 来源）+「申请」按钮：排在签名之前，
        // 因为"为什么我装了却用不了"比签名更常被问
        y = renderDeployment(g, font, x, y, w, bodyBottom, mouseX, mouseY);

        y = renderSignature(g, font, x, y, w, bodyBottom);

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
        // 【签名无效】那一档由 blockedReason 走 BLOCKED，本来就画不出按钮；
        // 【作者密钥变了】要抄对新指纹才放行 —— 不然按钮画灰，不给"点一下就走"
        btnEnabled = (s == State.BUY || s == State.DOWNLOAD) && signatureSatisfied();
        btnHovered = btnEnabled && mouseX >= btnX && mouseX <= btnX + btnW
                && mouseY >= btnY && mouseY <= btnY + BUTTON_H;

        if (btnEnabled) {
            // 悬停那一档要传下去：这个位上一旦有了贴图，"换个颜色"就再也看不出来了，
            // 得让 PhoneSkin 整张提亮（1.9.2 补齐自带贴图之后这里就是这么坏掉的）
            PhoneSkin.drawOrFill(g, PhoneSkin.Element.STORE_BUTTON, btnX, btnY, btnW, BUTTON_H,
                    btnHovered ? PhoneTheme.COLOR_BUTTON_HOVER : PhoneTheme.COLOR_BUTTON,
                    btnHovered);
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

    /**
     * 空壳横幅（§13.7 的宿主兜底）。画的条件很窄：<b>这个 App 的包里有 {@code server.js}</b>
     * （也就是它需要服务端那一半），而握手说本服没有它的部署。画在详情页最上面，返回新的 y。
     *
     * <p>作者自己写了 {@code v-if="!backend.available"} 分支时这条横幅是多余的 —— 但宿主
     * 静态看不出作者写没写，宁可多一条也不要白屏。它只是提示，不参与任何授权判断。
     */
    private int renderBackendBanner(GuiGraphics g, Font font, int x, int y, int w) {
        if (!needsBackend()) return y;
        if (ClientHandshake.deployment(info.id().toString()) != null) return y;

        var lines = font.split(Component.translatable("mcphone.store.backend_missing"), w - 8);
        int h = lines.size() * font.lineHeight + 6;
        g.fill(x, y, x + w, y + h, PhoneTheme.COLOR_BUTTON_DISABLED);
        int ly = y + 3;
        for (var line : lines) {
            g.drawString(font, line, x + 4, ly, FontPalette.notice(), false);
            ly += font.lineHeight;
        }
        return y + h + 4;
    }

    /** 这个包带不带服务端那一半。{@code server.js} 永远不下发给客户端，本地这一份就是判据。 */
    private boolean needsBackend() {
        return script != null && script.pkg() != null && script.pkg().entry("server.js") != null;
    }

    /**
     * 玩家侧三行 +「申请」按钮（§14.5）。
     *
     * <pre>
     * 部署   本服已部署 · 版本 3   ｜ 本服未部署
     * 授权   你可以使用：a、b      ｜ 你还没有被授权  [申请]
     * 来源   服务器商店 · 服主 2026-09-07 批准
     * </pre>
     *
     * <p>数据来自握手（{@link ClientHandshake}）与本地包，<b>全部是展示</b>：
     * 授权那一行就算画出"你可以使用"，服务端每次请求照样重查（§13.8）。
     * 界面里再判一遍就会有两份判据 —— 所以这里只渲染事实。
     */
    private int renderDeployment(GuiGraphics g, Font font, int x, int y, int w, int bodyBottom,
                                 int mouseX, int mouseY) {
        applyW = 0;
        applyHovered = false;
        if (script == null || info == null) return y;

        String appId = info.id().toString();
        ClientHandshake.Entry entry = ClientHandshake.deployment(appId);
        boolean deployed = entry != null;
        if (!needsBackend() && !deployed) return y;   // 纯前端脚本 App：这三行对它没有意义

        y += 3;

        // 部署
        y = factLine(g, font, x, y, w, bodyBottom, "mcphone.store.detail.deploy",
                deployed
                        ? Component.translatable("mcphone.store.deploy.deployed",
                                String.valueOf(entry.approvalRevision())).getString()
                        : Component.translatable("mcphone.store.deploy.not_deployed").getString());

        // 授权 + 「申请」
        String license = deployed && !entry.actions().isEmpty()
                ? Component.translatable("mcphone.store.license.granted",
                        String.join("、", entry.actions())).getString()
                : Component.translatable("mcphone.store.license.none").getString();
        boolean apply = !deployed || entry.actions().isEmpty();
        String applyLabel = apply ? Component.translatable("mcphone.store.license.apply").getString() : "";
        int btnW = apply ? font.width(applyLabel) + 8 : 0;
        if (y + font.lineHeight <= bodyBottom) {
            y = factLine(g, font, x, y, w - (btnW == 0 ? 0 : btnW + 4), bodyBottom,
                    "mcphone.store.detail.license", license);
            if (apply) {
                applyX = x + w - btnW;
                applyY = y - font.lineHeight - 1;
                applyW = btnW;
                applyH = font.lineHeight + 2;
                applyHovered = mouseX >= applyX && mouseX <= applyX + applyW
                        && mouseY >= applyY && mouseY <= applyY + applyH;
                g.fill(applyX, applyY, applyX + applyW, applyY + applyH,
                        applyHovered ? PhoneTheme.COLOR_BUTTON_HOVER : PhoneTheme.COLOR_BUTTON);
                g.drawString(font, applyLabel, applyX + 4, applyY + 1,
                        PhoneTheme.FONT_COLOR_BUTTON, false);
            }
        }

        // 来源
        String source;
        if (deployed) {
            source = Component.translatable("mcphone.store.source.server",
                    date(entry.approvedAt())).getString();
        } else {
            IAppSource src = AppSourceRegistry.getSource(info.sourceId());
            source = src == null
                    ? info.sourceId().toString()
                    : src.getDisplayName().getString();
        }
        y = factLine(g, font, x, y, w, bodyBottom, "mcphone.store.detail.source", source);

        // 前端摘要只回显「界面被本地改过」——这不是安全边界（§13.3）：
        // 恶意客户端伪造它什么也换不来，服务端的判定一次都不会看它
        if (deployed && script.pkg() != null) {
            String local = FrontendDigest.of(script.pkg());
            if (entry.frontendDigest() != null && !entry.frontendDigest().isEmpty()
                    && !entry.frontendDigest().equals(local)
                    && y + font.lineHeight <= bodyBottom) {
                g.drawString(font, GuiUtil.truncate(font,
                                Component.translatable("mcphone.store.frontend_modified").getString(), w),
                        x, y, FontPalette.subtle(), false);
                y += font.lineHeight;
            }
        }
        return y + 2;
    }

    /** 一行「标签 + 内容」，标签灰、内容正常色；内容一行放不下就截断。返回新的 y。 */
    private int factLine(GuiGraphics g, Font font, int x, int y, int w, int bodyBottom,
                         String labelKey, String value) {
        if (y + font.lineHeight > bodyBottom) return y;
        String label = Component.translatable(labelKey).getString();
        int labelW = font.width(label) + 4;
        g.drawString(font, label, x, y, FontPalette.subtle(), false);
        g.drawString(font, GuiUtil.truncate(font, value, Math.max(0, w - labelW)),
                x + labelW, y, FontPalette.body(), false);
        return y + font.lineHeight + 1;
    }

    /** 批准日期（epoch 毫秒 → 本机时区的 yyyy-MM-dd）；没有记录时给一句可读的话。 */
    private static String date(long epochMillis) {
        if (epochMillis <= 0L) {
            return Component.translatable("mcphone.store.source.unknown_date").getString();
        }
        return DateTimeFormatter.ISO_LOCAL_DATE
                .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    /**
     * 签名那一段（§12.4 / §12.5）。档位提示 + 作者指纹 + <b>每次都显示的 INSTALL_NOTE</b>。
     *
     * <p><b>这里不判签名</b>：状态是来源算好带过来的（{@link AppInfo#signature()}）。
     * 界面里再判一遍就会有两份判据。
     *
     * <p><b>「已签名」处不画对勾</b>：签名确认的是"谁做的、有没有被改过"，不是"内容安不安全"。
     * 一个绿色对勾会把那个区分抹掉，而 INSTALL_NOTE 正是为了讲清这件事才每次都显示。
     */
    private int renderSignature(GuiGraphics g, Font font, int x, int y, int w, int bodyBottom) {
        AppInfo.Signature sig = info.signature();
        if (sig == null) return y;                       // 内建 App 没有包，也就没有签名这一说

        y += 3;

        // 档位提示。参数按各档的文案填：密钥变了要新旧两个，陌生作者要指纹，已信任要名字与指纹
        String line = switch (sig.stateKey()) {
            case "mcphone.sig.key_changed" -> Component.translatable(sig.stateKey(),
                    String.valueOf(sig.previous()), String.valueOf(sig.fingerprint())).getString();
            case "mcphone.sig.unknown" -> Component.translatable(sig.stateKey(),
                    String.valueOf(sig.fingerprint())).getString();
            case "mcphone.sig.trusted" -> Component.translatable(sig.stateKey(),
                    info.author(), String.valueOf(sig.fingerprint())).getString();
            default -> Component.translatable(sig.stateKey()).getString();
        };
        int colour = sig.hardRejected() || "mcphone.sig.key_changed".equals(sig.stateKey())
                ? FontPalette.notice() : FontPalette.subtle();
        for (var l : font.split(Component.literal(line), w)) {
            if (y + font.lineHeight > bodyBottom) return y;
            g.drawString(font, l, x, y, colour, false);
            y += font.lineHeight;
        }

        // 指纹。【身份是它，不是 author】。未签名时这一格写「无」
        String fp = sig.fingerprint() == null
                ? Component.translatable("mcphone.sig.fingerprint_none").getString()
                : sig.fingerprint();
        if (y + font.lineHeight <= bodyBottom) {
            g.drawString(font, GuiUtil.truncate(font, fp, w), x, y, FontPalette.subtle(), false);
            y += font.lineHeight + 1;
        }

        // 要抄指纹的那一档：把输入框画出来
        if (sig.requiredPhrase() != null && y + font.lineHeight * 2 <= bodyBottom) {
            g.drawString(font, GuiUtil.truncate(font,
                            Component.translatable("mcphone.sig.confirm_prompt").getString(), w),
                    x, y, FontPalette.subtle(), false);
            y += font.lineHeight;
            g.fill(x, y, x + w, y + font.lineHeight + 2, PhoneTheme.COLOR_BUTTON_DISABLED);
            g.drawString(font, GuiUtil.truncate(font, typedPhrase, w - 4), x + 2, y + 2,
                    phraseTyped() ? FontPalette.body() : FontPalette.notice(), false);
            y += font.lineHeight + 4;
        }

        // INSTALL_NOTE —— 【每次安装都显示】。它把「签名 ≠ 安全」讲给玩家
        if (sig.needsConfirm() && y + font.lineHeight <= bodyBottom) {
            confirmBoxX = x;
            confirmBoxY = y;
            g.fill(x, y, x + font.lineHeight, y + font.lineHeight,
                    confirmTicked ? PhoneTheme.COLOR_BUTTON : PhoneTheme.COLOR_BUTTON_DISABLED);
            g.drawString(font, GuiUtil.truncate(font,
                            Component.translatable("mcphone.sig.confirm_tick").getString(),
                            w - font.lineHeight - 4),
                    x + font.lineHeight + 4, y + 1, FontPalette.body(), false);
            y += font.lineHeight + 4;
        } else {
            confirmBoxY = -1;
        }

        for (var l : font.split(Component.translatable("mcphone.sig.install_note"), w)) {
            if (y + font.lineHeight > bodyBottom) break;
            g.drawString(font, l, x, y, FontPalette.subtle(), false);
            y += font.lineHeight;
        }
        return y;
    }

    /** 不需要确认短语的档位恒为真；需要的那一档要抄对新指纹。 */
    /**
     * 界面这一层还差什么才让点安装。
     *
     * <p><b>这不是闸</b> —— 真正的闸在来源的 {@code install()} 里（它才是任何调用方都绕不开的
     * 那一道）。这里只是别让按钮看起来能点、点下去却被拒。
     */
    private boolean signatureSatisfied() {
        AppInfo.Signature sig = info == null ? null : info.signature();
        if (sig == null) return true;
        if (sig.requiredPhrase() != null) return phraseTyped();
        return !sig.needsConfirm() || confirmTicked;
    }

    /**
     * 输入的短语对不对。
     *
     * <p>比对本身<b>不在这里写第二份</b>：一份 {@code equalsIgnoreCase} 写在界面、
     * 另一份写在 {@code SigCopy}，两份迟早对不上。这里问的是那一份。
     */
    private boolean phraseTyped() {
        AppInfo.Signature sig = info.signature();
        return SigCopy.phraseAccepted(typedPhrase, sig.requiredPhrase());
    }

    /** 抄指纹用。只有那一档收键盘。 */
    public boolean charTyped(char c, int modifiers) {
        AppInfo.Signature sig = info == null ? null : info.signature();
        if (sig == null || sig.requiredPhrase() == null) return false;
        if (c < ' ' || typedPhrase.length() >= 64) return true;
        typedPhrase += c;
        return true;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        AppInfo.Signature sig = info == null ? null : info.signature();
        if (sig == null || sig.requiredPhrase() == null) return false;
        if (keyCode == 259 && !typedPhrase.isEmpty()) {          // backspace
            typedPhrase = typedPhrase.substring(0, typedPhrase.length() - 1);
            return true;
        }
        return false;
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (info == null) return false;

        // 「申请」（§14.5）：现在只如实说"通道还没接通"，不假装发出去了一条通知。
        // 真链路（通知 OP / 审批界面）到货时把这里换成发送，界面其余部分不用动
        if (applyW > 0 && mx >= applyX && mx <= applyX + applyW
                && my >= applyY && my <= applyY + applyH) {
            message = Component.translatable("mcphone.store.license.apply_hint");
            return true;
        }

        AppInfo.Signature sig = info.signature();
        if (sig != null && sig.needsConfirm() && confirmBoxY >= 0
                && mx >= confirmBoxX && mx <= confirmBoxX + 160
                && my >= confirmBoxY && my <= confirmBoxY + 12) {
            confirmTicked = !confirmTicked;
            return true;
        }

        if (!btnHovered) return false;

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
        // 先把玩家输入的东西交给来源判。【放行的是来源，不是这个按钮】——
        // 界面只负责收集，判据那一份写在来源的 confirmSignature / install 里
        if (info.signature() != null && !source.confirmSignature(info, typedPhrase)) {
            message = Component.translatable("mcphone.sig.confirm_mismatch");
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
