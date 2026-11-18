package com.november.testbootstrap;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModLoadingException;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.startup.JUnitGameBootstrapper;
import net.neoforged.fml.startup.StartupArgs;

/**
 * 26.1 命令断言的无窗口入口：沿用 FML JUnitService 的公开加载/引导流程。
 * 入口使用模组包之外的独立命名空间，避免 FML 将启动器当作游戏内容。
 * 原断言 main 完整运行，不创建服务器或世界；本类仅在测试源码集中，不能进玩家 JAR。
 */
public final class LoaderAssertionMain {
    private LoaderAssertionMain() {}

    public static void main(String[] args) throws Throwable {
        if (args.length != 1) throw new IllegalArgumentException("必须指定一份完整断言的入口类");
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        StartupArgs startup = new StartupArgs(Path.of("."), true, Dist.DEDICATED_SERVER,
                false, new String[0], Set.of(), List.of(), previous);
        try (FMLLoader loader = FMLLoader.create(startup)) {
            if (loader.getLoadingModList().hasErrors()) {
                throw new ModLoadingException(loader.getLoadingModList().getModLoadingIssues());
            }
            ClassLoader game = loader.getCurrentClassLoader();
            Thread.currentThread().setContextClassLoader(game);
            int bootstrappers = 0;
            for (JUnitGameBootstrapper bootstrapper : ServiceLoader.load(JUnitGameBootstrapper.class, game)) {
                bootstrapper.bootstrap(loader);
                bootstrappers++;
            }
            if (bootstrappers == 0) throw new IllegalStateException("缺少 NeoForge 游戏测试引导，不能运行裸 JVM 代替");
            try {
                Class.forName(args[0], true, game).getMethod("main", String[].class)
                        .invoke(null, (Object) new String[0]);
            } catch (InvocationTargetException failedAssertion) {
                throw failedAssertion.getCause();
            }
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }
}
