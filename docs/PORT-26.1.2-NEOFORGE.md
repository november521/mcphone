# Minecraft 26.1.2 NeoForge 移植记录

## 交付状态

- 起点：正式版 MCphone 1.11.0，提交 `d540374f17305870e4520223f46fbdf6063f846f`。
- 工作仓库：`C:/Users/31286/Desktop/MCP/mcphone-local-updates`，分支 `codex/port-26.1-neoforge`。
- **已完成源码 API 移植，本地 `assemble` 成功，可以交付独立测试 JAR。**
- 用户明确要求自行进行兼容性测试：本轮执行源码编译、结构检查及无窗口断言，没有启动游戏、ATM11 或联动兼容性测试。
- 本次提交包含移植、Mixin 包隔离、相机位姿修复及私有 Rhino；没有修改另一个含用户改动的 `mcphone` 目录。
- 用户已反馈相机修复测试通过，并于 2026-10-10 反馈私有 Rhino 预览包简单测试无问题、授权提交 PR；具体测试组合未逐项列明，完整兼容矩阵不能据此视为通过。
- `mod_version` 保持 1.11.0，正式版发布说明没有修改。新目标仍为 `buildable: false`，不自动加入正式 Release。

## 精确目标与工具链

| 项目 | 配置 |
|---|---|
| 整合包基准 | 用户确认的 ATM11 0.11.1-beta |
| Minecraft | 26.1.2 |
| NeoForge 编译基线 | 26.1.2.100；ATM11 0.11.1-beta 运行版本为 109 |
| Java | Java 25；本机 Microsoft OpenJDK 25.0.3+9 x64 |
| ModDevGradle | 2.0.148 |
| Gradle wrapper | 9.2.1 |
| 原版名称 | 官方名称；移除旧 Parchment |

Minecraft 运行范围保持 `[26.1.2]`；NeoForge 运行范围为 `[26.1.2.100,26.1.3)`，允许同系列后续稳定版本。范围声明不等于所有版本已经游戏实测；没有把 26.2、26.1 初版或其他加载器声明为已适配。

