package com.november.mcphone.feature.settings.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.november.mcphone.MCphone;
import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.app.RequiredMod;
import com.november.mcphone.core.client.AppHotkeys;
import com.november.mcphone.core.client.AppOptions;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.GuiUtil;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import com.november.mcphone.core.client.PhoneTheme;
import com.november.mcphone.platform.ModPresence;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.client.settings.KeyModifier;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Supplier;

/**
 * 一个 App 的管理页 —— 它是谁、谁给的、以及能对它做什么。
 *
 * 为什么要有这一页
 *
 * 在它之前，App 管理器是一个平列表，【点一行就卸载】。那有两个毛病：玩家点开这一页
 * 多半是想看看某个 App 是什么来路，结果手一抖就把它卸了；而删一张照片反倒是要点两次的
 * （见 Gallery 的删除键），同一部手机里两套规矩。
 *
 * 更要紧的是往后：每个 App 迟早要有自己的开关。没有"一个 App 一页"这个地方，那些开关
 * 只能往列表行上挤，或者散到各自的 App 里去——前者那一行只有 108 像素宽，后者等于没有
 * 统一的入口。所以先把这一页立起来，操作区做成一行一个，以后加开关就是加一行。
 *
 * 操作区：一行一个
 *
 * 现在有两行：上面是快捷键，下面是卸载。安装不在这儿——那是应用商店的事，
 * 那一页管的是"有价钱的、远程来源的"，而这里只列已经装上的。系统 App 的卸载键
 * 是灰的，并写明为什么——不写的话玩家会以为是坏了。
 *
 * 快捷键这一行是【每个 App 各绑各的】，默认未指定：点一下开始等键，按哪个是哪个，
 * 键盘与鼠标键（含侧键）都行，按住 Ctrl / Shift / Alt 再按就是组合键，ESC 清除、
 * 左键算了。撞了车不拦死——先说被谁占了，
 * 再按一次同一个组合就照绑。绑定表与"为什么不做成 KeyMapping"见
 * {@link com.november.mcphone.core.client.AppHotkeys}。
 *
 * 读第三方 App 的元数据一律兜住
 *
 * getVersion / getAuthor / getDescription 都是附属实现的，抛什么全凭它们高兴。
 * 这一页的全部内容都来自这些方法，不兜的话一个坏附属能让整页画不出来——而玩家
 * 恰恰是为了搞清楚"这个 App 有什么毛病"才点进来的。见 {@link #safe}。
 */
public final class AppManagerDetail {

    private static final int PAD = 6;
    private static final int BIG_ICON = 32;
    private static final int BUTTON_H = 16;

    private IPhoneApp app;

    /** 卸载键：第一次点上膛，第二次才真卸。与相册删照片同一条规矩 */
    private boolean uninstallArmed;

    /** 渲染时算出来，点击时复用 */
    private int btnX, btnY, btnW;
    private boolean btnHovered;

    /** 快捷键那一行的位置与悬停，同样是渲染时算、点击时用 */
    private int keyRowY;
    private boolean keyRowHovered;

    /** 这个 App 自己的开关（相机的快门闪光就是一个）。鼠标停在第几行，-1 表示没有 */
    private List<AppOptions.Toggle> options = List.of();
    private int optionsTop;
    private int hoveredOption = -1;

    /**
     * 正在等玩家按一个键。
     *
     * 开着的时候 PhoneScreen 会把【所有】按键先送到这里来（包括 ESC，它在这一页上
     * 的意思是"清除绑定"而不是关机），所以这个状态必须能被离开这一页、点别处、
     * 换 App 之类的动作可靠地关掉，否则玩家会发现手机的 ESC 不灵了。
     */
    private boolean capturingKey;

    /**
     * 撞了车、等玩家再按一次确认的那个组合。
     *
     * 冲突不拦死：说清楚被谁占了，玩家坚持的话照绑。与卸载、删照片同一条规矩——
     * 有代价的事要按两次，但第二次一定做得成。
     */
    private AppHotkeys.Binding pendingForce;

    /** 占着 {@link #pendingForce} 的是谁，画在那一行右边 */
    private String pendingOwner;

    /** 卸载完了，请求退回列表页，等 PhoneScreen 来取 */
    private boolean backRequest;

