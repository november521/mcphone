package com.november.mcphone.core.script.engine;

import com.november.mcphone.internal.rhino.javascript.Context;
import com.november.mcphone.internal.rhino.javascript.ContextFactory;
import com.november.mcphone.internal.rhino.javascript.RegExpLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ServiceLoader;

/** 用实际断言运行类路径核验引擎来源及正则服务，不能借 WorldEdit 提供的类型通过。 */
public final class PrivateRhinoIdentityTest {
    public static void main(String[] args) throws Exception {
        String expected = System.getProperty("mcphone.privateRhino.sha256");
        String expectedVersion = System.getProperty("mcphone.privateRhino.version");
        if (expected == null) throw new AssertionError("测试必须由平台任务提供已锁定引擎摘要");
        Path origin = Path.of(Context.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(origin)));
        if (!expected.equals(actual)) throw new AssertionError("实际引擎来源/摘要不同：" + origin);
        if (expectedVersion == null || !expectedVersion.equals(Context.class.getPackage().getImplementationVersion())) throw new AssertionError("引擎版本错误");
        if (!ContextFactory.class.getProtectionDomain().getCodeSource().getLocation().equals(origin.toUri().toURL())) throw new AssertionError("引擎核心类型来自不同构件");
        try {
            Class<?> external = Class.forName("org.mozilla.javascript.Context", false, Context.class.getClassLoader());
            if (external.getProtectionDomain().getCodeSource().getLocation().equals(origin.toUri().toURL()))
                throw new AssertionError("MCPhone 的私有构件含原始 Rhino，外部 mod 自带的引擎则允许共存");
        }
        catch (ClassNotFoundException expectedMissing) { }
        var provider = ServiceLoader.load(RegExpLoader.class).findFirst().orElseThrow();
        if (!provider.getClass().getName().equals("com.november.mcphone.internal.rhino.javascript.regexp.RegExpLoaderImpl")) throw new AssertionError("正则服务来源错误");
        var factory = new ContextFactory();
        try (var context = factory.enterContext()) {
            context.setOptimizationLevel(-1);
            context.setLanguageVersion(Context.VERSION_ES6);
            var scope = context.initStandardObjects();
            if (!Context.toString(context.evaluateString(scope, "'abc123'.replace(/\\d+/, '!')", "identity-probe", 1, null)).equals("abc!")) throw new AssertionError("正则功能失败");
            if (!Context.toString(context.evaluateString(scope, "JSON.stringify({v:String(10n+2n)})", "identity-probe", 1, null)).equals("{\"v\":\"12\"}")) throw new AssertionError("JSON/BigInt 功能失败");
            try { context.evaluateString(scope, "function (", "error-probe", 1, null); throw new AssertionError("语法错误未拒绝"); }
            catch (com.november.mcphone.internal.rhino.javascript.EvaluatorException expectedError) {
                if (expectedError.getMessage().isBlank()) throw new AssertionError("错误资源缺失");
            }
        }
        System.out.println("PRIVATE_RHINO_IDENTITY_PASS assertions=8 version=" + expectedVersion + " sha256=" + actual + " origin=" + origin);
    }
}
