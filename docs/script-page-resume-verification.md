# 页面恢复回调交付与验证

日期：2026-10-27。分支：`codex/script-page-resume`。原开发基线：已验收的存储删除提交 `a1ded891`（PR #86）。本步只增加 `onResume()`，与存储删除分开提交；不增加依赖、服务端协议或公开页面接口版本，`mod_version` 保持 1.12.0。

## 行为约定

首次创建一个页面状态实例只执行 onInit。返回栈恢复原实例，或通过 nav 再次进入同参数缓存页时，在第一帧前执行一次无参 onResume；当前页相同参数导航仍无操作。改变参数创建新实例，关闭重开创建新状态，均不额外执行 onResume。

钩子使用原页面参数和最新 state，不自动覆盖草稿或重建页面数据。返回滚动快照沿用原规则。重绘、布局、tick、HTTP 响应或关闭本身不触发钩子。未声明钩子的 App 保持原行为。

PageStates 复用同一条初始化/恢复事务执行路径，统一 state、存储、HTTP 暂存与取消的提交顺序；失败保留原数据和原请求。初始化失败信息保持原规则，恢复失败日志明确标注 onResume，宿主复用已有的本地化操作失败提示。旧的 Opened 构造器、open 重载和两参数 restore 保持签名；新增带上下文的恢复重载供实际页面宿主调用。

## 改动清单（12 个文件）

| 文件 | 变化 |
|---|---|
| `shared/src/main/java/com/november/mcphone/core/script/engine/ClientScript.java` | 生命周期函数 onInit/onResume 均校验无参 |
| `shared/src/main/java/com/november/mcphone/core/script/sfc/PageStates.java` | 首次初始化、缓存激活恢复、精确返回恢复复用统一事务 |
| `shared/src/main/java/com/november/mcphone/core/script/client/ScriptPage.java` | 返回时传入原实例及执行上下文，复用已有失败提示 |
| `docs/ClientResumeTest.java` | 新增生命周期、真实宿主、事务回滚、请求取消、参数与草稿/滚动保留及可安装示例检查 |
| `docs/samples/page-resume/manifest.json` | 独立演示清单 |
| `docs/samples/page-resume/app.vue` | 列表恢复刷新摘要，保留已应用筛选与输入草稿 |
| `docs/samples/page-resume/pages/detail.vue` | 按编号保存/删除，返回触发列表恢复 |
| `docs/samples/page-resume/assets/icon.png` | 复用便签图标 |
| `docs/samples/page-resume/README.md` | 打包与游戏验收说明，不包含在安装 ZIP 中 |
| `docs/script-app-author-guide.md` | 钩子触发、事务、失败处理、开发分支状态 |
| `docs/release-notes/next.md` | 下一版本双语说明 |
| `docs/script-page-resume-verification.md` | 本报告 |

## 验证

首轮 NeoForge 1.21.1 通过 91 项恢复检查、76 项初始化回归、82 项导航回归。实际宿主的失败提示测试需要原版语言资源，测试在自己的输出目录临时提供最小资源，并于结束清理；该资源不进入成品 JAR。后续四端结果、CI 提交与验收包哈希以工作区 `script-page-resume-preview/REPORT.md` 为准。

Windows 既有 EconomyDataTest POSIX 权限检查不适用本机平台，未修改或跳过。此前 Forge 配置的外部证书预检也可能影响本地重复构建；以对应 PR 的四端完整 Linux CI 和产物为正式验证证据，不关闭证书检查。

## 游戏验收

1. 使用本轮对应目标的验收 JAR，将 `page-resume.zip` 放入 `mcphone/apps/`，从 AppStore 安装“页面恢复刷新演示”。
2. 输入“便签”并筛选，再把输入改为一段未应用的草稿，打开便签 1。
3. 编辑正文并保存后返回，摘要立即更新、恢复计数增加一次；筛选和输入草稿保留。
4. 再次打开便签 1，删除保存记录并返回，摘要变为暂无正文；筛选和草稿仍保留。
5. 使用手机返回键复核同样流程。关闭重开后恢复计数为 0，读取持久化数据；检查手机和平板布局。

本轮恢复回调尚待用户游戏验收；若提交草稿 PR，完成 CI 和游戏验收后再转为可合并状态。未发布新版本。
