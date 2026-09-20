# S17 Stage 1：服务端判定链的模型与契约

对象：`feature/s17-deployment-authority`（基线 `main@c723d41`）。本文件是 Stage 1 的模型说明与
"后续切片必须带上"的约束清单，含定向对抗（Q1–Q8）的处置。

## 判定链表（每道闸：输入 / 失败码 / 是否入账本 / 是否计限流）

| # | 闸 | 输入 | 失败码 | 入账本 | 计限流 | 备注 |
|---|---|---|---|---|---|---|
| 1 | 协议号 | `rpc.protocol` | `VERSION_MISMATCH` | 否 | 否 | 不断线 |
| 2 | 连接 epoch | `epochs[player]` vs `rpc.connectionEpoch` | `INVALID_ARGUMENT` + `KEY_STALE_CONNECTION` | 否 | 否 | **成对已接**（三平台登录建/登出忘）；客户端拿到 epoch 的握手下发属 Stage 2 |
| 3 | 部署：App 轴 | `deployments.deployed(appId)` | `NOT_DEPLOYED` | 否 | 否 | 两轴都不在就不建桶 |
| 4 | 部署：动作轴 | `hasAction`（查 **declaredActions**） | `NOT_DEPLOYED` + `KEY_NO_SUCH_ACTION` | 否 | 否 | 文案与"整个 App 没部署"分开 |
| 5 | 部署版本 | `deployRev`（= `packageDigest`） | `VERSION_MISMATCH` | 否 | 否 | 包轴 |
| 6 | 限流 | `limiter.allow` | `RATE_LIMITED` | 否 | — | |
| 7 | 幂等账本 | `ledger.check` | `Replay` / `IN_PROGRESS` / `INVALID_ARGUMENT` / `RATE_LIMITED` | **是**（`RESERVED`） | — | 从这里才开始入账本 |
| 8 | 求值 | `RhinoEvaluator` | `OK` / `INTERNAL` / `UNKNOWN` / … | 是（`settle`） | — | |
| 9 | 落地前重查授权 | `authority.allows` | 未动钱 `NOT_AUTHORIZED`；**钱已动 `UNKNOWN`**（后续切片） | 是 | — | §15.9 |

**故意冗余的五层（§13.4）**：①后端不跟着走（server.js 永不下发）；②授权表在服务端存档；
③动作 id 必须在**本服**已批准部署里；④`trusted_issuers=[]` 默认拒；⑤（将来）令牌 `audience` 非 `*`。
第 1/2 层是主承重，3/4/5 是冗余。

## 三条轴：声明 / 批准 / 许可

- **声明**（`Deployment.declaredActions`）：包里声明的动作全集 → 决定 `hasAction`，不在里面就是"部署两轴不在"。
- **批准**（`Deployment.approvedActions`）：OP 逐条勾选、必须 ⊆ 声明（`approve` 强制，丢掉的经 `Approval` 回显）。
- **许可**（`AuthorityData`）：所有人 / 指定玩家两档，按 UUID 存世界级存档、离线可查。

`allows = 批准 ∩ 许可`。

## 定向对抗结论的处置

| 项 | 结论 | 处置 |
|---|---|---|
| Q1 失败码分界 | 没划错 | 动作轴加 `KEY_NO_SUCH_ACTION`（码仍是 `NOT_DEPLOYED`） |
| Q2 两条轴压成一根 | 模型级 | **新增 `approvalRevision`**（同一 App 每次批准 +1）；`revision` 永远 = 包摘要，不参与 deployRev 之外的语义 |
| Q3 空列表 fail-open | 模型级 | **空列表 = 什么都不批（fail-closed）**；`null` 才是显式全批；`Approval` 回显 dropped/replaced |
| Q4 容量与上限 | 必须 | 候选 ≤64（按 `queuedAt` LRU 淘汰）；动作/能力 ≤32、单元素 ≤64；摘要必须 64 位小写 hex，全部在**入队就校验** |
| Q5 身份与隔离 | 干净 + 要写下来 | 见下"身份 = 世界谱系" |
| Q6 撤销语义不对称 | 模型级 | `unlicenseAll` 只清所有人档（与 `licenseAll` 对称）；新增 `clearApp` 全清并**返回清掉几人**；`scopeOf` 两档都报 |
| Q7 裸 `UUID.fromString` | 模型级 | `Deployment.fromTag` 坏批准人只丢批准人；`AuthorityData.load` 坏 UUID **跳过 + WARN**；`DeploymentData.load` 坏条目跳过；**load 绝不抛** |
| Q8 `frontendDigest` 范围 | 约束 | 见下"前端摘要的谓词" |
| extra 1 换包静默替换 | 约束 | `Approval.replaced` 回显；命令面要打"旧 → 新"差异 |
| extra 2 `land` 不重查 revision | 约束 | 留给 S18（意图落地之前必须补；`ActionEvaluator.Request` 已带 `deployRev`） |
| extra 3 `deploymentId` | 约束 | 只用于展示/日志，注释已写明"不是身份" |

## 身份 = 世界谱系（写下来）

`ServerIdentity` 存世界存档：**复制世界 = 复制身份**是设计（副本继承授权）。推论：客户端按
`serverId` 分桶，所以**副本与正本在客户端眼里是同一个服务器**——在副本上留下的客户端状态会出现在
正本那一桶里。这不是缺陷，是要写进文档的推论。`currentWorldKey`（按 IP 分桶）**绝不许**用于授权/保险箱/敏感 KV。

## 前端摘要的谓词（Q8，后续切片必须照此实现）

- **客户端能加载什么 = 摘要覆盖什么**（同一个谓词）。不在摘要集合里的条目**一律不加载**；
  否则把 `evil.js` 改名成 `server/evil.js` 就能"改了前端而摘要不变"。
