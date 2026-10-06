package com.november.mcphone.core.script.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.api.client.ui.IPhonePage;
import com.november.mcphone.api.client.ui.PhoneCanvas;
import com.november.mcphone.core.client.FontPalette;
import com.november.mcphone.core.client.PhoneScreen;
import com.november.mcphone.core.script.engine.FrontendRuntime;
import com.november.mcphone.core.script.client.render.FontMeasure;
import com.november.mcphone.core.script.client.render.Frame;
import com.november.mcphone.core.script.client.render.HitTest;
import com.november.mcphone.core.script.client.render.Renderer;
import com.november.mcphone.core.script.client.tex.AppTextures;
import com.november.mcphone.core.script.layout.ImageSizes;
import com.november.mcphone.core.script.layout.LayoutEngine;
import com.november.mcphone.core.script.layout.LayoutNode;
import com.november.mcphone.core.script.layout.NodeType;
import com.november.mcphone.core.script.layout.TextMeasure;
import com.november.mcphone.core.script.layout.UiState;
import com.november.mcphone.core.script.layout.TextInputBuffer;
import org.lwjgl.glfw.GLFW;
import com.november.mcphone.core.script.net.ScriptRpcResult;
import com.november.mcphone.core.script.sfc.SfcCompiler;
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

    public String appId() { return app.id().toString(); }

    /** 当前在哪一页，空串是入口页。 */
    private String current = "";
    /** 返回栈，存页名。 */
    private final Deque<String> back = new ArrayDeque<>();

    /** 页名 → 那一页的 state。退回来时接着用，不是从初值重来。 */
    private final Map<String, UiState> states = new HashMap<>();
    private final Map<String, FrontendRuntime> frontends = new HashMap<>();
    private String frontendError = "";
    private long frontendEpoch;

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
    private long lastGuardGeneration = Long.MIN_VALUE;
    private long resultGeneration, lastResultGeneration = Long.MIN_VALUE, openGeneration;
    private Map<String, Object> lastResult = Map.of("code", "", "data", Map.of());
    private Map<String, Object> lastNetwork = Map.of("status", "", "text", "");
    private Map<String,Object> lastVault=Map.of("unlocked",false,"message","");

    /** 最近一次脚本调用的结果提示（§15.1 的回调风格里那是 toast），到点自己消失。 */
    private String toast = "";
    private long toastUntilMs;
    private static final long TOAST_MS = 3000L;

    /** 这一页已经关了（{@link #onClose()} 调过）：结果回来时不再有可渲染的地方，只能记日志。 */
    private boolean closed;
    private boolean switchingServerUpdate;
    private final Deque<Runnable> policyResults=new ArrayDeque<>();

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
    private String focusedInput;
    private TextInputBuffer input;
    private int inputX, inputVisibleStart;
    private boolean inputDragging;
    private record InputGeometry(int x, int start) {}
    private final Map<String, InputGeometry> inputGeometry = new java.util.LinkedHashMap<>();

    /**
     * 刚按下的那个按钮的 key，以及这个高亮什么时候过期。
     *
     * <p>存 key 不存节点：点一下几乎一定改 state，下一帧就重排出一整棵新的 LayoutNode 树，
     * 存节点的话按下色永远比不中 —— 反过来，只有"点了不生效的按钮"才会亮。
     */
    private String pressedKey;
    private long pressedUntil;

    public ScriptPage(ScriptApp app) {
        this.app = app;
    }

    // ============================================================
    //  生命周期
    // ============================================================

    @Override
    public void onOpen() {
        openGeneration++;
        lastResult = Map.of("code", "", "data", Map.of());
        lastNetwork = Map.of("status", "", "text", "");
        resultGeneration++;
        closed = false;
        switchingServerUpdate=false;
        frontendError = "";
        frontendEpoch = ClientHandshake.connectionEpoch();
        ClientRevocations.open(app);
        openPage("");
        ClientGuardState.open(app.id().toString());
    }

    /**
     * 这一页被切走了。释放这个包占的显存 —— 一个 App 至多 16 张贴图，留着也没人看（§8.8）。
     *
     * <p>图标不在此列：它是主屏与商店画的，走的是另一张表（{@code AppTextures.releaseIcon}），
     * 卸载时才还。
     */
    @Override
    public void onClose() {
        ClientRevocations.close(app);
        policyResults.clear();
        openGeneration++;
        blurInput();
        ClientGuardState.close(app.id().toString());
        AppTextures.release(app.pkg());
        layout = null;
        tree = null;
        scroll.clear();
        states.clear();
        frontends.values().forEach(FrontendRuntime::close);
        frontends.clear();
        closed = true;
    }

    /** 切到某一页：重建 state 与实例，滚动位置不跨页保留。 */
    private void openPage(String name) {
        SfcCompiler.Page page = app.page(name);
        if (page == null) {
            MCphone.LOGGER.warn("[MCphone] {} 里没有 '{}' 这一页，nav 不动", app.id(), name);
            return;
        }
        current = name;
        blurInput();
        pressedKey = null;   // 上一页那个按钮的 key 在这一页可能指到另一个节点，跨页串色
        // 每一页各自的 state：从详情页退回来时，玩家在上一页勾的开关还在
        state = states.computeIfAbsent(name, k -> UiState.of(page.template().initialState()));
        if (!page.template().program().handlers().isEmpty()) frontends.computeIfAbsent(name, k ->
                new FrontendRuntime(page.template().program(), state, app.file() + (name.isEmpty() ? "" : "/pages/" + name + ".vue"), frontendBridge(name)));
        instance = new TemplateInstance(page.template(), app.id() + (name.isEmpty() ? "" : "/" + name));
        tree = null;
        layout = null;
        scroll.clear();
        lastRevision = -1;
    }

    // ============================================================
    //  画
    // ============================================================

    @Override
    public void render(PhoneCanvas c) {
        ClientGuardState.open(app.id().toString());
        cx = c.x();
        cy = c.y();
        cw = c.width();
        ch = c.height();
        font = c.font();
        if(ClientServerUpdates.required(app)){
            frontends.values().forEach(FrontendRuntime::discardCallbacks);blurInput();
            c.graphics().drawString(font,Component.translatable("mcphone.server_update.required"),cx+3,cy+4,c.style().bodyColor(),false);
            if(!switchingServerUpdate){switchingServerUpdate=true;Minecraft.getInstance().execute(()->{if(!closed&&Minecraft.getInstance().screen instanceof PhoneScreen phone)phone.openAddonPage(new ServerUpdatePage(app));});}
            return;
        }
        if (frontendEpoch != ClientHandshake.connectionEpoch()) {
            frontends.values().forEach(FrontendRuntime::discardCallbacks);
            policyResults.clear();
            frontendEpoch = ClientHandshake.connectionEpoch();
        }
        Component revoked=ClientRevocations.blocked(app);if(revoked!=null){policyResults.clear();frontends.values().forEach(FrontendRuntime::discardCallbacks);blurInput();c.graphics().fill(cx,cy,cx+cw,cy+ch,0xFF492929);int py=cy+4;for(var line:c.font().split(revoked,Math.max(1,cw-8))){if(py+font.lineHeight>=cy+ch-27)break;c.graphics().drawString(font,line,cx+4,py,0xFFFFCCCC,false);py+=font.lineHeight+2;}c.graphics().drawString(font,Component.translatable("mcphone.update.check_or_contact"),cx+3,cy+ch-19,0xFFFFFFFF,false);return;}
        if(ClientRevocations.checking(app)){blurInput();c.graphics().drawString(font,Component.translatable("mcphone.update.policy_checking"),cx+3,cy+4,c.style().bodyColor(),false);return;}
        while(!policyResults.isEmpty()&&!inputBlocked())policyResults.removeFirst().run();
        if (!frontendError.isEmpty()) {
            c.graphics().fill(cx, cy, cx + cw, cy + ch, 0xFF492929);
            int py = cy + 4;
            for (var line : font.split(Component.literal(frontendError), Math.max(1, cw - 8))) {
                if (py + font.lineHeight >= cy + ch - 8) break;
                c.graphics().drawString(font, line, cx + 4, py, 0xFFFFCCCC, false); py += font.lineHeight + 2;
            }
            return;
        }

        TextMeasure tm = new FontMeasure(c.font());
        if (needsRelayout(c, tm)) relayout(c, tm);
        if (layout == null) return;

        Frame frame = new Frame(layout, c, state, app.pkg(), pressedNow());
        frame.inputPainter = this::paintInput;
        LayoutNode focused = byKey(layout, focusedInput);
        if (focusedInput != null && (focused == null || focused.node.type() != NodeType.TEXT_INPUT
                || !Renderer.isEnabled(focused, state))) blurInput();
        inputGeometry.clear();
        Renderer.draw(layout, frame);
        if(quotaHelp()){c.graphics().fill(cx,cy+ch-30,cx+cw,cy+ch-15,0xFF445566);c.graphics().drawString(c.font(),c.font().plainSubstrByWidth("查看存储、邮箱和托管",cw-8),cx+4,cy+ch-27,0xFFFFFFFF,false);}
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
        if (ClientGuardState.generation() != lastGuardGeneration) return true;
        if (resultGeneration != lastResultGeneration) return true;
        return !language().equals(lastLanguage);
    }

    private void relayout(PhoneCanvas c, TextMeasure tm) {
        SfcCompiler.Page page = app.page(current);
        if (page == null) return;

        keepScroll();
        var backend = new java.util.LinkedHashMap<String, Object>(ClientHandshake.backendValues(app.id().toString()));
        backend.put("result", lastResult);
        backend.put("network", lastNetwork);
        backend.put("vault",lastVault);
        tree = instance.instantiate(state, Map.of("backend", java.util.Collections.unmodifiableMap(backend)));
        for (String w : tree.warnings()) MCphone.LOGGER.warn("[MCphone] {}", w);

        layout = LayoutEngine.layout(tree.root(), page.stylesheet(), state, c.width(), c.height(), tm, images());
        restoreScroll(layout);

        lastW = c.width();
        lastH = c.height();
        lastRevision = state.revision();
        lastLineHeight = tm.lineHeight();
        lastProbe = tm.width(FONT_PROBE);
        lastTextureEpoch = AppTextures.epoch();
        lastHandshakeRevision = ClientHandshake.revision();
        lastGuardGeneration = ClientGuardState.generation();
        lastResultGeneration = resultGeneration;
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
        if (n == null || key == null) return null;
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

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (inputBlocked()) { blurInput(); return true; }
        if(button==0&&quotaHelp()&&mx>=cx&&mx<cx+cw&&my>=cy+ch-30&&my<cy+ch-15){if(Minecraft.getInstance().screen instanceof com.november.mcphone.core.client.PhoneScreen phone)phone.openAddonPage(new AppStoragePage(app.id().toString()));return true;}
        if (layout == null || tree == null || font == null) return false;
        LayoutNode hit = HitTest.pick(layout, cx, cy, cw, ch, mx, my, new FontMeasure(font));
        if (hit == null) { blurInput(); return false; }

        NodeType type = hit.node.type();
        if (type == NodeType.TEXT_INPUT) {
            if (!Renderer.isEnabled(hit, state)) { blurInput(); return true; }
            if (!hit.key.equals(focusedInput)) {
                focusedInput = hit.key;
                input = new TextInputBuffer(state.getString(hit.node.str("bind", "")), hit.node.num("max-length", 256));
            }
            InputGeometry geometry = inputGeometry.get(hit.key);
            if (geometry != null) { inputX = geometry.x(); inputVisibleStart = geometry.start(); }
            positionInput(mx, net.minecraft.client.gui.screens.Screen.hasShiftDown()); inputDragging = button == 0;
            return true;
        }
        blurInput();
        if (type == NodeType.BUTTON) {
            // 禁用的按钮吃掉这一下：落到宿主手里就成了"点手机里面 = 关机"（§5.3）
            if (!Renderer.isEnabled(hit, state)) return true;
            press(hit);
            run(tree.click(hit.node, state, 0));
            return true;
        }
        if (type == NodeType.TOGGLE) {
            press(hit);
            run(tree.click(hit.node, state, 0));
            return true;
        }
        if (type == NodeType.TAB_BAR) {
            press(hit);
            run(tree.click(hit.node, state, HitTest.segmentAt(hit, cx, mx)));
            return true;
        }
        return false;
    }

    private void press(LayoutNode n) {
        pressedKey = n.key;
        pressedUntil = System.currentTimeMillis() + 120L;
    }

    /**
     * 一次点击的后果（§9.6）。三个内建动作同时写了的话按 close → back → nav 的先后取一个：
     * 关掉之后再导航没有意义，而方案把先后留给了页面。
     *
     * <p>{@code call(...)} 是唯一出网的语句（§15.1），排在导航之前执行：页面关掉也好、
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
        if (!outcome.applied()) return;

        for (Statements.HandlerRequest handler : outcome.handlers()) {
            FrontendRuntime runtime = frontends.get(current);
            if (runtime != null) runtime.invoke(handler.name(), handler.arguments());
            if (closed || !frontendError.isEmpty()) return;
        }

        for (Statements.CallRequest request : outcome.requests()) callAction(request);
        for(Statements.SealedRequest request:outcome.sealedRequests()) {
            long generation=openGeneration;UiState target=state;
            java.util.function.Consumer<String> message=text->{if(generation!=openGeneration||closed)return;lastVault=Map.of("unlocked",ClientVault.unlocked(),"message",text);resultGeneration++;};
            if(request.write())ClientVault.put(app.id().toString(),request.key(),request.plaintext(),message);
            else ClientVault.get(app.id().toString(),request.key(),text->{if(generation!=openGeneration||closed)return;
                String why=target.writeProblem(request.stateKey(),text);if(why!=null){message.accept("解密值不能放入声明的 state");return;}target.set(request.stateKey(),text);message.accept("密文已在本机解密");},message);
        }
        for (Statements.FetchRequest request : outcome.networkRequests()) {
            long generation = openGeneration;
            ClientNetwork.fetch(app, request.url(), request.authorization(), request.offset(), response -> {
                if (generation != openGeneration || closed) return;
                lastNetwork = response; resultGeneration++;
            });
        }

        if (outcome.close()) {
            close();
            return;
        }
        if (outcome.back()) {
            if (!popBack()) close();
            return;
        }
        if (outcome.nav() != null) navTo(outcome.nav());
    }

    /**
     * 发一条 {@code call('动作')}。字段由 {@link ScriptCall} 从握手状态回填 ——
     * <b>作者改不了</b> epoch/deployRev/摘要，这正是"请求不再停在 epoch 一档"的那一截。
     * 摘要读 {@link ScriptApp#frontendDigest()}（装载时算过一次），不在点击路径上重算。
     */
    private void callAction(Statements.CallRequest request) {
        callAction(request, null);
    }

    private void callAction(Statements.CallRequest request, java.util.function.Consumer<Map<String, Object>> callback) {
        Component revoked=ClientRevocations.blocked(app);if(revoked!=null){toast=revoked.getString();toastUntilMs=System.currentTimeMillis()+TOAST_MS;return;}
        long generation = openGeneration;
        long epoch=ClientHandshake.connectionEpoch();java.util.UUID server=ClientHandshake.serverId();
        ScriptCall.call(app.id().toString(), request.action(), request.paramsJson().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                app.frontendDigest(),app.pkg()==null?"":app.pkg().digest(), result -> {
                    if(generation!=openGeneration||epoch!=ClientHandshake.connectionEpoch()||!java.util.Objects.equals(server,ClientHandshake.serverId())||closed||!frontendError.isEmpty()||ClientRevocations.blocked(app)!=null||ClientServerUpdates.required(app))return;
                    Runnable delivery=()->{if(generation==openGeneration&&!inputBlocked()){onCallResult(result);if(callback!=null)callback.accept(lastResult);}};
                    // 短暂的原生策略核对不吞掉已经收到的钱包结果；最多四个在飞，最终撤销才丢弃。
                    if(ClientRevocations.checking(app)){if(policyResults.size()<ScriptCall.MAX_IN_FLIGHT)policyResults.addLast(delivery);else{frontendError="等待策略核对的结果已满";frontends.values().forEach(FrontendRuntime::discardCallbacks);}}
                    else delivery.run();
                });
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
        Object data;
        try { data = com.november.mcphone.core.script.JsonValues.decode(result.data()); }
        catch (IllegalArgumentException e) { data = Map.of(); }
        var view = new java.util.LinkedHashMap<String, Object>();
        view.put("code", result.code().name()); view.put("data", data);
        view.put("ok", result.code() == com.november.mcphone.core.script.net.ScriptErrorCode.OK);
        view.put("message", text);
        view.put("requestId", Long.toString(result.requestId()));
        view.put("retryAfterMs", Long.toString(result.retryAfterMs()));
        view.put("stateRevision", Long.toString(result.stateRevision()));
        lastResult = java.util.Collections.unmodifiableMap(view);
        resultGeneration++;
        showToast(text);
    }

    private void showToast(String text) {
        toast = text == null ? "" : text;
        toastUntilMs = System.currentTimeMillis() + TOAST_MS;
    }

    private boolean inputBlocked() { return closed || !frontendError.isEmpty() || ClientRevocations.blocked(app) != null || ClientRevocations.checking(app) || ClientServerUpdates.required(app); }
    private boolean quotaHelp(){Object code=lastResult.get("code");return "QUOTA".equals(code)||"INVENTORY_FULL".equals(code);}
    private void deliverFrontend(long generation,Runnable result){
        if(generation!=openGeneration||closed||!frontendError.isEmpty()||ClientRevocations.blocked(app)!=null||ClientServerUpdates.required(app))return;
        Runnable delivery=()->{if(generation==openGeneration&&!inputBlocked())result.run();};
        if(ClientRevocations.checking(app)){if(policyResults.size()<ScriptCall.MAX_IN_FLIGHT)policyResults.addLast(delivery);else{frontendError="等待策略核对的结果已满";frontends.values().forEach(FrontendRuntime::discardCallbacks);}}else delivery.run();
    }

    private Map<String, Object> frontendBackend() {
        var backend = new java.util.LinkedHashMap<String, Object>(ClientHandshake.backendValues(app.id().toString()));
        backend.put("result", lastResult); backend.put("network", lastNetwork); backend.put("vault", lastVault);
        return java.util.Collections.unmodifiableMap(backend);
    }

    private FrontendRuntime.Bridge frontendBridge(String pageName) {
        return new FrontendRuntime.Bridge() {
            @Override public void call(String action, String params, java.util.function.Consumer<Map<String,Object>> callback) {
                if (!inputBlocked()) callAction(new Statements.CallRequest(action, params), callback);
            }
            @Override public Map<String,Object> backend() { return frontendBackend(); }
            @Override public int textInputLimit(String key) { return inputLimit(layout,key); }
            @Override public boolean allowRetained(long bytes) {
                long total = bytes;
                for (var entry : frontends.entrySet()) if (!entry.getKey().equals(pageName)) total += entry.getValue().retainedBytes();
                return total <= com.november.mcphone.core.script.engine.AppScope.MAX_RETAINED_CHARS;
            }
            @Override public void fetch(String url, String authorization, int offset, java.util.function.Consumer<Map<String,Object>> callback) {
                long generation = openGeneration;
                ClientNetwork.fetch(app, url, authorization, offset, response -> {
                    if(!"PENDING".equals(response.get("status")))deliverFrontend(generation,()->{lastNetwork=response;resultGeneration++;callback.accept(response);});
                });
            }
            @Override public void sealed(boolean write, String key, String text, java.util.function.Consumer<Map<String,Object>> callback) {
                long generation = openGeneration;
                java.util.function.BiConsumer<Boolean,String> message = (ok, value) -> {
                    deliverFrontend(generation,()->callback.accept(Map.of("ok", ok, "message", value, "data", Map.of())));
                };
                if (write) ClientVault.put(app.id().toString(), key, text, message);
                else ClientVault.get(app.id().toString(), key, value -> {
                    deliverFrontend(generation,()->callback.accept(Map.of("ok", true, "message", "", "data", Map.of("text", value))));
                }, value -> message.accept(false, value));
            }
            @Override public void image(String url, String authorization, java.util.function.Consumer<Map<String,Object>> callback) {
                long generation = openGeneration;
                if (inputBlocked()) return;
                ClientNetwork.image(app, url, authorization, () -> generation == openGeneration && !closed && frontendError.isEmpty() && ClientRevocations.blocked(app)==null && !ClientServerUpdates.required(app), response -> {
                    if(!"PENDING".equals(response.get("status")))deliverFrontend(generation,()->callback.accept(response));
                });
            }
            @Override public void imageBytes(String base64, java.util.function.Consumer<Map<String,Object>> callback) {
                if (!inputBlocked()) callback.accept(ClientNetwork.imageBytes(app, base64));
            }
            @Override public void toast(String text) { if (!inputBlocked()) showToast(text); }
            @Override public void navigate(String page) { if (!inputBlocked()) navTo(page); }
            @Override public void back() { if (!inputBlocked() && !popBack()) ScriptPage.this.close(); }
            @Override public void closePage() { ScriptPage.this.close(); }
            @Override public void failed(String text) {
                if (frontendError.isEmpty()) { frontendError = text; blurInput(); MCphone.LOGGER.warn("[MCphone] 前端脚本停止：{}", text); }
            }
        };
    }

    /** 走到另一页，当前这页压进返回栈。 */
    private void navTo(String name) {
        if (app.page(name) == null) {
            MCphone.LOGGER.warn("[MCphone] {} 里没有 '{}' 这一页，nav 不动", app.id(), name);
            return;
        }
        if (name.equals(current)) return;
        if (back.size() >= MAX_NAV) back.removeLast();   // 丢最老的那层，别让 nav 循环把栈堆满
        back.push(current);
        openPage(name);
    }

    private boolean popBack() {
        if (back.isEmpty()) return false;
        openPage(back.pop());
        return true;
    }

    /** 关掉这个 App 回主屏。宿主的 navigateTo 会顺手调 {@link #onClose()}。 */
    private void close() {
        if (Minecraft.getInstance().screen instanceof PhoneScreen phone) phone.back();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        if (inputBlocked()) return true;
        if (layout == null || font == null) return false;
        LayoutNode scroller = HitTest.pickScroller(layout, cx, cy, cw, ch, mx, my, new FontMeasure(font));
        // 滚到头就不吃这一下，留给手机页面（§8.7）
        return scroller != null && HitTest.scroll(scroller, amount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!capturesKeyboard()) return false;
        boolean ctrl = net.minecraft.client.gui.screens.Screen.hasControlDown();
        boolean shift = net.minecraft.client.gui.screens.Screen.hasShiftDown();
        if (keyCode == GLFW.GLFW_KEY_TAB) { cycleInput(shift); return true; }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            blurInput(); return true;
        }
        if (ctrl && keyCode == GLFW.GLFW_KEY_A) input.selectAll();
        else if (ctrl && (keyCode == GLFW.GLFW_KEY_C || keyCode == GLFW.GLFW_KEY_X)) {
            Minecraft.getInstance().keyboardHandler.setClipboard(input.selected());
            if (keyCode == GLFW.GLFW_KEY_X) input.replace("");
        } else if (ctrl && keyCode == GLFW.GLFW_KEY_V) input.replace(Minecraft.getInstance().keyboardHandler.getClipboard());
        else if (keyCode == GLFW.GLFW_KEY_LEFT) input.move(-1, shift);
        else if (keyCode == GLFW.GLFW_KEY_RIGHT) input.move(1, shift);
        else if (keyCode == GLFW.GLFW_KEY_HOME) input.moveTo(0, shift);
        else if (keyCode == GLFW.GLFW_KEY_END) input.moveTo(input.text().length(), shift);
        else if (keyCode == GLFW.GLFW_KEY_BACKSPACE) input.delete(true);
        else if (keyCode == GLFW.GLFW_KEY_DELETE) input.delete(false);
        syncInput();
        return true;
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (!capturesKeyboard()) return false;
        input.type(codePoint); syncInput(); return true;
    }

    @Override
    public boolean capturesKeyboard() {
        return !inputBlocked() && focusedInput != null && input != null;
    }

    private void blurInput() { focusedInput = null; input = null; inputVisibleStart = 0; inputDragging = false; }

    private void cycleInput(boolean backwards) {
        var keys = inputGeometry.keySet().stream().filter(key -> {
            LayoutNode node = byKey(layout, key); return node != null && Renderer.isEnabled(node, state);
        }).toList();
        if (keys.isEmpty()) { blurInput(); return; }
        int at = keys.indexOf(focusedInput), next = Math.floorMod(at + (backwards ? -1 : 1), keys.size());
        focusedInput = keys.get(next); LayoutNode node = byKey(layout, focusedInput);
        input = new TextInputBuffer(state.getString(node.node.str("bind", "")), node.node.num("max-length", 256));
        InputGeometry geometry = inputGeometry.get(focusedInput); inputX = geometry.x(); inputVisibleStart = geometry.start(); inputDragging = false;
    }

    private void positionInput(double mx, boolean select) {
        if (input == null || font == null) return;
        String text = input.text(); int start = Math.min(inputVisibleStart, text.length()), at = start;
        while (at < text.length()) {
            int next = text.offsetByCodePoints(at, 1);
            int before = font.width(text.substring(start, at)), after = font.width(text.substring(start, next));
            if (mx - inputX < (before + after) / 2.0) break;
            at = next;
        }
        input.moveTo(at, select);
    }

    @Override public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!inputDragging || button != 0 || !capturesKeyboard()) return false;
        positionInput(mx, true); return true;
    }
    @Override public boolean mouseReleased(double mx, double my, int button) {
        boolean handled = inputDragging && button == 0; if (button == 0) inputDragging = false; return handled;
    }

    private void syncInput() {
        LayoutNode node = byKey(layout, focusedInput);
        if (node != null && node.node.type() == NodeType.TEXT_INPUT)
            state.set(node.node.str("bind", ""), input.text());
    }
    private static int inputLimit(LayoutNode node,String key){if(node==null)return 0;int limit=node.node.type()==NodeType.TEXT_INPUT&&key.equals(node.node.str("bind",""))?node.node.num("max-length",256):0;for(LayoutNode child:node.children){int next=inputLimit(child,key);if(next>0)limit=limit==0?next:Math.min(limit,next);}return limit;}

    /** 在 Renderer 的裁剪栈内部画；输入框在 scroll/list 内也不会越界。 */
    private void paintInput(LayoutNode node, PhoneCanvas c, int x, int y, int color) {
        boolean focused = node.key.equals(focusedInput);
        String text = focused ? input.text() : state.getString(node.node.str("bind", ""));
        int px = x + 3, py = y + 3, width = Math.max(0, node.w - 6);
        c.graphics().fill(x, y, x + node.w, y + node.h, c.style().buttonColor());
        int start = 0;
        if (focused) {
            while (start < input.cursor() && c.font().width(text.substring(start, input.cursor())) > width - 2)
                start = text.offsetByCodePoints(start, 1);
            inputX = px; inputVisibleStart = start;
        }
        int first = start;
        if (y + node.h > cy && y < cy + ch && x + node.w > cx && x < cx + cw)
            inputGeometry.put(node.key, new InputGeometry(px, start));
        String shown = text.isEmpty() ? node.node.str("placeholder", "") : text.substring(start);
        c.clipped(x, y, node.w, node.h, () -> {
            if (focused && input.start() != input.end()) {
                int sx = px + c.font().width(text.substring(first, Math.max(first, input.start())));
                int ex = px + c.font().width(text.substring(first, Math.max(first, input.end())));
                c.graphics().fill(sx, py, ex, py + c.font().lineHeight, 0x805080D0);
            }
            c.graphics().drawString(c.font(), shown, px, py, text.isEmpty() ? c.style().subtleColor() : color, false);
            if (focused && (System.currentTimeMillis() / 500L) % 2 == 0) {
                int cursorX = px + c.font().width(text.substring(first, input.cursor()));
                c.graphics().fill(cursorX, py, cursorX + 1, py + c.font().lineHeight, color);
            }
        });
    }

    /** 返回栈里还有东西就退一层，没有就让宿主退回主屏。 */
    @Override
    public boolean onBack() {
        if (capturesKeyboard()) { blurInput(); return true; }
        return popBack();
    }
}
