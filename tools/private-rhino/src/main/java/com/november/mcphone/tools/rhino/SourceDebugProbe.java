package com.november.mcphone.tools.rhino;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** 使用 IDE 同样的 JDI 协议设断点，核对匹配源码路径和行号，不能只检查 sources.jar 存在。 */
public final class SourceDebugProbe {
    public static void main(String[] args) throws Exception {
        var sourceEntries = ArtifactFiles.read(Path.of(args[1]));
        var connector = Bootstrap.virtualMachineManager().defaultConnector();
        var launch = connector.defaultArguments();
        launch.get("main").setValue(EngineProbe.class.getName() + " \"" + args[0] + "\" \"" + args[2] + "\"");
        launch.get("options").setValue("-cp \"" + System.getProperty("java.class.path") + "\"");
        launch.get("suspend").setValue("true");
        var vm = connector.launch(launch);
        Set<String> hit = new HashSet<>();
        Map<String, String> methods = Map.of(
                ModuleContract.PREFIX + ".javascript.Context", "evaluateString",
                ModuleContract.PREFIX + ".javascript.ContextFactory", "enterContext",
                ModuleContract.PREFIX + ".javascript.regexp.RegExpLoaderImpl", "newProxy");
        try {
            for (String name : methods.keySet()) {
                var request = vm.eventRequestManager().createClassPrepareRequest();
                request.addClassFilter(name); request.enable();
            }
            vm.resume();
            long deadline = System.nanoTime() + 30_000_000_000L;
            boolean finished = false;
            while (!finished && System.nanoTime() < deadline) {
                var events = vm.eventQueue().remove(1000);
                if (events == null) continue;
                for (var event : events) {
                    if (event instanceof ClassPrepareEvent prepare) {
                        String name = prepare.referenceType().name();
                        var locations = prepare.referenceType().methodsByName(methods.get(name)).stream()
                                .flatMap(m -> { try { return m.allLineLocations().stream(); } catch (com.sun.jdi.AbsentInformationException e) { throw new IllegalStateException(e); } }).toList();
                        ModuleContract.require(!locations.isEmpty(), "类没有可用源码行号：" + name);
                        var location = locations.get(0);
                        String sourcePath = location.sourcePath().replace('\\', '/');
                        ModuleContract.require(sourceEntries.containsKey(sourcePath), "源码导航路径不匹配：" + sourcePath);
                        String text = new String(sourceEntries.get(sourcePath), java.nio.charset.StandardCharsets.UTF_8);
                        ModuleContract.require(text.contains("package " + name.substring(0, name.lastIndexOf('.')) + ";"), "源码包声明不匹配");
                        ModuleContract.require(location.lineNumber() <= text.lines().count(), "断点源码行号越界");
                        vm.eventRequestManager().createBreakpointRequest(location).enable();
                    } else if (event instanceof BreakpointEvent breakpoint) {
                        hit.add(breakpoint.location().declaringType().name());
                        breakpoint.request().disable();
                    } else if (event instanceof VMDeathEvent || event instanceof VMDisconnectEvent) finished = true;
                }
                if (!finished) events.resume();
            }
            ModuleContract.require(hit.equals(methods.keySet()), "源码断点未全部命中：" + hit);
            System.out.println("SOURCE_DEBUG_PASS Context/ContextFactory/RegExpLoaderImpl navigation+breakpoints=3");
        } finally {
            try { vm.dispose(); } catch (com.sun.jdi.VMDisconnectedException ignored) {}
            if (vm.process().isAlive()) vm.process().destroy();
        }
    }
}