    /**
     * 正文往上滚了多少像素。
     *
     * 描述是附属自己写的，长度不由我们定；再加上前置与联动各占一行，正文放不下是常态。
     * 原来放不下就直接不画（{@code drawInfoLine} 里那句提前 return），玩家看不到自己
     * 缺哪个前置 —— 而那正是他点进这一页要找的答案。
     */
    private int scrollPx;

    /** 上一帧量出来的滚动上限，正文有多高只有画完才知道 */
    private int maxScroll;

    public void open(IPhoneApp target) {
        this.app = target;
        this.uninstallArmed = false;
        this.btnHovered = false;
        this.backRequest = false;
        this.scrollPx = 0;
        this.maxScroll = 0;
        this.capturingKey = false;
        this.pendingForce = null;
    }

    public void close() {
        this.app = null;
        this.uninstallArmed = false;
        // 离开这一页必须收掉：不收的话 ESC 会一直被当成"清除绑定"吃掉
        this.capturingKey = false;
        this.pendingForce = null;
    }

    public boolean consumeBackRequest() {
        boolean r = backRequest;
        backRequest = false;
        return r;
    }

    public void render(GuiGraphics g, int phoneLeft, int phoneTop,
                       int screenW, int screenH, int statusH, int navH,
                       int mouseX, int mouseY, float partialTick, Font font) {

        int x = phoneLeft + PAD;
        int y = phoneTop + statusH + 4;
        int w = screenW - PAD * 2;
        int bottom = phoneTop + screenH - navH;

        if (app == null) {
            g.drawString(font, Component.translatable("mcphone.gui.app_manager_empty").getString(),
                    x, y, FontPalette.subtle(), false);
            return;
        }

        //  头部：图标 + 名字 + 作者·版本 
        // 用 App 自己的 renderIcon 而不是直接画贴图：图标可以是自己画的、甚至是动的
        final int iconX = x;
        final int iconY = y;
        safeRun(() -> app.renderIcon(g, iconX, iconY, BIG_ICON, partialTick));

        int textX = x + BIG_ICON + 5;
        int textW = w - BIG_ICON - 5;

        String name = safe(() -> app.getDisplayName().getString(), app.getId().toString());
        g.drawString(font, GuiUtil.truncate(font, name, textW), textX, y + 2,
                FontPalette.title(), false);

        String author = safe(app::getAuthor, "");
        String version = safe(app::getVersion, "");
        String meta = author.isBlank() ? "v" + version : author + " · v" + version;
        g.drawString(font, GuiUtil.truncate(font, meta, textW),
                textX, y + 2 + font.lineHeight + 2, FontPalette.subtle(), false);

        y += BIG_ICON + 6;
        g.fill(x, y, x + w, y + 1, PhoneTheme.COLOR_DIVIDER);
        y += 4;

        //  正文：描述 + 由谁提供 + 前置/联动 
        // 操作区的位置先扣出来，正文只能画到它上面为止。行数按这个 App 有几个开关算：
        // 卸载一行、快捷键一行，再加它自己的那几行，最后是系统 App 那句说明
        options = AppOptions.of(app.getId());
        final int actionRows = 2 + options.size();
        final int bodyBottom = bottom - BUTTON_H * actionRows - font.lineHeight - 8 - actionRows * 2;
        final int bodyTop = y;

        scrollPx = Mth.clamp(scrollPx, 0, maxScroll);
        y -= scrollPx;

        // 越界的部分交给 scissor 裁，不再"放不下就不画"——那样卸载键上方会凭空少几行
        GuiUtil.enableScissor(g, x, bodyTop, x + w, bodyBottom);

        String desc = safe(app::getDescription, "");
        if (desc.isBlank()) desc = Component.translatable("mcphone.store.no_description").getString();
        for (var line : font.split(Component.literal(desc), w)) {
            g.drawString(font, line, x, y, FontPalette.body(), false);
            y += font.lineHeight + 1;
        }

        y += 3;
        y = drawInfoLine(g, font, x, y, w,
                Component.translatable("mcphone.gui.app_provider").getString(), providerName());

        for (RequiredMod required : PhoneScreenRegistry.requiredModsOf(app)) {
            y = drawModLine(g, font, x, y, w, "mcphone.gui.app_requires", required);
        }
        for (RequiredMod companion : PhoneScreenRegistry.companionModsOf(app)) {
            y = drawModLine(g, font, x, y, w, "mcphone.gui.app_companion", companion);
        }

        GuiUtil.disableScissor(g);

        maxScroll = Math.max(0, (y + scrollPx) - bodyBottom);

        //  操作区：一行一个，自下而上排 
        renderUninstallButton(g, font, x, bottom, w, mouseX, mouseY);
        renderHotkeyRow(g, font, x, w, mouseX, mouseY);
        renderOptionRows(g, font, x, w, mouseX, mouseY);
    }

