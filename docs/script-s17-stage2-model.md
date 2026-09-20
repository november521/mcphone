# S17 Stage 2：握手 / 客户端 / 真实请求闭环 —— 模型说明

对象：`feature/s17-stage2-handshake-client`（基线 `main@847828c`，Stage 1 已合并）。
本文是 Stage 2 的模型、时序与"留给后续切片"的口径清单。判定链本身见 `docs/script-s17-stage1-model.md`
与 `docs/script-s15g-host-wiring.md`。

## 一、登录 → 请求 → 落地 → 登出（时序）

```text
服务端主线程                         网络                     客户端主线程
────────────────────────────────────────────────────────────────────────────────
玩家登录事件
  │ ScriptHost.newEpoch(player)  ── epoch 非 0，写 epochs 表
  │ HandshakeService.pushTo(player, epoch)
  │   begin(serverId, serverName, epoch, count, features) ──► ScriptPush ──► ClientHandshake.onPush
  │   deployment(appId, deployRev, frontendDigest,              begin：记 serverId/serverName/epoch，
  │              approvalRevision, approvedAt, actions*)        清空未完成批次
  │   …（每个已批准部署一条，actions* = 仅该玩家被授权）
  │   end(epoch)                                          ──►   end：epoch 一致且条数收齐才 complete
  ▼
点一下（App 界面）
  │                                                          ScriptPage 执行 @click="call('动作')"
  │                                                          ScriptCall.call：
  │                                                            epoch      ← ClientHandshake
  │                                                            deployRev  ← 握手下发的部署项
  │                                                            摘要       ← FrontendDigest.of(本地包)
  │  ◄──────────── ScriptRpc(protocol, requestId, epoch, appId, deployRev, actionId, params, 摘要)
  │ ScriptPipeline.accept（主线程，顺序固定）：
  │   ① 协议号        错 → VERSION_MISMATCH（不断线）
  │   ② 连接 epoch    错 → INVALID_ARGUMENT + stale_connection（不入账本、不计限流）
  │   ③ 部署 App 轴   错 → NOT_DEPLOYED
  │   ④ 部署动作轴   错 → NOT_DEPLOYED + no_such_action
  │   ⑤ 版本轴       错 → VERSION_MISMATCH
  │   ⑥ 限流 / ⑦ 幂等账本 / ⑧ evaluator.submit（worker 求值）
  │  ◄───────────── worker 完成，回到主线程 land()
  │   ⑨ 落地前重查授权：未动钱 NOT_AUTHORIZED；钱已动 UNKNOWN
  │  ── ScriptRpcResult(requestId, code, data, messageKey, args, retryAfterMs, stateRevision) ──►
  │                                                          ScriptCall.onResult 按 requestId 找回调
  │                                                          ScriptPage → toast（当前形态）
  ▼
玩家登出事件
  │ ScriptHost.forget(player)   ── 只删 epoch，账本保留 24 小时
  ▼                                                          LoggingOut：ClientHandshake.clear()
                                                             ScriptCall.clear()（在飞调用丢弃）
```

**重连丢旧响应**：服务端登出删 epoch、再次登录发新值；旧连接的迟到结果回到客户端时，在飞表已清，
`requestId` 无主 ⇒ `ScriptCall.onResult` 安静丢弃。还挂在表里但 epoch 已换的请求由服务端
`stale_connection` 那一档兜底。

## 二、客户端三个字段各从哪来（都不参与授权判定）

| 字段 | 来源 | 用途 | 是不是边界 |
|---|---|---|---|
| `connectionEpoch` | 登录握手 `begin.epoch` | 服务端防旧响应串台 | 否（只是连接归属） |
| `deployRev` | 握手 `deployment.deployRev`（= `packageDigest`） | 服务端比对"你手里的包 == 我批的那个" | 是（⑤ 版本轴，服务端说了算） |
| `frontendDigest` | `ScriptApp.frontendDigest()`（装载时算一次，与服务端 `FrontendDigest` 同一份谓词） | 详情页灰字"界面已被本地修改" | **否**（§13.3：只回显，恶意客户端可伪造） |

### 版本闸（定向对抗 ADV-S2b-1）

握手线格式改了（部署项加了 `approvalRevision`/`approvedAt`）就必须抬闸，三个位置：

| 目标 | 闸 | 值 |
|---|---|---|
| 1.20.1-forge | `MCphoneNetwork.PROTOCOL_VERSION` | `"6"` |
| 1.21.1-neoforge | `NetworkHandler` 的 `event.registrar(...)` | `"3"` |
| 1.21.1-fabric | 没有加载器闸 → `begin.scriptApi` | `ScriptProtocol.SCRIPT_API = 2` |

客户端 `ClientHandshake` 在 BEGIN 处比对 `scriptApi`：对不上就整批不应用并留
`scriptApiMismatch()` 标记（`/mcphoneclient handshake` 会显示）。**Fabric 的混版本表现是
"看不见部署"而不是"连不上"** —— 加载器不会拦，只有这个字段能识别。

### `ID_MAX` 是整条 id 的上限，不是每段（定向对抗 ADV-S2b-3）

`Manifest` 的段正则上限（每段 64）与线格式/部署表的整条上限（64）**是两个不同的闸**。
两段各 64 能拼出 129 字符的 id：清单装得下，`ScriptRpc.writeUtf(..., 64)` 却编码不出来。
现在 `Manifest.MAX_ID = 64` 在清单入口按整条卡死（`E_PKG_ID_TOO_LONG`）。改动这三处
（`Manifest.MAX_ID` / `ScriptProtocol.ID_MAX` / `Deployment.MAX_ID_LEN`）必须一起改。

### 在飞名额的回收只依赖三条（定向对抗 ADV-S2b-4 的裁定）

