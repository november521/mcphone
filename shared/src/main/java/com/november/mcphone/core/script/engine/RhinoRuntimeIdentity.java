package com.november.mcphone.core.script.engine;

import com.november.mcphone.MCphone;
import com.november.mcphone.internal.rhino.javascript.Context;
import java.util.concurrent.atomic.AtomicBoolean;

/** 启动诊断只读取类的版本与来源，不执行 App、初始化全局工厂或接触存储。 */
final class RhinoRuntimeIdentity {
    private static final AtomicBoolean REPORTED = new AtomicBoolean();
    private RhinoRuntimeIdentity() {}
    static void reportOnce() {
        if (!REPORTED.compareAndSet(false, true)) return;
        var type = Context.class;
        var domain = type.getProtectionDomain();
        var source = domain.getCodeSource();
        var module = type.getModule();
        MCphone.LOGGER.info("[MCphone] 私有 Rhino：version={} module={} origin={} class={}",
                type.getPackage().getImplementationVersion(),
                module.isNamed() ? module.getName() : "unnamed",
                source == null ? type.getResource("Context.class") : source.getLocation(), type.getName());
    }
}