    /** 滚轮翻正文。头部与底下那两行操作不跟着滚：它们得一直够得着 */
    public boolean mouseScrolled(double scrollY, Font font) {
        int before = scrollPx;
        scrollPx = Mth.clamp(scrollPx - (int) (scrollY * font.lineHeight * 3), 0, maxScroll);
        return scrollPx != before;
    }

    /** 「标签：值」一行。越界由调用方的 scissor 裁，这里只管画 */
    private static int drawInfoLine(GuiGraphics g, Font font, int x, int y, int w,
                                    String label, String value) {
        g.drawString(font, GuiUtil.truncate(font, label + " " + value, w), x, y,
                FontPalette.subtle(), false);
        return y + font.lineHeight + 1;
    }

    /** 前置 / 联动那几行：模组名 + 装没装 */
    private static int drawModLine(GuiGraphics g, Font font, int x, int y, int w,
                                   String labelKey, RequiredMod mod) {
        boolean loaded = ModPresence.isLoaded(mod.modId());
        String label = Component.translatable(labelKey).getString() + " " + mod.displayName();
        String mark = Component.translatable(loaded
                ? "mcphone.gui.app_mod_present" : "mcphone.gui.app_mod_absent").getString();

        g.drawString(font, GuiUtil.truncate(font, label, w - font.width(mark) - 4), x, y,
                FontPalette.subtle(), false);
        g.drawString(font, mark, x + w - font.width(mark), y,
                loaded ? FontPalette.confirm() : FontPalette.danger(), false);
        return y + font.lineHeight + 1;
    }

    /**
     * 这个 App 自己的开关，一个一行，排在快捷键那一行上面。
     *
     * 谁有开关由各功能自己登记（见 {@link AppOptions}），这一页不认识任何具体的开关——
     * 相机的闪光、以后别的什么，对这里都只是"一行字 + 右边两个字"。
     */
    private void renderOptionRows(GuiGraphics g, Font font, int x, int w, int mouseX, int mouseY) {
        hoveredOption = -1;
        if (options.isEmpty()) return;

        optionsTop = keyRowY - (BUTTON_H + 2) * options.size();

        for (int i = 0; i < options.size(); i++) {
            AppOptions.Toggle option = options.get(i);
            int y = optionsTop + i * (BUTTON_H + 2);

            if (GuiUtil.hit(mouseX, mouseY, x, y, w, BUTTON_H)) {
                hoveredOption = i;
                g.fill(x, y, x + w, y + BUTTON_H, PhoneTheme.COLOR_ROW_HOVER);
            }

            int textY = y + (BUTTON_H - font.lineHeight) / 2;

            // 读一个开关的当前值同样要兜住：getter 抛了不能把整页带走
            boolean on;
            String value;
            try {
                on = option.value();
                value = Component.translatable(option.valueKey()).getString();
            } catch (Throwable t) {
                on = false;
                value = "?";
            }

            int valueW = font.width(value);
            g.drawString(font, value, x + w - valueW - 2, textY,
                    on ? FontPalette.confirm() : FontPalette.dim(), false);

            String label = Component.translatable(option.labelKey()).getString();
            g.drawString(font, GuiUtil.truncate(font, label, w - valueW - 8),
                    x + 2, textY, FontPalette.body(), false);
        }
    }

