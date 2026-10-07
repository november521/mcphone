# 开发交付矩阵（2026-10-06）

依据桌面 `MCphoneappbuildplan/mcphone-app-build-plan.md` 及其中勘误，基线 `2c8565c`，分支 `feature/complete-script-platform`。用户要求完成开发后自行进行游戏测试，因此继续实现了后续阶段；本记录不把阶段检查或编译结果当作真服验收。

| 方案范围 | 本次落地或延续的实现 | 自动化证据 | 游戏验收 |
| --- | --- | --- | --- |
| §3–11 包、单文件、节点、样式、布局、输入、跨版本 | 保留既有包规则及 18 个组件，增加 text-input；前端具名函数、显式参数、真实回调与受限状态提交 | ScriptPackage/SfcCompiler/ScriptNode/LayoutEngine/FrontendRuntime/TextInput/AppTextures | T01、T04、T12–T14 |
| §12–14 签名、权限、内源商店 | 完整公钥身份、原生信任确认、签名后的前端封套；独立审核/部署/礼包 UUID 角色、作品与审核队列、许可管理 | PackageSign/FrontendEnvelope/StoreRepository/DeploymentAuthority | T03–T05、T21 |
| §15–16 RPC 与后端 | 普通请求及结果仍为 4 KiB；有界 Rhino、主线程落地、连接 epoch、能力与许可重查、金额动过后 UNKNOWN | ScriptRpc/ScriptHost/ScriptEngine/IntentLanding/ScriptConnectionEpoch/CurrencyScriptGate | T06、T15、T28 |
| §17 存储与保险箱 | 玩家 KV、共享 KV/CAS、客户端加密保险箱；玩家/App/全服组合预算，超限拒绝、已有数据保留 | DurablePlayerStore/SharedQuota/NotificationCombinedQuota/Vault | T11–T14、T25 |
| §18 五层能力 | 真实原版战利品/谓词/属性/效果/积分；自身读取、消息与受控给他人；物品引用只认宿主签发值 | ScriptEngine/CapabilityCatalog/CapabilityPolicy/ScriptItemRefs/ScriptReadSurface/ItemHandleTable | T07–T10、T26 |
| §19、24 更新与吊销 | 内源 optional/forced/off；外源双签名和增量审批；服务器显式跟随作者；完整公钥版本见证、同版本冲突；原生换作者及精确包降级例外 | AppcastUpdate/VersionWitness/StoreVersionWitness/ServerFrontendUpdate/ClientServerUpdates/AuthorRevocation | T20–T24 |
| §20 守卫与限量 | cooldown/once/limit/window/predicate 持久资格与原子预留；周期显式 IANA 时区，默认 04:00；异常记录需人工核对 | GuardLedger/GuardPipeline/GuardSubscriptions/RuntimeCycleConfig/TimeCycle | T06、T27–T29 |
| §21 礼包 | 原生礼包与容器编辑、物品完整数据、战利品表、编辑 UUID 角色、审计 | GiftDefinitions/InventoryFit/MailboxLedger | T07、T30 |
| §22 货币 | 内置/外部提供者、BigInt 桥；脚本托管归属账与全局结算日志；异常不自动再退款；内置只读对账、原生 UNKNOWN 核对 | Currency/EconomyData/EconomyConfig/ScriptCurrencyEscrows/SettlementJournal | T15–T19、R1–R5 |
| §23 SDK | A 档六项冻结契约和对应脚本运行链；资源 SDK 升到 2，保留旧接口默认方法；版本常量不内联 | ItemRef/PlayerRef/Mailbox/Notification/TimeCycle/Currency/SdkGate/ResourceReading | T01、T09、T26；说明见 SDK-RUNTIME.md |
| §25 两个外网出口 | 服务端白名单 HTTPS 与有限缓存；私人端完整包许可、凭证仅客户端、DNS/重定向/内容边界；私人 PNG 惰性解码与释放 | SafeFetch/FetchCache/NetDeclaration/RemoteImages/AppTextures/FrontendRuntime | T14、T31–T33 |
| §26 命令模板 | 固定 reviewed 模板、强类型槽、自身目标验证、当前 Brigadier 节点重解析、主线程执行、重要调用前审计 | CommandTemplate/CapabilityPolicy/ScriptEngine | T34 |
| §28、30、31 配额、边界、预设与管理 | 原生差异逐页确认、三配置事务、独立权限、模板开关与文件重载、作者/许可管理、占用 Top 10、有限审计导出、本人 KV 清理及邮箱/托管入口 | AdminConfiguration/Quota/QuotaPackage/AuditExport/AppStorageCleanup/ScriptExecutionPolicy/CapabilityConfig | T03、T25、T35–T38、T43；A1–A11、R7 |
| §29 资源生态 | 当前玩家物品与限定范围已加载方块读取；BigInteger 128 位数字长度限制；Forge/NeoForge FE 和流体、Fabric 流体单位；外部 SPI | ResourceReading、各平台编译与架构检查 | T26；真实机器及适配器需测试服提供 |
| §32、33 通知与后台 | 统一持久通知、HIGH 原生浮层与锁屏卡片、语言键、去重/已读/角标、有限分块；默认关闭的只读后台批次、抖动/退避/全局闸 | NotificationInbox/NotificationSync/NotificationCombinedQuota/Background | T10、T39–T41 |
| 大包上传与下载 | 宿主独立 16 KiB 分块、完整 SHA-256、玩家/连接/令牌/顺序绑定、字节及时间预算；兼容旧通道 | StoreFile/StoreTransfer/StoreFileBudget/QuotaPackage | T21、T42 |

## 按方案保留的边界

- `cost` 按 §20.8 策略 A 明确拒绝；没有先扣钱再尝试发奖的假原子流程。
- B 档 `groups/waypoints/escrow/stats` 是 §23.5 允许的预留 Java 接口；不宣称这些接口已经提供通用运行期服务。物品托管和玩家统计的具体脚本入口已有实现。
- `container.read/write`、`resource.move`、远程机器操作、世界任意改块、他人物品扣除、飞行等未开放目录保持拒绝；切到开放预设不会创造未实现能力。
- 外部经济、气体/化学品和模组特有资源需真实提供者。Fabric 不伪造 FE；外部余额无法对账时明确标为不可对账。
- 配置中的角色名单只能改文件后重载；预设不会改这些身份，也不会生成跨服信任。已有服主数据包的标签继续由服主维护。

## 验证与交付

最终构建结果和 JAR 校验值见验收包中的 `构建结果.md`、`SHA256SUMS.txt`。自动测试只证明其覆盖的代码路径，游戏任务在 `COMPLETION-TEST-TASKS.md` 中均待用户记录。崩溃窗口、物品完整附加数据、真实 GL 释放、输入法、外部提供者和 KubeJS/加载顺序须真实环境验证。

本地实现尚未推送或合入远端。仓库规定推送须获得明确许可，并通过 PR 的 CI 后由用户合并。
