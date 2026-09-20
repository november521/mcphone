# S15g 脚本宿主接线（谁在哪条线程建、谁在哪条线程销毁）

本步把四段接成一条能跑的路：`ScriptRpcHandler.handle → ScriptPipeline → RhinoEvaluator → CtxBuilder.build`。
只接线，不顺带重构；生产侧新增一个装配点 `core/script/server/ScriptHost`，两个占位视图 `DenyAllDeployments` / `DenyAllAuthority`。

> **S17 Stage 1 已替换的两个占位**：`ServerDeployments` / `ServerAuthority`（`DeploymentData` / `AuthorityData`
> 世界级存档），审批入口 `ScriptAdminCommand`，包来源 `ServerPackageScanner`。详见 `docs/script-s17-stage1-model.md`。
>
> **S17 Stage 2 补上的两段**：①`newEpoch`/`forget` 成对接进生产 + 握手下发 epoch + 客户端在每条请求里回填
> （见"epoch 成对"一节）；②批准/撤部署后的主线程重装配（见"重装配"一节）。`AppScope` 的生产装配也在这两段
> 之间落地：`ServerAppAssembler.assemble` 从已批准的包建 scope，`apps` 表可变、`RhinoEvaluator` 持引用。

## 接线图（线程 × 五个时机）

| 时机 | 线程 | 顺序 |
|---|---|---|
| 开服 | 服务端主线程 | `EconomyRuntime.start`（建注册表、扫超时托管、最后开网关）→ `ScriptWorkers.start` → `ScriptHost.start`：**主线程**建 `StrikeTracker` → 扫 `incoming/` + 按已批准部署建 `AppScope` → `RhinoEvaluator(apps, backends, server::execute)` → `ScriptPipeline(serverId, ledger, limiter, ServerDeployments, ServerAuthority, evaluator)` → `ScriptRpcHandler.install` |
| 玩家登录 | 服务端主线程 | `ScriptHost.newEpoch(player)`（写 `epochs`）→ `HandshakeService.pushTo(player, epoch)`：`begin`（serverId/serverName/epoch/count）→ 每个部署一条 `deployment`（deployRev/frontendDigest/approvalRevision/approvedAt/仅该玩家被授权的 actions）→ `end` |
| 请求进来 | 主线程（门面保证） | `handle → pipeline.accept`：协议 → 连接 epoch → 部署两轴（`ServerDeployments`）→ 版本（`deployRev`）→ 限流 → 幂等账本 → `evaluator.submit` |
| 求值 | 2 条 worker | `RhinoEvaluator.evaluate`：`AppScope` / `ctx` / 预算 / 异常分类 → `Completion`。**不碰 `StrikeTracker`** |
| 落地 | 主线程 | `land`：重查授权（`ServerAuthority`）→ 结算账本 → 回包；`StrikeTracker` 只在这条线程上被碰 |
| 玩家登出 | 服务端主线程 | `ScriptHost.forget(player)`：只删 epoch，**不清账本**（账本保留 24 小时，跨重连命中正是它存在的理由） |
| 批准 / 撤部署 | 服务端主线程 | 写表成功后 `ScriptHost.reassemble(server, appId)`（见下），命令面如实回显三种结果 |
| 停服 | 主线程 | `EconomyRuntime.stop`（先关货币网关）→ `ScriptHost.stop`（摘管线 + `discard` 各 scope）→ `ScriptWorkers.stop`（最后停 worker） |

各步失败回什么码：部署两轴不在 → `NOT_DEPLOYED`；epoch 过期 → `INVALID_ARGUMENT`（带 `KEY_STALE_CONNECTION`）；授权重查不过 → `NOT_AUTHORIZED`；有界队列满 → `RATE_LIMITED`（带 `KEY_SERVER_BUSY`）；脚本自身未捕获 → `INTERNAL`（记过失）；钱已动 → `UNKNOWN`。

## 本步的边界

- **不做部署表 / 授权表 / 审批链 / 能力勾选**（S17）。两个视图只做"空表即拒"，没有任何"允许"分支。

> **这条边界已在 S17 Stage 1 拆掉**（两个视图换成生产实现）。S15g 当时钉住的真实行为是：
> `newEpoch` / `forget` 还没有生产调用点 ⇒ `epochs` 表恒空 ⇒ 真实请求在 **epoch 一档**就被拒，
> 回 `INVALID_ARGUMENT` + `mcphone.script.stale_connection`。**Stage 2 之后这条不再成立**：
> 三平台都在登录/登出事件里成对调用（见下），登录后带 epoch 的请求会走完整判定链。
> `docs/ScriptHostTest.java` 里那条"故意不喂 epoch"的断言仍在，它现在钉的是"喂错 epoch 仍然被拒"。
- **不做 AppScope 的生产装配**（S15g 当时）。Stage 2 已由 `ServerAppAssembler` 接上。
- 没有后端的 `ctx.*` 整项不挂（E12）：本期 `item / cycle / store / sealed / currencies` 全是 `null`。

## S17 Stage 2：epoch 成对（登录写、登出删）

```text
三平台同一处接线（顺序固定）：
  登录： ScriptHost.newEpoch(player)  ──►  HandshakeService.pushTo(player, epoch)
  登出： ScriptHost.forget(player)    ──►  （账本不动）
```

