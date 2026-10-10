package com.november.mcphone.tools.rhino;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Shadow 只转换引擎；此步骤生成显式模块、匹配源码、可复现归档及来源记录。 */
public final class PrivateRhinoProducer {
    public static final String VERSION = "1.9.1-mcphone.2";
    static final String INPUT_SHA = "12dbb224cea124a5d32968722e8eb161c967bf15f3c9a795d1b05592b1147975";
    static final String SOURCES_SHA = "026dfb1162c0f066624fe1c89c8670d0bddb4680d1e88aab9d290b96856d3c37";
    // 点号限定的 Java 类型/包标识符：不改链接、任意子串或脚本文本。
    private static final Pattern QUALIFIED = Pattern.compile("(?<![\\w.$/])org\\.mozilla(?=\\.)");
    private PrivateRhinoProducer() {}
    public static void main(String[] args) throws Exception {
        Path original = Path.of(args[0]), shaded = Path.of(args[1]), sources = Path.of(args[2]), output = Path.of(args[3]);
        ModuleContract.require(ArtifactFiles.sha(original).equals(INPUT_SHA), "上游二进制摘要不匹配");
        ModuleContract.require(ArtifactFiles.sha(sources).equals(SOURCES_SHA), "上游源码摘要不匹配");
        Path license = Path.of(args[4]);
        ModuleContract.require(ArtifactFiles.sha(license).equals("ada0787f27b581e024972a18b51db92c8e8508d0f05017d104a1c70a61b59951"), "上游许可证摘要不匹配");
        var originalEntries = ArtifactFiles.read(original);
        var entries = ArtifactFiles.read(shaded);
        ModuleContract.require(entries.keySet().stream().noneMatch(n -> n.startsWith("org/mozilla/")), "原始包残留");
        ModuleContract.require(entries.keySet().stream().noneMatch(n -> n.startsWith("com/november/mcphone/tools/")), "生产工具混入引擎");
        entries.put("module-info.class", ModuleContract.rebuild(originalEntries.get("module-info.class")));
        entries.put("META-INF/MANIFEST.MF", bytes("Manifest-Version: 1.0\r\nImplementation-Title: MCPhone private Rhino\r\n"
                + "Implementation-Version: " + VERSION + "\r\nAutomatic-Module-Name: " + ModuleContract.MODULE
                + "\r\nFMLModType: LIBRARY\r\n\r\n"));
        String notice = "Mozilla Rhino 1.9.1, MPL-2.0.\nSource: https://github.com/mozilla/rhino/tree/Rhino1_9_1_Release\n"
                + "MCPhone changes: package/resource/service relocation and private module descriptor.\n"
                + "Conversion source: tools/private-rhino/. Matching sources distributed beside this artifact.\n";
        entries.put("META-INF/MCPhone-Rhino-NOTICE.txt", bytes(notice));
        var sourceEntries = new TreeMap<String, byte[]>();
        for (var entry : ArtifactFiles.read(sources).entrySet()) {
            String name = entry.getKey();
            if (name.startsWith("META-INF/") && !name.toUpperCase(java.util.Locale.ROOT).contains("LICENSE")) continue;
            String targetName = name.startsWith("org/mozilla/") ? ModuleContract.PREFIX.replace('.', '/')
                    + name.substring("org/mozilla".length()) : name;
            byte[] content = entry.getValue();
            if (name.endsWith(".java")) {
                String text = new String(content, StandardCharsets.UTF_8);
                if (name.equals("module-info.java"))
                    text = text.replace("module org.mozilla.rhino", "module " + ModuleContract.MODULE);
                text = QUALIFIED.matcher(text).replaceAll(ModuleContract.PREFIX);
                content = bytes(text);
            }
            ModuleContract.require(sourceEntries.put(targetName, content) == null, "重复源码条目：" + targetName);
        }
        sourceEntries.put("META-INF/MCPhone-Rhino-NOTICE.txt", bytes(notice));
        entries.put("META-INF/LICENSE-Rhino.txt", Files.readAllBytes(license));
        sourceEntries.put("META-INF/LICENSE-Rhino.txt", Files.readAllBytes(license));
        // 上游许可证仍属于 Rhino，不能沿用主 mod 的 MIT。
        for (var e : originalEntries.entrySet())
            if (e.getKey().toUpperCase(java.util.Locale.ROOT).contains("LICENSE")) {
                entries.put(e.getKey(), e.getValue()); sourceEntries.put(e.getKey(), e.getValue());
            }
        Path binary = output.resolve("rhino-" + VERSION + ".jar");
        Path sourceJar = output.resolve("rhino-" + VERSION + "-sources.jar");
        ArtifactFiles.write(binary, entries); ArtifactFiles.write(sourceJar, sourceEntries);
        Files.writeString(output.resolve("rhino-" + VERSION + ".pom"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                  <groupId>com.november.mcphone.internal</groupId><artifactId>rhino</artifactId>
                  <version>1.9.1-mcphone.2</version><name>MCPhone private Rhino</name>
                  <url>https://github.com/november521/mcphone</url>
                  <licenses><license><name>Mozilla Public License 2.0</name>
                    <url>https://www.mozilla.org/MPL/2.0/</url><distribution>repo</distribution></license></licenses>
                  <description>Relocated Mozilla Rhino 1.9.1; no transitive upstream runtime dependency.</description>
                </project>
                """, StandardCharsets.UTF_8);
        // 生成候选来源；验收后显式纳入 vendor，普通 build 不更新期望摘要。
        Files.writeString(output.resolve("provenance.properties"), "coordinate=com.november.mcphone.internal:rhino:" + VERSION
                + "\nupstream.coordinate=org.mozilla:rhino:1.9.1\nupstream.url=https://repo.maven.apache.org/maven2/org/mozilla/rhino/1.9.1/\n"
                + "upstream.jar.sha256=" + INPUT_SHA + "\nupstream.sources.sha256=" + ArtifactFiles.sha(sources)
                + "\njar.sha256=" + ArtifactFiles.sha(binary) + "\nsources.sha256=" + ArtifactFiles.sha(sourceJar)
                + "\npom.sha256=" + ArtifactFiles.sha(output.resolve("rhino-" + VERSION + ".pom"))
                + "\nlicense.source=https://github.com/mozilla/rhino/blob/Rhino1_9_1_Release/LICENSE.txt\nlicense.sha256=" + ArtifactFiles.sha(license)
                + "\nrules.version=2\njava.minimum=17\nproducer.gradle=8.8\nproducer.shadow=8.3.6\nproducer.asm=9.9.1\n"
                + "producer.java=" + System.getProperty("java.runtime.version") + "\nmodule=" + ModuleContract.MODULE + "\n", StandardCharsets.UTF_8);
        System.out.println("PRIVATE_RHINO_CANDIDATE " + VERSION + " SHA256=" + ArtifactFiles.sha(binary));
    }
    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }
}