    /**
     * 快捷键那一行。三种样子：平时是「快捷键 · Ctrl + K」（没绑就是"未指定"），
     * 等键时是「按一个键… · Esc 清除」，撞了车是「再按一次强制 · ⚠ 谁占的」。
     *
     * 位置贴着卸载键上方，所以要在它之后画（btnY 是那边算出来的）。它和卸载键一样
     * 不跟着正文滚——绑键是这一页的操作，不是它的内容。
     */
    private void renderHotkeyRow(GuiGraphics g, Font font, int x, int w, int mouseX, int mouseY) {
        keyRowY = btnY - BUTTON_H - 2;
        keyRowHovered = GuiUtil.hit(mouseX, mouseY, x, keyRowY, w, BUTTON_H);

        if (keyRowHovered || capturingKey) {
            g.fill(x, keyRowY, x + w, keyRowY + BUTTON_H, PhoneTheme.COLOR_ROW_HOVER);
        }

        final int textY = keyRowY + (BUTTON_H - font.lineHeight) / 2;

        String left;
        int leftColor;
        String right;
        int rightColor;

        if (pendingForce != null) {
            // 撞了车：左边写怎么坚持，右边写被谁占了。两截都短，108 像素里放得下
            left = Component.translatable("mcphone.gui.hotkey_force").getString();
            leftColor = FontPalette.armed();
            right = Component.translatable("mcphone.gui.hotkey_conflict", pendingOwner).getString();
            rightColor = FontPalette.danger();
        } else if (capturingKey) {
            left = Component.translatable("mcphone.gui.hotkey_press").getString();
            leftColor = FontPalette.armed();
            // 等键的时候右边写 ESC 干什么用：这一页上它是"清除"，不是"关机"，
            // 不写的话玩家只会按 ESC 想退出，然后发现绑定没了
            right = Component.translatable("mcphone.gui.hotkey_esc_clears").getString();
            rightColor = FontPalette.dim();
        } else {
            left = Component.translatable("mcphone.gui.hotkey").getString();
            leftColor = FontPalette.body();

            AppHotkeys.Binding bound = AppHotkeys.get(app.getId());
            if (bound == null) {
                right = Component.translatable("mcphone.gui.hotkey_none").getString();
                rightColor = FontPalette.dim();
            } else if (AppHotkeys.conflictingMapping(bound) != null) {
                // 绑是绑上了，但它和别处的键位重着。每帧问一次不贵（只有这一页会问），
                // 而且【必须现问】：玩家随时可能在原版界面里把某个键改成这个组合
                right = Component.translatable("mcphone.gui.hotkey_conflict",
                        bound.displayName().getString()).getString();
                rightColor = FontPalette.notice();
            } else {
                right = bound.displayName().getString();
                rightColor = FontPalette.confirm();
            }
        }

        // 右边先按"左边至少留得下"截一刀，再按剩下的宽度截左边。组合键名与模组名
        // 都可能很长，不截的话两截会叠在一起
        int leftMin = Math.min(font.width(left), w / 2);
        right = GuiUtil.truncate(font, right, w - leftMin - 8);
        int rightW = font.width(right);
        g.drawString(font, right, x + w - rightW - 2, textY, rightColor, false);
        g.drawString(font, GuiUtil.truncate(font, left, w - rightW - 8),
                x + 2, textY, leftColor, false);
    }

    /** 正在等玩家按键吗。PhoneScreen 据此把按键抢在 ESC 关机之前送进来 */
    public boolean isCapturingKey() {
        return capturingKey;
    }

