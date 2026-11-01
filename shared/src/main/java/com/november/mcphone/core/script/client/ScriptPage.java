package com.november.mcphone.core.script.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneScreen;
import com.november.mcphone.core.script.client.render.FontMeasure;
import com.november.mcphone.core.script.client.render.Frame;
import com.november.mcphone.core.script.client.render.HitTest;
import com.november.mcphone.core.script.client.render.TextInputs;
import com.november.mcphone.core.script.client.render.Renderer;
import com.november.mcphone.core.script.client.tex.AppTextures;
import com.november.mcphone.core.script.layout.ImageSizes;
import com.november.mcphone.core.script.layout.LayoutEngine;
import com.november.mcphone.core.script.layout.LayoutNode;
import com.november.mcphone.core.script.layout.NodeType;
import com.november.mcphone.core.script.layout.TextMeasure;
import com.november.mcphone.core.script.layout.UiState;
import com.november.mcphone.core.script.layout.PageParameters;
import com.november.mcphone.core.script.engine.ClientScript;
import com.november.mcphone.core.script.timer.DelayedCallbacks;
import com.november.mcphone.core.script.http.HttpRequests;
import com.november.mcphone.core.script.http.HttpSession;
import com.november.mcphone.core.script.net.ScriptRpcResult;
import com.november.mcphone.core.script.server.store.LocalStore;
import com.november.mcphone.core.script.sfc.SfcCompiler;
import com.november.mcphone.core.script.sfc.PageStates;
import com.november.mcphone.core.script.sfc.Statements;
import com.november.mcphone.core.script.sfc.TemplateInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * 一个脚本 App 画在手机屏幕里的那一页（施工方案 §8.7、§10）。
 *
 * <p><b>一次编译、每帧只做实例化</b>：模板是装包时编好的（§9.9），这里握着 state 与返回栈，
 * 重排时 {@link TemplateInstance#instantiate} 出一棵普通的 Node 树再交给布局引擎。
 *
 * <p><b>什么时候重排</b>（§7.6）：可用区变了、state 改了、字宽变了、语言变了、贴图那头的 epoch 变了。
 * 主题不在此列 —— 颜色是画的时候才从调色板解析的（见 {@code Renderer.color}），换主题一个几何都不变。
 *
 * <p><b>滚动位置</b>（§7.7）：按节点的 key 存在页面级的表里，重排之后按新的 scrollMax 夹紧，不清零。
 */
public final class ScriptPage implements IPhonePage {

    /** 返回栈上限（§10）。堆满了就把最老的那层丢掉，不让一个 nav 循环把内存吃干净。 */
    public static final int MAX_NAV = 8;

    /** 会叠按下色的那几种。 */
    private static final Set<NodeType> INTERACTIVE = Set.of(NodeType.BUTTON, NodeType.TOGGLE, NodeType.TAB_BAR);

    /**
     * 字体探针：拿它的宽度看字宽变没变。
     *
     * <p>它抓的不是界面缩放 —— 手机那档缩放是整体 pose 缩放，布局里没有一个数跟着变（见 PhoneScale）。
     * 它抓的是「强制 Unicode 字体」那个开关：它当场改字宽，而且不走资源重载，任何 epoch 都看不见。
     */
    private static final String FONT_PROBE = "MCphone 字体";

    private final ScriptApp app;
    private final LongSupplier timerClock;
    private final TextInputs inputs = new TextInputs(this::inputChanged);
    private Supplier<LocalStore> storage;
    private HttpSession http;
    private BooleanSupplier activeAccount = () -> true;
    private DelayedCallbacks timers;
    private UiState timerOwner;
    /** 页面（包括副手 HUD）占用旧包时，自动更新延后，避免释放仍在使用的贴图。 */
    private static final Map<net.minecraft.resources.ResourceLocation, Integer> OPEN = new HashMap<>();
    private boolean opened;

    static boolean isOpen(net.minecraft.resources.ResourceLocation id) {
        return OPEN.containsKey(id);
    }

    /** 当前在哪一页，空串是入口页。 */
    private String current = "";
    /** Back retains exact page instances; changing parameters cannot overwrite a previous entry. */
    private record BackEntry(String name, UiState state, Map<String, Integer> scroll) {}
    private final Deque<BackEntry> back = new ArrayDeque<>();

    /** Latest parameter variant per page; back entries retain their exact originating state. */
    private final PageStates states = new PageStates();

    private UiState state;
    private TemplateInstance instance;
    private TemplateInstance.Tree tree;
    private LayoutNode layout;

    /** 每个 scroll / list 的滚动位置，按 {@link LayoutNode#key} 存（§7.7）。 */
    private final Map<String, Integer> scroll = new HashMap<>();

    // 上一次重排时的取值，见 needsRelayout
    private int lastW = -1;
    private int lastH = -1;
    private int lastRevision = -1;
    private int lastLineHeight = -1;
    private int lastProbe = -1;
    private int lastTextureEpoch = -1;
    private String lastLanguage = "";
    /** 上一次重排时的握手批次 revision：换服/重连之后 backend.available 会变，得重排一次 */
    private long lastHandshakeRevision = Long.MIN_VALUE;

    /** 最近一次脚本调用的结果提示（§15.1 的回调风格里那是 toast），到点自己消失。 */
    private String toast = "";
    private long toastUntilMs;
    private static final long TOAST_MS = 3000L;

    /** 这一页已经关了（{@link #onClose()} 调过）：结果回来时不再有可渲染的地方，只能记日志。 */
    private boolean closed;

    /**
     * 画布的几何与字体，点击时要用。
     *
     * <p>【不存 PhoneCanvas 本身】：它只在那一帧有效（见那个类的注释）。这里存的是几个 int 和
     * 一个 Font 引用 —— 命中判定要的就是这些，{@link HitTest} 为此留了收裸数的重载。
     */
    private int cx;
    private int cy;
    private int cw;
    private int ch;
    private Font font;

    /**
     * 刚按下的那个按钮的 key，以及这个高亮什么时候过期。
     *
     * <p>存 key 不存节点：点一下几乎一定改 state，下一帧就重排出一整棵新的 LayoutNode 树，
     * 存节点的话按下色永远比不中 —— 反过来，只有"点了不生效的按钮"才会亮。
     */
    private String pressedKey;
    private long pressedUntil;

    public ScriptPage(ScriptApp app) {
        this(app, System::nanoTime);
    }

    ScriptPage(ScriptApp app, LongSupplier timerClock) {
        this.app = app;
        this.timerClock = java.util.Objects.requireNonNull(timerClock);
    }

    // ============================================================
    //  生命周期
    // ============================================================

    @Override
    public void onOpen() {
        if (!opened) {
            OPEN.merge(app.id(), 1, Integer::sum);
            opened = true;
        }
        closed = false;
        storage = ScriptLocalStorage.forApp(app.id().toString());
        if (http != null) http.close();
        activeAccount = ScriptLocalStorage.accountGuard();
        http = new HttpSession(activeAccount);
        openPage("");
    }

    /**
     * 这一页被切走了。释放这个包占的显存 —— 一个 App 至多 16 张贴图，留着也没人看（§8.8）。
     *
     * <p>图标不在此列：它是主屏与商店画的，走的是另一张表（{@code AppTextures.releaseIcon}），
     * 卸载时才还。
     */
    @Override
    public void onClose() {
        if (http != null) http.close();
        http = null;
        if (timers != null) timers.close();
        timers = null;
        timerOwner = null;
        inputs.clear();
        if (opened) {
            OPEN.computeIfPresent(app.id(), (id, count) -> count <= 1 ? null : count - 1);
            opened = false;
        }
        AppTextures.release(app.pkg());
        layout = null;
        tree = null;
        scroll.clear();
        states.clear();
        back.clear();
        storage = null;
        closed = true;
    }

    /** Enter with empty parameters; back navigation restores its captured state and scroll positions. */
    private void openPage(String name) {
        openPage(name, PageParameters.EMPTY, null);
    }

    private void openPage(String name, PageParameters parameters, BackEntry retained) {
        SfcCompiler.Page page = app.page(name);
        if (page == null) {
            MCphone.LOGGER.warn("[MCphone] {} 里没有 '{}' 这一页，nav 不动", app.id(), name);
            return;
        }
        if (timers != null) timers.close();
        timers = new DelayedCallbacks(activeAccount, timerClock);
        timerOwner = null;
        current = name;
        inputs.clear();
        pressedKey = null;   // 上一页那个按钮的 key 在这一页可能指到另一个节点，跨页串色
        // 每一页各自的 state：从详情页退回来时，玩家在上一页勾的开关还在
        String file = app.id() + (name.isEmpty() ? "" : "/" + name);
        PageStates.Opened pageState = retained == null
                ? states.open(name, page.template(), file, storage,
                    target -> requests(page.template().script(), target, file), parameters,
                    target -> timers(page.template().script(), target, file))
                : states.restore(name, retained.state(), page.template(), file, storage,
                    target -> requests(page.template().script(), target, file),
                    target -> timers(page.template().script(), target, file));
        state = pageState.state();
        timerOwner = state;
        instance = new TemplateInstance(page.template(), file);
        tree = null;
        layout = null;
        scroll.clear();
        if (retained != null) scroll.putAll(retained.scroll());
        lastRevision = -1;
        for (String warning : pageState.warnings()) MCphone.LOGGER.warn("[MCphone] {}", warning);
        if (!pageState.warnings().isEmpty()) {
            showToast(Component.translatable("mcphone.script.client_function.failed").getString());
        }
    }

    // ============================================================
    //  画
    // ============================================================

    @Override
    public void render(PhoneCanvas c) {
        if (http != null) http.poll();
        cx = c.x();
        cy = c.y();
        cw = c.width();
        ch = c.height();
        font = c.font();

        TextMeasure tm = new FontMeasure(c.font());
        if (needsRelayout(c, tm)) relayout(c.width(), c.height(), tm);
        if (layout == null) return;
        refreshInputs();

        Frame frame = new Frame(layout, c, state, app.pkg(), pressedNow(), inputs);
        Renderer.draw(layout, frame);
        renderToast(c);
    }

    /** 脚本调用的结果提示画在内容区底部（结果回调在主线程，改了提示下一帧就能看到，不需要重排）。 */
    private void renderToast(PhoneCanvas c) {
        if (toast.isEmpty() || System.currentTimeMillis() > toastUntilMs) return;
        Font font = c.font();
        String shown = toast;
        int max = c.width() - 8;
        if (font.width(shown) > max) shown = font.plainSubstrByWidth(shown, max);
        c.graphics().drawString(font, shown,
                c.x() + (c.width() - font.width(shown)) / 2,
                c.y() + c.height() - font.lineHeight - 3,
                FontPalette.notice(), true);
    }

    /** §7.6 的判定。少一个就是 bug，多问一个的代价只是几十微秒。 */
    private boolean needsRelayout(PhoneCanvas c, TextMeasure tm) {
        if (layout == null || instance == null) return true;
        if (c.width() != lastW || c.height() != lastH) return true;
        if (state.revision() != lastRevision) return true;
        if (tm.lineHeight() != lastLineHeight || tm.width(FONT_PROBE) != lastProbe) return true;
        if (AppTextures.epoch() != lastTextureEpoch) return true;
        if (ClientHandshake.revision() != lastHandshakeRevision) return true;
        return !language().equals(lastLanguage);
    }

    private void relayout(int width, int height, TextMeasure tm) {
        SfcCompiler.Page page = app.page(current);
        if (page == null) return;

        keepScroll();
        tree = instance.instantiate(state, ClientHandshake.backendContext(app.id().toString()));
        for (String w : tree.warnings()) MCphone.LOGGER.warn("[MCphone] {}", w);

        layout = LayoutEngine.layout(tree.root(), page.stylesheet(), state, width, height, tm, images());
        restoreScroll(layout);

        lastW = width;
        lastH = height;
        lastRevision = state.revision();
        lastLineHeight = tm.lineHeight();
        lastProbe = tm.width(FONT_PROBE);
        lastTextureEpoch = AppTextures.epoch();
        lastHandshakeRevision = ClientHandshake.revision();
        lastLanguage = language();
    }

    /** 图片原始尺寸的来源（§5.2）。单文件形态没有包，一张图都问不到，image 按占位尺寸排。 */
    private ImageSizes images() {
        return app.pkg() == null ? ImageSizes.NONE : AppTextures.sizes(app.pkg());
    }

    private String language() {
        Minecraft mc = Minecraft.getInstance();
        return mc == null || mc.getLanguageManager() == null ? "" : mc.getLanguageManager().getSelected();
    }

    /** 这一帧要叠按下色的那个节点。高亮只留一会儿：不设上限的话，点一下按钮之后它会一直亮着。 */
    private LayoutNode pressedNow() {
        if (pressedKey == null) return null;
        if (System.currentTimeMillis() > pressedUntil) {
            pressedKey = null;
            return null;
        }
        LayoutNode hit = byKey(layout, pressedKey);
        // 只认可交互的：key 是按树路径拼的，而 v-if 的兄弟一藏，后面的路径整体前移（见 LayoutNode.key），
        // 同一个 key 可能落到另一个节点上。叠错按下色不致命，但没必要叠到一个不能点的东西上
        return hit != null && INTERACTIVE.contains(hit.node.type()) ? hit : null;
    }

    /** 按 key 在这一棵树里找回那个节点（§7.7 的 key 就是为"重排之后还认得出是同一个"留的）。 */
    private static LayoutNode byKey(LayoutNode n, String key) {
        if (n == null) return null;
        if (key.equals(n.key)) return n;
        for (LayoutNode c : n.children) {
            LayoutNode hit = byKey(c, key);
            if (hit != null) return hit;
        }
        return null;
    }

    // ============================================================
    //  滚动位置（§7.7）
    // ============================================================

    private void keepScroll() {
        if (layout != null) collectScroll(layout);
    }

    private void collectScroll(LayoutNode n) {
        if (Renderer.isScroller(n) && !n.key.isEmpty()) scroll.put(n.key, n.scrollY);
        for (LayoutNode c : n.children) collectScroll(c);
    }

    /** 按新的 scrollMax 夹紧，不清零：内容变短了就贴到底，而不是跳回顶上。 */
    private void restoreScroll(LayoutNode n) {
        if (Renderer.isScroller(n)) {
            Integer saved = scroll.get(n.key);
            if (saved != null) n.scrollY = Math.max(0, Math.min(saved, n.scrollMax()));
        }
        for (LayoutNode c : n.children) restoreScroll(c);
    }

    // ============================================================
    //  输入（§8.7）
    // ============================================================

    private void inputChanged(LayoutNode edited) {
        if (closed || tree == null || state == null) return;
        run(tree.input(edited.node, state, storage, requests(), timers()));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (layout == null || tree == null || font == null) return false;
        refreshInputs();
        LayoutNode hit = HitTest.pick(layout, cx, cy, cw, ch, mx, my, new FontMeasure(font));
        if (inputs.mouseClicked(hit, mx, my, button)) return true;
        if (hit == null) return false;

        NodeType type = hit.node.type();
        if (type == NodeType.BUTTON) {
            // 禁用的按钮吃掉这一下：落到宿主手里就成了"点手机里面 = 关机"（§5.3）
            if (!Renderer.isEnabled(hit, state)) return true;
            press(hit);
            run(tree.click(hit.node, state, 0, storage, requests(), timers()));
            return true;
        }
        if (type == NodeType.TOGGLE) {
            press(hit);
            run(tree.click(hit.node, state, 0, storage, requests(), timers()));
            return true;
        }
        if (type == NodeType.TAB_BAR) {
            press(hit);
            run(tree.click(hit.node, state, HitTest.segmentAt(hit, cx, mx), storage, requests(), timers()));
            return true;
        }
        return false;
    }

    private void press(LayoutNode n) {
        pressedKey = n.key;
        pressedUntil = System.currentTimeMillis() + 120L;
    }

    private HttpRequests requests() {
        return requests(app.page(current).template().script(), state,
                app.id() + (current.isEmpty() ? "" : "/" + current));
    }

    private HttpRequests requests(ClientScript script, UiState target, String file) {
        HttpSession session = http;
        Supplier<LocalStore> local = storage;
        return new HttpRequests(session, batch -> {
            if (session == null || session != http || closed) return;
            for (HttpRequests.Request request : batch) {
                session.submit(request, result -> {
                    if (closed || session != http) return;
                    java.util.List<String> warnings = PageStates.respond(script, request, result, target, file, local,
                            requests(script, target, file), timers(script, target, file));
                    for (String warning : warnings) MCphone.LOGGER.warn("[MCphone] {}", warning);
                    if (!warnings.isEmpty()) showToast(Component.translatable("mcphone.script.http_callback.failed").getString());
                });
            }
        });
    }

    private DelayedCallbacks.Batch timers() {
        return timers(app.page(current).template().script(), state,
                app.id() + (current.isEmpty() ? "" : "/" + current));
    }

    private DelayedCallbacks.Batch timers(ClientScript script, UiState target, String file) {
        DelayedCallbacks session = timers;
        if (session == null || (timerOwner != null && timerOwner != target)) return null;
        timerOwner = target;
        Supplier<LocalStore> local = storage;
        return session.batch(callback -> {
            if (closed || session != timers || target != state) return;
            var result = PageStates.delayed(target, script, callback, file, local,
                    ignored -> requests(script, target, file), ignored -> timers(script, target, file));
            for (String warning : result.warnings()) MCphone.LOGGER.warn("[MCphone] {}", warning);
            if (!result.warnings().isEmpty()) showToast(Component.translatable("mcphone.script.client_function.failed").getString());
        });
    }

    /**
     * 一次点击的后果（§9.6）。三个内建动作同时写了的话按 close → back → nav 的先后取一个：
     * 关掉之后再导航没有意义，而方案把先后留给了页面。
     *
     * <p>{@code call(...)} 的服务端动作排在导航之前执行：页面关掉也好、
     * 跳走也好，作者写下的那次调用都得发出去。
     *
     * <p><b>结果的可见性有边界</b>：同一个 App 内 {@code nav} 到别的页仍是同一个
     * {@code ScriptPage} 实例，toast 照常画；但 {@code close()} 之后这个实例不再渲染 ——
     * 结果回来时只在客户端日志留一条（见 {@link #onCallResult}），<b>不再承诺弹提示</b>。
     * 跨页面也可见的提示要等宿主那一层（`UNKNOWN` 这类"钱可能动了"的码最终得走那儿）。
     */
    private void run(Statements.Outcome outcome) {
        if (outcome == null) return;
        for (String w : outcome.warnings()) MCphone.LOGGER.warn("[MCphone] {}", w);
        if (!outcome.applied()) {
            showToast(Component.translatable("mcphone.script.client_function.failed").getString());
            return;
        }

        for (String action : outcome.calls()) callAction(action);

        if (outcome.close()) {
            close();
            return;
        }
        if (outcome.back()) {
            if (!popBack()) close();
            return;
        }
        if (outcome.nav() != null) navTo(outcome.nav(), outcome.navParameters());
    }

    /**
     * 发一条 {@code call('动作')}。字段由 {@link ScriptCall} 从握手状态回填 ——
     * <b>作者改不了</b> epoch/deployRev/摘要，这正是"请求不再停在 epoch 一档"的那一截。
     * 摘要读 {@link ScriptApp#frontendDigest()}（装载时算过一次），不在点击路径上重算。
     */
    private void callAction(String action) {
        ScriptCall.call(app.id().toString(), action, new byte[0], app.frontendDigest(),
                this::onCallResult);
    }

    /** 结果回调：主线程，可以安全地改提示与重排判据。 */
    private void onCallResult(ScriptRpcResult result) {
        String key = result.messageKey();
        if (key == null || key.isEmpty()) key = result.code().defaultMessageKey();
        String text = Component.translatable(key, result.messageArgs().toArray()).getString();
        if (closed) {
            // 页面已经关了：没有可渲染的地方。留一条日志（至少可查），别假装弹了提示
            MCphone.LOGGER.warn("[MCphone] 调用结果回来时页面已关，本次提示只记日志：app={} code={} {}",
                    app.id(), result.code(), text);
            return;
        }
        showToast(text);
    }

    private void showToast(String text) {
        toast = text == null ? "" : text;
        toastUntilMs = System.currentTimeMillis() + TOAST_MS;
    }

    /** 走到另一页，当前这页压进返回栈。 */
    private void navTo(String name, PageParameters parameters) {
        if (app.page(name) == null) {
            MCphone.LOGGER.warn("[MCphone] {} 里没有 '{}' 这一页，nav 不动", app.id(), name);
            return;
        }
        if (name.equals(current) && state.parameters().equals(parameters)) return;
        keepScroll();
        if (back.size() >= MAX_NAV) back.removeLast();   // 丢最老的那层，别让 nav 循环把栈堆满
        back.push(new BackEntry(current, state, Map.copyOf(scroll)));
        openPage(name, parameters, null);
    }

    private boolean popBack() {
        if (back.isEmpty()) return false;
        BackEntry retained = back.pop();
        openPage(retained.name(), retained.state().parameters(), retained);
        return true;
    }

    /** 关掉这个 App 回主屏。宿主的 navigateTo 会顺手调 {@link #onClose()}。 */
    private void close() {
        if (Minecraft.getInstance().screen instanceof PhoneScreen phone) phone.back();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (layout == null || font == null) return false;
        refreshInputs();
        LayoutNode hit = HitTest.pick(layout, cx, cy, cw, ch, mx, my, new FontMeasure(font));
        if (inputs.mouseScrolled(hit, amount)) return true;
        LayoutNode scroller = HitTest.pickScroller(layout, cx, cy, cw, ch, mx, my, new FontMeasure(font));
        // 滚到头就不吃这一下，留给手机页面（§8.7）
        boolean changed = scroller != null && HitTest.scroll(scroller, amount);
        if (changed) refreshInputs();
        return changed;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        refreshInputs();
        return inputs.mouseDragged(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) { return inputs.mouseReleased(button); }

    /** Bring state-driven visibility/enabled changes into effect before the next keyboard event. */
    private void refreshInputs() {
        if (closed || layout == null || font == null) return;
        TextMeasure tm = new FontMeasure(font);
        if (state.revision() != lastRevision || ClientHandshake.revision() != lastHandshakeRevision) relayout(cw, ch, tm);
        inputs.sync(layout, state, font, cx, cy, cw, ch);
        // A max-length change can normalize a bound value; rebuild its text echoes in the same frame.
        if (state.revision() != lastRevision) {
            relayout(cw, ch, tm);
            inputs.sync(layout, state, font, cx, cy, cw, ch);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        refreshInputs();
        return inputs.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        refreshInputs();
        return inputs.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean charTyped(int codePoint, int modifiers) {
        refreshInputs();
        return inputs.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean capturesKeyboard() {
        refreshInputs();
        return inputs.focused();
    }

    @Override
    public void onInputCancelled() { inputs.blur(); }

    @Override
    public void tick() {
        inputs.tick();
        if (http != null) http.poll();
        if (timers != null) timers.poll();
    }

    /** 返回栈里还有东西就退一层，没有就让宿主退回主屏。 */
    @Override
    public boolean onBack() {
        return popBack();
    }
}