- `newEpoch` 保证发出去的 epoch **非 0**（0 只表示"没有 epoch"）；`ScriptHost.newEpoch` 在管线没装
  （装配失败降级）时返回 0，`pushTo` 看到 0 就不发握手 —— 客户端按"本服没有脚本后端"处理。
- 客户端收到 `begin → deployment × N → end` 后，`ClientHandshake.connectionEpoch()` 才非 0；
  每个 `ScriptRpc` 由 `ScriptCall` 回填这个 epoch。<b>epoch 对表示"是这一次连接"，不表示有权限</b>：
  判定链顺序不变，权限每次由服务端重查。
- **重连**：服务端登出删、登录给新值；客户端 `LoggingOut` 清空握手状态与在飞调用。旧连接的迟到结果
  回到客户端时 `requestId` 已无主，被 `ScriptCall.onResult` 安静丢弃（`INVALID_ARGUMENT +
  stale_connection` 那条兜底只对"还在飞但 epoch 已换"的请求生效）。
- **降级**：握手下发失败（`HandshakeService.pushTo` 的 try/catch）不打断登录；这名玩家本次连接的请求
  回到 epoch 一档的拒绝 —— 有明确返回码，不静默。

## S17 Stage 2：批准 / 撤部署后的主线程重装配

`/mcphone script approve` 与 `remove` 成功后，命令面调用 `ScriptHost.reassemble(server, appId)`
（**主线程**）：

```
新部署在表里？
  ├─ 不在（撤部署）        → apps.remove(appId) + 旧 scope discard()；请求回 NOT_DEPLOYED
  └─ 在                    → 扫 incoming 找该摘要的包
        ├─ 包不在 / 预检失败 → apps.remove(appId) + ERROR 日志；请求回 NOT_DEPLOYED
        └─ 装配成功         → 建完整新 scope → apps.put(appId, fresh) → 旧 scope discard()
```

- **在飞的求值用旧 scope 跑完**（它们持有旧引用，`discard()` 不等不打断）；批准后的新请求走新 scope。
  与 §20.4/"已进 worker 的请求不取消"一致。
- **失败即报错，不留中间态**：绝不出现"判定说已批准、执行却是空/旧包"；命令面回显
  "立即生效 / 未启动 / 失败"三种，失败时提示重新 approve 或重启。
- 不改判定链顺序、不动幂等账本语义 —— 只是把 `apps` 表换成可变 `LinkedHashMap`，
  `RhinoEvaluator` 持的引用在重装配后自然指向新项。

## 注册表口径（P4，E37）

- `CurrencyRegistry` **保持普通 `LinkedHashMap`**，不加锁、不换并发容器。
- 写（`register` / `clear`）只许发生在 `EconomyRuntime.install` / `stop` 内（那两处已 `synchronized`）；运行期不许再注册/清空 —— 要改货币表就重开世界。
- 读只许来自主线程（启动扫描是合规路径）。本步 `Backends.currencies` 传 `null`（生产里还没有 App 求值），**不新增任何 worker 侧的注册表读取**。S17 接 `ctx.currency` 时必须在装配期取一次冻结引用放进 `Backends`，或改为并发结构 —— 见 `EconomyRuntime.registry()` 的注释。
- 依据："读一个启动后不再变化的 `LinkedHashMap`"与"权威状态只归主线程"是两种不同的正确性来源，别混为一谈。

## "为什么不可用"只给键（E30③）

脚本 / 客户端能拿到的"为什么不可用"一律是**本地化键**（`mcphone.*`），不是自由文本：
`ScriptErrorCode.defaultMessageKey()` = `mcphone.script.code.<小写名>`；货币侧的 `CurrencyUnavailableException.reasonKey()` / `unavailableReasonKey()` 同样是 `mcphone.economy.*`。
断言钉在 `docs/ScriptHostTest.java` 的 `messageKeysAreLocalizationKeys()`。

## 交给 S17 的必做项（对抗组复核开出，本步不实施）

1. **`newEpoch` / `forget` 必须成对接到生产**（登录/登出写在同一处）：只接 `newEpoch` ⇒ `epochs` 表按玩家无界增长；只接 `forget` ⇒ 所有请求判过期。接上之前，真实请求一直停在 epoch 一档。
2. **`mainThread.accept` 的失败归宿**（P3）：`RhinoEvaluator.submit` 里 `mainThread.accept(...)` 不在任何 `catch` 内；生产是 `server::execute`，服务器停/已停时可能抛 `RejectedExecutionException` —— 那会让 worker 线程死、`onDone` 永不调、账本那条 `RESERVED` 本局永久挂着。必须给它一个确定的归宿（落定或下一拍重试），账本那条一定要结掉。
3. **`ScriptHost.stop()` 的 discard 时机**（P4）：现在是"先 `discard` 各 scope、后停 worker"。今天 `apps` 恒空无事；S17 接上真 App 后要明确"求值中途 scope 被 discard"的语义（等 worker 停完再 discard，或让 `AppScope` 支持并发 discard）。
4. **装配点失败的平台差异**（对抗组推算，需实测）：`ScriptHost.start` 若抛，NeoForge/Forge 的事件总线 per-listener catch，而 Fabric 的调用方直接调 handler ⇒ 可能等于**开服失败**。S17 之前让 `ScriptHost.start` 故意抛一次，在 Fabric 上确认后才决定要不要自己 `try/catch`。

