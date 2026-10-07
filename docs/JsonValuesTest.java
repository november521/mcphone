package com.november.mcphone.core.script.engine;

import com.november.mcphone.core.script.JsonValues;
import com.november.mcphone.core.script.server.PlayerSnapshot;
import org.mozilla.javascript.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 用户参数不是代码；严格编码、尺寸、数值与递归不可变都走真实桥。 */
public final class JsonValuesTest {
    private static int checks;
    private static void check(boolean value, String why) { checks++; if (!value) throw new AssertionError(why); }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    public static void main(String[] args) {
        for (String bad : List.of("{a:1}", "{'a':1}", "{\"a\":1,\"a\":2}", "{\"x\":2147483648}",
                "{\"x\":0.5}", "{\"__proto__\":{}}", "{\"constructor\":1}", "[]", "null",
                "{\"x\":\"\\uD800\"}", "{\"x\":\"" + "中".repeat(2000) + "\"}")) {
            boolean rejected = false;
            try { JsonValues.object(bytes(bad)); } catch (IllegalArgumentException e) { rejected = true; }
            check(rejected, "拒绝非法参数：" + bad.substring(0, Math.min(40, bad.length())));
        }
        boolean malformed = false;
        try { JsonValues.decode(new byte[]{(byte) 0xc0, (byte) 0xaf}); }
        catch (IllegalArgumentException e) { malformed = true; }
        check(malformed, "拒绝非规范 UTF-8");
        ScriptBudget budget = ScriptBudget.server(); Context cx = budget.enterContext();
        try {
            Scriptable scope = ScriptSandbox.harden(cx); CtxBuilder.Result result = new CtxBuilder.Result();
            result.params = JsonValues.object(bytes("{\"text\":\"你好😀\",\"nested\":{\"n\":2},\"array\":[null,3]}"));
            ScriptableObject.putProperty(scope, "ctx", CtxBuilder.build(cx, scope, "t:app",
                    new PlayerSnapshot(UUID.randomUUID(), "tester", "minecraft:overworld", "survival", 0),
                    new CtxBuilder.Backends(null, null, null), result));
            String source = "try{ctx.params.nested.n=9}catch(e){};try{ctx.params.array[1]=8}catch(e){};"
                    + "String(ctx.params.nested.n)+'/'+String(ctx.params.array[1])+'/'+ctx.params.text"
                    + "+'/'+String(Object.getPrototypeOf(ctx.params))";
            check(Context.toString(cx.evaluateString(scope, source, "params", 1, null)).equals("2/3/你好😀/null"),
                    "参数的嵌套对象与数组只读、无原型，Unicode 完整");
        } finally { Context.exit(); }
        System.out.println("JsonValuesTest: " + checks + " passed");
    }
}
