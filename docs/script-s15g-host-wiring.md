# S15g 脚本宿主接线（谁在哪条线程建、谁在哪条线程销毁）

本步把四段接成一条能跑的路：`ScriptRpcHandler.handle → ScriptPipeline → RhinoEvaluator → CtxBuilder.build`。
只接线，不顺带重构；生产侧新增一个装配点 `core/script/server/ScriptHost`，两个占位视图 `DenyAllDeployments` / `DenyAllAuthority`。

## 接线图（线程 × 五个时机）

| 时机 | 线程 | 顺序 |
|---|---|---|
| 开服 | 服务端主线程 | `EconomyRuntime.start`（建注册表、扫超时托管、最后开网关）→ `ScriptWorkers.start` → `ScriptHost.start`：**主线程**建 `StrikeTracker` → `RhinoEvaluator(apps={}, backends, server::execute)` → `ScriptPipeline(ledger, limiter, DenyAll*, evaluator)` → `ScriptRpcHandler.install` |
| 请求进来 | 主线程（门面保证） | `handle → pipeline.accept`：协议 → 连接 epoch → 部署（`DenyAll` ⇒ `NOT_DEPLOYED`）→ 限流 → 幂等账本 → `evaluator.submit` |
| 求值 | 2 条 worker | `RhinoEvaluator.evaluate`：`AppScope`（本步为空）/ `ctx` / 预算 / 异常分类 → `Completion`。**不碰 `StrikeTracker`** |
| 落地 | 主线程 | `land`：重查授权（`DenyAll` ⇒ `NOT_AUTHORIZED`）→ 结算账本 → 回包；`StrikeTracker` 只在这条线程上被碰 |
| 停服 | 主线程 | `EconomyRuntime.stop`（先关货币网关）→ `ScriptHost.stop`（摘管线 + `discard` 各 scope）→ `ScriptWorkers.stop`（最后停 worker） |

各步失败回什么码：部署两轴不在 → `NOT_DEPLOYED`；epoch 过期 → `INVALID_ARGUMENT`（带 `KEY_STALE_CONNECTION`）；授权重查不过 → `NOT_AUTHORIZED`；有界队列满 → `RATE_LIMITED`（带 `KEY_SERVER_BUSY`）；脚本自身未捕获 → `INTERNAL`（记过失）；钱已动 → `UNKNOWN`。

## 本步的边界

- **不做部署表 / 授权表 / 审批链 / 能力勾选**（S17）。两个视图只做"空表即拒"，没有任何"允许"分支。

> **当前真实返回码要说准（对抗组 P1）**：管线确实装上了，但 `newEpoch` / `forget` 还没有生产调用点
> ⇒ `epochs` 表恒空 ⇒ 真实请求在 **epoch 一档**就被拒，回 `INVALID_ARGUMENT` + `mcphone.script.stale_connection`，
> **根本走不到部署判定**。所以"部署表为空 ⇒ `NOT_DEPLOYED`"是 S17 接上握手之后的第一道，不是今天的原因。
> `docs/ScriptHostTest.java` 里有一条**故意不喂 epoch** 的断言钉住这个真实行为。
- **不做 AppScope 的生产装配**：它的来源是"已部署的 server 包"，那正是 S17 的本体。本步 `apps` 表为空，端到端只由 `docs/ScriptHostTest.java`（真 `AppScope` + 真 `RhinoEvaluator` + 真 `ScriptPipeline` + 真 worker）跑通。
- 没有后端的 `ctx.*` 整项不挂（E12）：本期 `item / cycle / store / sealed / currencies` 全是 `null`。

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

