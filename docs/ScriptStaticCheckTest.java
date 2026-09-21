package com.november.mcphone.core.script.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * S18 断言：装配期静态预检（{@link ScriptStaticCheck}）。
 *
 * <p>它是"零副作用预检"的落点：<b>只编译、只解析 require，绝不执行</b>。最能证明"没执行"的
 * 一条是"顶层直接 throw 的包照样通过"——真跑一遍的话它立刻就炸了。
 */
public class ScriptStaticCheckTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures.add(what);
    }

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!Objects.equals(actual, expected)) failures.add(what + "  期望 " + expected + "，实际 " + actual);
    }

    static Map<String, String> mods(String... nameThenSource) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < nameThenSource.length; i += 2) m.put(nameThenSource[i], nameThenSource[i + 1]);
        return m;
    }

    static void valid() {
        String why = ScriptStaticCheck.check(mods(
                "server.js", "var u = require('./server/util.js');\nactions.ping = function () { return ctx.ok({}); };",
                "server/util.js", "exports.n = 1;"));
        eq(why, null, "字面量 require 指向包内模块：通过");
    }

    static void syntax() {
        String why = ScriptStaticCheck.check(mods("server.js", "function ("));
        check(why != null && why.contains("语法错误"), "语法错要报出来：" + why);
        check(why != null && why.contains("server.js"), "要报是哪个文件：" + why);
    }

    static void missingAndDynamic() {
        String missing = ScriptStaticCheck.check(mods("server.js", "require('./server/nope.js');"));
        check(missing != null && missing.contains("找不到"), "require 找不到要报出来：" + missing);

        String dyn = ScriptStaticCheck.check(mods("server.js", "var n = 'util'; require('./server/' + n + '.js');"));
        check(dyn != null && dyn.contains("字面量"), "动态 require 预检不收：" + dyn);

        String bare = ScriptStaticCheck.check(mods("server.js", "require('server/util.js');"));
        check(bare != null && bare.contains("相对路径"), "非 ./ ../ 开头要拒：" + bare);

        String escape = ScriptStaticCheck.check(mods("server.js", "require('../../x.js');"));
        check(escape != null, "弹出包根要拒：" + escape);
    }

    static void cycles() {
        String why = ScriptStaticCheck.check(mods(
                "server.js", "require('./server/a.js');",
                "server/a.js", "require('./b.js');",
                "server/b.js", "require('./a.js');"));
        check(why != null && why.contains("循环依赖"), "环要报出来：" + why);
    }

    static void noExecution() {
        // 真跑一遍的话这两条都会炸；静态预检必须让它们通过
        eq(ScriptStaticCheck.check(mods("server.js", "throw new Error('顶层就炸');")), null,
                "顶层 throw 照样通过（证明预检不执行代码）");
        eq(ScriptStaticCheck.check(mods("server.js", "undefinedFunctionThatDoesNotExist();")), null,
                "未定义名字只有运行时才炸，静态预检不报");

        // 注释与字符串里的 require 不算依赖
        eq(ScriptStaticCheck.check(mods(
                "server.js", "// require('./nope.js')\nvar s = \"require('./nope.js')\";\nvoid s;")), null,
                "注释/字符串里的 require 不当依赖");
    }

    public static void main(String[] args) {
        valid();
        syntax();
        missingAndDynamic();
        cycles();
        noExecution();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
