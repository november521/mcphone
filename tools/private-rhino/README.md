# MCPhone 私有 Rhino 生产工程

平台日常开发只消费 `vendor/maven/` 的固定模块。这里仅在上游或转换规则更新时执行，不会成为平台 build、IDE 导入或玩家安装的前置步骤。

固定工具：Gradle 8.8、Java 17、Shadow 8.3.6、公开 ASM 9.9.1。编译和运行生产工具使用同一 Java 17；来源记录保留实际补丁版本。上游二进制、源码和许可证摘要分别锁定在生产工具中。Shadow 专用任务只包含 Rhino 输入；默认 Shadow fat jar 已禁用，不启用 minimize。

```powershell
# 当前目录是 tools/private-rhino，JAVA_HOME 指向完整 JDK 17
.\gradlew.bat clean check --no-build-cache
# 实物 WorldEdit 组合（这个探针需完整 JDK 21）
.\gradlew.bat verifyWorldEditLayer '-PworldEditJar=C:\path\worldedit-mod-7.3.8.jar'
```

候选在 `build/staging/`，不自动写 vendor。重复一次不使用缓存的生产，比较 jar、sources、POM 和来源记录摘要。通过模块、服务、资源、语言、源码断点及平台封装验收后，再用一次明确的依赖更新纳入 vendor 和 `versions/private-rhino.json`。同版本不同内容不得覆盖；升级工具或规则需要新私有修订。

普通类路径/JPMS 探针使用两个明确的类加载环境，并在测试中设置与定义类加载器匹配的线程上下文，核验上游依赖的 ServiceLoader 行为；不会修改 Rhino 的服务加载策略。

源码断点验收：用 `SourceDebugProbe` 通过 JDI 连接一个独立探针 JVM，验证 Context、ContextFactory、RegExpLoaderImpl 的包声明、源码路径、行号和实际断点命中。IDE 的来源导航仍应由使用者在实际 IDE 中复核。

`WorldEditLayerProbe` 接受候选 jar、原始 Rhino jar 和真实 WorldEdit jar 三个参数：先证明原始同包解析失败，再证明私有模块和 WorldEdit 的原始引擎分别执行。这是模块结构和引擎共存验收，不能代替游戏客户端、专服、WorldEdit 编辑或 CraftScript 功能实测。

MPL 2.0 许可证输入来自 Rhino 官方 `Rhino1_9_1_Release` 标签；构件内含全文和改动通知，旁边提供匹配源码。生成源码保持原始行数，以供断点调试；只转换 Java 限定类型/包标识符，URL 与任意子串不做全局替换。
