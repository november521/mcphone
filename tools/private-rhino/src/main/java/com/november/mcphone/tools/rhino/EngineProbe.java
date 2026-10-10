package com.november.mcphone.tools.rhino;

import java.lang.module.ModuleFinder;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ServiceLoader;
import java.util.Set;

/** 在普通类路径和真实 JPMS 模块层中执行相同探针，不加载 Minecraft 或操作存档。 */
public final class EngineProbe {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path privateJar = Path.of(args[0]); Path upstream = Path.of(args[1]);
        try (var loader = new URLClassLoader(new java.net.URL[]{privateJar.toUri().toURL(), upstream.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            checkEngine(loader, ModuleContract.PREFIX, null);
            checkEngine(loader, "org.mozilla", null);
            ModuleContract.require(loader.loadClass(ModuleContract.PREFIX + ".javascript.Context")
                    != loader.loadClass("org.mozilla.javascript.Context"), "两份引擎类型混淆"); checks++;
        }
        var finder = ModuleFinder.of(privateJar, upstream);
        var config = ModuleLayer.boot().configuration().resolveAndBind(finder, ModuleFinder.of(), Set.of(ModuleContract.MODULE, "org.mozilla.rhino"));
        var layer = ModuleLayer.boot().defineModulesWithOneLoader(config, ClassLoader.getPlatformClassLoader());
        checkEngine(layer.findLoader(ModuleContract.MODULE), ModuleContract.PREFIX, layer);
        checkEngine(layer.findLoader("org.mozilla.rhino"), "org.mozilla", layer);
        ModuleContract.require(layer.findModule(ModuleContract.MODULE).orElseThrow().getDescriptor().requires()
                .stream().anyMatch(r -> r.name().equals("java.desktop") && r.modifiers().contains(java.lang.module.ModuleDescriptor.Requires.Modifier.TRANSITIVE)),
                "丢失 transitive java.desktop"); checks++;
        System.out.println("ENGINE_PROTOTYPE_PASS checks=" + checks + " classpath+JPMS private+upstream");
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void checkEngine(ClassLoader loader, String prefix, ModuleLayer layer) throws Exception {
        var thread = Thread.currentThread();
        var previousLoader = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try { checkEngineWithLoader(loader, prefix, layer); }
        finally { thread.setContextClassLoader(previousLoader); }
    }
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void checkEngineWithLoader(ClassLoader loader, String prefix, ModuleLayer layer) throws Exception {
        Class<?> context = loader.loadClass(prefix + ".javascript.Context");
        Class<?> factory = loader.loadClass(prefix + ".javascript.ContextFactory");
        Class<?> scriptable = loader.loadClass(prefix + ".javascript.Scriptable");
        Object cx = factory.getMethod("enterContext").invoke(factory.getConstructor().newInstance());
        try {
            context.getMethod("setOptimizationLevel", int.class).invoke(cx, -1);
            context.getMethod("setLanguageVersion", int.class).invoke(cx, context.getField("VERSION_ES6").getInt(null));
            Object scope = context.getMethod("initStandardObjects").invoke(cx);
            var eval = context.getMethod("evaluateString", scriptable, String.class, String.class, int.class, Object.class);
            String[] scripts = {"1+2", "JSON.stringify({a:[1,2]})", "String(10n+2n)", "'abc123'.replace(/\\d+/g,'!')", "String(new Map([['x',4]]).get('x'))"};
            String[] expected = {"3.0", "{\"a\":[1,2]}", "12", "abc!", "4"};
            for (int i = 0; i < scripts.length; i++) {
                Object result = eval.invoke(cx, scope, scripts[i], "private-engine-probe", 1, null);
                ModuleContract.require(String.valueOf(result).equals(expected[i]), "脚本探针错误 " + scripts[i] + " => " + result); checks++;
            }
            try {
                eval.invoke(cx, scope, "function (", "syntax-probe", 1, null);
                throw new AssertionError("语法错误未拒绝");
            } catch (java.lang.reflect.InvocationTargetException expectedError) {
                ModuleContract.require(expectedError.getCause().getMessage() != null && !expectedError.getCause().toString().contains("MissingResource"), "错误消息资源缺失"); checks++;
            }
            Class service = loader.loadClass(prefix + ".javascript.RegExpLoader");
            var services = layer == null ? ServiceLoader.load(service, loader) : ServiceLoader.load(layer, service);
            ModuleContract.require(services.findFirst().orElseThrow().getClass().getName().equals(prefix + ".javascript.regexp.RegExpLoaderImpl"), "正则服务发现错误"); checks++;
        } finally { context.getMethod("exit").invoke(null); }
    }
}
