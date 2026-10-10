package com.november.mcphone.tools.rhino;

import java.lang.module.ModuleFinder;
import java.lang.module.ResolutionException;
import java.nio.file.Path;
import java.util.Set;

/** 使用真实 WorldEdit jar 重现同包解析失败；不将这个结构探针冒充游戏启动测试。 */
public final class WorldEditLayerProbe {
    public static void main(String[] args) throws Exception {
        Path engine = Path.of(args[0]), original = Path.of(args[1]), worldEdit = Path.of(args[2]);
        String worldModule = ModuleFinder.of(worldEdit).findAll().iterator().next().descriptor().name();
        boolean failed = false;
        try {
            ModuleLayer.boot().configuration().resolveAndBind(ModuleFinder.of(original, worldEdit), ModuleFinder.of(), Set.of("org.mozilla.rhino", worldModule));
        } catch (ResolutionException expected) {
            ModuleContract.require(expected.getMessage().contains("org.mozilla"), "不是预期的 Rhino 包冲突：" + expected);
            failed = true;
            System.out.println("BEFORE_EXPECTED_SPLIT_PACKAGE " + expected.getMessage());
        }
        ModuleContract.require(failed, "原始 Rhino + WorldEdit 未重现包冲突");
        var config = ModuleLayer.boot().configuration().resolveAndBind(ModuleFinder.of(engine, worldEdit), ModuleFinder.of(), Set.of(ModuleContract.MODULE, worldModule));
        var layer = ModuleLayer.boot().defineModulesWithOneLoader(config, ClassLoader.getPlatformClassLoader());
        EngineProbe.checkEngine(layer.findLoader(ModuleContract.MODULE), ModuleContract.PREFIX, layer);
        var worldContext = layer.findLoader(worldModule).loadClass("org.mozilla.javascript.Context");
        ModuleContract.require(worldContext.getModule().getName().equals(worldModule), "WorldEdit 引擎来源错误");
        var privateContext = layer.findLoader(ModuleContract.MODULE).loadClass(ModuleContract.PREFIX + ".javascript.Context");
        ModuleContract.require(privateContext.getModule().getName().equals(ModuleContract.MODULE), "MCPhone 引擎来源错误");
        Object cx = worldContext.getMethod("enter").invoke(null);
        try {
            worldContext.getMethod("setOptimizationLevel", int.class).invoke(cx, -1);
            var scope = worldContext.getMethod("initStandardObjects").invoke(cx);
            var scriptable = layer.findLoader(worldModule).loadClass("org.mozilla.javascript.Scriptable");
            var value = worldContext.getMethod("evaluateString", scriptable, String.class, String.class, int.class, Object.class)
                    .invoke(cx, scope, "'abc123'.replace(/\\d+/, '!')", "worldedit-engine-probe", 1, null);
            ModuleContract.require(value.toString().equals("abc!"), "WorldEdit 原引擎执行失败");
        } finally { worldContext.getMethod("exit").invoke(null); }
        System.out.println("WORLDEDIT_LAYER_PASS module=" + worldModule + " private.sha256=" + ArtifactFiles.sha(engine)
                + " worldedit.sha256=" + ArtifactFiles.sha(worldEdit));
    }
}
