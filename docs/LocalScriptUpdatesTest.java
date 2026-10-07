package com.november.mcphone.core.script.client;

import com.november.mcphone.api.client.app.IPhoneApp;
import com.november.mcphone.core.script.pkg.TrustState;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/** 运行真实目录提交协议：失败、页面占用、冲突都不能换掉旧版或提前释放资源。 */
public final class LocalScriptUpdatesTest {
    private static int checks;

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }

    private static ScriptApp app(String version) {
        return ScriptAppFolder.read("demo.vue", ScriptAppLoadTest.vue("")
                .replace("1.0.0", version).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public static void main(String[] args) throws Exception {
        Map<ResourceLocation, IPhoneApp> registry = new HashMap<>();
        int[] releases = {0};
        boolean[] failCommit = {false};
        LocalScriptCatalog catalog = new LocalScriptCatalog(registry::get, (old, fresh) -> {
            if (failCommit[0] || registry.get(old.getId()) != old) return false;
            registry.put(fresh.getId(), fresh);
            return true;
        }, old -> {
            check(registry.get(old.getId()) != old, "必须先提交注册表再释放旧资源");
            releases[0]++;
        });
        ScriptApp one = app("1.0.0");
        ScriptApp two = app("1.1.0");
        Map<ResourceLocation, ScriptApp> observed = new HashMap<>();
        check(LocalScriptUpdates.stableCandidates(java.util.List.of(one), observed).isEmpty(), "第一轮候选不立即启用");
        check(LocalScriptUpdates.stableCandidates(java.util.List.of(one), observed).equals(java.util.List.of(one)),
                "连续两轮相同内容进入启用阶段");
        check(LocalScriptUpdates.stableCandidates(java.util.List.of(two), observed).isEmpty(), "变化后的内容重新等待稳定");
        LocalScriptUpdates.stableCandidates(java.util.List.of(), observed);
        check(LocalScriptUpdates.stableCandidates(java.util.List.of(two), observed).isEmpty(), "半包或删除打断稳定记录");
        ScriptAppAdapter old = catalog.observe(one, (a, b) -> true, id -> false);
        check(registry.isEmpty(), "发现新文件不能直接注册或安装");
        registry.put(one.id(), old);
        check(catalog.observe(two, (a, b) -> false, id -> false) == old, "拒绝候选保留旧版");
        check(releases[0] == 0 && registry.get(one.id()) == old, "拒绝不能释放或提交");
        check(catalog.observe(two, (a, b) -> true, id -> true) == old, "活动页面延后更新");
        check(releases[0] == 0, "页面占用不能释放贴图");
        failCommit[0] = true;
        check(catalog.observe(two, (a, b) -> true, id -> false) == old, "注册表失败保留旧适配器");
        check(catalog.get(one.id()) == old && releases[0] == 0, "失败不修改已接受目录");
        failCommit[0] = false;
        ScriptAppAdapter fresh = catalog.observe(two, (a, b) -> true, id -> false);
        check(fresh.script() == two && registry.get(one.id()) == fresh, "关页后重试启用新版");
        check(releases[0] == 1, "成功恰好释放一次");
        check(catalog.observe(two, (a, b) -> true, id -> false) == fresh && releases[0] == 1,
                "重复扫描相同对象不重复释放");
        ScriptAppAdapter otherOwner = new ScriptAppAdapter(one);
        registry.put(one.id(), otherOwner);
        check(catalog.observe(app("2.0.0"), (a, b) -> true, id -> false) == fresh,
                "不能抢占其他来源的同名 App");
        check(registry.get(one.id()) == otherOwner && releases[0] == 1, "冲突不触碰他人的注册");

        ScriptPage page = new ScriptPage(one);
        ScriptPage hud = new ScriptPage(one);
        page.onOpen();
        page.onOpen();
        hud.onOpen();
        check(ScriptPage.isOpen(one.id()), "真实页面打开时登记占用");
        page.onClose();
        page.onClose();
        check(ScriptPage.isOpen(one.id()), "重复关页不误减 HUD 的占用");
        hud.onClose();
        check(!ScriptPage.isOpen(one.id()), "全部页面关闭后允许切换");

        TrustState.Verdict unsigned = verdict(TrustState.State.UNSIGNED, null);
        TrustState.Verdict trusted = verdict(TrustState.State.TRUSTED, "author-a");
        check(LocalScriptSource.canReplace(one, two, unsigned, unsigned, false), "已接受未签名本地 App 可递增更新");
        check(!LocalScriptSource.canReplace(one, two, unsigned, unsigned, true), "SDK 不满足不能自动更新");
        check(!LocalScriptSource.canReplace(two, one, unsigned, unsigned, false), "低版本不能自动启用");
        check(!LocalScriptSource.canReplace(one, app("1.0.0"), unsigned, unsigned, false), "同版本不静默替换");
        check(LocalScriptSource.canReplace(one, two, trusted, trusted, false), "同一可信作者可更新");
        check(!LocalScriptSource.canReplace(one, two, trusted,
                verdict(TrustState.State.TRUSTED, "author-b"), false), "其他可信作者也不能顶替身份");
        check(!LocalScriptSource.canReplace(one, two, unsigned,
                verdict(TrustState.State.UNKNOWN_AUTHOR, "author-a"), false), "新作者需要人工确认");
        check(!LocalScriptSource.canReplace(one, two, trusted,
                verdict(TrustState.State.SIGNATURE_REMOVED, null), false), "签名降级不能更新");
        check(LocalScriptCatalog.newer(one, app("1.10.0")), "按数字比较版本，不按字符串");
        check(LocalScriptCatalog.newer(one, app("999999999999999999999.0.0")), "大版本号不溢出");
        Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        entries.put("manifest.json", ScriptAppLoadTest.ZIP_MANIFEST.replace("1.0.0", "2.0.0")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        entries.put("app.vue", ScriptAppLoadTest.PAGE_VUE.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        entries.put("assets/icon.png", ScriptAppLoadTest.png());
        entries.put("server.js", "var actions = {};".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ScriptApp backend = ScriptAppFolder.read("backend.zip", ScriptAppLoadTest.zip(entries));
        check(!LocalScriptCatalog.isFrontend(backend), "带后端入口不进入定时纯前端更新");
        check(!LocalScriptSource.canReplace(one, backend, unsigned, unsigned, false), "纯前端不能自动变为带后端包");
        check(LocalScriptCatalog.isFrontend(one), "单文件识别为纯前端");
        System.out.println("本地更新断言 " + checks + " 条通过");
    }

    private static TrustState.Verdict verdict(TrustState.State state, String fingerprint) {
        return new TrustState.Verdict(state, fingerprint, null, "tester");
    }
}