    /**
     * 收玩家按的那一下键盘。鼠标那一下走 {@link #captureMouse}。
     *
     * ESC 是清除，与原版「按键设置」里的意思一致——那儿也是按 ESC 解绑，玩家不用
     * 学第二套。
     *
     * 组合键怎么按出来：Ctrl / Shift / Alt 自己按下去【不结束等键】，只有主键那一下
     * 才算数，此刻按住的修饰键一并记下。所以"按住 Ctrl 再按 K"就是 Ctrl+K，和玩家
     * 在别处按组合键的手感一样。
     *
     * 撞了车不拦死，改成【再按一次就照绑】：
     *
     * 一是别的 App 已经绑了它——两个 App 抢同一个键，按下去只有一个开得成，所以要
     * 先说一声；坚持的话后来的赢，前一个自动松开（AppHotkeys.bind 会摘掉旧的那条）。
     * 但占着它的那个 App 要是【没装】就直接抢：它不在管理器的列表里，玩家看不到、
     * 也解不掉那条绑定，为它拦一道只会变成一个谁也解释不了的"这个键不能用"。
     *
     * 二是原版或别的模组的键位占了它。这个更要说：我们这套快捷键不出现在原版的
     * 「按键设置」界面里，玩家日后查不出"按 E 怎么同时开背包和手机"是谁干的。
     * 说完还是他说了算——真按下去时那条 KeyMapping 攒的点击会被倒掉，见
     * {@link com.november.mcphone.core.client.AppHotkeyHandler}。
     */
    public void captureKey(int keyCode, int scanCode) {
        if (app == null) {
            capturingKey = false;
            return;
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            AppHotkeys.clear(app.getId());
            capturingKey = false;
            pendingForce = null;
            return;
        }

        InputConstants.Key key = InputConstants.getKey(keyCode, scanCode);

        // 修饰键本身不成一条绑定：还按着呢，等主键
        if (KeyModifier.isKeyCodeModifier(key)) return;
        if (key.equals(InputConstants.UNKNOWN)) return;

        applyCapture(key);
    }

    /**
     * 等键时按下的鼠标键。原版的按键设置里鼠标键（含侧键）本来就能绑，这里没有理由
     * 不能——第一版只接了键盘那条路，侧键就是从那儿漏掉的。
     *
     * 【左键除外，它是"算了"】：左键是在手机里点东西的那只手，绑给某个 App 的话，
     * 玩家在世界里每次挖方块都会开一次手机；何况等键时总得留一个不用记的退路——
     * 点一下走开就等于反悔，和这一页别处（卸载上膛后点别处即卸下）是同一条规矩。
     * 其余的键（右键、中键、侧键 4/5……）照绑，撞了车照样是"再按一次强制"。
     */
    public void captureMouse(int button) {
        if (app == null) {
            capturingKey = false;
            return;
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            MCphone.LOGGER.info("[MCphone] 绑键：收到鼠标左键，当作取消");
            capturingKey = false;
            pendingForce = null;
            return;
        }

        // 收到的是几号键先记下来。侧键在有些鼠标的驱动里被映射成了键盘按键，
        // 那种情况下这一行不会出现，而是走 captureKey——一眼就能分清是哪种
        MCphone.LOGGER.info("[MCphone] 绑键：收到鼠标第 {} 号键", button);
        applyCapture(InputConstants.Type.MOUSE.getOrCreate(button));
    }

    /** 键盘与鼠标合流的地方：连着此刻按住的修饰键成一条绑定，撞车就上膛等确认 */
    private void applyCapture(InputConstants.Key key) {
        AppHotkeys.Binding binding = AppHotkeys.Binding.of(key, AppHotkeys.activeModifiers());

        // 上膛的那个组合又按了一次＝他知道自己在做什么
        if (binding.equals(pendingForce)) {
            AppHotkeys.bind(app.getId(), binding);
            capturingKey = false;
            pendingForce = null;
            return;
        }

        String owner = ownerOf(binding);
        if (owner != null) {
            MCphone.LOGGER.info("[MCphone] 绑键：{} 已被「{}」占用，等再按一次确认",
                    binding.serialize(), owner);
            pendingForce = binding;
            pendingOwner = owner;
            return;                 // 继续等：可以再按一次坚持，也可以换一个组合
        }

        AppHotkeys.bind(app.getId(), binding);
        capturingKey = false;
        pendingForce = null;
    }

    /** 这个组合现在归谁，没人占就是 null */
    private String ownerOf(AppHotkeys.Binding binding) {
        ResourceLocation taken = AppHotkeys.appFor(binding);
        if (taken != null && !taken.equals(app.getId()) && PhoneScreenRegistry.isInstalled(taken)) {
            return takenAppName(taken);
        }

        KeyMapping conflict = AppHotkeys.conflictingMapping(binding);
        return conflict == null ? null : Component.translatable(conflict.getName()).getString();
    }

    /** 占着这个组合的那个 App 叫什么。它可能是个坏附属，所以照样兜住 */
    private static String takenAppName(ResourceLocation id) {
        IPhoneApp other = PhoneScreenRegistry.getApp(id);
        if (other == null) return id.toString();
        return safe(() -> other.getDisplayName().getString(), id.toString());
    }

