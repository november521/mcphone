# 一次性延迟回调交付与验证

日期：2026-11-01。分支：`codex/script-delayed-callback`。基线：main `1da81c39`，已包含用户验收通过的 #88。模组版本仍为 1.12.0。

## 本步范围

新增 `ctx.timer.after(delayMs, callbackName)` 与 `ctx.timer.cancel(id)`，支持当前活动页面的无参函数延迟执行。延时 50–60000 毫秒、每页最多 8 个等待任务，每个页面 tick 最多执行一个到期函数；使用单调时钟，不创建线程或周期任务。

创建/取消与原有状态、存储、HTTP 事务一起提交。函数失败撤回该函数的效果，已到期的一次性任务不自动重试。切页、返回、关闭或账号守卫失效清理任务，返回旧状态不复活旧任务。函数读取最新 state 和原参数，不保留 JS 闭包。旧页 HTTP 响应不能向新页面安排任务。

保留旧 ClientScript、Statements.Bound、TemplateInstance.Tree、PageStates 和 ScriptPage 构造/调用签名。新增可选参数重载，未使用 timer 的旧 App 沿用原行为。纯 Java 调度器位于 `core.script.timer`，不引入 Minecraft 客户端类型、依赖、服务端协议或公开页面 API 版本。

## 改动清单（19 个文件）

| 文件 | 变化 |
|---|---|
| `shared/src/main/java/com/november/mcphone/core/script/timer/DelayedCallbacks.java` | 有界单页任务队列与事务批次，单调时间、取消和清理 |
| `shared/src/main/java/com/november/mcphone/core/script/engine/ClientScript.java` | ctx.timer 桥、参数及回调检查，保留原重载 |
| `shared/src/main/java/com/november/mcphone/core/script/sfc/Statements.java` | 函数调用传递任务事务 |
| `shared/src/main/java/com/november/mcphone/core/script/sfc/TemplateInstance.java` | 点击与输入事务提交/撤回任务批次 |
| `shared/src/main/java/com/november/mcphone/core/script/sfc/PageStates.java` | 初始化、恢复、HTTP 与延迟回调统一事务规则 |
| `shared/src/main/java/com/november/mcphone/core/script/client/ScriptPage.java` | 活动页面拥有队列，tick 执行，切页/关闭清理，隔离旧响应 |
| `docs/ClientTimerTest.java` | 确定性调度、事务、宿主生命周期、示例与布局测试 |
| `docs/ClientStorageTest.java` | 精确验证包含 timer 的宿主白名单及其 after/cancel 方法 |
| `docs/ClientInputEventTest.java` | 语言资源由测试构建任务提供，不再自行创建/删除共享文件 |
| `docs/ClientResumeTest.java` | 同上，消除并行测试语言资源竞态 |
| `gradle/mcphone-checks.gradle` | 统一生成独立测试资源，仅供断言 JVM 使用 |
| `docs/samples/delayed-search/manifest.json` | 独立防抖演示清单 |
| `docs/samples/delayed-search/app.vue` | 300 毫秒防抖、手动取消及 3 秒任务测试 |
| `docs/samples/delayed-search/pages/away.vue` | 切页清理的验收页面 |
| `docs/samples/delayed-search/assets/icon.png` | 复用便签图标 |
| `docs/samples/delayed-search/README.md` | 打包与验收说明 |
| `docs/script-app-author-guide.md` | API、时序、预算、取消和生命周期约定 |
| `docs/release-notes/next.md` | 下一版本双语说明 |
| `docs/script-delayed-callback-verification.md` | 本报告 |

## 本地验证

NeoForge 1.21.1：ClientTimerTest 131 项通过，覆盖暂存/提交、时间边界与 nanoTime 回绕、任务限额、取消/替换、跨页面编号隔离、每 tick 上限、嵌套轮询、账号失效、JS 桥参数、状态/文件/网络/任务回滚、磁盘冲突、回调失败、初始化/恢复、输入防抖、HTTP 及旧页响应隔离、切页/返回/关闭、真实可安装示例与手机/平板命中。

使用可控单调时钟推进时间，不用 sleep 或真实网络。测试隔离字体和操作系统边界，调用真实脚本、事务和 ScriptPage 路径；失败提示所需最小语言资源由构建任务生成到独立测试资源目录，不由并行 JVM 创建或删除，也不进入成品。

旧输入事件 130 项、页面恢复 101 项、初始化 76 项、HTTP 165 项、HTTP 取消 77 项、客户端函数 119 项、本地存储 122 项均通过。四端完整 Linux CI、成品校验和源文件快照以工作区交付报告为准。

首轮四端 CI 发现旧 ctx 白名单未包含新入口，以及 NeoForge 并行测试临时语言文件的竞态。白名单已精确加入 timer 及其 after/cancel 方法，测试资源由构建任务统一生成；生产预算和所有检查继续生效。

## 游戏验收

1. 替换本轮对应目标 JAR，安装 `delayed-search.zip` 中的“搜索防抖演示”。
2. 连续输入，停下约 300 毫秒后才应用筛选，“已查询”只增加一次。
3. 安排 3 秒任务后点“取消等待”，等 3 秒不增加查询次数，草稿保留。
4. 安排 3 秒任务后切页，等几秒返回；查询次数不增加，旧等待不恢复。
5. 安排任务后关闭 App，再打开时没有旧任务；手机和平板控件可见且可点击。

尚未发布，等待用户游戏验收。
