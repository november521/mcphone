package com.november.mcphone.tools.rhino;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.lang.module.ModuleDescriptor;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** 上游模块契约是升级闸门；ASM 重建描述符保留 requires 的修饰符及服务约束。 */
final class ModuleContract {
    static final String PREFIX = "com.november.mcphone.internal.rhino";
    static final String MODULE = PREFIX;
    static final String UPSTREAM = "org.mozilla";
    private static final Set<String> EXPORTS = Set.of("classfile", "javascript", "javascript.annotations",
            "javascript.ast", "javascript.commonjs.module", "javascript.commonjs.module.provider",
            "javascript.debug", "javascript.optimizer", "javascript.serialize", "javascript.typedarrays",
            "javascript.xml", "javascript.config", "javascript.lc.type");
    static String map(String name) {
        return name.startsWith(UPSTREAM + ".") ? PREFIX + name.substring(UPSTREAM.length()) : name;
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    static byte[] rebuild(byte[] upstream) {
        var before = ModuleDescriptor.read(ByteBuffer.wrap(upstream));
        require(before.name().equals("org.mozilla.rhino") && !before.isOpen() && !before.isAutomatic(),
                "未经审查的上游模块名称或开放性变化");
        require(before.exports().stream().map(ModuleDescriptor.Exports::source).collect(Collectors.toSet())
                .equals(EXPORTS.stream().map(p -> UPSTREAM + "." + p).collect(Collectors.toSet())),
                "未经审查的上游 exports 变化");
        require(before.exports().stream().noneMatch(e -> e.isQualified() || !e.modifiers().isEmpty()),
                "未经审查的上游 exports 修饰符变化");
        require(before.opens().isEmpty() && before.mainClass().isEmpty(), "未经审查的 opens/mainClass");
        require(before.requires().stream().map(ModuleDescriptor.Requires::name).collect(Collectors.toSet())
                .equals(Set.of("java.base", "java.compiler", "java.desktop", "jdk.dynalink")),
                "未经审查的 JDK 依赖变化");
        require(before.requires().stream().filter(r -> r.name().equals("java.desktop")).findFirst().orElseThrow()
                .modifiers().equals(Set.of(ModuleDescriptor.Requires.Modifier.TRANSITIVE)), "java.desktop 契约变化");
        require(before.requires().stream().filter(r -> r.name().equals("java.base")).findFirst().orElseThrow()
                .modifiers().equals(Set.of(ModuleDescriptor.Requires.Modifier.MANDATED)), "java.base 契约变化");
        require(before.requires().stream().filter(r -> r.name().equals("java.compiler") || r.name().equals("jdk.dynalink"))
                .allMatch(r -> r.modifiers().isEmpty()), "JDK requires 修饰符变化");
        require(before.uses().equals(Set.of("org.mozilla.javascript.NullabilityDetector", "org.mozilla.javascript.RegExpLoader",
                "org.mozilla.javascript.xml.XMLLoader", "org.mozilla.javascript.config.RhinoPropertiesLoader")), "uses 契约变化");
        require(before.provides().size() == 1 && before.provides().iterator().next().service().equals("org.mozilla.javascript.RegExpLoader")
                && before.provides().iterator().next().providers()
                .equals(java.util.List.of("org.mozilla.javascript.regexp.RegExpLoaderImpl")), "provides 契约变化");
        var writer = new ClassWriter(0);
        var remapper = new Remapper(Opcodes.ASM9) {
            @Override public String map(String name) {
                return name.startsWith("org/mozilla/") ? PREFIX.replace('.', '/') + name.substring("org/mozilla".length()) : name;
            }
            @Override public String mapPackageName(String name) { return map(name); }
            @Override public String mapModuleName(String name) { return name.equals(before.name()) ? MODULE : name; }
        };
        var versioned = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public ModuleVisitor visitModule(String name, int access, String version) {
                return super.visitModule(name, access, PrivateRhinoProducer.VERSION);
            }
        };
        new ClassReader(upstream).accept(new ClassRemapper(versioned, remapper), 0);
        byte[] rebuilt = writer.toByteArray();
        var after = ModuleDescriptor.read(ByteBuffer.wrap(rebuilt));
        require(after.name().equals(MODULE) && after.requires().equals(before.requires()), "模块/JDK requires 未正确保留");
        require(after.rawVersion().orElseThrow().equals(PrivateRhinoProducer.VERSION), "私有模块版本错误");
        require(after.exports().stream().map(ModuleDescriptor.Exports::source).collect(Collectors.toSet())
                .equals(before.exports().stream().map(e -> map(e.source())).collect(Collectors.toSet())), "exports 转换错误");
        require(after.uses().equals(before.uses().stream().map(ModuleContract::map).collect(Collectors.toSet())), "uses 转换错误");
        require(after.provides().iterator().next().service().equals(map("org.mozilla.javascript.RegExpLoader"))
                && after.provides().iterator().next().providers()
                .equals(java.util.List.of(map("org.mozilla.javascript.regexp.RegExpLoaderImpl"))), "provides 转换错误");
        require(after.packages().equals(before.packages().stream().map(ModuleContract::map).collect(Collectors.toSet())), "包集合变化");
        return rebuilt;
    }
}
