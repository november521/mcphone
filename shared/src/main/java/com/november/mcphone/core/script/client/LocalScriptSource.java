package com.november.mcphone.core.script.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.api.client.store.AppInfo;
import com.november.mcphone.api.client.store.IAppSource;
import com.november.mcphone.api.sdk.SdkGate;
import com.november.mcphone.core.script.pkg.SigCopy;
import com.november.mcphone.core.script.pkg.SigManifest;
import com.november.mcphone.core.script.pkg.TrustState;
import com.november.mcphone.core.script.pkg.TrustStore;
import com.november.mcphone.core.client.PhoneScreenRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 本地脚本来源（施工方案 §14.1）：玩家放进 {@code mcphone/apps/} 的 {@code .vue} 与 zip。
 *
 * <p>商店入口同步回调；定时刷新另由后台扫描产出候选，在客户端主线程提交。
 *
 * <p>脚本首次安装与卸载后重装始终走这个来源。Java App 来源排除脚本适配器，避免重装漏掉签名与 SDK 检查。
 * 定时刷新只启用纯前端包；带后端包的客户端界面保留商店扫描入口，服务端部署仍是独立流程。
 */
public final class LocalScriptSource implements IAppSource {

    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "local_script");

    /** id → 适配器。同一个包只有一个实例：注册表里那个与商店里那个必须是同一个。 */
    private static final LocalScriptCatalog CATALOG = new LocalScriptCatalog(
            PhoneScreenRegistry::getApp, PhoneScreenRegistry::replace, ScriptAppAdapter::onUninstall);

    /** 这一局登记过（或试过）的 id。进世界每次都会扫一遍目录，这张表挡住重复登记的告警。 */
    private static final Set<ResourceLocation> TRIED = new HashSet<>();
    /** 同一份被拒候选只告警一次；每轮仍重查信任和 SDK，不缓存授权结论。 */
    private static final Map<ResourceLocation, ScriptApp> REPORTED = new java.util.HashMap<>();

    @Override
    public ResourceLocation getId() {
        return ID;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("mcphone.store.source.local_script");
    }

    /**
     * 同步回调，<b>只在客户端主线程调</b>。契约允许来源在后台线程干活（{@link IAppSource}），
     * 这一份不行：{@link AppInfo#of} 会问 {@code getIconTexture()}，那条路会把图标传进显存。
     */
    @Override
    public void listAvailable(Consumer<List<AppInfo>> callback) {
        List<AppInfo> out = new ArrayList<>();
        List<ScriptApp> candidates = ScriptAppFolder.scan();
        refresh(candidates, false);
        for (ScriptApp app : candidates) {
            ScriptAppAdapter adapter = CATALOG.get(app.id());
            if (adapter == null || PhoneScreenRegistry.isInstalled(app.id())) continue;
            if (PhoneScreenRegistry.getApp(app.id()) != null
                    && PhoneScreenRegistry.getApp(app.id()) != adapter) continue;
            // 未安装时展示磁盘候选，允许玩家显式接受新作者；不能拿已接受旧版冒充候选。
            ScriptAppAdapter display = new ScriptAppAdapter(app);
            out.add(AppInfo.builder(display.getId(), display.getDisplayName(), ID)
                    .icon(display.getIconTexture())
                    .version(display.getVersion())
                    .author(display.getAuthor())
                    .description(display.getDescription())
                    .blocked(blockedReason(app))
                    .signature(signatureOf(app))
                    .build());
        }
        callback.accept(out);
    }

    @Override
    public void install(AppInfo info, Consumer<IPhoneApp> onSuccess, Consumer<Component> onError) {
        if (PhoneScreenRegistry.isInstalled(info.id())) {
            onError.accept(Component.translatable("mcphone.store.error.install_failed", info.id().toString()));
            return;
        }
        ScriptApp candidate = null;
        for (ScriptApp app : ScriptAppFolder.scan()) {
            if (app.id().equals(info.id())) {
                candidate = app;
                break;
            }
        }
        if (candidate == null) {
            // 玩家在商店开着的时候把文件删了
            onError.accept(Component.translatable("mcphone.store.error.not_found", info.id().toString()));
            return;
        }
        Component blocked = blockedReason(candidate);
        if (blocked != null) {
            // 界面已经把按钮画灰了，这一道是给"不走界面的调用方"的：门控写在两处，
            // 少了哪一处都能让一个用不了的 App 装进主屏
            onError.accept(blocked);
            return;
        }

        // §12.4 的二次确认与确认短语。【判据在这儿，不在按钮的 enabled 上】——
        // IAppSource 是对外接口，任何拿到 AppInfo 的调用方都能直接调 install()，
        // 只把闸写在 AppDetail 里等于没有闸
        TrustState.Verdict v = trustOf(candidate);
        if (v.state() != TrustState.State.TRUSTED
                && CONFIRMED.get(confirmKey(info.id().toString(), v.fingerprint())) != candidate) {
            onError.accept(Component.translatable(SigCopy.keyFor(v.state())));
            return;
        }

        ScriptAppAdapter adapter = CATALOG.observe(candidate, (old, fresh) -> true, ScriptPage::isOpen);
        if (adapter == null || adapter.script() != candidate) {
            onError.accept(Component.translatable("mcphone.store.error.install_failed", info.id().toString()));
            return;
        }

        boolean installed = PhoneScreenRegistry.getApp(info.id()) == adapter
                ? PhoneScreenRegistry.install(info.id()) : PhoneScreenRegistry.install(adapter);
        if (!installed) {
            onError.accept(Component.translatable("mcphone.store.error.install_failed", info.id().toString()));
            return;
        }

        // 装成了才记：TOFU 的「首次见到即记录」记的是玩家真的接受了这个作者
        if (v.fingerprint() != null && adapter.script().pkg() != null) {
            SigManifest sig = SigManifest.parse(adapter.script().pkg().signature());
            trust().record(v.fingerprint(), sig.author(), sig.pubkey(), System.currentTimeMillis());
            trust().trust(v.fingerprint(), info.id().toString());
            trust().save(trustFile());
        }
        onSuccess.accept(adapter);
    }

    /**
     * 这个包的 {@code sdk} 段本机满不满足（§23.4）。满足返回 null。
     *
     * <p>本机版本低于声明 → 标「需要更新 MCphone」并不可安装；高于声明 → 正常，契约只增不减。
     *
     * <p><b>这是 UX，不是边界</b>（§13.8）：这一段整个删掉也只是让商店的按钮不灰，
     * 真正的判定在服务端审批部署那一侧，调的是同一个 {@link SdkGate}。
     */
    /** 信任库落盘的位置（§12.3）。 */
    private static final String TRUST_FILE = "config/mcphone/authors.json";

    /**
     * 这一局的信任库（§12.3 的 TOFU）。<b>惰性从盘上读，改完立刻写回。</b>
     *
     * <p>不落盘的话 TRUSTED / KEY_CHANGED / 封禁三档全是不可达分支 —— 库永远是空的，
     * 判定就只剩 UNSIGNED / UNKNOWN_AUTHOR / INVALID，而前两档都是点一下就装。
     */
    private static TrustStore trust;

    private static Path trustFile() {
        var mc = net.minecraft.client.Minecraft.getInstance();
        Path game = mc == null || mc.gameDirectory == null ? Path.of(".") : mc.gameDirectory.toPath();
        return game.resolve(TRUST_FILE);
    }

    private static TrustStore trust() {
        if (trust == null) trust = TrustStore.load(trustFile());
        return trust;
    }

    /**
     * 玩家在界面上确认过的 {@code "appId 指纹"}。
     *
     * <p><b>放行的判据在这儿，不在按钮的 enabled 上。</b>界面那一层只负责把玩家输入的东西
     * 交给 {@link #confirm}；忘了交的调用方装不上 —— 失败的方向是"装不了"，不是"随便装"。
     */
    private static final Map<String, ScriptApp> CONFIRMED = new java.util.HashMap<>();

    private static String confirmKey(String appId, String fingerprint) {
        return appId + " " + fingerprint;
    }

    /**
     * 界面确认一次（§12.4 的二次确认与确认短语）。
     *
     * <p>短语对不对由 {@link SigCopy#canProceed} 判 —— <b>判据只有那一份</b>，
     * 界面自己再写一份 {@code equalsIgnoreCase} 的话两份迟早对不上。
     *
     * @param typedPhrase 玩家输入的东西；不需要短语的那几档传什么都行
     * @return 确认成功了没有
     */
    @Override
    public boolean confirmSignature(AppInfo info, String typedPhrase) {
        return confirm(info.id(), typedPhrase);
    }

    public static boolean confirm(ResourceLocation appId, String typedPhrase) {
        ScriptApp app = find(appId);
        if (app == null) return false;
        TrustState.Verdict v = trustOf(app);
        if (!SigCopy.canProceed(v, typedPhrase)) return false;
        // 确认绑定本次编译对象；确认后文件换了，即使 id/指纹相同也要重新确认。
        CONFIRMED.put(confirmKey(appId.toString(), v.fingerprint()), app);
        return true;
    }

    private static ScriptApp find(ResourceLocation appId) {
        for (ScriptApp app : ScriptAppFolder.scan()) if (app.id().equals(appId)) return app;
        return null;
    }

    /**
     * 给商店详情页用（§14.5）：按 id 找本机的脚本 App，<b>装没装都找得到</b>；不是脚本 App
     * 时返回 null。详情页在 {@code open()} 时问一次并记住，不要每帧问 —— 它会扫目录。
     */
    public static ScriptApp scriptOf(ResourceLocation appId) {
        return appId == null ? null : find(appId);
    }

    /** 给界面用：判这个包属于哪一档（§12.4）。<b>UI 只渲染，不再判一遍。</b> */
    public static TrustState.Verdict trustOf(ScriptApp app) {
        if (app.pkg() == null) {
            // .vue 单文件没有包，也就没有签名。但它同样会被钉扎顶替，判据与 zip 那条一致
            String pinned = trust().fingerprintFor(app.id().toString());
            return pinned != null
                    ? new TrustState.Verdict(TrustState.State.SIGNATURE_REMOVED, null, pinned, "")
                    : new TrustState.Verdict(TrustState.State.UNSIGNED, null, null, "");
        }
        return TrustState.of(app.pkg(), app.id().toString(), trust());
    }

    /** 把 §12.4 判好的结果整理成界面要的那几格。<b>界面不再判一遍。</b> */
    private static AppInfo.Signature signatureOf(ScriptApp app) {
        TrustState.Verdict v = trustOf(app);
        return new AppInfo.Signature(
                SigCopy.keyFor(v.state()),
                v.fingerprint(),
                v.knownFingerprint(),
                v.state().needsPhrase() ? SigCopy.requiredPhrase(v.fingerprint()) : null,
                !v.state().installable,
                v.state().needsConfirm());
    }

    private static Component blockedReason(ScriptApp app) {
        return blockedReason(app, true);
    }

    private static Component blockedReason(ScriptApp app, boolean report) {
        // 硬拒绝有两档（§12.4）：「签名无效」与「签名被摘掉了」，都不给"仍然继续"。
        // 其余几档要走确认路径，那一道在 install() 里
        TrustState.Verdict v = trustOf(app);
        if (!v.state().installable) {
            if (report) MCphone.LOGGER.warn("[MCphone] 拒绝安装 {}：签名无效（指纹 {}）", app.id(), v.fingerprint());
            return Component.translatable(v.state().messageKey);
        }

        Map<String, Integer> missing = SdkGate.unsatisfied(app.manifest().sdk());
        if (missing.isEmpty()) return null;
        if (report) MCphone.LOGGER.info("[MCphone] 脚本 App {} 要的 SDK 本机给不了: {}（本机 {}）",
                app.id(), app.manifest().sdk(), missing);
        return Component.translatable("mcphone.store.needs_update");
    }

    /**
     * 启动恢复（§3.2）：把<b>存档里记着装过的</b>那几个脚本 App 登记进目录，不改安装状态。
     *
     * <p>必须早于读存档状态：{@code loadState()} 只把"目录里存在的 id"装回已安装集合，
     * 末尾又按当前集合覆写存档。不先登记的话，重启之后已安装的脚本 App 会从主屏消失，
     * 而且那一次覆写会把它从存档里也抹掉 —— 玩家再也装不回原来的位置。
     *
     * <p>只恢复装过的，不给未安装候选提前注册；重装仍由本来源负责，不转到 Java App 来源。
     *
     * @return 这一次新登记了几个
     */
    public static int registerAll() {
        // 先让内建那批进目录：register 不会自己触发 SPI 扫描，而"先注册者胜"。
        // 脚本先进去的话，一个 .vue 声明 someaddon:foo 就能把那个附属模组的 App 挡在门外
        PhoneScreenRegistry.getAppCount();

        Set<ResourceLocation> installed = PhoneScreenRegistry.savedInstalledIds();
        int n = 0;
        for (ScriptApp app : ScriptAppFolder.scan()) {
            // 换过的包在这一步被换进注册表，所以每次进世界都跟得上磁盘上的版本
            ScriptAppAdapter adapter = CATALOG.observe(app, LocalScriptSource::canReplace, ScriptPage::isOpen);
            if (adapter == null) continue;
            if (!installed.contains(app.id())) continue;
            if (!TRIED.add(app.id())) continue;   // 这一局试过了，别让每次进世界都重报一次 id 冲突
            // 【装过一次不等于以后都算数】：判的是磁盘上现在这一份。少了这一道，
            // 把 mcphone/apps/ 里的包换成同 id 的另一份，下次进世界就直接跑起来了
            TrustState.Verdict v = trustOf(app);
            if (blockedReason(adapter.script()) != null || v.state().needsPhrase()) {
                MCphone.LOGGER.warn("[MCphone] 不恢复 {}：磁盘上这一份是「{}」，要去商店重新确认",
                        app.id(), v.state());
                continue;
            }
            if (PhoneScreenRegistry.register(adapter)) n++;
        }
        return n;
    }

    /** 显式刷新已接受目录；列元数据不再自己实现替换协议。 */
    static void refresh(List<ScriptApp> candidates, boolean frontendOnly) {
        for (ScriptApp app : candidates) {
            if (frontendOnly && (!LocalScriptCatalog.isFrontend(app)
                    || !PhoneScreenRegistry.isInstalled(app.id()))) continue;
            ScriptAppAdapter old = CATALOG.get(app.id());
            ScriptAppAdapter accepted = CATALOG.observe(app, LocalScriptSource::canReplace, ScriptPage::isOpen);
            if (old != null && accepted != null && old != accepted
                    && PhoneScreenRegistry.getApp(app.id()) == accepted) {
                MCphone.LOGGER.info("[MCphone] 本地 App {} 已启用 v{}", app.id(), app.manifest().version());
            }
        }
    }

    private static boolean canReplace(ScriptApp old, ScriptApp candidate) {
        TrustState.Verdict before = trustOf(old);
        TrustState.Verdict after = trustOf(candidate);
        Component blocked = blockedReason(candidate, false);
        boolean allowed = canReplace(old, candidate, before, after, blocked != null);
        if (allowed) {
            REPORTED.remove(candidate.id());
        } else if (REPORTED.put(candidate.id(), candidate) != candidate) {
            String reason = blocked != null ? blocked.getString()
                    : LocalScriptCatalog.isFrontend(old) && !LocalScriptCatalog.isFrontend(candidate)
                    ? "纯前端更新不能引入后端文件"
                    : LocalScriptCatalog.isFrontend(old) && !LocalScriptCatalog.newer(old, candidate)
                    ? "版本号未递增" : "作者身份未确认或发生变化";
            MCphone.LOGGER.warn("[MCphone] {} v{} 未启用，保留旧版：{}", candidate.id(),
                    candidate.manifest().version(), reason);
        }
        return allowed;
    }

    /** 纯判据供真实更新链与无窗口回归共用，不碰磁盘、注册表或纹理。 */
    static boolean canReplace(ScriptApp old, ScriptApp candidate, TrustState.Verdict before,
                              TrustState.Verdict after, boolean blocked) {
        if (blocked) return false;
        // 未签名本地 App 沿用玩家首次接受的选择；首次出现的签名作者不能静默获得信任。
        boolean identity = after.state() == TrustState.State.TRUSTED
                && java.util.Objects.equals(before.fingerprint(), after.fingerprint())
                || before.state() == TrustState.State.UNSIGNED && after.state() == TrustState.State.UNSIGNED;
        if (!identity) return false;
        if (LocalScriptCatalog.isFrontend(old)) {
            return LocalScriptCatalog.isFrontend(candidate) && LocalScriptCatalog.newer(old, candidate);
        }
        // 本卡不改变带后端 App 的版本同步协议，保留商店显式扫描的替换路径。
        return true;
    }
}