1. 服务端对每条 RPC **恰好回一条结果**（管线八条返回路径条条回包，队列满也回）；
2. 连接断开 → 三平台 `LoggingOut` 调 `ScriptCall.clear()`；
3. 本地编/发失败 → `ScriptCall` 回滚名额并合成 `UNAVAILABLE`。

**不做客户端超时回收**：超时放掉名额之后结果仍可能已在服务端落地（甚至钱已动），玩家看不到
反馈再点一次就是**新的 requestId = 新的幂等键**，服务端会再执行一次。真要回收必须由服务端
定义超时并回 `UNKNOWN`（"结果未知，别自动重试"），客户端只展示。

## 三、动作 id 的字节预算（`ScriptPush.data ≤ 4096`）

握手一个部署一条 push，编码后必须 ≤ `ScriptProtocol.DATA_MAX = 4096`，否则
`HandshakeService` **整条跳过并告警**（不静默截断动作列表 —— 那会把部署内容悄悄改小）。

| 项 | 最坏情况 | 典型 |
|---|---|---|
| appId（≤64 字符 UTF-8） | 中文 64 字 = 192 字节（+1~2 前缀） | 20 字节 |
| deployRev / frontendDigest | 各 64 个 hex 字符 = 64 字节 | 各 64 字节 |
| visibility + approvalRevision + approvedAt | 各 ≤9 字节 varlong | ~12 字节 |
| actions 前缀 | 1 字节条数 | 1 字节 |
| **32 条动作（每条约 19 字节）** | —— | 32 × 20 = 640 字节 |
| **32 条中文动作（每条约 193 字节）** | 32 × 194 = **6208 字节 → 超限，整条不推** | —— |

结论：**32 条中文动作名会超**。作者与服主应让动作 id 保持短 ASCII（线格式允许 64 字符，但预算是
整条 push）。**批准期就会拦**：`DeploymentData.approve` 用 `Handshake.wireSize` 先算一遍，
超限直接拒批并报出字节数（ADV-S2b-5）；运行时的整条跳过只是老存档的兜底。客户端拿不到某条部署时
等同"本服没有它"（`backend.available = false`），这是设计好的降级方向（少信息，不是错信息）。

## 四、UI 落点（Stage 2）

| 数据 | 落点 | 说明 |
|---|---|---|
| `frontendDigest` | 商店 / 详情页 | 握手那格 ≠ 本地包摘要时，`mcphone.store.frontend_modified` 灰字；**只提示** |
| `backend.available / serverName / actions` | 脚本 App 的模板表达式（`backend.*`） | 作者写 `v-if="!backend.available"` 分支；宿主注入，只读、不许赋值、不许进 state |
| 空壳横幅 | 详情页顶部（宿主兜底） | 包的 `server.js` 存在 + 握手无该部署 ⇒ 一幅横幅；作者分支之外的第二道可读提示 |
| 部署三行 | 详情页（部署 / 授权 / 来源） | 数据来自握手；「申请」按钮暂时如实提示"通道未接通"，真链路留给审批界面 |
| `visibility` | 握手已下发，UI 过滤留待服务器商店来源 | 现在没有消费者，字段先留着 |
| `features` | `begin.features`（externalFetch/vault/serverScripts） | 同上，留给后续"服务端能力"的 UI 提示 |

两处"这不是安全边界"注释在 `ScriptRpc.frontendDigest` 与 `ClientHandshake.backend`（含 `backendValues`）。

## 五、授权/部署变更不重推（有意为之）

- `approve` / `remove` / `authorize` / `revoke` **立即**改服务端表；请求判定链每次都读当前表，
  所以**权限立即生效**。
- 客户端手上的 `backend.actions` 是**登录那一刻**的快照：变更后不会主动重推，详情页三行与灰按钮
  可能滞后到下一次登录/换服。这是刻意的（不做推送通道就不会有"推送丢包 ⇒ 状态漂移"的新面），
  代价是 UX 滞后 —— **权限的正确性不依赖它**。
- 批准后重装配（Stage 2）只影响"跑的是哪份 `server.js`"，不影响这个快照问题。

## 六、本阶段新增的客户端口子（给验收与后续用）

- `@click="call('actionId')"`：唯一的作者出网语句。空参数、结果由宿主弹 toast；同一个 App 同时最多
  4 个未完成调用（§15.1），超了立即 `IN_PROGRESS` 不排队。`phone.call(action, params, callback)`
  的完整回调形态要等 P1 的 `<script>`，不在本阶段。
  - **toast 的可见性边界（ADV-S2b-6）**：同一 App 内 `nav` 到别的页仍是同一个 `ScriptPage` 实例，
    toast 照常画；`close()` 之后实例不再渲染，结果回来只记客户端日志、**不承诺提示**。
    `UNKNOWN` 这类"钱可能动了"的码最终要走跨页面的宿主提示（后续切片）。
- `/mcphoneclient handshake`：打印 serverId / epoch / 部署表快照（含握手版本不匹配标记）。
- `/mcphoneclient rpc <app> <动作> [deployRev] [frontendDigest]`：**绕过回填**按给定字段发原始调用，
  结果码打到聊天栏。剧本 3/4/5 的"伪造"步骤用它；普通 App 走 `ScriptCall.call`，伪造不了。

## 七、留给后续切片

1. `phone.call` 的完整回调（`<script>` 函数与回调，P1）——现在的 `call()` 是它的前置最小形态。
2. `ServerStoreSource`：把握手里的部署表变成商店目录（`visibility` 过滤的落点）。
3. 补发/重推机制（如果需要）：现在靠重登录对齐，任何"增量推送"都要先解决乱序与丢包。
4. 详情页「申请」的真实通知链路（审批界面那一批）。
