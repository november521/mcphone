# MCPhone / WorldEdit 私有 Rhino V3 实施与验证记录

日期：2026-10-10。源码和四个平台预览包已完成；真实客户端、专服、IDE GUI 与整合包验收尚未完成，不能据此宣称全部游戏兼容或正式发布验收通过。

本次针对 WorldEdit 与 MCPhone 同时导出 `org.mozilla.*` 的 Java 模块解析冲突。已用用户实例里的 WorldEdit 7.3.8 实物 JAR 重现原始 Rhino 的同包解析失败；换成私有 Rhino 后，两份引擎的模块层解析及各自代表性脚本均通过。这个探针没有启动 Minecraft，也没有测试 WorldEdit 编辑和 CraftScript 全流程。

工作基线为 `codex/port-26.1-neoforge`、`d540374`（1.11.0），包含此前完成的 26.1.2 移植及用户已实测通过的相机修复。没有修改另一个 `mcphone` 工作目录、根 mod_version 或用户实例；发布工作流仅补充 Java 25 工具链，未触发发布。本次工作已提交至 [PR #68](https://github.com/november521/mcphone/pull/68)，不自动合并。

**固定依赖与维护入口**

坐标为 `com.november.mcphone.internal:rhino:1.9.1-mcphone.2`，模块名为 `com.november.mcphone.internal.rhino`。只改包名、资源、服务和封装，不升级 Rhino 1.9.1，不改变现有沙箱、预算、脚本 App 格式、存档或网络协议。

```text
tools/private-rhino/
  build.gradle + Gradle 8.8 wrapper      独立生产及验收，不参与平台日常构建
  inputs/LICENSE-Rhino.txt              固定上游 MPL 2.0 许可证全文
  src/main/java/.../tools/rhino/
    ArtifactFiles.java                 归档、摘要、重复项检查及可复现输出
    ModuleContract.java                上游契约审查、私有显式模块重建
    PrivateRhinoProducer.java          私有二进制/源码/POM/来源记录生产
    EngineProbe.java                   普通类路径与 JPMS 引擎验收
    SourceDebugProbe.java              源码路径、行号及真实 JDI 断点
    WorldEditLayerProbe.java           真实 WorldEdit 模块冲突前后对照
vendor/maven/com/november/mcphone/internal/rhino/1.9.1-mcphone.2/
  rhino-1.9.1-mcphone.2.jar
  rhino-1.9.1-mcphone.2-sources.jar
  rhino-1.9.1-mcphone.2.pom
  provenance.properties
versions/private-rhino.json            消费端唯一坐标、版本及期望摘要
gradle/mcphone-engine-dependency.gradle
  固定模块解析、各加载器薄适配、身份/边界/正式包检查及规则回归
shared/src/main/java/.../core/script/engine/
  原有 9 个引擎文件                    改为私有类型，不做业务重构
  RhinoRuntimeIdentity.java            一次性只读版本、模块和来源日志
docs/PrivateRhinoIdentityTest.java       真正使用平台断言类路径检查来源与功能
```

实际源码比审查稿多两处引擎使用者：`HostError` 和 `ScriptStaticCheck`；测试也实际涉及 `ScriptEngineTest`、`ScriptHostTest`、`ScriptAttributionTest` 三份，均同步迁移。公开 API 及非引擎生产类的类型关系通过 ASM 字节码检查，涵盖签名、泛型、注解、继承和方法体；不是仅搜索 import。CurrencyGateway 的纯 Java 预算调用保留。

独立生产固定 Gradle 8.8、Java 17、Shadow 8.3.6 和 ASM 9.9.1。Shadow 专用任务只加工 Rhino；保留原始类的真实字节码要求，不 minimize、不把业务字节码或生产工具依赖塞入引擎。模块通过 ASM 读取、重建并使用 ModuleDescriptor 核对，上游 JDK requires/修饰符、exports、uses、provides 和包集合均保留对应约束，模块版本更新为私有版本。二进制与源码均附许可证全文及改动通知。

第一候选 `.1` 因上游 JAR 未附许可证全文而被构件检查拒绝。它只保留在本机拒绝记录中；另建 `.2` 补齐许可证与模块版本，没有覆盖已固定的同版本文件。vendor 保存普通 Maven 模块，不使用 mavenLocal 或文件依赖接口；POM 不带回原始 Rhino 的传递依赖。

**消费流程与检查**

日常编译、现有断言、开发类路径和正式内嵌包都消费锁定的 vendor 模块，普通 build 不修改 vendor、不运行转换。三个原有平台和 26.1.2 NeoForge 共用同一规范产物。NeoForge 1.21.1 保留旧运行模型的额外库类路径；26.1.2 按新模型使用普通 implementation。Forge 使用精确 jarJar 范围与开发库配置，Fabric 使用 implementation/include。没有给平台加入 Shadow 或统一其 wrapper/JDK。

每次 check 核验 vendor 四个文件的 SHA-256、编译/开发类路径的实际构件、模块和服务、Java 17 兼容、业务类型边界、最终包内嵌内容及精确协商范围。只禁止 MCPhone 自己声明、引用或分发原始 Rhino；允许 WorldEdit/KubeJS 自带引擎，未用文件名做全局禁用。规则包含 10 个正常和拒绝用例。

Forge 和两份 NeoForge 内嵌引擎整个 JAR 的摘要与规范产物一致。Fabric 仅新增加载器生成的 `fabric.mod.json`；所有规范类、资源、模块声明、许可证和通知逐条字节一致，额外条目明确限定。断言检查还确认引擎实际 CodeSource 和摘要，无原始引擎回退。启动日志只记录版本、模块、来源和类名，不执行 App 或接触世界/经济数据。

**自动验证结果**

| 验证 | 结果 |
|---|---|
| 两次 clean、禁用构建缓存的库生产 | 二进制、源码、POM、来源记录全部摘要相同 |
| 普通类路径和真实 JPMS 模块层 | Java 17/25 两份引擎共存探针各 30 项通过；Java 21 平台脚本回归通过 |
| 正则、JSON、BigInt、Map 和错误资源 | 通过 |
| 全部转换源码 | 367 个文件独立 Java 17 编译通过，保留上游弃用警告 |
| Context/ContextFactory/RegExpLoaderImpl | 三个源码路径、行号及真实 JDI 断点通过 |
| 真实 WorldEdit 7.3.8 | 原始同包失败复现；私有模块与 WorldEdit 原引擎分别执行通过 |
| 带 WorldEdit 的类路径 | MCPhone 私有来源验证通过，未误禁外部引擎 |
| 生产工程标准 check | 模块探针、源码编译、JDI 和 WorldEdit 可选实物任务通过 |
| 原有脚本回归 | 四个平台各 395 + 81 + 58 = 534 条断言通过 |
| 新增来源测试/边界规则 | 四个平台各 8 条来源断言及 10 条边界用例通过 |
| 四个平台正式包与既有 SPI/Mixin/隔离检查 | 通过 |
| 接缝与孪生基线 | 通过；124 对，9323 行差异 |

首次完整平台构建均从 clean 状态开始，未重新生产 vendor。最后一次最终源码 build --continue --max-workers=1 的结果如下，所有测试均执行，没有删除、跳过或削弱测试：

| 目标 | Java | 断言任务通过/总数 | 剩余失败 |
|---|---:|---:|---|
| Forge 1.20.1 | 17 | 82/83 | EconomyDataTest：Windows POSIX 文件权限 |
| NeoForge 1.21.1 | 21 | 83/84 | 同上 |
| Fabric 1.21.1 | 21 | 83/84 | 同上 |
| NeoForge 26.1.2 | 25 | 86/88 | 同上；DeploymentAuthorityTest：独立 JVM 启动环境限制 |

合计 334/339 个断言任务通过。完整 build 没有全绿；这些剩余环境问题保留为失败，不能作为正式发版全部验收通过的依据。生产工程、NeoForge/Fabric 配置缓存已存储/复用；Forge 保持既有设置。

**完整检查发现的移植回归**

1.21.1 的三个协议测试曾因之前的移植代码调用 `CustomPacketPayload.createType("mcphone:...")` 失败；该原版工厂会追加 minecraft 默认命名空间。这与 Rhino 迁移无关，但会影响本次旧平台交付。

恢复了 43 个版本层工厂到发布基线的 `new Type<>(ResourceLocation.fromNamespaceAndPath(...))` 写法，核实 26.1.2 对应 Identifier 工厂仍存在。没有改协议 ID 或编码。三项回归测试 24/55/60 条断言均通过，四个平台最终全量回归也通过这些测试。生成的孪生基线从 9098 调整到 9323；40 个改变的基线项均是协议类型，未手改基线。

**游戏测试与交付边界**

四个测试包及机器可读摘要位于 `output/rhino-v3-compatibility-preview/`；`validation.json` 记录正式源 JAR、输出摘要、内嵌身份、Java 要求、全部失败和未覆盖环境。预览包保留 1.11.0 内部版本，未发布或宣称新的正式支持。

用户于 2026-10-10 反馈预览包简单测试无问题，并授权提交 PR；未逐项说明平台、模组组合和操作，因此不将完整共存矩阵标记为通过。

按此前分工，游戏兼容测试由用户执行。优先在 1.21.1 NeoForge 原失败组合保留 WorldEdit 验证启动、手机脚本、基础编辑及 CraftScript；26.1.2 使用对应测试包。然后覆盖 IMBlocker、原整合包、KubeJS、客户端/专服及正常开发运行。专服只放服务端适用模组。实际 IDE 的导航/断点 UI 操作仍未验收；JDI 与源码全编译只是自动化证据。

三项前置技术点中，源码/断点、规范构件及打包一致性已有自动化证据，模块共存已有实际 JPMS/WorldEdit 证据；真实加载器生命周期与实际游戏加载来源仍需按测试组合记录验证。因此这里交付实现与候选包，没有把这些未执行项目写成通过。

**依赖更新与回滚**

在 `tools/private-rhino/` 用完整 JDK 17 运行 `gradlew clean check --no-build-cache`；用 `verifyWorldEditLayer -PworldEditJar=<实物路径>` 执行真实 JAR 模块探针，后者使用 Java 21。重复生产并检查差异后，明确采用新私有修订，纳入 vendor 和唯一锁文件，再完成各平台与真实游戏验收。不能覆盖同坐标不同内容，也不能在普通构建中自动接受摘要。

回滚应一起回退私有依赖锁、消费规则和内部类型迁移；不删存档、不撤销无关相机/聊天改动。回到原始 Rhino 会重新引入 WorldEdit 同包冲突。附属 mod 使用公开 API，不重复嵌入引擎，也不直接传递原始 Rhino 的 Context/Function/Scriptable。

参考依据：[Rhino 上游模块声明](https://raw.githubusercontent.com/mozilla/rhino/Rhino1_9_1_Release/rhino/src/main/java/module-info.java)、[ModDevGradle 库依赖与运行](https://docs.neoforged.net/toolchain/docs/plugins/mdg/)、[Loom include](https://docs.fabricmc.net/develop/loom/)、[Shadow 兼容矩阵](https://github.com/GradleUp/shadow#compatibility-matrix)。

**PR CI 补正（2026-10-10）**

首次 Linux CI 的三个旧平台全量构建通过；26.1.2 的两个失败暴露出此前 Windows 验收未能覆盖的路径：

- `DeploymentAuthorityTest` 的命令权限 codec 需要新版注册表引导。26.1.2 为它及 `EconomyDataTest` 接入官方 FML `StartupArgs`、`FMLLoader`、`JUnitGameBootstrapper` 流程；完整调用原断言 main，不创建服务器或世界。入口位于独立的 `src/assertionBootstrap/java/com/november/testbootstrap/`，不进入玩家包，也不借修改生产命令避开测试。运行类路径不引入 compileOnly 联动，主源码和断言通过 MOD_CLASSES 交给同一加载器，运行目录隔离在 build 下。
- Java 25 的文件系统可能将 ENOTDIR 判成不存在。经济存档现在读取文件属性并核对最近已有父级：普通文件、失效链接、不可访问路径继续按看不到处理，只有能确认的缺失才允许新账本。保留原有坏路径锁账断言；Java 17/25 的原存档路径用例及正常缺失目录对照共各 21 条通过。

接入后的 Windows 命令测试 94 条通过；完整经济测试的异常仍如实传递为失败，仅剩 Windows POSIX 权限限制。配置缓存已保存，Linux 完整回归在 PR CI 继续确认。此前预览包的摘要和旧全量计数是对应交付时的记录，不代表这些补正后的 JAR。

测试引导依据：[NeoForge 官方 JUnitService](https://github.com/neoforged/FancyModLoader/blob/main/junit-fml/src/main/java/net/neoforged/fml/junit/JUnitService.java)。
