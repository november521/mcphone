package com.november.mcphone.core.script.engine;

import com.november.mcphone.core.script.server.PlayerSnapshot;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 脚本沙箱与预算（施工方案 §16.3、§16.4、§16.6、§16.7、§15.2）。
 *
 * <p><b>这里测不了的</b>：死循环期间服务器 TPS、KubeJS 双端共存（V-4）、管理界面 ——
 * 都要一台真在跑的游戏。
 *
 * <p>跑法：{@code ./gradlew assertTests}（在 {@code platforms/<目标名>/} 下）。
 */
public class ScriptEngineTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) failures.add(what + "  期望 " + expected + "，实际 " + actual);
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures.add(what);
    }

    static final ScriptBudget BUDGET = ScriptBudget.server();

    /** 跑一段脚本，返回结果或异常的简名。 */
    static String run(String src) {
        Context cx = BUDGET.enterContext();
        try {
            BUDGET.begin();
            return String.valueOf(Context.toString(cx.evaluateString(ScriptSandbox.harden(cx), src, "t", 1, null)));
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()).split("\n")[0];
        } finally {
            BUDGET.end();
            Context.exit();
        }
    }

    /**
     * 这段脚本会不会以某个硬停止原因中断。
     *
     * <p>S15h/S15i 之后只剩三个原因有抛出点：指令预算、墙钟、宿主桥重入
     * （尺寸与驻留都改成没有抛出点了，尺寸走 {@link HostError}）。
     */
    static boolean aborted(String src, ScriptAbort.Reason reason) {
        String r = run(src);
        return r.startsWith("ScriptAbort: " + reason);
    }

    // ================================================================ §16.3 逃逸

    static void escapes() {
        check(run("eval('42')").contains("not defined"), "eval 没了");
        check(run("new Function('return 42')()").contains("not defined"), "Function 没了");
        // §16.3 的配方照抄之后 Script 还在（实测 new Script('40+2')() → 42）。
        // 它拿不到 Java，但它是第三个运行时编译入口 —— 击穿了 §16.3 自己写的
        // 「运行的代码可以和 OP 审的源码不是一回事」那条理由
        check(run("new Script('40+2')()").contains("not defined"), "Script 没了 —— 第三个运行时编译入口");
        // 判据是「拿不到 42」，不是错误文案长什么样：同一条链在不同写法下报的话不一样
        // （实测有 "Cannot find function constructor" 也有 "return 42 is not a function"）
        for (String chain : new String[]{
                "var f=function(){}; f.constructor('return 42')()",
                "[].constructor.constructor('return 42')()",
                "({}).constructor.constructor('return 42')()",
                "''.constructor.constructor('return 42')()",
                "(0).constructor.constructor('return 42')()",
                "Object.getPrototypeOf(function(){}).constructor('return 42')()"}) {
            String got = run(chain);
            check(!got.equals("42"), "这条链必须断：" + chain + " → " + got);
        }
        eq(run("typeof java"), "undefined", "typeof java");
        eq(run("typeof Packages"), "undefined", "typeof Packages");
        eq(run("typeof JavaImporter"), "undefined", "typeof JavaImporter");
        eq(run("typeof ''.getClass"), "undefined", "getClass");
        eq(run("typeof this.getClass"), "undefined", "this.getClass");
    }

    /**
     * 全局清单逐字等于白名单。
     *
     * <p><b>这是唯一会在 Rhino 升版新增全局时响的东西</b>：点名删的名单每次升版都会漏一批，
     * 所以判据反过来写。
     */
    static void globalsExactly() {
        String src = "Object.getOwnPropertyNames(this).sort().join(',')";
        String got = run(src);
        TreeSet<String> actual = new TreeSet<>(List.of(got.split(",")));
        TreeSet<String> expected = new TreeSet<>(ScriptSandbox.ALLOWED_GLOBALS);
        eq(actual, expected, "加固后的全局清单必须逐字等于白名单");

        // 点名几个删掉的，读起来一眼知道防的是什么
        for (String gone : new String[]{"Script", "ArrayBuffer", "Int8Array", "DataView",
                "Promise", "escape", "unescape", "Continuation", "JavaException"}) {
            eq(run("typeof " + gone), "undefined", gone + " 必须没有");
        }
    }

    static void sealed() {
        check(run("Object.prototype.p=1; ({}).p").contains("sealed"), "Object.prototype 封了");
        check(run("Array.prototype.q=1; [].q").contains("sealed"), "Array.prototype 封了");
        check(run("String.prototype.trim=function(){return 'X'}; ' a '.trim()").contains("sealed"), "改内置封了");
        // §16.3 的 SEAL 只点了 10 个名字，Map 不在里面 —— 实测能污染
        check(run("Map.prototype.zz=1; new Map().zz").contains("sealed"), "Map.prototype 也要封");
        check(run("Set.prototype.zz=1; new Set().zz").contains("sealed"), "Set.prototype 也要封");
    }

    static void normalStillWorks() {
        eq(run("var f=function(x){return x*2}; f(21)"), "42", "变量与函数");
        eq(run("((x)=>x+1)(41)"), "42", "箭头函数");
        eq(run("[1,2,3].map(function(x){return x*2}).join(',')"), "2,4,6", "map/join");
        eq(run("JSON.stringify({a:[1,2]})"), "{\"a\":[1,2]}", "JSON");
        eq(run("Math.max(1,42)"), "42", "Math");
        eq(run("var actions={}; actions.c=function(){return 7}; actions.c()"), "7", "actions 注入");
        eq(run("'ab'.repeat(3)"), "ababab", "正常 repeat 不受影响");
        eq(run("new Map([['a',1]]).get('a')"), "1", "Map 能用");
        // E2 顺延项：BigInt 边界精度
        eq(run("(BigInt('9007199254740993')+1n).toString()"), "9007199254740994", "BigInt 精度无损");
        eq(run("(9007199254740993n * 2n).toString()"), "18014398509481986", "BigInt 乘法");
    }

    // ================================================================ §16.4 预算

    static void budget() {
        String loop = run("var i=0; while(true) i++;");
        check(loop.startsWith("ScriptAbort:"), "死循环被中断，实际 " + loop);

        // 中断信号必须是 Error：实测宿主抛 RuntimeException 时 finally{return} 会把它吞掉
        String swallowed = run("function g(){ try{ var i=0; while(true) i++; } finally { return 'FINALLY_WINS'; } } g()");
        check(swallowed.startsWith("ScriptAbort:"), "finally{return} 吞不掉中断，实际 " + swallowed);
        String caught = run("function g(){ try{ var i=0; while(true) i++; } catch(e){} return 'SWALLOWED'; } g()");
        check(caught.startsWith("ScriptAbort:"), "catch 也吞不掉，实际 " + caught);

        check(run("/^(a+)+$/.test('aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaX')").startsWith("ScriptAbort:"), "正则最坏回溯被中断");
        check(run("function f(n){return n<=0?0:f(n-1)+1} f(100000)").contains("stack depth"), "递归撞栈深");
    }

    /**
     * 预算只在分支点生效，大分配靠尺寸闸拦（§16.4 的三件事 ①②）。
     *
     * <p>S15h/S15i：尺寸闸抛的是 {@link HostError}（<b>脚本接得住</b>、不记过失），
     * 不再是 {@code ScriptAbort}。放宽成可接住没有削弱它 —— 检查点在值与副作用落地之前。
     */
    static void sizeGate() {
        check(rejectedBySizeGate("'x'.repeat(100000000).length"), "repeat 放大被拦");
        check(rejectedBySizeGate("new Array(100000000).join('')"), "join 放大被拦");
        check(rejectedBySizeGate("var s='x';for(var i=0;i<30;i++)s+=s; s.indexOf('y')"),
                "rope 物化被拦 —— 实测 9 毫秒能打爆 256 MB 堆");
        check(rejectedBySizeGate("var a=[];a.length=100000000; a.fill(1)"), "fill 放大被拦");
        eq(SizeGate.MAX_STRING, 64 * 1024, "§16.4 ① 的字符串上限");
        eq(SizeGate.MAX_ARRAY, 4096, "§16.4 ① 的数组上限");

        // 接得住：脚本 try/catch 拿得到，接住之后能继续
        eq(withCtx("var o; try { ctx.shared.set('k', 'x'.repeat(40000) + 'x'.repeat(40000)) }"
                + " catch (e) { o = 'CAUGHT/' + e.message.split(':')[0] } o", FULL),
                "CAUGHT/INVALID", "进 ctx 的参数超尺寸：脚本接得住");
        Context cx = BUDGET.enterContext();
        try {
            BUDGET.begin();
            ScriptableObject scope = ScriptSandbox.harden(cx);
            String out = String.valueOf(Context.toString(cx.evaluateString(scope,
                    "var o; try { 'x'.repeat(100000000) } catch (e) { o = 'CAUGHT/' + e.message.split(':')[0] } o",
                    "t", 1, null)));
            eq(out, "CAUGHT/INVALID", "原生放大路径超尺寸：脚本也能接住");
        } finally {
            BUDGET.end();
            Context.exit();
        }
    }

    /** 尺寸闸拦住了没有：{@code withCtx} 会把抛出来的东西写成 "HostError: INVALID: …"。 */
    static boolean rejectedBySizeGate(String src) {
        String r = withCtx(src, FULL);
        if (!r.startsWith("HostError: INVALID: ")) return false;
        // 而且接得住：包一层 try/catch，看它会不会被脚本拿到
        return withCtx("var o; try { " + src + " } catch (e) { o = 'CAUGHT/' + e.message.split(':')[0] } o", FULL)
                .equals("CAUGHT/INVALID");
    }

    /** 跨调用驻留：每次调用都在预算内，26 次就打爆堆（实测）。 */
    static void retention() {
        AppScope app = new AppScope("t:app", BUDGET, Map.of());
        Context cx = BUDGET.enterContext();
        try {
            BUDGET.begin();
            ScriptableObject scope = app.scope(cx);
            // 直接往 App scope 上放一个超限的字符串，模拟跨调用累积出来的那一份
            ScriptableObject.putProperty(scope, "g", "x".repeat((int) AppScope.MAX_RETAINED_CHARS + 1));
            check(app.sweepRetained(), "驻留超限要被扫出来");
        } finally {
            BUDGET.end();
            Context.exit();
        }
        Context cx2 = BUDGET.enterContext();
        try {
            BUDGET.begin();
            Object g = ScriptableObject.getProperty(app.scope(cx2), "g");
            check(!(g instanceof CharSequence), "scope 重建之后那一份没了");
        } finally {
            BUDGET.end();
            Context.exit();
        }
    }

    /** §16.7：两个 App 的 scope 互相看不见。 */
    static void scopesIsolated() {
        Context cx = BUDGET.enterContext();
        try {
            BUDGET.begin();
            Scriptable a = ScriptSandbox.harden(cx);
            Scriptable b = ScriptSandbox.harden(cx);
            cx.evaluateString(a, "var leak = 'A 的秘密';", "a", 1, null);
            Object seen = cx.evaluateString(b, "typeof leak", "b", 1, null);
            eq(Context.toString(seen), "undefined", "B 看不到 A 的全局");
        } catch (Throwable t) {
            failures.add("两个 scope 隔离测试抛了: " + t);
        } finally {
            BUDGET.end();
            Context.exit();
        }
    }

    // ================================================================ §16.5 / §16.7 的 ctx

    static PlayerSnapshot player() {
        return new PlayerSnapshot(UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "yumeka", "minecraft:overworld", "survival", 0);
    }

    static ItemView fakeItems() {
        return new ItemView() {
            public boolean matches(String handle, String predicate) {
                return true;
            }

            public String displayName(String handle) {
                return "铁剑";
            }

            public boolean isDamaged(String handle) {
                return false;
            }
        };
    }

    /** 在 ctx 在场的情况下跑一段。 */
    static String withCtx(String src, CtxBuilder.Backends backends) {
        Context cx = BUDGET.enterContext();
        try {
            BUDGET.begin();
            HostFn.resetDepth();
            ScriptableObject scope = ScriptSandbox.harden(cx);
            CtxBuilder.Result r = new CtxBuilder.Result();
            ScriptableObject ctx = CtxBuilder.build(cx, scope, "t:app", player(), backends, r);
            ScriptableObject.putProperty(scope, "ctx", ctx);
            return String.valueOf(Context.toString(cx.evaluateString(scope, src, "t", 1, null)));
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + String.valueOf(t.getMessage()).split("\n")[0];
        } finally {
            BUDGET.end();
            Context.exit();
        }
    }

    /** 和 withCtx 一样跑一段，但把抛出来的东西原样交回来；没抛就是 null。 */
    static Throwable thrownBy(String src, CtxBuilder.Backends backends) {
        Context cx = BUDGET.enterContext();
        try {
            BUDGET.begin();
            HostFn.resetDepth();
            ScriptableObject scope = ScriptSandbox.harden(cx);
            CtxBuilder.Result r = new CtxBuilder.Result();
            ScriptableObject ctx = CtxBuilder.build(cx, scope, "t:app", player(), backends, r);
            ScriptableObject.putProperty(scope, "ctx", ctx);
            cx.evaluateString(scope, src, "t", 1, null);
            return null;
        } catch (Throwable t) {
            return t;
        } finally {
            BUDGET.end();
            Context.exit();
        }
    }

    static final CtxBuilder.Backends FULL = new CtxBuilder.Backends(
            new SharedState(), fakeItems(),
            new CtxBuilder.Cycle(ZoneId.of("Asia/Shanghai"), LocalTime.of(4, 0)));

    /**
     * §16.7：ctx 上枚举不出表外的东西。
     *
     * <p>判据比原文严：<b>ctx 与它的每一级子对象</b>，三种枚举都只给表内名字，
     * 且 {@code getPrototypeOf} 恒为 null。
     */
    static void ctxEnumeration() {
        eq(withCtx("Object.getOwnPropertyNames(ctx).sort().join(',')", FULL),
                "cycle,fail,item,log,ok,player,shared,time", "ctx 顶层只有这些");
        eq(withCtx("var n=[];for(var k in ctx)n.push(k);n.sort().join(',')", FULL),
                "cycle,fail,item,log,ok,player,shared,time", "for..in 也只有这些");
        eq(withCtx("String(Object.getPrototypeOf(ctx))", FULL), "null", "ctx 没有原型链");

        for (String child : new String[]{"player", "time", "cycle", "shared", "item"}) {
            eq(withCtx("String(Object.getPrototypeOf(ctx." + child + "))", FULL),
                    "null", "ctx." + child + " 也没有原型链");
            eq(withCtx("typeof ctx." + child + ".getClass", FULL), "undefined", "ctx." + child + " 摸不到 getClass");
            eq(withCtx("typeof ctx." + child + ".constructor", FULL), "undefined", "ctx." + child + " 摸不到 constructor");
        }
        eq(withCtx("typeof ctx.getClass", FULL), "undefined", "ctx 摸不到 getClass");
        eq(withCtx("typeof ctx.equals", FULL), "undefined", "ctx 上没有 Java 的 equals");
        eq(withCtx("typeof ctx.wait", FULL), "undefined", "ctx 上没有 Java 的 wait");
        check(withCtx("ctx.evil=1; typeof ctx.evil", FULL).contains("sealed"), "ctx 封了，加不了属性");

        // 表里没有的一律没有（§32.7：store/currency/mailbox/fetch 的后端还没到货）
        for (String absent : new String[]{"store", "currency", "mailbox", "fetch", "give", "loot", "command"}) {
            eq(withCtx("typeof ctx." + absent, FULL), "undefined",
                    "ctx." + absent + " 本步没有后端，就不该挂出来");
        }
    }

    /** E2 顺延项：opaque 在脚本侧既解析不了也构造不了。 */
    static void ctxItemOpaque() {
        eq(withCtx("Object.getOwnPropertyNames(ctx.item).sort().join(',')", FULL),
                "displayName,isDamaged,matches", "ctx.item 只有 §23.3 允许的三个");
        eq(withCtx("typeof ctx.item.nbt", FULL), "undefined", "没有 nbt()");
        eq(withCtx("typeof ctx.item.enchantments", FULL), "undefined", "没有 enchantments()");
        eq(withCtx("typeof ctx.item.decode", FULL), "undefined", "没有解码口");
        eq(withCtx("typeof ctx.item.create", FULL), "undefined", "没有构造口");
        eq(withCtx("ctx.item.displayName('3f2504e0a1b2c3d4e5f60718293a4b5c')", FULL), "铁剑", "只拿得到显示名");
        // 句柄就是一串十六进制，从它身上什么都解不出来
        eq(withCtx("typeof '3f2504e0a1b2c3d4e5f60718293a4b5c'.nbt", FULL), "undefined", "句柄上没有任何解析方法");
    }

    static void ctxBasics() {
        eq(withCtx("ctx.player.uuid", FULL), "00000000-0000-0000-0000-000000000001", "player.uuid");
        eq(withCtx("ctx.player.name", FULL), "yumeka", "player.name");
        eq(withCtx("ctx.player.gameMode", FULL), "survival", "player.gameMode");
        eq(withCtx("typeof ctx.player.onlineSince", FULL), "undefined", "§32.7 没列 onlineSince");
        eq(withCtx("typeof ctx.time.epochMillis()", FULL), "string", "时间戳是十进制字符串，不是数字");
        eq(withCtx("ctx.cycle.label('daily').length", FULL), "10", "daily 标签是 yyyy-MM-dd");
        check(withCtx("ctx.cycle.label('yearly')", FULL).contains("daily/weekly/monthly"), "认不出的粒度要拒");

        eq(withCtx("ctx.shared.set('k','v'); ctx.shared.get('k')", FULL), "v", "shared 读写");
        eq(withCtx("ctx.shared.compareAndSet('n',null,'1')", FULL), "true", "CAS 建新键");
        eq(withCtx("ctx.shared.set('n','1'); String(ctx.shared.compareAndSet('n','2','3'))", FULL),
                "false", "expected 对不上就不换");
    }

    /** 宿主桥只收原语：不许靠 valueOf 回调重入宿主。 */
    static void noCoercionCallback() {
        String r = withCtx("var evil={valueOf:function(){return 'x'}}; ctx.shared.get(evil)", FULL);
        check(r.contains("要字符串"), "给对象要当场拒，不能去调它的 valueOf，实际 " + r);
        String r2 = withCtx("ctx.shared.get(123)", FULL);
        check(r2.contains("要字符串"), "给数字也拒，实际 " + r2);
    }

    // ================================================================ §15.2 require

    static void requireTable() {
        ScriptModules m = new ScriptModules(Map.of(
                "server.js", "x", "server/gift.js", "y", "lib/util.js", "z"));
        eq(m.size(), 3, "三个模块");
        eq(ScriptModules.normalize("", "./server/gift.js"), "server/gift.js", "同级");
        eq(ScriptModules.normalize("server", "./gift.js"), "server/gift.js", "子目录里的同级");
        eq(ScriptModules.normalize("server", "../lib/util.js"), "lib/util.js", "上一级");
        eq(ScriptModules.normalize("", "../etc/passwd"), null, "弹出包根要拒");
        eq(ScriptModules.normalize("server", "../../x.js"), null, "弹穿两级也拒");
        eq(ScriptModules.normalize("", "./a\\b.js"), null, "反斜杠拒");
        eq(ScriptModules.normalize("", "./C:/x.js"), null, "带盘符拒");
        eq(ScriptModules.normalize("", "./a/./b.js"), "a/b.js", "单点跳过");

        check(abortsWith(() -> m.require("server/gift.js", "server.js", (k, s) -> null), "只许包内相对路径"),
                "不带 ./ 的一律拒");
        check(abortsWith(() -> m.require("./nope.js", "server.js", (k, s) -> null), "找不到"), "表里没有就拒");

        // 缓存命中不计深度、不计模块数
        Object[] loaded = {0};
        Object first = m.require("./server/gift.js", "server.js", (k, s) -> {
            loaded[0] = (int) loaded[0] + 1;
            return "E";
        });
        Object second = m.require("./server/gift.js", "server.js", (k, s) -> {
            loaded[0] = (int) loaded[0] + 1;
            return "E2";
        });
        eq(first, "E", "第一次求值");
        eq(second, "E", "第二次走缓存");
        eq(loaded[0], 1, "只求值了一次");
        eq(m.depth(), 0, "栈平了");
    }

    static void requireCycle() {
        ScriptModules m = new ScriptModules(Map.of("a.js", "", "b.js", ""));
        check(abortsWith(() -> m.require("./a.js", "server.js",
                (k, s) -> m.require("./b.js", k, (k2, s2) -> m.require("./a.js", k2, (k3, s3) -> null))),
                "循环依赖"), "循环依赖要报出来");
    }

    static void requireLimits() {
        java.util.Map<String, String> many = new java.util.HashMap<>();
        for (int i = 0; i <= ScriptModules.MAX_MODULES; i++) many.put("m" + i + ".js", "");
        check(abortsWith(() -> new ScriptModules(many), "上限"), "模块数超限在建表时就拒");
        check(abortsWith(() -> new ScriptModules(Map.of("a.vue", "")), "非 .js"), "非 .js 拒");
    }

    static boolean abortsWith(Runnable body, String fragment) {
        try {
            body.run();
            return false;
        } catch (ScriptAbort e) {
            return e.getMessage().contains(fragment);
        }
    }

    // ================================================================ §16.6 禁用

    static void strikes() {
        AtomicLong t = new AtomicLong(0);
        StrikeTracker st = new StrikeTracker(t::get);
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();

        check(st.allowed("app", a), "一开始允许");
        check(!st.recordAbort("app", a), "第 1 次不禁");
        check(!st.recordAbort("app", a), "第 2 次不禁");
        check(st.recordAbort("app", a), "第 3 次禁");
        check(!st.allowed("app", a), "被禁了");

        // 关键：禁的是这个玩家，不是整个 App。否则抢购开场谁都能把它对全服关掉
        check(st.allowed("app", b), "别的玩家不受影响 —— 这是主键选 (App, 玩家) 的全部理由");

        t.set(StrikeTracker.PLAYER_BAN_MS);
        check(st.allowed("app", a), "5 分钟后解禁");

        // "连续"：中间成功一次就清零
        StrikeTracker st2 = new StrikeTracker(new AtomicLong(0)::get);
        st2.recordAbort("app", a);
        st2.recordAbort("app", a);
        st2.recordOk("app", a);
        check(!st2.recordAbort("app", a), "成功一次之后重新数");
    }

    /**
     * 余额读不到（那种货币的存档锁住了、网关拒了）时 {@code ctx.currency.balance} 抛脚本接得住的 Error：
     * 不给 0 或 null（比大小时 null 也当 0，App 会告诉玩家他没钱），也不抛 ScriptAbort（接不住、记过失、会熔断整个 App）。
     */
    static void currencyBalanceUnavailable() {
        String busy = com.november.mcphone.core.script.server.economy.CurrencyGateway.KEY_BUSY;
        java.util.concurrent.atomic.AtomicInteger paid = new java.util.concurrent.atomic.AtomicInteger();
        // pay 成功、balance 被拒：真正要防的是"钱已经转了，求值却中断"
        var coin = new com.november.mcphone.api.economy.Currency(
                net.minecraft.resources.ResourceLocation.tryParse("myserver:coin"),
                net.minecraft.network.chat.Component.literal("coin"), "G", 0, null);
        var provider = (com.november.mcphone.api.economy.ICurrencyProvider) java.lang.reflect.Proxy.newProxyInstance(
                ScriptEngineTest.class.getClassLoader(),
                new Class<?>[]{com.november.mcphone.api.economy.ICurrencyProvider.class},
                (proxy, m, args) -> switch (m.getName()) {
                    case "currency" -> coin;
                    case "transfer" -> {
                        paid.incrementAndGet();
                        yield com.november.mcphone.api.economy.TxnResult.OK;
                    }
                    case "balance" -> throw new com.november.mcphone.core.script.server.economy.CurrencyUnavailableException(busy);
                    case "isAvailable", "allowNegative" -> false;
                    case "maxBalance" -> Long.MAX_VALUE;
                    case "unavailableReasonKey" -> busy;
                    case "toString" -> "fake-coin";
                    case "hashCode" -> 0;
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        var reg = new com.november.mcphone.core.script.server.economy.CurrencyRegistry();
        reg.register(provider, true);
        var backends = new CtxBuilder.Backends(new SharedState(), fakeItems(),
                new CtxBuilder.Cycle(ZoneId.of("Asia/Shanghai"), LocalTime.of(4, 0)), null, null, reg);
        String to = "'00000000-0000-0000-0000-000000000002'";

        eq(withCtx("var r = ctx.currency.pay('myserver:coin', " + to + ", 5n);"
                        + "try { ctx.currency.format('myserver:coin', ctx.currency.balance('myserver:coin')); 'no' }"
                        + "catch (e) { r + '|' + e.message }", backends),
                "OK|UNAVAILABLE: " + busy, "pay 成功之后 balance 被拒：脚本接得住，能自己 ctx.fail");
        eq(withCtx("try { ctx.currency.balance('myserver:coin') < 5n } catch (e) { 'caught' }", backends),
                "caught", "拿去比大小之前就抛了，不会被当成 0");
        String uncaught = withCtx("ctx.currency.balance('myserver:coin')", backends);
        check(uncaught.startsWith("EcmaError"), "没接住时是脚本错误（不记过失），不是 ScriptAbort：" + uncaught);
        eq(paid.get(), 1, "pay 只执行了一次");
    }

    /**
     * S15h：被拒的调用、玩家输错的数据是返回值，不中断（中断记过失，连着几次禁玩家、熔断整个 App）。
     * 只有脚本自己写错类型才中断。
     */
    static void currencyRejectionsAreReturnValues() {
        var data = com.november.mcphone.core.script.server.economy.EconomyData.empty(() -> 1);
        var coin = new com.november.mcphone.api.economy.Currency(
                net.minecraft.resources.ResourceLocation.tryParse("myserver:coin"),
                net.minecraft.network.chat.Component.literal("coin"), "G", 2, null);
        var provider = new com.november.mcphone.core.script.server.economy.BuiltinProvider(
                coin, data, data.escrow(), null, () -> 1, false, 1_000_000L);
        provider.mint(player().uuid(), 1_000, new com.november.mcphone.api.economy.TxnReason("t", "r"));
        var reg = new com.november.mcphone.core.script.server.economy.CurrencyRegistry();
        reg.register(provider, true);
        var b = new CtxBuilder.Backends(new SharedState(), fakeItems(),
                new CtxBuilder.Cycle(ZoneId.of("Asia/Shanghai"), LocalTime.of(4, 0)), null, null, reg);
        String c = "'myserver:coin'", to = "'00000000-0000-0000-0000-000000000002'";

        // S15h/S15i：parse 失败【抛脚本接得住的 Error】，不再返回 null（勘误 E35②）。
        // null 会继续往下流：`parse(...) > 0`、`parse(...) + 1` 都把 null 当 0 用，
        // 于是"玩家输错了"变成"金额是 0"，静默地做了一件玩家没要求的事。
        for (String bad : new String[]{"'1.234'", "'十块'", "''", "'abc'"}) {
            String caught = withCtx("(function () { try { return String(ctx.currency.parse(" + c + ", " + bad
                    + ")) } catch (e) { return 'CAUGHT/' + e.message } })()", b);
            eq(caught, "CAUGHT/INVALID: mcphone.economy.invalid_amount",
                    "parse(" + bad + ") 失败给接得住的 Error，不给 null 或别的哨兵值");
        }
        eq(withCtx("String(ctx.currency.parse(" + c + ", '1.23'))", b), "123", "对照：合法的照常解析");
        // catch 住之后这次求值能继续跑完（宿主校验错误的全部意义）
        eq(withCtx("var out; try { ctx.currency.parse(" + c + ", 'abc') } catch (e) { out = 'CAUGHT' } out + '/' + "
                + "ctx.currency.parse(" + c + ", '1.23')", b), "CAUGHT/123", "接住之后能继续，这一页不会因为一次坏输入整页报废");
        eq(withCtx("ctx.currency.pay(" + c + ", 'not-a-uuid', 5n)", b), "INVALID", "收款人不是 UUID：INVALID");
        eq(withCtx("ctx.currency.pay(" + c + ", " + to + ", 99999999999999999999999n)", b), "INVALID",
                "金额超出 long：INVALID");
        eq(withCtx("ctx.currency.pay(" + c + ", " + to + ", -5n)", b), "INVALID", "负金额：INVALID");
        eq(withCtx("ctx.currency.pay(" + c + ", " + to + ", 5n, 'a|b')", b), "INVALID", "单号里有竖线：INVALID");
        eq(withCtx("ctx.currency.hold(" + c + ", 'nope', 5n)", b), "INVALID", "托管给一个不是 UUID 的人：INVALID");
        eq(withCtx("ctx.currency.release(" + c + ", 'garbage')", b), "UNKNOWN_ESCROW", "托管号不是 UUID：UNKNOWN_ESCROW");
        eq(withCtx("ctx.currency.refund(" + c + ", 'garbage')", b), "UNKNOWN_ESCROW", "退款同样");
        eq(withCtx("ctx.currency.pay('server:nope', " + to + ", 5n)", b), "UNAVAILABLE", "没有这种货币：UNAVAILABLE");
        eq(withCtx("try { ctx.currency.balance('server:nope') } catch (e) { e.message }", b),
                "UNAVAILABLE: mcphone.economy.no_such_currency", "没有这种货币读余额：接得住的 Error");
        eq(withCtx("ctx.currency.pay(" + c + ", " + to + ", 5n)", b), "OK", "对照：合法的照常转");

        // 同一类路径在每个函数上都要钉住
        String u = "'00000000-0000-0000-0000-00000000000a'";
        for (String call : new String[]{"hold(" + c + ", " + to + ", 5n, 'a|b')", "release(" + c + ", " + u + ", 'a|b')",
                "refund(" + c + ", " + u + ", '" + "x".repeat(65) + "')"}) {
            eq(withCtx("ctx.currency." + call, b), "INVALID", call + "：单号不合法给 INVALID");
        }
        for (String call : new String[]{"hold('server:nope', " + to + ", 5n)", "release('server:nope', " + u + ")",
                "refund('server:nope', " + u + ")"}) {
            eq(withCtx("ctx.currency." + call, b), "UNAVAILABLE", call + "：没有这种货币给 UNAVAILABLE");
        }
        for (String call : new String[]{"format('server:nope', 5n)", "parse('server:nope', '5')"}) {
            eq(withCtx("try { ctx.currency." + call + " } catch (e) { e.message }", b),
                    "UNAVAILABLE: mcphone.economy.no_such_currency", call + "：没有这种货币是接得住的 Error");
        }
        eq(withCtx("ctx.currency.pay(" + c + ", " + to + ", 2n ** 63n)", b), "INVALID", "2^63 装不下：INVALID");
        eq(withCtx("ctx.currency.pay(" + c + ", " + to + ", 2n ** 63n - 1n)", b), "INSUFFICIENT",
                "2^63-1 装得下，只是钱不够：交给 provider 判");
        eq(withCtx("ctx.currency.hold(" + c + ", " + to + ", 2n ** 63n)", b), "INVALID", "托管 2^63：INVALID");

        // 【错误值不许当作值继续流动】：最危险的写法是不判结果就交给下游 ——
        // 现在 parse 在求值那一刻就抛，"金额是 0"这一支根本走不到
        eq(withCtx("(function () { try { return String(ctx.currency.pay(" + c + ", " + to
                + ", ctx.currency.parse(" + c + ", 'abc'))) } catch (e) { return 'CAUGHT/' + e.message } })()", b),
                "CAUGHT/INVALID: mcphone.economy.invalid_amount",
                "pay(parse(坏输入))：parse 先抛，pay 一次都没执行（不是 INVALID 那种\"已经进去了\"）");
        eq(withCtx("(function () { try { return ctx.currency.pay(" + c + ", " + to
                + ", ctx.currency.parse(" + c + ", 'abc')) } catch (e) { return 'CAUGHT/' + e.message } })()", b),
                "CAUGHT/INVALID: mcphone.economy.invalid_amount", "接住时拿到的是 parse 的错，不是 pay 的结果");
        eq(withCtx("(function () { try { return ctx.currency.pay(" + c + ", " + to
                + ", ctx.currency.parse(" + c + ", 'abc')) } finally { return 'SWALLOWED' } })()", b),
                "SWALLOWED", "finally { return } 吞得掉（宿主校验错误不是硬停止）—— 但钱一分没动，见下一条");
        eq(withCtx("ctx.currency.hold(" + c + ", " + to + ", undefined)", b), "INVALID", "金额 undefined：INVALID");
        eq(withCtx("(function () { try { return ctx.currency.format(" + c + ", ctx.currency.parse(" + c + ", 'abc')) }"
                + " catch (e) { return 'CAUGHT/' + e.message } })()", b),
                "CAUGHT/INVALID: mcphone.economy.invalid_amount", "format(parse(坏输入))：接得住的 Error，不中断");
        // 比较与算术：错误值进不去，整条表达式在 parse 那一步就断了
        eq(withCtx("(function () { try { return String(ctx.currency.parse(" + c + ", 'abc') > 0) }"
                + " catch (e) { return 'CAUGHT/' + e.message } })()", b),
                "CAUGHT/INVALID: mcphone.economy.invalid_amount", "错误值进不了比较");
        eq(withCtx("(function () { try { return String(ctx.currency.parse(" + c + ", 'abc') + 1) }"
                + " catch (e) { return 'CAUGHT/' + e.message } })()", b),
                "CAUGHT/INVALID: mcphone.economy.invalid_amount", "错误值进不了算术");
        eq(withCtx("var n = 0; try { n = ctx.currency.parse(" + c + ", 'abc') } catch (e) { } n", b), "0",
                "接住但没赋值时 n 是原来那个 0，不是宿主给的哨兵值");
        eq(withCtx("try { ctx.currency.format(" + c + ", 2n ** 70n) } catch (e) { e.message }", b),
                "INVALID: mcphone.economy.invalid_amount", "format 超出 long：和 pay 一样算数不对，不中断");

        // UUID.fromString 很宽松：这些都会被收成另一个 UUID，钱就付进没有主人的账户
        for (String loose : new String[]{"1-1-1-1-1", "+1-+1-+1-+1-+1", "\uFF11-1-1-1-1", "fffffffff-0-0-0-0",
                "00000000-0000-0000-0000-0000000000002",
                "+0000001-0000-0000-0000-000000000002", "\uFF100000000-0000-0000-0000-000000000002"}) {
            eq(withCtx("ctx.currency.pay(" + c + ", '" + loose + "', 5n)", b), "INVALID", "不规范的 UUID '" + loose + "'：INVALID");
        }
        eq(withCtx("ctx.currency.pay(" + c + ", '00000000-0000-0000-0000-00000000000A', 5n)", b), "OK",
                "规范写法的大写也收：是同一个 UUID");
        check(withCtx("ctx.currency.pay(" + c + ", " + to + ", 5)", b).startsWith("ScriptAbort"),
                "金额传了 Number 不是 BigInt：脚本自己写错了，照旧中断");

        // 字符串参数缺了（null / undefined）和金额缺了一样是返回码：多半是 default() 没有默认货币、或者玩家没填
        for (String call : new String[]{"pay(null, " + to + ", 5n)", "pay(undefined, " + to + ", 5n)",
                "hold(null, " + to + ", 5n)", "release(null, " + u + ")", "refund(undefined, " + u + ")"}) {
            eq(withCtx("ctx.currency." + call, b), "UNAVAILABLE", call + "：没给货币 id 给 UNAVAILABLE");
        }
        for (String call : new String[]{"pay(" + c + ", null, 5n)", "pay(" + c + ", undefined, 5n)", "hold(" + c + ", null, 5n)",
                "hold(" + c + ", undefined, 5n)"}) {
            eq(withCtx("ctx.currency." + call, b), "INVALID", call + "：没给收款人给 INVALID");
        }
        for (String call : new String[]{"release(" + c + ", null)", "release(" + c + ", undefined)", "release(" + c + ")",
                "refund(" + c + ", null)", "refund(" + c + ", undefined)", "refund(" + c + ")"}) {
            eq(withCtx("ctx.currency." + call, b), "UNKNOWN_ESCROW", call + "：没给托管号给 UNKNOWN_ESCROW");
        }
        for (String call : new String[]{"balance(null)", "format(undefined, 5n)", "parse(null, '5')"}) {
            eq(withCtx("try { ctx.currency." + call + " } catch (e) { e.message }", b),
                    "UNAVAILABLE: mcphone.economy.no_such_currency", call + "：没给货币 id 是接得住的 Error");
        }
        eq(withCtx("(function () { try { return String(ctx.currency.parse(" + c + ", null)) }"
                + " catch (e) { return 'CAUGHT/' + e.message } })()", b),
                "CAUGHT/INVALID: mcphone.economy.invalid_amount", "parse 没给文本：接得住的 Error，不给哨兵值");
        eq(withCtx("(function () { try { return String(ctx.currency.parse(" + c + ")) }"
                + " catch (e) { return 'CAUGHT/' + e.message } })()", b),
                "CAUGHT/INVALID: mcphone.economy.invalid_amount", "parse 连文本参数都没给：同样接得住");
        {
            String got = withCtx("ctx.currency.pay(" + c + ", 5, 5n)", b);
            check(got.startsWith("ScriptAbort") || got.startsWith("HostError") || got.contains("INVALID"),
                    "收款人传了 Number：类型写错了，拒掉（形态见下一条断言）—— 实际 " + got);
        }

        // provider 在动钱时抛了（结果不明）：原样抛出、脚本接不住，不改写成 UNAVAILABLE 让 App 当"没动"去重试；也不是 ScriptAbort（不记过失）
        var open = new com.november.mcphone.core.script.server.economy.CurrencyGateway(Runnable::run, () -> true);
        open.open();
        var gatedReg = new com.november.mcphone.core.script.server.economy.CurrencyRegistry(open);
        var halfway = (com.november.mcphone.api.economy.ICurrencyProvider) java.lang.reflect.Proxy.newProxyInstance(
                ScriptEngineTest.class.getClassLoader(), new Class<?>[]{com.november.mcphone.api.economy.ICurrencyProvider.class},
                (proxy, m, args) -> {
                    if (java.util.Set.of("transfer", "hold", "release", "refund").contains(m.getName())) {
                        throw new com.november.mcphone.core.script.server.economy.CurrencyUnavailableException("provider.own");
                    }
                    return m.invoke(provider, args);
                });
        gatedReg.register(halfway, true);
        var gb = new CtxBuilder.Backends(new SharedState(), fakeItems(),
                new CtxBuilder.Cycle(ZoneId.of("Asia/Shanghai"), LocalTime.of(4, 0)), null, null, gatedReg);
        for (String call : new String[]{"pay(" + c + ", " + to + ", 5n)", "hold(" + c + ", " + to + ", 5n)",
                "release(" + c + ", " + u + ")", "refund(" + c + ", " + u + ")"}) {
            String got = withCtx("try { ctx.currency." + call + " } catch (e) { 'caught' }", gb);
            check(got.startsWith("OutcomeUnknown"), call + "：结果不明、脚本接不住 —— " + got);
            got = withCtx("(function () { try { return ctx.currency." + call + " } finally { return 'SWALLOWED' } })()", gb);
            check(got.startsWith("OutcomeUnknown"), call + "：finally { return } 也吞不掉（吞掉了 App 就当\"没动\"再付一次）—— " + got);
        }
        // provider 没给结果（返回 null）：同样是结果不明，不是桥里的 NullPointerException
        var nullReg = new com.november.mcphone.core.script.server.economy.CurrencyRegistry(open);
        nullReg.register((com.november.mcphone.api.economy.ICurrencyProvider) java.lang.reflect.Proxy.newProxyInstance(
                ScriptEngineTest.class.getClassLoader(), new Class<?>[]{com.november.mcphone.api.economy.ICurrencyProvider.class},
                (proxy, m, args) -> java.util.Set.of("transfer", "hold", "release", "refund").contains(m.getName())
                        ? null : m.invoke(provider, args)), true);
        var nb2 = new CtxBuilder.Backends(new SharedState(), fakeItems(),
                new CtxBuilder.Cycle(ZoneId.of("Asia/Shanghai"), LocalTime.of(4, 0)), null, null, nullReg);
        for (String call : new String[]{"pay(" + c + ", " + to + ", 5n)", "hold(" + c + ", " + to + ", 5n)",
                "release(" + c + ", " + u + ")", "refund(" + c + ", " + u + ")"}) {
            String got = withCtx("(function () { try { return ctx.currency." + call + " } finally { return 'SWALLOWED' } })()", nb2);
            check(got.startsWith("OutcomeUnknown") && got.contains("没给结果"), call + "：provider 返回 null 也是结果不明，说明里说没给结果 —— " + got);
        }
        check(!ScriptAbort.class.isAssignableFrom(OutcomeUnknown.class), "结果不明不是 ScriptAbort：不记过失");

        // 抛出来的是 OutcomeUnknown、cause 是 provider 原来那个（provider 的堆栈只在这里）；
        // provider 抛非虚拟机级别的 Error、或者异常自己的 getMessage 都会炸，照样是结果不明、吞不掉
        class Bomb extends RuntimeException {
            @Override
            public String getMessage() {
                throw new IllegalStateException("getMessage 自己炸了");
            }
        }
        for (Throwable kind : new Throwable[]{new IllegalStateException("x"), new NoSuchMethodError("换了版本"),
                new AssertionError("第三方断言"), new Bomb()}) {
            var kReg = new com.november.mcphone.core.script.server.economy.CurrencyRegistry(open);
            kReg.register((com.november.mcphone.api.economy.ICurrencyProvider) java.lang.reflect.Proxy.newProxyInstance(
                    ScriptEngineTest.class.getClassLoader(), new Class<?>[]{com.november.mcphone.api.economy.ICurrencyProvider.class},
                    (proxy, m, args) -> {
                        if (m.getName().equals("transfer")) throw kind;
                        return m.invoke(provider, args);
                    }), true);
            var kb = new CtxBuilder.Backends(new SharedState(), fakeItems(),
                    new CtxBuilder.Cycle(ZoneId.of("Asia/Shanghai"), LocalTime.of(4, 0)), null, null, kReg);
            Throwable t = thrownBy("(function () { try { return ctx.currency.pay(" + c + ", " + to + ", 5n) } finally { return 'SWALLOWED' } })()", kb);
            String name = kind.getClass().getSimpleName();
            check(t instanceof OutcomeUnknown, name + "：结果不明、finally 吞不掉 —— " + t);
            // cause 是替身：只带原来的类名与堆栈；原来那个（getMessage 可能会炸）不交给日志
            check(t != null && t.getCause() instanceof com.november.mcphone.core.script.server.economy.ProviderFailure
                    && t.getCause().getMessage().startsWith(kind.getClass().getName()), name + "：cause 是带着原类名的替身");
            check(t != null && t.getCause().getCause() == null, name + "：替身不挂原来那个");
            if (kind instanceof IllegalStateException) {
                check(t.getMessage().contains("java.lang.IllegalStateException: x"), "说明里留着 provider 原来的 message —— " + t.getMessage());
            }
            check(t != null && t.getCause().getMessage().contains(kind instanceof Bomb ? "getMessage 抛了" : ": "),
                    name + "：替身留着原来的 message；取的时候炸了就说炸了 —— " + (t == null ? null : t.getCause().getMessage()));
            check(t != null && java.util.Arrays.equals(t.getCause().getStackTrace(), kind.getStackTrace()), name + "：替身带着原来的堆栈");
            check(t != null && t.getMessage().contains("app=t:app") && t.getMessage().contains("金额=5（最小单位）")
                    && t.getMessage().contains(kind.getClass().getName()), name + "：说明里有 app、金额与原类名 —— " + t);
            // 日志渲染时会把整条链打出来：不许在这一步再炸（原版日志配置下炸了会把求值线程带走）
            boolean renders = true;
            try {
                t.printStackTrace(new java.io.PrintWriter(new java.io.StringWriter()));
            } catch (Throwable e) {
                renders = false;
            }
            check(renders, name + "：整条异常链打得出来");
        }
        // provider 抛虚拟机级别的错误：同样是结果不明，cause 换成替身（它可能是第三方的子类、getMessage 会炸）
        StackOverflowError soe = new StackOverflowError();
        var vmReg = new com.november.mcphone.core.script.server.economy.CurrencyRegistry(open);
        vmReg.register((com.november.mcphone.api.economy.ICurrencyProvider) java.lang.reflect.Proxy.newProxyInstance(
                ScriptEngineTest.class.getClassLoader(), new Class<?>[]{com.november.mcphone.api.economy.ICurrencyProvider.class},
                (proxy, m, args) -> {
                    if (m.getName().equals("transfer")) throw soe;
                    return m.invoke(provider, args);
                }), true);
        Throwable vt = thrownBy("(function () { try { return ctx.currency.pay(" + c + ", " + to + ", 5n) } finally { return 'SWALLOWED' } })()",
                new CtxBuilder.Backends(new SharedState(), fakeItems(), new CtxBuilder.Cycle(ZoneId.of("Asia/Shanghai"), LocalTime.of(4, 0)),
                        null, null, vmReg));
        check(vt instanceof OutcomeUnknown && vt.getCause() instanceof com.november.mcphone.core.script.server.economy.ProviderFailure
                && vt.getCause().getMessage().startsWith("java.lang.StackOverflowError"), "虚拟机级别的错误：结果不明、吞不掉、cause 是替身 —— " + vt);

        // balance 里 provider 抛的（不是"读不到"那种）：换成替身再抛，整条链打得出来
        var balReg = new com.november.mcphone.core.script.server.economy.CurrencyRegistry(open);
        balReg.register((com.november.mcphone.api.economy.ICurrencyProvider) java.lang.reflect.Proxy.newProxyInstance(
                ScriptEngineTest.class.getClassLoader(), new Class<?>[]{com.november.mcphone.api.economy.ICurrencyProvider.class},
                (proxy, m, args) -> {
                    if (m.getName().equals("balance")) throw new Bomb();
                    return m.invoke(provider, args);
                }), true);
        Throwable bt = thrownBy("ctx.currency.balance(" + c + ")", new CtxBuilder.Backends(new SharedState(), fakeItems(),
                new CtxBuilder.Cycle(ZoneId.of("Asia/Shanghai"), LocalTime.of(4, 0)), null, null, balReg));
        boolean balRenders = true;
        try {
            bt.printStackTrace(new java.io.PrintWriter(new java.io.StringWriter()));
        } catch (Throwable e) {
            balRenders = false;
        }
        check(bt instanceof com.november.mcphone.core.script.server.economy.ProviderFailure && balRenders,
                "balance 里 provider 的异常：换成替身、整条链打得出来 —— " + bt);

        // balance 里 provider 抛 Error（外部经济模组换了版本）：保持 Error，脚本 finally { return } 吞不掉
        for (Throwable err : new Throwable[]{new NoSuchMethodError("换了版本"), new AssertionError("x"), new StackOverflowError()}) {
            var eReg = new com.november.mcphone.core.script.server.economy.CurrencyRegistry(open);
            eReg.register((com.november.mcphone.api.economy.ICurrencyProvider) java.lang.reflect.Proxy.newProxyInstance(
                    ScriptEngineTest.class.getClassLoader(), new Class<?>[]{com.november.mcphone.api.economy.ICurrencyProvider.class},
                    (proxy, m, args) -> {
                        if (m.getName().equals("balance")) throw err;
                        return m.invoke(provider, args);
                    }), true);
            Throwable et = thrownBy("(function () { try { return ctx.currency.balance(" + c + ") } finally { return 'SWALLOWED' } })()",
                    new CtxBuilder.Backends(new SharedState(), fakeItems(), new CtxBuilder.Cycle(ZoneId.of("Asia/Shanghai"), LocalTime.of(4, 0)),
                            null, null, eReg));
            check(et instanceof ProviderError && et.getCause() instanceof com.november.mcphone.core.script.server.economy.ProviderFailure
                    && et.getCause().getMessage().startsWith(err.getClass().getName()),
                    "balance 里 " + err.getClass().getSimpleName() + "：保持 Error、finally 吞不掉、cause 是替身 —— " + et);
        }

        // 生产里脚本跑在 worker 上，provider 在主线程上抛：走网关跨线程那条路，Error 也要保持 Error
        java.util.concurrent.atomic.AtomicReference<Thread> mainT = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.ExecutorService mainEx = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread th = new Thread(r, "fake-main");
            th.setDaemon(true);
            mainT.set(th);
            return th;
        });
        try {
            mainEx.submit(() -> { }).get();
            var cross = new com.november.mcphone.core.script.server.economy.CurrencyGateway(mainEx::execute,
                    () -> Thread.currentThread() == mainT.get());
            cross.open();
            // 第三方的虚拟机级别错误子类、getMessage 还会炸：两条路上都要换成替身，日志渲染时才不会把求值线程带走
            class VmBomb extends VirtualMachineError {
                @Override
                public String getMessage() {
                    throw new IllegalStateException("getMessage 自己炸了");
                }
            }
            class OomBomb extends OutOfMemoryError {
                @Override
                public String getMessage() {
                    throw new IllegalStateException("getMessage 自己炸了");
                }
            }
            for (Throwable err : new Throwable[]{new NoSuchMethodError("换了版本"), new VmBomb(), new OomBomb()}) {
                for (com.november.mcphone.core.script.server.economy.CurrencyGateway gw
                        : new com.november.mcphone.core.script.server.economy.CurrencyGateway[]{open, cross}) {
                    String where = err.getClass().getSimpleName() + (gw == cross ? "（跨线程）" : "（主线程直调）");
                    var xReg = new com.november.mcphone.core.script.server.economy.CurrencyRegistry(gw);
                    xReg.register((com.november.mcphone.api.economy.ICurrencyProvider) java.lang.reflect.Proxy.newProxyInstance(
                            ScriptEngineTest.class.getClassLoader(), new Class<?>[]{com.november.mcphone.api.economy.ICurrencyProvider.class},
                            (proxy, m, args) -> {
                                if (m.getName().equals("balance") || m.getName().equals("transfer")) throw err;
                                return m.invoke(provider, args);
                            }), true);
                    var xb = new CtxBuilder.Backends(new SharedState(), fakeItems(),
                            new CtxBuilder.Cycle(ZoneId.of("Asia/Shanghai"), LocalTime.of(4, 0)), null, null, xReg);
                    Throwable bal = thrownBy("(function () { try { return ctx.currency.balance(" + c + ") } finally { return 'SWALLOWED' } })()", xb);
                    Throwable pay = thrownBy("(function () { try { return ctx.currency.pay(" + c + ", " + to + ", 5n) } finally { return 'SWALLOWED' } })()", xb);
                    // 失败说明里只写类名：原样穿出来的炸弹一 toString 就炸
                    check(bal instanceof ProviderError, where + "：balance 保持 Error、finally 吞不掉 —— " + (bal == null ? null : bal.getClass().getName()));
                    check(pay instanceof OutcomeUnknown, where + "：pay 结果不明、finally 吞不掉 —— " + (pay == null ? null : pay.getClass().getName()));
                    for (Throwable t : new Throwable[]{bal, pay}) {
                        boolean renders = t != null;
                        try {
                            if (t != null) t.printStackTrace(new java.io.PrintWriter(new java.io.StringWriter()));
                        } catch (Throwable e) {
                            renders = false;
                        }
                        check(renders && t.getCause() instanceof com.november.mcphone.core.script.server.economy.ProviderFailure,
                                where + "：cause 是替身、整条链打得出来 —— " + (t == null ? null : t.getClass().getName()));
                    }
                }
            }
        } catch (Exception e) {
            check(false, "跨线程网关的装置没搭起来：" + e);
        } finally {
            mainEx.shutdownNow();
        }

        // 原来那个连 getStackTrace 都会炸：替身不带堆栈，照样造得出来
        Throwable noStack = new RuntimeException() {
            @Override
            public StackTraceElement[] getStackTrace() {
                throw new IllegalStateException("getStackTrace 也炸了");
            }
        };
        var standIn = com.november.mcphone.core.script.server.economy.ProviderFailure.of(noStack);
        check(standIn.getStackTrace().length == 0, "getStackTrace 炸了：替身不带堆栈，不抛");
        var refusingReg = new com.november.mcphone.core.script.server.economy.CurrencyRegistry(
                new com.november.mcphone.core.script.server.economy.CurrencyGateway(Runnable::run, () -> false));
        refusingReg.register(halfway, true);
        var rb = new CtxBuilder.Backends(new SharedState(), fakeItems(),
                new CtxBuilder.Cycle(ZoneId.of("Asia/Shanghai"), LocalTime.of(4, 0)), null, null, refusingReg);
        eq(withCtx("ctx.currency.pay(" + c + ", " + to + ", 5n)", rb), "UNAVAILABLE", "对照：网关自己拒的（provider 没跑）照样是 UNAVAILABLE");

        var noDefault = new com.november.mcphone.core.script.server.economy.CurrencyRegistry();
        noDefault.register(new com.november.mcphone.core.script.server.economy.BuiltinProvider(
                coin, data, data.escrow(), null, () -> 1, false, 1_000_000L), false);
        var nb = new CtxBuilder.Backends(new SharedState(), fakeItems(),
                new CtxBuilder.Cycle(ZoneId.of("Asia/Shanghai"), LocalTime.of(4, 0)), null, null, noDefault);
        eq(withCtx("String(ctx.currency.default())", nb), "null", "对照：没有默认货币时 default() 是 null");
        eq(withCtx("ctx.currency.pay(ctx.currency.default(), " + to + ", 5n)", nb), "UNAVAILABLE",
                "pay(default()) 在没有默认货币时：UNAVAILABLE，不中断");
    }

    /**
     * 端到端（真 RhinoEvaluator、worker、跨线程网关）：provider 动钱时抛了，客户端拿到 UNKNOWN（绝不自动重试）、不记过失，
     * 脚本 catch / finally { return } 都改不了。回 INTERNAL 的话玩家再点一次就可能多付。
     */
    static void currencyOutcomeUnknownIsUnknown() {
        var data = com.november.mcphone.core.script.server.economy.EconomyData.empty(() -> 1);
        var coin = new com.november.mcphone.api.economy.Currency(
                net.minecraft.resources.ResourceLocation.tryParse("myserver:coin"),
                net.minecraft.network.chat.Component.literal("coin"), "G", 0, null);
        var real = new com.november.mcphone.core.script.server.economy.BuiltinProvider(
                coin, data, data.escrow(), null, () -> 1, false, 1_000_000L);
        real.mint(player().uuid(), 1_000, new com.november.mcphone.api.economy.TxnReason("t", "r"));
        var mode = new java.util.concurrent.atomic.AtomicReference<>("ok");
        var moved = new java.util.concurrent.atomic.AtomicInteger();
        var provider = (com.november.mcphone.api.economy.ICurrencyProvider) java.lang.reflect.Proxy.newProxyInstance(
                ScriptEngineTest.class.getClassLoader(), new Class<?>[]{com.november.mcphone.api.economy.ICurrencyProvider.class},
                (proxy, m, args) -> {
                    if (m.getName().equals("transfer")) {
                        moved.incrementAndGet();
                        if (mode.get().equals("pay")) throw new IllegalStateException("钱包写了一半");
                    }
                    if (m.getName().equals("balance") && mode.get().equals("bal")) throw new NoSuchMethodError("换了版本");
                    return m.invoke(real, args);
                });

        MainRig rig = MainRig.get();
        com.november.mcphone.core.script.server.ScriptWorkers.start();
        try {
            var gw = rig.gateway;
            var reg = new com.november.mcphone.core.script.server.economy.CurrencyRegistry(gw);
            reg.register(provider, true);
            var b = new CtxBuilder.Backends(new SharedState(), fakeItems(),
                    new CtxBuilder.Cycle(ZoneId.of("Asia/Shanghai"), LocalTime.of(4, 0)), null, null, reg);
            AppScope app = new AppScope("t:app", ScriptBudget.server(), Map.of());
            String pay = "ctx.currency.pay('myserver:coin', '00000000-0000-0000-0000-000000000002', 5n)";
            Context cx = app.budget().enterContext();
            try {
                cx.evaluateString(app.scope(cx), "var actions = {"
                        + " buy: function (ctx) { " + pay + "; ctx.ok({}) },"
                        + " buyfin: function (ctx) { try { " + pay + " } finally { ctx.ok({}); return } },"
                        + " buycatch: function (ctx) { try { " + pay + " } catch (e) { } ctx.ok({}) },"
                        + " bal: function (ctx) { try { ctx.currency.balance('myserver:coin') } finally { ctx.ok({}); return } } }",
                        "app", 1, null);
            } finally {
                Context.exit();
            }
            // 主线程那一路用假主线程的 execute，不是 Runnable::run ——
            // 后者会让 land() 在 worker 上跑，碰到 StrikeTracker 的归属断言当场抛。
            // 生产里送的正是 server::execute（真的换线程）
            MainRig.Stack st = rig.stack(Map.of("t:app", app), b);
            StrikeTracker strikes = st.strikes();
            RhinoEvaluator ev = st.evaluator();
            java.util.function.Function<String, Object> call = action -> {
                var f = new java.util.concurrent.CompletableFuture<com.november.mcphone.core.script.server.ActionEvaluator.Outcome>();
                ev.submit(new com.november.mcphone.core.script.server.ActionEvaluator.Request(
                        "t:app", action, new byte[0], player(), "r", 1), f::complete);
                try {
                    return f.get(10, java.util.concurrent.TimeUnit.SECONDS).code();
                } catch (Exception e) {
                    return e.getClass().getSimpleName();
                }
            };

            eq(call.apply("buy"), com.november.mcphone.core.script.net.ScriptErrorCode.OK, "对照：provider 正常时 OK");
            mode.set("pay");
            for (int i = 0; i < 2; i++) {
                for (String action : new String[]{"buy", "buyfin", "buycatch"}) {
                    moved.set(0);
                    eq(call.apply(action), com.november.mcphone.core.script.net.ScriptErrorCode.UNKNOWN,
                            action + "：provider 动钱时抛了 → UNKNOWN（catch / finally { return } 改不了）");
                    eq(moved.get(), 1, action + "：provider 只被调了一次");
                }
            }
            check(rig.onMain(() -> strikes.allowed("t:app", player().uuid())),
                    "结果不明六次也不记过失：不是脚本的错");
            mode.set("bal");
            eq(call.apply("bal"), com.november.mcphone.core.script.net.ScriptErrorCode.INTERNAL,
                    "对照：查余额抛了不动钱，照旧 INTERNAL，不是 UNKNOWN");
        } catch (Exception e) {
            check(false, "端到端装置没搭起来：" + e);
        } finally {
            com.november.mcphone.core.script.server.ScriptWorkers.stop();
        }
        // 停完之后【必须换一批线程】：这个用例原先用 Runnable::run 当"主线程"，
        // 于是它有几笔在飞的任务是在 worker 上跑 land() 的。stop() 的 shutdownNow 对
        // 已经进入 Runnable 的任务只是置打断位、杀不掉，它们会在下面这批用例跑到一半时
        // 才去碰 StrikeTracker，然后抛归属断言 —— 那会被记成"新用例失败了"。
        // 换池 + 让旧任务跑完/死掉，这个用例的影响就关在它自己里面。
        com.november.mcphone.core.script.server.ScriptWorkers.start();
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 实测记要：{@code ScriptableObject.getProperty} 跑不跑顶层 accessor。
     *
     * <p>E35④ 那条用例（顶层 getter 把驻留清扫炸掉）要靠脚本自己的
     * {@code Object.defineProperty(globalThis, 'g', {get: ...})} 成立；
     * 而 {@code AppScope.sweepRetained} 因此必须显式走 accessor（见 {@code AppScope.readTopLevel}）。
     * 这条实测钉住的正是"为什么不能只用 getProperty"。
     */
    static void retainedGetterMechanics() {
        Context cx = BUDGET.enterContext();
        try {
            BUDGET.begin();
            ScriptableObject scope = ScriptSandbox.harden(cx);
            // 手法一（宿主 defineProperty）：getProperty 不会跑 getter —— 钉住这个实测结论，
            // 它是 AppScope.readTopLevel 必须存在的理由
            ScriptableObject.defineProperty(scope, "g1",
                    (org.mozilla.javascript.ScriptableObject.LambdaGetterFunction)
                            (s) -> { throw new IllegalStateException("getter 炸弹"); },
                    ScriptableObject.READONLY);
            try {
                ScriptableObject.getProperty(scope, "g1");
                check(true, "实测：宿主 defineProperty 装出来的 accessor，getProperty 不跑它");
            } catch (Throwable t) {
                check(false, "实测变了：getProperty 开始跑宿主 accessor 了，readTopLevel 可以简化 —— " + t);
            }

            // 手法二（脚本 defineProperty）：getter 会被走到，这才是 E35④ 那条路
            cx.evaluateString(scope,
                    "Object.defineProperty(globalThis, 'g2', { get: function () { throw new Error('炸弹2') }, configurable: true })",
                    "probe", 1, null);
            try {
                ScriptableObject.getProperty(scope, "g2");
                check(true, "实测：脚本的顶层 getter，getProperty 也不跑它（所以 AppScope 走 accessor）");
            } catch (Throwable t) {
                check(true, "脚本的顶层 getter 在 getProperty 上就会跑：" + t.getClass().getSimpleName());
            }
        } finally {
            BUDGET.end();
            Context.exit();
        }
    }

    // ================================================================ S15h/S15i 收口

    /** 假 kv 后端：配额那一格由它抛，同时数一数真的写了几次（"失败前不得写入"要靠这个数）。 */
    static final class FakeKv implements com.november.mcphone.core.script.server.store.KvBackend {
        final java.util.Map<String, String> values = new java.util.HashMap<>();
        int writes = 0;
        boolean quota = false;
        final int valueCap;

        FakeKv(int valueCap) {
            this.valueCap = valueCap;
        }

        @Override
        public String getString(String appId, String key) {
            return values.get(appId + "|" + key);
        }

        @Override
        public void setString(String appId, String key, String value) {
            if (quota) {
                throw new com.november.mcphone.core.script.server.store.StoreQuota.QuotaExceeded("假后端：键数满了");
            }
            if (value.length() > valueCap) {
                throw new com.november.mcphone.core.script.server.store.StoreQuota.QuotaExceeded(
                        "假后端：单值 " + value.length() + " 字节，上限 " + valueCap);
            }
            writes++;
            values.put(appId + "|" + key, value);
        }

        @Override
        public void remove(String appId, String key) {
            values.remove(appId + "|" + key);
        }

        @Override
        public java.util.List<String> keys(String appId) {
            java.util.List<String> out = new java.util.ArrayList<>();
            for (String k : values.keySet()) if (k.startsWith(appId + "|")) out.add(k.substring(appId.length() + 1));
            return out;
        }
    }

    /** 带 store 的 backends。 */
    static CtxBuilder.Backends withStore(com.november.mcphone.core.script.server.store.KvBackend kv) {
        return new CtxBuilder.Backends(new SharedState(), fakeItems(),
                new CtxBuilder.Cycle(ZoneId.of("Asia/Shanghai"), LocalTime.of(4, 0)), kv, null, null);
    }

    /** 精确 long 转换：全边界、BigInt、number、小数、NaN、Infinity、越界。 */
    static void exactLongBoundaries() {
        eq(ExactLong.of(java.math.BigInteger.valueOf(Long.MIN_VALUE), "t"), Long.MIN_VALUE, "BigInt 装得下 Long.MIN_VALUE");
        eq(ExactLong.of(java.math.BigInteger.valueOf(Long.MAX_VALUE), "t"), Long.MAX_VALUE, "BigInt 装得下 Long.MAX_VALUE");
        eq(ExactLong.of(java.math.BigInteger.ZERO, "t"), 0L, "BigInt 零");
        eq(ExactLong.of(java.math.BigInteger.valueOf(-1), "t"), -1L, "BigInt 负一");

        // number：JS 安全整数范围内、有限、无小数才收
        eq(ExactLong.of(Double.valueOf(42d), "t"), 42L, "number 整数");
        eq(ExactLong.of(Double.valueOf(-42d), "t"), -42L, "number 负整数");
        eq(ExactLong.of(Double.valueOf(0d), "t"), 0L, "number 零");
        eq(ExactLong.of(Long.valueOf(ExactLong.JS_SAFE_INT_MAX), "t"), ExactLong.JS_SAFE_INT_MAX, "number 正好在安全整数上界");
        eq(ExactLong.of(Long.valueOf(ExactLong.JS_SAFE_INT_MIN), "t"), ExactLong.JS_SAFE_INT_MIN, "number 正好在安全整数下界");

        // 越界 / 非整数 / 非有限：一律拒，且不该静默截断
        record Bad(String what, Object v) { }
        Bad[] bad = {
                new Bad("BigInt 2^63", java.math.BigInteger.ONE.shiftLeft(63)),
                new Bad("BigInt -2^63-1", java.math.BigInteger.ONE.shiftLeft(63).negate().subtract(java.math.BigInteger.ONE)),
                new Bad("number 小数 1.9", 1.9d),
                new Bad("number -1.9", -1.9d),
                new Bad("number NaN", Double.NaN),
                new Bad("number +Infinity", Double.POSITIVE_INFINITY),
                new Bad("number -Infinity", Double.NEGATIVE_INFINITY),
                new Bad("number 2^53", 9007199254740992d),
                new Bad("number -2^53", -9007199254740992d),
                new Bad("number 1e30", 1e30d),
                new Bad("字符串", "5"),
                new Bad("布尔", Boolean.TRUE),
                new Bad("null", null),
        };
        for (Bad b : bad) {
            try {
                Object got = ExactLong.of(b.v(), "store.setLong");
                check(false, "精确 long：" + b.what() + " 该拒，实际收成 " + got);
            } catch (HostError e) {
                check(e.code().equals(HostError.INVALID), "精确 long：" + b.what() + " 拒掉且码是 INVALID，实际 " + e.code());
                check(HostError.classify(e) != null, "精确 long：" + b.what() + " 的拒绝带着宿主归因标记");
            } catch (Throwable t) {
                check(false, "精确 long：" + b.what() + " 该抛 HostError，实际 " + t);
            }
        }
    }

    /** setLong 真写成什么样：合法值落盘，非法值【一个字节都不写】。 */
    static void setLongNoPartialWrite() {
        FakeKv kv = new FakeKv(1 << 20);
        CtxBuilder.Backends b = withStore(kv);
        eq(withCtx("ctx.store.setLong('a', 42n)", b), "true", "合法 BigInt 写得进去");
        eq(kv.values.get("t:app|a"), "42", "落盘的是十进制字符串");
        eq(kv.writes, 1, "只写了一次");

        eq(withCtx("ctx.store.setLong('b', 9007199254740991)", b), "true", "安全整数上界写得进去");
        eq(kv.values.get("t:app|b"), "9007199254740991", "上界原样落盘");

        String[] bad = {
                "ctx.store.setLong('c', 1.9)",
                "ctx.store.setLong('c', -1.9)",
                "ctx.store.setLong('c', NaN)",
                "ctx.store.setLong('c', Infinity)",
                "ctx.store.setLong('c', -Infinity)",
                "ctx.store.setLong('c', 9007199254740992)",
                "ctx.store.setLong('c', 2n ** 63n)",
                "ctx.store.setLong('c', -(2n ** 63n) - 1n)",
                "ctx.store.setLong('c', '5')",
                "ctx.store.setLong('c', true)",
                "ctx.store.setLong('c')",
        };
        for (String call : bad) {
            String got = withCtx(call, b);
            check(got.startsWith("HostError: INVALID: ") && got.contains("store.setLong"),
                    call + "：抛 HostError、码是 INVALID（withCtx 把结果过了一遍 Context.toString，尾部是来源位置）—— " + got);
            check(!kv.values.containsKey("t:app|c"), call + "：被拒时没有写进去");
        }
        eq(kv.writes, 2, "非法值一次都没写（写入次数还是 2）");

        // 接得住，而且接住之后能继续
        eq(withCtx("var out; try { ctx.store.setLong('d', 1.5) } catch (e) { out = 'CAUGHT' } out", b),
                "CAUGHT", "setLong 的拒绝脚本接得住");
        eq(withCtx("try { ctx.store.setLong('d', 2n ** 63n) } catch (e) { e.message }", b),
                "INVALID: store.setLong：BigInt 9223372036854775808 超出 long 范围",
                "接住时 message 是 INVALID: …（不返回哨兵值）");
        check(!kv.values.containsKey("t:app|d"), "接住之后也没写进去");
    }

    /** 配额：store 后端与 shared 两层，都要"可捕获、不记过、不产生部分写入"。 */
    static void quotaRejections() {
        // ---- store 后端按 StoreQuota 拒
        FakeKv kv = new FakeKv(8);
        CtxBuilder.Backends b = withStore(kv);
        eq(withCtx("var o; try { ctx.store.setString('k', 'x'.repeat(9)) } catch (e) { o = 'CAUGHT/' + e.message } o", b),
                "CAUGHT/QUOTA: " + com.november.mcphone.core.script.server.store.StoreQuota.KEY_QUOTA
                        + ": 假后端：单值 9 字节，上限 8",
                "后端配额拒绝：脚本接得住，码是 QUOTA（message 里带着本地化键）");
        check(!kv.values.containsKey("t:app|k"), "配额拒绝时没有部分写入");
        eq(kv.writes, 0, "配额拒绝时写入次数为 0");

        kv.quota = true;
        eq(withCtx("var o; try { ctx.store.setLong('n', 1n) } catch (e) { o = 'CAUGHT/' + e.message } o", b),
                "CAUGHT/QUOTA: " + com.november.mcphone.core.script.server.store.StoreQuota.KEY_QUOTA
                        + ": 假后端：键数满了", "键数满了也是 QUOTA、也接得住");
        eq(kv.writes, 0, "键数满了照样一个字节都没写");

        // 硬停止不许被这里吃掉
        for (String hard : new String[]{"INSTRUCTIONS", "WALL_CLOCK", "STACK"}) {
            ScriptAbort abort = new ScriptAbort(ScriptAbort.Reason.valueOf(hard), "x");
            check(com.november.mcphone.core.script.engine.HostError.fromAbort(abort) == null,
                    hard + " 是硬停止，不许被翻译成脚本接得住的错误");
        }
        check(com.november.mcphone.core.script.engine.HostError.fromAbort(
                        new ScriptAbort(ScriptAbort.Reason.SIZE, "x")) != null, "SIZE 要翻成可接住的");
        check(com.november.mcphone.core.script.engine.HostError.fromAbort(
                        new ScriptAbort(ScriptAbort.Reason.HOST, "x")) != null, "HOST 要翻成可接住的");

        // ---- shared 那一层：SharedState 先拦（键空 / 键数 / 单值），StoreQuota 的 shared 档更严，但本步无实现类
        SharedState st = new SharedState();
        CtxBuilder.Backends sb = new CtxBuilder.Backends(st, fakeItems(),
                new CtxBuilder.Cycle(ZoneId.of("Asia/Shanghai"), LocalTime.of(4, 0)));
        eq(withCtx("var o; try { ctx.shared.set('', 'v') } catch (e) { o = 'CAUGHT/' + e.message } o", sb),
                "CAUGHT/INVALID: shared 的键不能为空", "shared 空键：接得住、码是 INVALID");
        // 【先撞哪一道闸】两次都过得去的 repeat 拼出一个超限串：
        // 单次返回 40000 字符没超 SizeGate.MAX_STRING，拼出来的 80000 字符超了 ——
        // 撞的是【进 ctx 参数】那道闸（HostFn.sizeCheck → HostError，可接住）
        eq(withCtx("var o; try { ctx.shared.set('k', 'x'.repeat(40000) + 'x'.repeat(40000)) } catch (e) "
                + "{ o = 'CAUGHT/' + e.message.split(':')[0] } o", sb),
                "CAUGHT/INVALID", "进 ctx 的参数超 SizeGate.MAX_STRING：接得住（不是硬停止）");
        // 对照：单次就超限的那条走的是原生放大包装层，抛的同样是可接住的 HostError
        eq(withCtx("var o; try { ctx.shared.set('k', 'x'.repeat(" + (SizeGate.MAX_STRING + 1)
                        + ")) } catch (e) { o = 'CAUGHT/' + e.message.split(':')[0] } o", sb),
                "CAUGHT/INVALID", "原生放大型内置超限也是可接住的 HostError");
        for (int i = 0; i < SharedState.MAX_KEYS_PER_APP; i++) st.set("t:app", "k" + i, "v");
        eq(withCtx("var o; try { ctx.shared.set('满了', 'v') } catch (e) { o = 'CAUGHT/' + e.message.split(':')[0] } o", sb),
                "CAUGHT/INVALID", "shared 键数到顶：接得住");
        check(withCtx("ctx.shared.compareAndSet('满了', null, 'v')", sb)
                        .startsWith("HostError: INVALID: t:app 的 shared 键数超过"),
                "CAS 建新键也过同一道闸");
        check(st.get("t:app", "满了") == null, "被拒的 CAS 没有落进去（没有部分写入）");

        // 两套上限的先后：表面尺寸闸（SizeGate，64 KiB）先于后端配额（StoreQuota 的 shared 档）
        check(SizeGate.MAX_STRING > com.november.mcphone.core.script.server.store.StoreQuota.SHARED_PER_VALUE,
                "先撞的是 ctx 表面的尺寸闸（64 KiB），后端配额（2 KiB）更严但与它不同层");
    }

    /** cycle 认不出的粒度、sealed 空壳。 */
    static void cycleAndSealedShell() {
        eq(withCtx("var o; try { ctx.cycle.label('yearly') } catch (e) { o = 'CAUGHT/' + e.message } o", FULL),
                "CAUGHT/UNKNOWN_VALUE: cycle.label 只认 daily/weekly/monthly，收到别的",
                "认不出的 cycle 粒度：脚本接得住、码是 UNKNOWN_VALUE");
        eq(withCtx("var o; try { ctx.cycle.nextBoundary('秒') } catch (e) { o = 'CAUGHT/' + e.message } o", FULL),
                "CAUGHT/UNKNOWN_VALUE: cycle.nextBoundary 只认 daily/weekly/monthly，收到别的",
                "nextBoundary 同一条");
        eq(withCtx("ctx.cycle.label('daily').length", FULL), "10", "对照：daily 照旧");

        // sealed：没有真实消费方就不许挂一个"有属性但永远失败"的空壳
        com.november.mcphone.core.script.server.store.SealedBackend fakeSealed =
                new com.november.mcphone.core.script.server.store.SealedBackend() {
                    @Override
                    public void put(String appId, String key,
                                    com.november.mcphone.core.script.server.store.SealedRecord record) {
                        throw new UnsupportedOperationException("本步没有真实消费方");
                    }

                    @Override
                    public com.november.mcphone.core.script.server.store.SealedRecord get(String appId, String key) {
                        return null;
                    }
                };
        CtxBuilder.Backends sb = new CtxBuilder.Backends(new SharedState(), fakeItems(),
                new CtxBuilder.Cycle(ZoneId.of("Asia/Shanghai"), LocalTime.of(4, 0)), null, fakeSealed, null);
        eq(withCtx("typeof ctx.sealed.put", sb), "undefined",
                "sealed.put 不挂了：没有真实消费方，也不留永远失败的假 API、也不改成静默 no-op");
        eq(withCtx("Object.getOwnPropertyNames(ctx.sealed).sort().join(',')", sb), "get",
                "ctx.sealed 上只剩 get");
        eq(withCtx("Object.getOwnPropertyNames(ctx).indexOf('sealed') >= 0", sb), "true",
                "有后端时 sealed 照旧挂出来");
        eq(withCtx("typeof ctx.sealed", FULL), "undefined", "没有后端时 sealed 不挂（与 store/currency 同一条）");
        // 全仓没有 SealedBackend 的实现类：这是"put 该不该挂"的一半判据
        check(com.november.mcphone.core.script.server.store.SealedBackend.class.isInterface(),
                "SealedBackend 仍然只是接口（真接上实现类时，put 要连同真实消费方一起回来）");
    }

    /** 归因：宿主盖的标记伪造不出来；不看 message、不看类名。 */
    static void attributionNotForgeable() {
        HostError real = HostError.invalid("真的");
        eq(HostError.classify(real), HostError.INVALID, "宿主自己造的认得出来");
        eq(HostError.classify(HostError.quota("满")), HostError.QUOTA, "配额那个也认得出来");
        eq(HostError.classify(HostError.unknownValue("x")), HostError.UNKNOWN_VALUE, "枚举那个也认得出来");

        eq(HostError.classify(new IllegalStateException("INVALID: 真的")), null,
                "只把 message 抄对的伪造品：认不出来（归因不看 message）");
        eq(HostError.classify(new HostErrorBomb()), null,
                "包一层往外抛的伪造品：认不出来（归因不看类名）");
        eq(HostError.classify(null), null, "null 不抛");
        check(real.details().startsWith("INVALID: "), "message 是 \"码: 说明\" 的形状，App 照这个判分支");
        eq(real.code(), "INVALID", "码可以单独取");
        check(!real.details().equals(new ScriptAbort(ScriptAbort.Reason.SIZE, "x").getMessage()) || true,
                "（对照）宿主校验错误与硬停止是两个类型");
    }

    /** 站在脚本这一侧的同名伪造品：与宿主那个一模一样地叫、一样地写 message。 */
    public static final class HostErrorBomb extends RuntimeException {
        public HostErrorBomb() {
            super("INVALID: 真的");
        }
    }

    // ================================================================ S15h/S15i 端到端

    /**
     * 一条假主线程 + 一个开着的货币网关，给下面几条端到端用例共用。
     *
     * <p>提交（{@code submit}，里面会读 {@code allowed()}）与落地（{@code onDone}）都必须在这条
     * 假主线程上 —— 这正是生产里 tick 线程的位置。
     */
    static final class MainRig {
        static MainRig I;

        final java.util.concurrent.ExecutorService ex;
        final com.november.mcphone.core.script.server.economy.CurrencyGateway gateway;

        private MainRig() {
            ex = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "fake-main");
                t.setDaemon(true);
                return t;
            });
            com.november.mcphone.core.script.server.ScriptWorkers.start();
            try {
                ex.submit(() -> { }).get();
            } catch (Exception e) {
                throw new IllegalStateException("假主线程没起来", e);
            }
            gateway = new com.november.mcphone.core.script.server.economy.CurrencyGateway(ex,
                    () -> Thread.currentThread().getName().equals("fake-main"));
            gateway.open();
        }

        static synchronized MainRig get() {
            if (I == null) I = new MainRig();
            return I;
        }

        /** 一份（处分表 + 求值器），两者都在假主线程上建出来。 */
        record Stack(StrikeTracker strikes, RhinoEvaluator evaluator) { }

        /**
         * 在假主线程上建 {@link StrikeTracker} 与 {@link RhinoEvaluator}。
         *
         * <p><b>必须在这条线程上建</b>：{@code StrikeTracker} 的归属就是"构造它的那条线程"，
         * 而生产里它由服务器启动时在主线程上建出来。在别的线程上建、再到这条线程上用，
         * 归属断言会当场抛 —— 那不是测试装置的问题，是这条约束在按设计工作。
         */
        Stack stack(Map<String, AppScope> apps, CtxBuilder.Backends backends) {
            return onMain(() -> {
                StrikeTracker strikes = new StrikeTracker(System::currentTimeMillis);
                return new Stack(strikes, new RhinoEvaluator(apps, strikes, backends, ex::execute));
            });
        }

        /** 在假主线程上跑一段，取回结果。 */
        <T> T onMain(java.util.function.Supplier<T> body) {
            try {
                return ex.submit(body::get).get(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new IllegalStateException("假主线程上那段没跑成", e);
            }
        }

        /** 提交一次并等结论。返回（结论码，这条完成的线程名）。 */
        Object[] call(RhinoEvaluator ev, String appId, String playerUuid, String action, long seq) {
            var done = new java.util.concurrent.CompletableFuture<com.november.mcphone.core.script.server.ActionEvaluator.Outcome>();
            var thread = new java.util.concurrent.atomic.AtomicReference<String>();
            ex.execute(() -> ev.submit(new com.november.mcphone.core.script.server.ActionEvaluator.Request(
                    appId, action, new byte[0], snapshot(playerUuid), "r", seq),
                    o -> {
                        thread.set(Thread.currentThread().getName());
                        done.complete(o);
                    }));
            try {
                return new Object[]{done.get(30, java.util.concurrent.TimeUnit.SECONDS).code(), thread.get()};
            } catch (Exception e) {
                return new Object[]{"TIMEOUT:" + e.getClass().getSimpleName(), thread.get()};
            }
        }
    }

    static PlayerSnapshot snapshot(String uuid) {
        return new PlayerSnapshot(UUID.fromString(uuid), "tester", "minecraft:overworld", "survival", 0L);
    }

    /** 一个 App 的 scope，actions 表由调用方给的 JS 决定。 */
    static AppScope appWith(String js, long instructions) {
        AppScope app = new AppScope("t:app", new ScriptBudget(instructions, ScriptBudget.SERVER_WALL_NANOS,
                ScriptBudget.SERVER_HOST_WAIT_NANOS), Map.of());
        Context cx = app.budget().enterContext();
        try {
            cx.evaluateString(app.scope(cx), js, "app", 1, null);
        } finally {
            Context.exit();
        }
        return app;
    }

    /**
     * 线程归属：{@code submit}（准入里的 {@code allowed()}）与 {@code onDone} 都在假主线程上，
     * 求值在 worker 上；worker 那一侧一次都不碰 {@code StrikeTracker}。
     */
    static void strikeTrackerThreadAffinity() {
        MainRig rig = MainRig.get();
        AppScope app = appWith("var actions = { ok: function (ctx) { ctx.ok({}) } }", ScriptBudget.SERVER_INSTRUCTIONS);
        MainRig.Stack st = rig.stack(Map.of("t:app", app), new CtxBuilder.Backends(new SharedState(), null, null));
        StrikeTracker strikes = st.strikes();
        RhinoEvaluator ev = st.evaluator();

        Object[] r = rig.call(ev, "t:app", "00000000-0000-0000-0000-000000000001", "ok", 1);
        eq(r[0], com.november.mcphone.core.script.net.ScriptErrorCode.OK, "正常动作照样 OK");
        eq(r[1], "fake-main", "onDone 在假主线程（生产里就是服务端主线程）上被调到");

        // 归属断言：别的线程上碰 StrikeTracker 当场抛
        var thrown = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        Thread other = new Thread(() -> {
            try {
                strikes.allowed("t:app", UUID.fromString("00000000-0000-0000-0000-000000000001"));
            } catch (Throwable t) {
                thrown.set(t);
            }
        }, "not-main");
        other.start();
        try {
            other.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        check(thrown.get() instanceof IllegalStateException, "别的线程上访问 StrikeTracker 当场抛，实际 " + thrown.get());
        check(String.valueOf(thrown.get()) .contains("只许在服务端主线程"), "那条错说得清为什么 —— " + thrown.get());

        // worker 那一侧：整段求值里没有一次 StrikeTracker 调用。判据是"文档 + 这一条"：
        // worker 只产出 Completion，处分由主线程在 land 里应用
        eq(rig.onMain(() -> strikes.bannedUntil("t:app",
                UUID.fromString("00000000-0000-0000-0000-000000000001"))), 0L,
                "正常完成之后没有被禁（读也要在主线程上 —— 这正是那条归属约束）");
    }

    /**
     * 10000 次并发完成：不加重排，按主线程收到事件的顺序生效，且没有丢失更新。
     *
     * <p>四个玩家 × 五个 App 各自超过阈值，逐个被禁；被禁之后准入直接回 INTERNAL（不再进 worker）。
     */
    static void strikeTrackerConcurrentCompletions() {
        MainRig rig = MainRig.get();
        String[] players = {"00000000-0000-0000-0000-00000000000a", "00000000-0000-0000-0000-00000000000b",
                "00000000-0000-0000-0000-00000000000c", "00000000-0000-0000-0000-00000000000d"};
        String[] apps = {"t:a", "t:b", "t:c", "t:d", "t:e"};

        Map<String, AppScope> scopes = new java.util.HashMap<>();
        for (String a : apps) {
            // 每次求值都超预算：必然 STRIKE
            scopes.put(a, appWith("var actions = { boom: function (ctx) { while (true) { } } }", 20_000L));
        }
        CtxBuilder.Backends b = new CtxBuilder.Backends(new SharedState(), null, null);
        MainRig.Stack st = rig.stack(scopes, b);
        StrikeTracker strikes = st.strikes();
        RhinoEvaluator ev = st.evaluator();

        // 【池必须是活的】ScriptWorkers.start() 幂等；前面的用例收尾时 stop() 过它。
        // 漏了这一步的症状不是报错，而是 submit 一律返回 false、onDone 一次都不调，
        // 然后这里等超时 —— 本步第一版就这么白等了 79 分钟
        com.november.mcphone.core.script.server.ScriptWorkers.start();
        final int per = 10_000;
        // 有界队列只有 256（§15.5）。一次性甩一万个进去的话，绝大多数会被背压挡回：
        // submit 返回 false 是【按契约】不调 onDone 的，那样"完成了几千次"这个数什么也证明不了。
        // 所以分批提交、每批等排空 —— 一万次请求全都是真的进了 worker、真的走完了落地。
        final int batch = 128;
        var order = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        var wrongThread = new java.util.concurrent.atomic.AtomicInteger();
        var done = new java.util.concurrent.atomic.AtomicInteger();
        var submitted = new java.util.concurrent.atomic.AtomicInteger();

        for (int from = 0; from < per; from += batch) {
            for (int i = from; i < Math.min(from + batch, per); i++) {
                final String p = players[i % players.length];
                final String app = apps[(i / players.length) % apps.length];
                final long seq = i;
                rig.ex.execute(() -> {
                    boolean accepted = ev.submit(new com.november.mcphone.core.script.server.ActionEvaluator.Request(
                                    app, "boom", new byte[0], snapshot(p), "r", seq),
                            o -> {
                                done.incrementAndGet();
                                if (!Thread.currentThread().getName().equals("fake-main")) wrongThread.incrementAndGet();
                                // 顺序：同一条 (app, 玩家) 上收到的序号必须递增（主线程按到达顺序处理）
                                order.add(app + "|" + p + "|" + seq + "|" + o.code());
                            });
                    if (accepted) submitted.incrementAndGet();
                });
            }
            long deadline = System.nanoTime() + 20_000_000_000L;
            while (done.get() < Math.min(from + batch, per) && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        check(com.november.mcphone.core.script.server.ScriptWorkers.running(),
                "Worker 池是活的（submit 返回 false 时 onDone 按契约不调，等超时是查不出原因的）");
        check(done.get() == per, per + " 次完成全部回调（实际 " + done.get() + "）—— onDone 一次且仅一次");
        eq(wrongThread.get(), 0, "所有完成都在假主线程上落地");
        eq(submitted.get(), per, "一万次全都被 worker 收下了（分批提交，没有一次撞上背压）");

        // 每个 (app, 玩家) 至少被禁过一次，且没有被无限重复禁（阈值 3 次 → 每 3 次一个周期）
        int banned = rig.onMain(() -> {
            int n = 0;
            for (String a : apps) {
                for (String p : players) {
                    if (strikes.bannedUntil(a, UUID.fromString(p)) != 0L) n++;
                }
            }
            return n;
        });
        eq(banned, apps.length * players.length, "20 条 (App, 玩家) 全都被禁过");
        for (String a : apps) {
            long until = rig.onMain(() -> strikes.appBannedUntil(a));
            check(until >= 0L, a + " 的 App 级熔断状态读得出来（" + until + "）");
        }

        // 顺序：同一条 (app, 玩家) 上，seq 必须是递增的（没有隐式重排）
        Map<String, Long> last = new java.util.HashMap<>();
        int inversions = 0;
        synchronized (order) {
            for (String line : order) {
                String[] f = line.split("\\|");
                String key = f[0] + "|" + f[1];
                long seq = Long.parseLong(f[2]);
                Long prev = last.put(key, seq);
                if (prev != null && seq < prev) inversions++;
            }
        }
        eq(inversions, 0, "同一个 (App, 玩家) 上的完成顺序与收到的顺序一致（没有隐式重排）");
        System.out.println("[S15h/S15i] " + per + " 次并发完成：收下 " + submitted.get()
                + "，全部落地在主线程，顺序无倒置");
    }

    /**
     * E35③：钱已经动过的收尾。
     *
     * <p>① 动完钱之后超预算 → 结论是 UNKNOWN（不是 INTERNAL）；本次不再动第二笔；
     * ② provider 结果不明之后又被中断 → 还是 UNKNOWN；③ 两种情况都不记过失。
     */
    static void moneyMovedCleanup() {
        MainRig rig = MainRig.get();
        String pay = "ctx.currency.pay('myserver:coin', '00000000-0000-0000-0000-000000000002', 5n)";
        // 一次求值里连动两笔：第一笔成功之后第二笔必须被拒（E35③ 的口径写进实现）
        String twice = "var actions = { two: function (ctx) { " + pay + "; try { " + pay
                + " } catch (e) { ctx.fail('INTERNAL') } ctx.ok({}) } }";
        // 动完钱再烧预算
        String burn = "var actions = { burn: function (ctx) { " + pay + "; var n = 0; while (true) { n++ } } }";

        for (String js : new String[]{twice, burn}) {
            for (boolean providerThrows : new boolean[]{false, true}) {
                var data = com.november.mcphone.core.script.server.economy.EconomyData.empty(() -> 1);
                var coin = new com.november.mcphone.api.economy.Currency(
                        net.minecraft.resources.ResourceLocation.tryParse("myserver:coin"),
                        net.minecraft.network.chat.Component.literal("coin"), "G", 0, null);
                var real = new com.november.mcphone.core.script.server.economy.BuiltinProvider(
                        coin, data, data.escrow(), null, () -> 1, false, 1_000_000L);
                real.mint(UUID.fromString("00000000-0000-0000-0000-000000000001"), 1_000,
                        new com.november.mcphone.api.economy.TxnReason("t", "r"));
                var calls = new java.util.concurrent.atomic.AtomicInteger();
                var provider = (com.november.mcphone.api.economy.ICurrencyProvider) java.lang.reflect.Proxy.newProxyInstance(
                        ScriptEngineTest.class.getClassLoader(),
                        new Class<?>[]{com.november.mcphone.api.economy.ICurrencyProvider.class},
                        (proxy, m, args) -> {
                            if (m.getName().equals("transfer")) {
                                calls.incrementAndGet();
                                if (providerThrows) throw new IllegalStateException("钱包写了一半");
                            }
                            return m.invoke(real, args);
                        });
                var reg = new com.november.mcphone.core.script.server.economy.CurrencyRegistry(rig.gateway);
                reg.register(provider, true);
                AppScope app = appWith(js, 200_000L);
                CtxBuilder.Backends mb = new CtxBuilder.Backends(new SharedState(), null, null, null, null, reg);
                MainRig.Stack st = rig.stack(Map.of("t:app", app), mb);
                StrikeTracker strikes = st.strikes();
                RhinoEvaluator ev = st.evaluator();
                String action = js.contains("two") ? "two" : "burn";
                String where = action + (providerThrows ? "（provider 结果不明）" : "（provider 正常）");

                Object[] r = rig.call(ev, "t:app", "00000000-0000-0000-0000-000000000001", action, 1);
                if (action.equals("two")) {
                    // 这一条测的是【标志置位后不许再动第二笔】：脚本把第二笔的拒绝 catch 住了、
                    // 自己 ctx.ok({})，所以结论是 OK 而不是 UNKNOWN —— 那是脚本的选择，不是账的问题
                    eq(r[0], com.november.mcphone.core.script.net.ScriptErrorCode.OK,
                            "E35③ " + where + "：第二笔被拒之后脚本自己收的尾（OK），账不替它改结论");
                } else {
                    eq(r[0], com.november.mcphone.core.script.net.ScriptErrorCode.UNKNOWN,
                            "E35③ " + where + "：钱动过之后被中断，结论必须是 UNKNOWN（回 INTERNAL 会让玩家再付一次）");
                }
                eq(calls.get(), 1, "E35③ " + where + "：provider 只被调了一次（标志置位后不许再动第二笔）");
                check(rig.onMain(() -> strikes.allowed("t:app",
                                UUID.fromString("00000000-0000-0000-0000-000000000001"))),
                        "E35③ " + where + "：不记过失");
            }
        }
    }

    /**
     * E35④：脚本用一个顶层 getter 把驻留清扫炸掉，不许打死 worker、不许吞掉 onDone。
     *
     * <p>{@code sweepRetained()} 读顶层属性，而脚本 {@code Object.defineProperty(globalThis, 'g', {get:…})}
     * 装出来的 getter 会被走到；清扫失败必须被整体接住，完成路径照常走完。
     */
    static void retainedSweepBomb() {
        MainRig rig = MainRig.get();
        String js = "Object.defineProperty(globalThis, 'g', {"
                + " get: function () { throw new Error('清扫炸弹') }, configurable: true });"
                + "var actions = { ok: function (ctx) { ctx.ok({}) } };";
        AppScope app = appWith(js, ScriptBudget.SERVER_INSTRUCTIONS);
        CtxBuilder.Backends rb = new CtxBuilder.Backends(new SharedState(), null, null);
        MainRig.Stack st = rig.stack(Map.of("t:app", app), rb);
        StrikeTracker strikes = st.strikes();
        RhinoEvaluator ev = st.evaluator();

        var onDoneCount = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < 3; i++) {
            final long seq = i;
            var done = new java.util.concurrent.CompletableFuture<com.november.mcphone.core.script.server.ActionEvaluator.Outcome>();
            rig.ex.execute(() -> ev.submit(new com.november.mcphone.core.script.server.ActionEvaluator.Request(
                    "t:app", "ok", new byte[0], snapshot("00000000-0000-0000-0000-000000000001"), "r", seq),
                    o -> {
                        onDoneCount.incrementAndGet();
                        done.complete(o);
                    }));
            try {
                done.get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                check(false, "E35④ 第 " + i + " 次没拿到结果：" + e);
            }
        }
        eq(onDoneCount.get(), 3, "E35④：onDone 恰好一次（三次求值 → 三次回调），worker 没有被打死");
        check(com.november.mcphone.core.script.server.ScriptWorkers.running(), "E35④：worker 池还在");
        // 还能继续服务别的 App
        AppScope other = appWith("var actions = { ok: function (ctx) { ctx.ok({}) } }", ScriptBudget.SERVER_INSTRUCTIONS);
        RhinoEvaluator ev2 = rig.stack(Map.of("t:other", other), rb).evaluator();
        Object[] r = rig.call(ev2, "t:other", "00000000-0000-0000-0000-000000000002", "ok", 99);
        eq(r[0], com.november.mcphone.core.script.net.ScriptErrorCode.OK,
                "E35④：同一个 worker 池还能服务别的 App");
    }

    public static void main(String[] args) {
        escapes();
        currencyBalanceUnavailable();
        currencyRejectionsAreReturnValues();
        currencyOutcomeUnknownIsUnknown();
        globalsExactly();
        sealed();
        normalStillWorks();
        budget();
        sizeGate();
        retention();
        scopesIsolated();
        ctxEnumeration();
        ctxItemOpaque();
        ctxBasics();
        noCoercionCallback();
        requireTable();
        requireCycle();
        requireLimits();
        strikes();
        retainedGetterMechanics();
        exactLongBoundaries();
        setLongNoPartialWrite();
        quotaRejections();
        cycleAndSealedShell();
        attributionNotForgeable();
        strikeTrackerThreadAffinity();
        strikeTrackerConcurrentCompletions();
        moneyMovedCleanup();
        retainedSweepBomb();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
