# 输入内容变化回调交付与验证

日期：2026-10-31。分支：`codex/script-input-events`。基线：main `892b5633`（已合并 PR #87）。本步仅增加 `@input`，模组版本保持 1.12.0。

## 行为约定

`text-input` 和 `textarea` 可以写 `@input="changed()"`。属性只接受一次本页已声明函数调用，可复用现有 0–4 个基本类型参数和循环行参数。赋值、多语句、导航和 RPC 语句不能放入 @input。

用户编辑实际改变文字、并通过现有控件及整页预算后，先写入绑定 state，再调用处理函数。光标、选区、无变化或被拒绝的编辑不调用。首次创建、代码赋值、规范化、重排、生命周期和 tick 不自动调用；处理函数修改自己的绑定字段也不会递归触发。

回调复用现有沙箱和点击事务执行路径，其他状态、存储、HTTP 暂存及请求取消同成败。失败保留已经接受的用户输入与编辑焦点，撤回回调效果，沿用已有提示和日志。单行和多行写入共用整页文本预算检查。旧 App 不写 @input 时继续只有绑定更新，旧点击入口及 TextInputs 构造器保留。

没有新增依赖、线程、服务端协议、SDK 占位能力或公开页面 API 版本。模板编译模型的扩展为内部结构；事件绑定保存在实例树中，不塞进 Node 业务属性。

## 改动清单（15 个文件）

| 文件 | 作用 |
|---|---|
| `shared/src/main/java/com/november/mcphone/core/script/sfc/CompiledTemplate.java` | 内部模板记录输入处理函数 |
| `shared/src/main/java/com/november/mcphone/core/script/sfc/TemplateCompiler.java` | 输入控件接受 @input，继续校验属性和位置 |
| `shared/src/main/java/com/november/mcphone/core/script/sfc/Statements.java` | 限定单次本页函数调用，复用函数参数和预算，保留事件诊断 |
| `shared/src/main/java/com/november/mcphone/core/script/sfc/TemplateInstance.java` | 输入与点击共用事务路径，失败保留已接受输入 |
| `shared/src/main/java/com/november/mcphone/core/script/client/render/TextInputs.java` | 仅在有效用户编辑后通知，程序化同步不通知 |
| `shared/src/main/java/com/november/mcphone/core/script/client/ScriptPage.java` | 将实际编辑节点接入输入事务及已有反馈 |
| `docs/ClientInputEventTest.java` | 编译、真实编辑器/宿主、事务及样例布局检查 |
| `docs/samples/input-events/manifest.json` | 独立即时输入演示清单 |
| `docs/samples/input-events/app.vue` | 输入即时筛选，无需筛选按钮 |
| `docs/samples/input-events/pages/detail.vue` | 多行编辑即时更新长度和预览，手动保存 |
| `docs/samples/input-events/assets/icon.png` | 复用便签图标 |
| `docs/samples/input-events/README.md` | 安装、行为和游戏验收步骤 |
| `docs/script-app-author-guide.md` | 用法、边界及开发分支状态 |
| `docs/release-notes/next.md` | 下一版本双语说明 |
| `docs/script-input-events-verification.md` | 本报告 |

## 本地验证

NeoForge 1.21.1：新增 ClientInputEventTest 130 项通过；ScriptTextInputTest 44 项、ScriptTextAreaTest 64 项、ClientResumeTest 101 项、ClientScriptTest 119 项、SfcCompilerTest 534 条断言通过。

检查覆盖实际页面按键分发、中文/emoji、粘贴删除与选区替换、程序赋值/上限变化不触发、隐藏禁用、递归抑制、脚本/类型/预算异常、存储/网络回滚、磁盘冲突、循环参数、旧无处理函数 App，以及手机和平板中输入框、编辑框、保存按钮的可用尺寸与命中。

操作系统窗口、原版单行剪贴板与字体边界在测试中隔离；使用真实编译器、原版编辑器和页面代码。失败提示所需最小语言资源仅临时写入测试输出，结束清理，不进入成品 JAR。四端完整 Linux CI 和产物校验记录以工作区交付报告为准。

## 游戏验收

1. 使用本轮对应目标的 JAR，把 `input-events.zip` 放入游戏 `mcphone/apps/`，从 AppStore 安装“便签即时输入演示”。旧示例不需要替换。
2. 输入“2”，列表应立即只显示便签 2；删空后恢复三条，输入无匹配内容后列表为空。
3. 打开便签，多行输入、粘贴、删除、换行，长度及预览立即更新。移动光标和选择文字不改变预览。
4. 点击保存后返回，列表摘要刷新；关闭重开确认已保存内容仍可读。
5. 检查手机和平板的搜索框、多行编辑区及保存/返回按钮。

尚未发布；游戏验收由用户完成，提交草稿 PR 后等待确认。