依据：[官方 MDK](https://github.com/NeoForgeMDKs/MDK-26.1.2-ModDevGradle)、[NeoForge 26.1 迁移说明](https://neoforged.net/news/26.1release/)、[原版迁移清单](https://docs.neoforged.net/primer/docs/26.1/)、[ATM11 官方发布文件](https://www.curseforge.com/minecraft/modpacks/all-the-mods-11/files/9094478)。具体签名以下载后实际编译使用的原版与 NeoForge 源码、类文件为准。

## 实际迁移内容

### 1. 构建与版本隔离

- 新建 `platforms/26.1.2-neoforge/` 的独立工程、wrapper、资源和模组元数据。
- `versions/targets.json` 按仓库要求补齐 26.1.2 的 Forge／NeoForge／Fabric 三行，其他两个加载器仍为未开始的占位项。
- CI 和 Release 的 JDK 安装清单增加 Java 25，原有 17／21 保留。
- `versions/26.1-api-names.json` 仅为新目标声明纯名称及门面类型绑定，例如 `ResourceLocation → Identifier`、旧 GUI 类型到本模组版本门面。
- `gradle/mcphone-port-bindings.gradle` 把源代码绑定到忽略的 `build/generated/` 目录；不改注释、字符串、字符或文本块。重复类型、越界路径、缺少显式覆盖实现会报错。
- 五个真正需要新版原版入口的类型显式覆盖：`PhoneItem`、`PhoneItemProperties`、`MCphoneKeyBindings`、`TerminalSlotReference`、`TerminalSlotReferenceFactory`。没有复制整套聊天业务。

### 2. GUI 与输入

- `PhoneGraphics` 将现有四维布局位姿转换为新 GUI 提取器的二维矩阵；字体、图片、填充、描边、物品及裁剪继续走原版管线。
- 显式分层保留原来的绘制先后，避免背景、文字和覆盖层被管线排序打乱。
- 共用 `GuiUtil` 已换算的窗口裁剪坐标避免再次被新版提取器变换。
- `PhoneScreenBase` 接入新版绘制提取、窗口变化和键鼠事件，转交原有页面接口。
- `PhoneEditBox` 与多行输入门面保留原版编辑、选区和剪贴板行为，转换新的事件记录。字符事件在转交输入框时保留完整码点，鼠标点击保留修饰键和双击信息。
- 多行输入使用新版原版 builder；新版原版裁剪已经支持位姿，因此不再照抄旧父类绘制实现。
- 容器尺寸在构造时提供，以适配新版 final 字段；容器槽位交互仍由原版处理。

### 3. 图片、头像、通知和相机

- 动态纹理提供新版 GPU 调试标签，像素使用对应 ABGR 接口。
- 玩家皮肤入口改为新版 `PlayerSkin.body().texturePath()`；方形头像仍绘制头部与帽子层，内存缓存保持原有生命周期。
- 通知更新与内容绘制拆开，保留发信人合并、条数和停留时间。
- 换肤元数据通过版本接缝解码，边宽、缩放及旧路径回退规则继续共用。
- 浏览器旧 GL 纹理进入原版持有的纹理后再交给 GUI 提取器，移除共用页面的即时顶点提交。
- 截图适配新版参数，继续保留坐标文件名。
- 相机软闪光在真正执行 GUI 模糊时处理，保留 220ms 反馈和半径收回；仅新目标的客户端 Mixin 接管相机主动请求的帧。普通菜单仍走原版模糊。
- 后处理半径按原版着色器本来的整数半径缓存，资源重载时释放 GPU 资源。

### 4. 存储、网络及其他原版 API

- `PhoneNbt` 保留旧的 getter 默认值、列表类型检查、UUID 四整数格式及数字转换语义，共用存储业务不直接依赖新版 Optional getter。
- `PhoneSavedData` 改为 Codec／`SavedDataType`，按文件名缓存类型，保证连续获取同一数据不会产生不同实例。
- `PhoneSavedDataFiles` 将旧 `data/<name>.dat` 或 `data/minecraft/<name>.dat` 复制到新版命名空间目录；保留原件、校验副本、拒绝覆盖已有新文件，两份旧文件内容冲突时停止迁移。
- 已有文件解码失败时拒绝新建空数据，防止聊天、删除标记、服务器身份或资金被空记录覆盖。
- 43 个载荷标识入口在新目标编译时绑定到 `PhonePayloadTypes.create`，显式解析完整命名空间；新版原版 `createType` 只接受默认命名空间路径。协议 ID 字符串及消息编码保持一致。
- 新版客户端发包类隔离到 `core/net/client/`。
- 玩家名称缓存、消息提示、管理权限、好友传送、注册表查询、降水、世界时钟、按键和独立 OpenAL 初始化通过版本接缝适配。
- 原有测试的字体、权限和 NBT 夹具调整到同一版本入口，断言、预期值及执行任务全部保留。

### 5. 可选 API 的编译适配

新目标使用对应新版本的 Curios、Waystones／Balm、Patchouli、AE2／GuideME／AE2WTLib、RS、Tom’s Storage、NetMusic 编译依赖。RS 3 的位置接口改用位置自身的 codec，保持注册 id 与 VarInt 槽位语义；终端充电改用 NeoForge 新的事务式能量能力。

这些是编译入口的迁移，不代表联动已经验收。可选模组没有放入本轮开发运行依赖或嵌入核心 JAR。MCEF 目前仍使用旧编译 API，浏览器后端的 26.1.2 运行支持尚未验证。

## 文件架构

```text
versions/26.1-api-names.json              新目标的名称／门面绑定与显式覆盖清单
gradle/mcphone-port-bindings.gradle       编译源码生成；其他三个目标不应用
shared/.../core/PhoneNbt.java             统一 NBT 读取语义
shared/.../feature/chat/                  继续共用聊天、选区、菜单及数据业务
platforms/26.1.2-neoforge/
  build.gradle / gradle.properties        Java 25、加载器与编译依赖
  src/main/java/.../core/
    PhoneSavedData / PhoneSavedDataFiles  Codec 存储与旧文件迁移
    PhoneItem                            新物品使用和提示入口
    client/PhoneItemProperties            新物品模型条件
    net/client/ClientPacketSender         客户端发包隔离
  src/main/java/.../platform/
    PlayerAccess                         名称、提示、权限及传送
    client/PhoneScreenBase               屏幕和输入事件入口
    client/PhoneContainerScreenBase       容器绘制入口
    client/PhoneToastBase                 通知生命周期
    client/PhoneKeyInput                  原版按键签名
    client/PhoneSoundDevice               音频设备初始化
    client/PhoneWeather / PhoneBookIcons  版本相关查询和第三方绘制
    client/mixin/CameraBlurMixin          专用注入包，不混放普通适配类
    client/port/                         新原版的渲染、编辑、头像和后处理适配
  src/main/resources/
    assets/mcphone/items/phone.json       新版物品模型选择
    assets/mcphone/items/tablet.json
    mcphone.mixins.json                  仅客户端相机渲染时点
  docs/                                  本目标存储测试与版本测试夹具
```

旧三个目标增加同名薄接缝以继续编译同一份业务代码。接缝清单和平台差异基线均由 Gradle 任务生成，没有手改生成块或基线数值。

## 已完成的验证

- 新目标 `assemble` 成功；主源码和全部 92 个断言／夹具源码文件编译成功。
- SPI、客户端隔离、shared 中立性、第三方导入、加载器孪生、平台差异基线、数据包目录名及接缝文档检查通过。
- 新目标包含 72 个平台类型；平台孪生基线 124 对、9098 行差异。
- 原有 Forge 1.20.1、NeoForge 1.21.1、Fabric 1.21.1 的主源码再次编译成功。
- `git diff --check` 通过。正式版本号和被冻结的发布说明没有修改。
- 用户调整测试范围之前，NBT 65 条断言在四目标真实类路径上通过；新存储 Codec 12 条、迁移文件保护 16 条通过。前期旧三目标完整构建只有既有 Windows POSIX 权限测试失败；没有删除、跳过该测试或修改它的权限故障用例。

修复后执行了完整无窗口断言：仅 `EconomyDataTest`（Windows POSIX 权限）和 `DeploymentAuthorityTest`（裸 JavaExec 缺少原版注册表／FML 引导）失败，原用例保留，完整 build 尚未全绿。没有执行客户端启动、专用服务器启动或整合包兼容性测试。以上编译与结构检查不构成游戏功能或视觉验收。

## 用户实测与交付

交付目录：`C:/Users/31286/Desktop/MCphoneappbuildplan/output/26.1.2-neoforge-camera-fix/`。

- 测试包：`mcphone-1.11.0-mc26.1.2-neoforge-camera-fix-preview.jar`。
- 校验记录：`validation.json`，包含 SHA256、实际检查命令与结果。
- 实际日志保留在 `C:/Users/31286/Desktop/MCphoneappbuildplan/port-26.1-work/`。

测试范围和反馈由用户安排。没有自动安装到玩家实例，没有启动或结束游戏，没有改动真实存档。新目标暴露新版原版／门面类型，旧版本附属 JAR 的二进制兼容性没有承诺。

## 启动阻断修复（2026-10-09 用户报告）

- 基础实例使用 NeoForge 114，旧包只允许 109，导致依赖检查失败；编译基线改为 100，运行范围改为同一 Minecraft 26.1.2 系列。
- ATM11 在 `WallpaperStore.scan()` 加载普通纹理类时触发 `IllegalClassLoadError`：旧 Mixin 配置把普通适配所在的整个 `port` 包声明为专用包。相机注入移入同级 `client.mixin`，配置同步修改，普通适配继续留在 `port`。
- 注入限定 `processBlurEffect()V`，保留客户端限定、必需注入及相机请求判断；不移除相机效果或关闭 Mixin 错误检查。
- 新增独立构建模块 `gradle/mcphone-mixin-checks.gradle`：读取实际资源和 ASM 类注解，检查专用包及子包隔离、配置类型存在及注入类声明。挂接 `check` 和 `jar`；回归夹具不进入模组包。
- 游戏实测仍由用户负责；构建、检查结果和新包 SHA256 记录在新测试包的校验文件中。
- 完整断言发现新版原版 `CustomPacketPayload.createType` 的语义变化会导致后续网络注册崩溃：新目标增加 `PhonePayloadTypes` 薄适配，通过编译绑定接入，旧目标和 43 个协议字面量保持原样；新增实际消息 ID 回归断言。
- 无窗口输入菜单断言通过版本夹具隔离系统输入法通知，仍执行原版焦点、选区和编辑逻辑。没有删除断言或跳过用例。
- 命令权限断言在新目标的裸 JavaExec 中会触发原版权限注册表初始化，需要完整 FML 引导；当前保留原用例并记录失败，不以伪造已初始化状态放行。既有 EconomyDataTest 在 Windows 的 POSIX 权限失败也保留。

本次修复验证：Mixin 规则 12 个回归用例、实际 JAR 专用包隔离及注入目标检查通过；消息 ID 的 3 条断言和三组消息编解码用例通过；输入菜单 14 条断言在四个目标通过。旧三个平台源码编译及 Mixin 检查通过，该轮平台孪生为 124 对／9090 行，接缝文档一致。旧测试包保留在原预览目录，最新修复包使用上面的独立交付目录。

## 相机覆盖层错位修复（2026-10-10）

- 用户实测：进入相机后，准星、四角与白闪一起偏到右下角。坐标戳最后一次文字提交留下了原版二维矩阵，旧式 `popPose` 只恢复适配器内部状态；随后新建的覆盖层适配器继承了该文字的右下角平移和放大。
- `CameraHandler` 让坐标戳与覆盖层共用同一个 `PhoneGraphics`；`withIsolatedPose` 使用原版矩阵 push/pop 和 finally，保证正常绘制、干净帧提前返回及绘制异常都恢复调用方矩阵。坐标戳仍留在照片中，拍照时取景框仍被抑制。
- 改动仅涉及 26.1.2 目标的相机绘制入口与适配器；相机尺寸、颜色、动画参数和旧平台业务保持原样。
- 新增 `CameraGraphicsPoseTest`：真实二维／四维矩阵适配，共 170 条断言，覆盖四种显示分辨率、GUI 缩放 1–4、中心准星、四角、白闪边界、提前返回、异常退出及嵌套上下文恢复。夹具不创建窗口或 GPU，因此不构成游戏视觉验收。
- `CameraNameTest` 7 条断言通过。孪生基线由任务生成，仅 CameraHandler 项从 93 到 101，总计 124 对／9098 行；接缝文档一致。
- 全量并发运行中 LayoutEngine 性能用例一次测得 104.52ms，超过原 100ms 阈值；保持代码、断言和阈值不变，以 `--max-workers=1` 复查全量，该用例最高 0.98ms 并通过。两次日志均保留，最后仍有 Windows POSIX 权限和无 FML 引导的命令解析两处失败。
- 本轮未自动安装、启动或结束游戏，未操作真实存档。新的独立测试包与验证记录在上面的相机修复交付目录；前两版预览包均保留。视觉效果由用户继续实测。