    /**
     * 卸载键。系统 App 画成灰的、点不动，下面写一行为什么。
     *
     * 上膛之后字变成「再点一次确认」，颜色也换——玩家得看得出来这一下与上一下不是同一件事。
     */
    private void renderUninstallButton(GuiGraphics g, Font font, int x, int bottom, int w,
                                       int mouseX, int mouseY) {

        boolean system = app.isSystemApp();

        btnX = x;
        btnW = w;
        btnY = bottom - BUTTON_H - font.lineHeight - 2;
        btnHovered = !system && GuiUtil.hit(mouseX, mouseY, btnX, btnY, btnW, BUTTON_H);

        int bg = system ? PhoneTheme.COLOR_BUTTON_DISABLED
                : (btnHovered ? PhoneTheme.COLOR_ROW_HOVER_DANGER : PhoneTheme.COLOR_ROW_HOVER);
        g.fill(btnX, btnY, btnX + btnW, btnY + BUTTON_H, bg);

        String label = Component.translatable(uninstallArmed
                ? "mcphone.gui.uninstall_confirm" : "mcphone.gui.uninstall").getString();
        int color = system ? FontPalette.dim()
                : (uninstallArmed ? FontPalette.dangerArmed() : FontPalette.uninstall());
        g.drawString(font, label, btnX + (btnW - font.width(label)) / 2,
                btnY + (BUTTON_H - font.lineHeight) / 2, color, false);

        if (system) {
            String why = Component.translatable("mcphone.gui.system_app_locked").getString();
            g.drawString(font, GuiUtil.truncate(font, why, w),
                    x, btnY + BUTTON_H + 2, FontPalette.dim(), false);
        }
    }

    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || app == null) return true;

        // 开关那几行：点一下就翻面，没有第二步——翻错了再点一次就回去了，
        // 与卸载那种"做了就回不来"的事不同
        if (hoveredOption >= 0 && hoveredOption < options.size()) {
            AppOptions.Toggle option = options.get(hoveredOption);
            try {
                option.flip();
            } catch (Throwable t) {
                MCphone.LOGGER.warn("[MCphone] App 开关 {} 翻面时抛了: {}", option.labelKey(), t.toString());
            }
            uninstallArmed = false;
            capturingKey = false;
            pendingForce = null;
            return true;
        }

        // 快捷键那一行：点一下开始等键，等键时再点一下就是算了
        if (keyRowHovered) {
            capturingKey = !capturingKey;
            uninstallArmed = false;
            pendingForce = null;
            return true;
        }

        if (!btnHovered) {
            // 点别处＝把上膛的卸载卸下来，也把等键收掉。
            // 与相册的删除键同一条：走开就等于反悔
            uninstallArmed = false;
            capturingKey = false;
            pendingForce = null;
            return true;
        }
        if (app.isSystemApp()) return true;

        if (!uninstallArmed) {
            uninstallArmed = true;
            return true;
        }

        PhoneScreenRegistry.uninstall(app.getId());
        uninstallArmed = false;
        backRequest = true;      // 这个 App 已经不在列表里了，留在它的详情页上没有意义
        return true;
    }

    /** 这个 App 是哪个模组给的：按 id 的命名空间查，查不到就把命名空间本身显示出来 */
    private String providerName() {
        return ModPresence.displayName(app.getId().getNamespace());
    }

    /**
     * 读一个第三方 App 的字符串，抛了就用兜底值。
     *
     * 连 Throwable 一起接：不可用的 App（前置没装）读它的方法会抛 NoClassDefFoundError，
     * 那不是 Exception。PhoneScreenRegistry.requiredModsOf 里已经踩过同一个坑。
     */
    private static String safe(Supplier<String> getter, String fallback) {
        try {
            String value = getter.get();
            return value == null ? fallback : value;
        } catch (Throwable t) {
            return fallback;
        }
    }

    private static void safeRun(Runnable action) {
        try {
            action.run();
        } catch (Throwable t) {
            MCphone.LOGGER.warn("[MCphone] App 管理页里画图标失败: {}", t.toString());
        }
    }
}
