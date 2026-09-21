# S15h/S15i 脚本失败与处分审计

基线：`feature/economy-persistence@4f4e42d`。本表描述本次实现后的实际分支；处分只存在于 `RhinoEvaluator.Completion`，不会进入脚本或网络结果。

| 来源 | Java 形态 | 脚本可 catch | 最终结果 | 处分 | 发生线程 / 落地线程 |
|---|---|---:|---|---|---|
| 指令预算、墙钟预算 | `ScriptAbort(INSTRUCTIONS/WALL_CLOCK)` | 否 | `INTERNAL`；钱已动则 `UNKNOWN` | `STRIKE`；钱已动则 `NONE` | worker / 主线程 |
| 宿主桥重入 | `ScriptAbort(STACK)` | 否 | 同上 | 同上 | worker / 主线程 |
| 模块越权、缺失、循环、深度或数量上限 | `ScriptAbort(HOST)` | 否 | 同上 | 同上 | worker / 主线程 |
| `ScriptAbort.SIZE` | 当前无抛出点；尺寸拒绝已走 `HostError` | — | — | — | — |
| `ScriptAbort.RETAINED` | 当前无抛出点；驻留量由 `sweepRetained` 返回值处理 | — | — | — | — |
| 字符串/数组尺寸、参数类型、精确整数越界 | 私有构造的 `HostError(INVALID)` | 是 | 未捕获时 `INTERNAL` | `NONE` | worker / 主线程 |
| `currency.parse` 非法文本 | `HostError(INVALID)` | 是 | 未捕获时 `INTERNAL` | `NONE` | worker / 主线程 |
| 未知 `cycle` 类型 | `HostError(UNKNOWN_VALUE)` | 是 | 未捕获时 `INTERNAL` | `NONE` | worker / 主线程 |
| `ctx.shared` 键/值上限 | 入口尺寸先为 `HostError(INVALID)`；通过入口后 SharedState 上限为 `HostError(QUOTA)` | 是 | 未捕获时 `INTERNAL` | `NONE` | worker / 主线程 |
| store shared 档配额 | `StoreQuota.QuotaExceeded` 在 `CtxBuilder` 写边界翻译为 `HostError(QUOTA)` | 是 | 未捕获时 `INTERNAL` | `NONE` | worker / 主线程 |
| 服务不可用、货币不存在 | `HostError(UNAVAILABLE)` 或业务返回码 | 是（异常路径） | 未捕获时 `INTERNAL` | `NONE` | worker / 主线程 |
| 外部输入格式错误（UUID、金额、ref） | 业务返回码 `INVALID/UNKNOWN_ESCROW` | 不适用 | 脚本决定 | `RESET`（求值正常完成） | worker / 主线程 |
| provider 动钱时抛普通异常/Error 或返回 null | `OutcomeUnknown` | 否 | `UNKNOWN` | `NONE` | provider 主线程 → worker / 主线程 |
| provider 抛 `ScriptAbort` | 显式包装为不可捕获 `ProviderAbort` | 否 | 动钱调用为 `UNKNOWN` | `NONE` | provider 主线程 → worker / 主线程 |
| provider 只读调用抛异常/Error | `ProviderFailure` / `ProviderError` / `ProviderAbort` | 否 | `INTERNAL` | `NONE` | provider 主线程 → worker / 主线程 |
| 脚本自身未捕获异常 | `RhinoException` | 已未捕获 | `INTERNAL`；钱已动则 `UNKNOWN` | `STRIKE`；钱已动则 `NONE` | worker / 主线程 |
| 驻留清扫 getter 抛任意 `Throwable` | 清扫局部捕获 | 不适用 | 保留已算出的业务结果；钱已动则 `UNKNOWN` | `STRIKE`；钱已动则 `NONE` | worker（Context 内）/ 主线程 |
| 求值器意外宿主失败 / VM 级错误 | 最外层 `Throwable` | 否 | `INTERNAL`；钱已动则 `UNKNOWN` | `NONE` | worker / 主线程 |
| 注册表：同一 `currencyId` 第二个实例（S15f） | `CurrencyRegistry.register` 记一条 ERROR 并返回 `false`，不替换 | 不适用（发生在接线/开服，不在脚本求值内） | 原实例保留、新实例不生效；不抛，不改任何结论 | `NONE`（不是脚本行为） | 主线程（开服接线）；测试直调时调用线程 |
| 注册表为空（新世界、尚无配置，S15f） | `get(id)` 返回 `null`；`ctx.currency` 不挂空壳 | 不适用 | `ctx.currency.default()` 为 `null`、`list()` 为空；App 该 `ctx.fail('UNAVAILABLE', …)`，不崩 | `NONE` | 主线程（开服） |
| 默认货币未配置（S15f） | `defaultCurrency()` 返回 `null` | 不适用 | 同左；不抛、不猜 | `NONE` | 主线程 |
| 能力未批 / 被服主关 / 首版不开放（S18） | `HostError.denied(NOT_AUTHORIZED/UNAVAILABLE, key)` | 是 | 未捕获时按 `resultCode` 回（`NOT_AUTHORIZED` / `UNAVAILABLE`，带能力文案键）；钱已动则 `UNKNOWN` | `NONE` | worker / 主线程 |
| 落地端没接通 / 玩家离线（S18） | `IntentApplier.UNWIRED` 或 `ServerIntentApplier` 返回 `Landed(UNAVAILABLE)` | 不适用（求值已结束） | `UNAVAILABLE` + `mcphone.script.intent_unavailable` | `RESET`（不是脚本的错） | 主线程 |
| 发放时背包满（§20.9 `reject`，S18） | `ServerIntentApplier` 返回 `Landed(INVENTORY_FULL)` | 不适用 | `INVENTORY_FULL`（追加的第 16 个码），一个物品都不放 | `RESET` | 主线程 |
| 物品不在礼包白名单（§18.5，S18） | `ServerIntentApplier` 返回 `Landed(INVALID_ARGUMENT)` + `NOT_GIFTABLE` | 不适用 | `INVALID_ARGUMENT` + `mcphone.script.give.not_giftable`，一个物品都不放 | `RESET` | 主线程 |
| 谓词 id 认不得（§18.3，S18） | `HostError.denied(UNAVAILABLE, NO_SUCH_PREDICATE)` | 是 | 未捕获时 `UNAVAILABLE` + `mcphone.script.predicate.unavailable`；是配置错，不当判否 | `NONE` | worker / 主线程 |
| 计分板此刻做不了（§18.6，S18） | 网关拒绝 / 只读 objective / 查不到玩家名 → `HostError.denied(UNAVAILABLE, SCORE_UNAVAILABLE)` | 是 | 未捕获时 `UNAVAILABLE` + `mcphone.script.score.unavailable`；读写全在主线程上做 | `NONE` | worker → 网关 → 主线程 |
| 落地期间部署换了包 / 撤了重批（S18） | `ScriptPipeline.land` 重查 `deployRev` 不相等 | 不适用 | `VERSION_MISMATCH`，意图一条都不落地 | `NONE`（管线判定，不是脚本行为） | 主线程 |
| 落地执行到一半失败（S18） | `ServerIntentApplier` 返回 `UNKNOWN` | 不适用 | `UNKNOWN`；钱已动同样是 `UNKNOWN` | `RESET` | 主线程 |