- 摘要范围 = 包内**除 `server.js` 与 `server/**` 外**的全部条目，**含** `manifest.json`、`lang/*`、图标、`META/`
  （"确认支付"这句话就在 lang 里；manifest 是被批准的对象本身）。
- 摘要**表示**必须是裸小写 SHA-256 hex（64 字符）；`"sha256:" + digest`（71 字符）不许进 `Deployment`/`Candidate`。
- 域分隔建议：前端摘要换一个域（`mcphone-front-v1`），别与整包摘要共用 `mcphone-pkg-v1`。

## 线程与冻结（后续切片不许破）

- 三张表（`ServerIdentity` / `DeploymentData` / `AuthorityData`）与两个视图都是普通 `LinkedHashMap`：
  **主线程改、请求路径只读**。写只发生在开服装配与 OP 命令里。
- 不许把表或视图交给 worker；若将来要做 `ctx.deployment.*`，必须像 `Backends.currencies` 那样
  **在装配期取冻结引用**，或改成并发结构。

## OP 命令面必须做到的（后续切片）

- `approve`：打印 `dropped` 与 `replaced`（旧 → 新批准集合差异）；空选择集按"什么都不批"报出来。
- `revoke <app> <player>`：若该 App 是"所有人"档，**拒掉并提示**"要收紧先取消所有人档"，不许回成功。
- `clearApp`：打印清掉了几人。

## 交给后续切片/热重载的约束（终审对抗 C1/C2）

- **C1 停服窗口的线程纪律**：`RhinoEvaluator` 的"投递失败降级落地"会把 `DeploymentData`/`AuthorityData`/
  账本在 **worker** 上读一遍。今天安全 —— 停服窗口里没有并发写者。**但从这一刻起**：任何在停服窗口里
  改这三样东西的代码（热重载、stopping 时保存、把 OP 命令挂到 stopping）都会把它变成真竞态。
  要么那条降级改成"投递到自有兜底队列、由主线程最后一次 drain"，要么把两张表换成并发结构。
- **C2 重装配必须重发 epoch**：`newEpoch` 只挂在登录事件上。任何**重建 pipeline** 的动作（未来的热重载/
  重装配）都会清空 `epochs` ⇒ 在线玩家的请求立刻变成 `INVALID_ARGUMENT`（过期连接），直到重登。
  当前的 `/mcphone script reload` 只重扫、不重装配 ✓，别在重装配时忘了这一条。

## 装配与命令面的既定口径（终审对抗 M2/M3/M4/C6/C7）

- **后端模块谓词 = `server.js` + `server/**`**，前端 `.js` 不进服务端模块表（否则吃掉 16 个模块额度、
  且能被 `server.js` require 进来在服务端求值）。
- **装配期预检**：每个 App 的入口在开服时（预算内）跑一遍，失败整个跳过并告警 —— 请求路径不重试坏入口。
- **能力轴与动作轴都逐条勾选**：`approve <digest> [动作列表] [能力列表]`，省略 = 全批、`-` = 一个都不批。
- **`clearApp` 有命令入口**，并报出清掉几人。
- **命令面的 appId 一律过 `Deployment.validId`**（非空、≤64、无控制字符）。
- **生效时机**：批准/撤部署对**授权**立即生效；**后端代码**由 Stage 2 的"批准后重装配"立即生效（见下）。
- **批准/撤部署后主线程重装配（Stage 2 取代"换包必须重启"）**：`approve`/`remove` 成功后立刻按新部署建新 scope、
  替换 apps 表项；在飞的请求仍用旧 scope，失败则摘掉 scope 并让命令报错（请求回 `NOT_DEPLOYED`）。

## 预检的三条后果（终审对抗 C8）

预检 = 开服时在**服务端主线程**上对每个已部署 App 跑一遍入口（预算内）。三条后果写在这里：

1. **Context 复用**：主线程上若有别的 mod 留下的活动 Rhino Context，`enterContext` 可能复用 ⇒ 预算闸失效。
   代码已 fail-safe：`Context.getCurrentContext() != null` 时跳过预检，该 App 改为首次请求时在 worker 上求值。
2. **预检跑在货币网关 open 之后**（平台顺序 `EconomyRuntime.start → ScriptWorkers.start → ScriptHost.start`）。
   今天安全只因 `ctx` 后端整项都没挂；**S18 挂上后端之前必须定**：预检要不要无副作用模式，
   或者明确"装配期入口会真的动世界/动钱、且没有请求人可归因"，并写进作者指南。
3. **预检失败 = 该 App 整局不可用**（不再每请求重试坏入口）。自愈路径：重启；将来要做重装配入口，
   必须同时满足 C2（给在线玩家重发 epoch）。

## 其余口径（终审对抗 C9–C14）

- **C9**：降级回退依赖"`accept` 抛出 ⇒ 没被排期"这条原子性（`ScriptWorkers.submit` 抛/拒时任务不会跑）。
- **C10**：`preflight` 里的 `HostFn.resetDepth()` 会清掉外层深度；预检不与其它求值嵌套（开服期），不冲突。
- **C11**：主线程若已有别的 `ScriptBudget.begin()` 在场，预检的 `begin()` 会抛 → 该 App 跳过并告警。
- **C12**：`server/**` 下的非 `.js` **不是**模块（谓词要求 `.js`），不会占 16 额度、不会被 require。
- **C13**：`Deployment` 紧凑构造器把 `revision` 归一为 `packageDigest` —— 单一来源，构造时即保证。
- **C14**：`approve` 的 `-` 是"一个都不批"的哨兵；若真有动作名就叫 `-`（合法 id 允许 `-`），命令回显会把空集打出来，不静默。
