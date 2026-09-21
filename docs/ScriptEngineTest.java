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

    static boolean aborted(String src, ScriptAbort.Reason reason) {
        String r = run(src);
        return r.startsWith("ScriptAbort: " + reason);
    }

    // ================================================================ §16.3 逃逸

    static void escapes() {
        check(!run("eval('42')").equals("42"), "eval 没了");
        check(!run("new Function('return 42')()").equals("42"), "Function 没了");
        // §16.3 的配方照抄之后 Script 还在（实测 new Script('40+2')() → 42）。
        // 它拿不到 Java，但它是第三个运行时编译入口 —— 击穿了 §16.3 自己写的
        // 「运行的代码可以和 OP 审的源码不是一回事」那条理由
        check(!run("new Script('40+2')()").equals("42"), "Script 没了 —— 第三个运行时编译入口");
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
        check(!run("Object.prototype.p=1; ({}).p").equals("1"), "Object.prototype 封了");
        check(!run("Array.prototype.q=1; [].q").equals("1"), "Array.prototype 封了");
        check(!run("String.prototype.trim=function(){return 'X'}; ' a '.trim()").equals("X"), "改内置封了");
        // §16.3 的 SEAL 只点了 10 个名字，Map 不在里面 —— 实测能污染
        check(!run("Map.prototype.zz=1; new Map().zz").equals("1"), "Map.prototype 也要封");
        check(!run("Set.prototype.zz=1; new Set().zz").equals("1"), "Set.prototype 也要封");
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

    /** 预算只在分支点生效，大分配靠尺寸闸拦（§16.4 的三件事 ①②）。 */
    static void sizeGate() {
        check(rejectedBySizeGate("'x'.repeat(100000000).length"), "repeat 放大被拦");
        check(rejectedBySizeGate("new Array(100000000).join('')"), "join 放大被拦");
        check(rejectedBySizeGate("var s='x';for(var i=0;i<30;i++)s+=s; s.indexOf('y')"),
                "rope 物化被拦 —— 实测 9 毫秒能打爆 256 MB 堆");
        check(rejectedBySizeGate("var a=[];a.length=100000000; a.fill(1)"), "fill 放大被拦");
        check(rejectedBySizeGate("var n=0;var o={get length(){n++;return n===1?1:10000000000},"
                        + "indexOf:Array.prototype.indexOf};o.indexOf('z')"),
                "类数组 length TOCTOU 在进入原生 indexOf 前拒绝");
        check(rejectedBySizeGate("var o={get length(){return o.indexOf('x')},"
                        + "indexOf:Array.prototype.indexOf};o.indexOf('x')"),
                "包装层递归不能落到 StackOverflowError");
        check(rejectedBySizeGate("new Array(1024).fill(new Array(1024).fill("
                        + "new Array(1024).fill(0))).flat(2).length"),
                "flat 深层展开必须在原生分配前被拒绝");
        eq(run("[[1,2],[3]].flat().join(',')"), "1,2,3", "小型 flat 保持可用");
        eq(run("[[[1]],[[2]]].flat(2).join(',')"), "1,2", "带 depth 的小型 flat 保持可用");
        check(rejectedBySizeGate("[1].flatMap(function(){return new Array(4096).fill(0)})"),
                "flatMap 回调输出无法预检，必须在执行回调前拒绝");
        check(rejectedBySizeGate("var a=[];a[0]=a;a.flat(Infinity)"),
                "flat 循环数组必须在原生递归前拒绝");
        eq(run("var n=0,a=[];Object.defineProperty(a,'0',{get:function(){n++;return [1]}});"
                        + "try{a.flat()}catch(e){};n"), "0",
                "flat 预检不得执行数组 getter");
        eq(run("Array.from({0:'a',1:'b',length:2}).join(',')"), "a,b",
                "Array.from 保留有界 array-like 路径");
        eq(run("Array.from('ab').join(',')"), "a,b", "Array.from 保留有界字符串路径");
        eq(run("Array.from([1,2]).join(',')"), "1,2", "Array.from 保留原生数组路径");
        check(rejectedBySizeGate("Array.from({length:4097})"),
                "Array.from array-like 超限必须在分配前拒绝");
        check(rejectedBySizeGate("Array.from('a'.repeat(4097))"),
                "Array.from 字符串超限必须在分配前拒绝");
        check(rejectedBySizeGate("Array.from(new Array(1000000))"),
                "Array.from 稀疏原生数组超限必须在迭代前拒绝");
        eq(run("var n=0,o={};Object.defineProperty(o,'length',{get:function(){n++;return 1}});"
                        + "try{Array.from(o)}catch(e){};n"), "0",
                "Array.from 预检不得执行 length getter");
        eq(run("var n=0,o={length:1};Object.defineProperty(o,'0',{get:function(){n++;return 1}});"
                        + "try{Array.from(o)}catch(e){};n"), "0",
                "Array.from 预检不得执行元素 getter");
        eq(run("var n=0,o={};Object.defineProperty(o,Symbol.iterator,{get:function(){n++;return function(){}}});"
                        + "try{Array.from(o)}catch(e){};n"), "0",
                "Array.from 预检不得执行 Symbol.iterator getter");
        eq(run("var n=0;function* g(){n++;while(true)yield 1}try{Array.from(g())}catch(e){};n"), "0",
                "Array.from 生成器必须在启动前拒绝");
        eq(run("var n=0;try{Array.from([1],function(x){n++;return x})}catch(e){};n"), "0",
                "Array.from 映射回调必须在执行前拒绝");
        check(rejectedBySizeGate("var a=[1];a[Symbol.iterator]=function*(){while(true)yield 1};Array.from(a)"),
                "Array.from 不接受数组自定义迭代器");
        eq(run("var n=0;function C(){n++;return []}try{Array.from.call(C,[1])}catch(e){};n"), "0",
                "Array.from 不得执行脚本结果构造器");
        eq(run("var n=0,o={get x(){n++;return 'x'}};try{JSON.stringify(o)}catch(e){};n"), "0",
                "JSON 预检不执行脚本 getter");
        eq(SizeGate.MAX_STRING, 64 * 1024, "§16.4 ① 的字符串上限");
        eq(SizeGate.MAX_ARRAY, 4096, "§16.4 ① 的数组上限");
    }

    static boolean rejectedBySizeGate(String source) {
        return run(source).startsWith("HostError: INVALID:");
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
        check(!withCtx("ctx.evil=1; typeof ctx.evil", FULL).equals("number"), "ctx 封了，加不了属性");

        // 表里没有的一律没有（§32.7：store/currency/mailbox/fetch 的后端还没到货）
        for (String absent : new String[]{"store", "currency", "mailbox", "fetch", "give", "loot", "command", "predicate"}) {
            eq(withCtx("typeof ctx." + absent, FULL), "undefined",
                    "ctx." + absent + " 本步没有后端，就不该挂出来");
        }

        // S18 谓词：有门面才挂；只有 test 一个方法，判定结果原样透传（plain 档、不过能力门）
        CtxBuilder.Backends withPredicate = new CtxBuilder.Backends(
                new SharedState(), fakeItems(),
                new CtxBuilder.Cycle(ZoneId.of("Asia/Shanghai"), LocalTime.of(4, 0)),
                null, null, null, false,
                (id, snapshot) -> "myserver:is_vip".equals(id) ? Boolean.TRUE : null);
        eq(withCtx("Object.getOwnPropertyNames(ctx.predicate).sort().join(',')", withPredicate),
                "test", "ctx.predicate 只有 test");
        eq(withCtx("String(Object.getPrototypeOf(ctx.predicate))", withPredicate), "null",
                "ctx.predicate 也没有原型链");
        eq(withCtx("ctx.predicate.test('myserver:is_vip')", withPredicate), "true",
                "谓词判定结果透传");
        Throwable noSuch = thrownBy("ctx.predicate.test('myserver:nope')", withPredicate);
        check(noSuch instanceof com.november.mcphone.core.script.engine.HostError he
                        && "mcphone.script.predicate.unavailable".equals(he.messageKey()),
                "认不得的谓词是配置错：回专门的文案键，不当判否 —— " + noSuch);
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

        check(failsWith(() -> m.require("server/gift.js", "server.js", (k, s) -> null), "只许包内相对路径"),
                "不带 ./ 的一律拒");
        check(failsWith(() -> m.require("./nope.js", "server.js", (k, s) -> null), "找不到"), "表里没有就拒");

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
        check(failsWith(() -> m.require("./a.js", "server.js",
                (k, s) -> m.require("./b.js", k, (k2, s2) -> m.require("./a.js", k2, (k3, s3) -> null))),
                "循环依赖"), "循环依赖要报出来");
        check(failsWith(() -> m.require("./a.js", "server.js",
                        (k, s) -> m.require("./b.js", k,
                                (k2, s2) -> m.require("./a.js", k2, (k3, s3) -> null))),
                "a.js -> b.js -> a.js"), "循环链按真实调用方向输出");
    }

    static void requireLimits() {
        java.util.Map<String, String> many = new java.util.HashMap<>();
        for (int i = 0; i <= ScriptModules.MAX_MODULES; i++) many.put("m" + i + ".js", "");
        check(failsWith(() -> new ScriptModules(many), "上限"), "模块数超限在建表时就拒");
        check(failsWith(() -> new ScriptModules(Map.of("a.vue", "")), "非 .js"), "非 .js 拒");
        for (String bad : List.of("../../evil.js", "/abs.js", "a\\b.js", "C:x.js", "..js", ".js")) {
            check(failsWith(() -> new ScriptModules(Map.of(bad, "")), "非规范名"),
                    "非规范模块名拒绝：" + bad);
        }
    }

    static boolean failsWith(Runnable body, String fragment) {
        try {
            body.run();
            return false;
        } catch (Throwable failure) {
            return String.valueOf(failure.getMessage()).contains(fragment);
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
        check(uncaught.startsWith("HostError") || uncaught.startsWith("EcmaError"),
                "没接住时是可捕获宿主错误（不记过失），不是 ScriptAbort：" + uncaught);
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

        eq(withCtx("try { ctx.currency.parse(" + c + ", '1.234') } catch (e) { e.message }", b),
                "INVALID: mcphone.economy.invalid_amount", "小数位超了：parse 抛可捕获错误");
        eq(withCtx("try { ctx.currency.parse(" + c + ", '十块') } catch (e) { e.message }", b),
                "INVALID: mcphone.economy.invalid_amount", "不是数：parse 抛可捕获错误");
        eq(withCtx("String(ctx.currency.parse(" + c + ", '1.23'))", b), "123", "对照：合法的照常解析");
        eq(withCtx("var out='before'; try { ctx.currency.parse(" + c + ", 'abc') } catch(e) { out='continued' } out", b),
                "continued", "parse 错误可捕获，捕获后脚本可继续");
        eq(withCtx("var used=false; try { used=(ctx.currency.parse(" + c + ", 'abc') + 1) > 0 }"
                        + " catch(e) { } String(used)", b),
                "false", "parse 错误值不能进入算术或比较");
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

        // parse 在产生任何哨兵值前就抛错，后续 pay 不会被调用。
        eq(withCtx("try { ctx.currency.pay(" + c + ", " + to + ", ctx.currency.parse(" + c + ", 'abc')) }"
                        + " catch (e) { e.message }", b), "INVALID: mcphone.economy.invalid_amount",
                "pay(parse(坏输入))：parse 先抛，错误值不继续流动");
        eq(withCtx("ctx.currency.hold(" + c + ", " + to + ", undefined)", b), "INVALID", "金额 undefined：INVALID");
        eq(withCtx("try { ctx.currency.format(" + c + ", ctx.currency.parse(" + c + ", 'abc')) } catch (e) { e.message }", b),
                "INVALID: mcphone.economy.invalid_amount", "format(parse(坏输入))：接得住的 Error，不中断");
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
        String badAmountType = withCtx("ctx.currency.pay(" + c + ", " + to + ", 5)", b);
        check(badAmountType.startsWith("ScriptAbort") || badAmountType.startsWith("HostError"),
                "金额传了 Number 不是 BigInt：宿主拒绝");

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
        eq(withCtx("try { ctx.currency.parse(" + c + ", null) } catch (e) { e.message }", b),
                "INVALID: mcphone.economy.invalid_amount", "parse 没给文本：抛可捕获错误");
        String badRecipient = withCtx("ctx.currency.pay(" + c + ", 5, 5n)", b);
        check(badRecipient.startsWith("HostError") || badRecipient.startsWith("ScriptAbort"),
                "收款人传了 Number：宿主拒绝");

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
                        if (mode.get().equals("abort")) throw new ScriptAbort(ScriptAbort.Reason.HOST,
                                "provider\u2028abort\u202E§");
                        if (mode.get().equals("error")) throw new AssertionError("provider\u2029error§");
                        if (mode.get().equals("null")) return null;
                    }
                    if (m.getName().equals("balance") && mode.get().equals("bal")) throw new NoSuchMethodError("换了版本");
                    return m.invoke(real, args);
                });

        java.util.concurrent.atomic.AtomicReference<Thread> mainT = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.ExecutorService mainEx = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread th = new Thread(r, "fake-main");
            th.setDaemon(true);
            mainT.set(th);
            return th;
        });
        com.november.mcphone.core.script.server.ScriptWorkers.start();
        try {
            mainEx.submit(() -> { }).get();
            var gw = new com.november.mcphone.core.script.server.economy.CurrencyGateway(mainEx::execute,
                    () -> Thread.currentThread() == mainT.get());
            gw.open();
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
                        + " buyabort: function (ctx) { " + pay + "; while (true) {} },"
                        + " buytwice: function (ctx) { " + pay + "; " + pay + "; ctx.ok({}) },"
                        + " buyfin: function (ctx) { try { " + pay + " } finally { ctx.ok({}); return } },"
                        + " buycatch: function (ctx) { try { " + pay + " } catch (e) { } ctx.ok({}) },"
                        + " okfirst: function (ctx) { ctx.ok({}); " + pay + " },"
                        + " bal: function (ctx) { try { ctx.currency.balance('myserver:coin') } finally { ctx.ok({}); return } } }",
                        "app", 1, null);
            } finally {
                Context.exit();
            }
            StrikeTracker strikes = mainEx.submit(() -> new StrikeTracker(System::currentTimeMillis)).get();
            RhinoEvaluator ev = new RhinoEvaluator(Map.of("t:app", app), strikes, b, mainEx::execute);
            java.util.function.Function<String, Object> call = action -> {
                var f = new java.util.concurrent.CompletableFuture<com.november.mcphone.core.script.server.ActionEvaluator.Outcome>();
                try {
                    mainEx.submit(() -> ev.submit(new com.november.mcphone.core.script.server.ActionEvaluator.Request(
                            "t:app", action, new byte[0], player(), "r", 1), f::complete)).get();
                    return f.get(10, java.util.concurrent.TimeUnit.SECONDS).code();
                } catch (Exception e) {
                    return e.getClass().getSimpleName();
                }
            };

            eq(call.apply("buy"), com.november.mcphone.core.script.net.ScriptErrorCode.OK, "对照：provider 正常时 OK");
            eq(call.apply("buyabort"), com.november.mcphone.core.script.net.ScriptErrorCode.UNKNOWN,
                    "pay 已返回后再中断：UNKNOWN 优先且不记过");
            eq(call.apply("buytwice"), com.november.mcphone.core.script.net.ScriptErrorCode.UNKNOWN,
                    "钱已动过后第二次动钱被拒，最终仍为 UNKNOWN");
            // 抛了、没给结果（返回 null）都是结果不明；脚本先调过 ctx.ok 也不算数
            for (String m : new String[]{"pay", "null", "abort", "error"}) {
                mode.set(m);
                for (String action : new String[]{"buy", "buyfin", "buycatch", "okfirst"}) {
                    moved.set(0);
                    eq(call.apply(action), com.november.mcphone.core.script.net.ScriptErrorCode.UNKNOWN,
                            m + " " + action + "：provider 动钱时抛异常、ScriptAbort、Error 或没给结果 → UNKNOWN");
                    eq(moved.get(), 1, m + " " + action + "：provider 只被调了一次");
                }
            }
            check(mainEx.submit(() -> strikes.allowed("t:app", player().uuid())).get(),
                    "provider 异常与钱后中断都不记过失：不是脚本的错");
            mode.set("bal");
            eq(call.apply("bal"), com.november.mcphone.core.script.net.ScriptErrorCode.INTERNAL,
                    "对照：查余额抛了不动钱，照旧 INTERNAL，不是 UNKNOWN");
        } catch (Exception e) {
            check(false, "端到端装置没搭起来：" + e);
        } finally {
            com.november.mcphone.core.script.server.ScriptWorkers.stop();
            mainEx.shutdownNow();
        }
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

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