注册表级三行与本表其余行不同：它们不经过脚本求值，因此没有"脚本可 catch"与"处分"可言 —— 一律 `NONE`，不记任何玩家过失。第二行是**正常状态**（新世界一种货币都没有），不是错误。

## 线程与顺序

- `StrikeTracker` 的四张普通表由构造它的服务端主线程独占；每个公开读写入口都有 owner 断言。没有并发容器、`synchronized` 或新锁来掩盖归属问题。
- worker 只返回 `Outcome + NONE/RESET/STRIKE`。主线程按队列实际收到的顺序先应用处分，再调用 `onDone`；已进入 worker 的请求不取消、不重排。
- `evaluate` 的脚本执行、驻留清扫、预算收尾和 `Context.exit` 都逐段收敛 `Throwable`，不会因 getter 炸弹漏掉完成回调。

## 不可伪造归因

`HostError` 是 final、构造器私有，每个实例携带与类内静态私有 token 做身份比较的字段。`classify` 不读取 message、类名或 JS 属性。脚本可以伪造同名文本，但拿不到 token，也不能改变处分分支。

## 两层配额的先后

宿主函数入口先执行 `SizeGate`（字符串 64 KiB、数组 4096）；通过后，store 的 `KvBackend` 再按 `StoreQuota` shared 档检查单值 2 KiB、64 键、每玩家每 App 8 KiB 等限制。`ctx.shared` 另有 `SharedState` 的 64 KiB 单值与每 App 4096 键限制，不等同于 store shared 档。所有拒绝都发生在后端写入前或由后端的原子拒绝抛出，不产生部分写入。

## 搜索核对

审计时使用以下构造点集合核对实现：`new ScriptAbort`、`throw HostError`、`QuotaExceeded`、`OutcomeUnknown`、`ProviderAbort`、`RhinoException` catch。冻结的 `api/**` 无差异。
