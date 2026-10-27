# 多行文本输入交付与验证

开发日期：2026-10-26；复核日期：2026-10-27。开发分支：`codex/script-textarea`。基线：已发布 `v1.12.0`（`a2b9f500`）。

本步完成一项功能：脚本 App 的 `textarea` 多行输入控件。功能尚未发布，验收包内的本体版本仍为 `1.12.0`，文件名另标记 `textarea-preview`。

## 功能与实现

- 已声明的页面字符串双向绑定；支持换行、按宽度折行、光标、键盘与拖动选区、复制剪切粘贴、内部滚动、可见区绘制。
- SFC 默认最多 4096 个 UTF-16 单元，可用 `max-length` 缩小；文本写入遵守现有整页 16384 单元预算。长度截断、删除和移动不拆开表情符号的 UTF-16 代理对。
- 单行与多行输入共用焦点顺序，重排保留光标和滚动；隐藏、禁用、切页、关闭及取消输入时释放捕获。
- 编辑模型在共享客户端层实现，复用既有状态、MSS、画布裁剪、字体和剪贴板接口。原版 `EditBox` 作为焦点委托，为 26.1.2 转发系统文字输入启停通知；多行正文由独立模型保存和绘制。
- 新增第三方依赖为零；加载器源码、服务端协议和公开 Java API 签名无改动。
- [本地便签示例](samples/textarea.vue) 复用 `ctx.store` 与 `onInit()` 保存和恢复。示例上限为 1024 单元，以适配既有单值 4 KiB UTF-8 存储配额；组件本身不扩大存储配额。

## 改动清单（16 个文件）

| 文件 | 变化 |
|---|---|
| `shared/src/main/java/com/november/mcphone/core/script/client/render/TextAreaEditor.java` | 新增有界编辑模型，处理 Unicode、换行、选区、光标与滚动 |
| `shared/src/main/java/com/november/mcphone/core/script/client/render/TextInputs.java` | 接入多行编辑、绘制、系统焦点及统一输入管理 |
| `shared/src/main/java/com/november/mcphone/core/script/client/render/Renderer.java` | 多行控件绘制分派与禁用状态 |
| `shared/src/main/java/com/november/mcphone/core/script/client/ScriptPage.java` | 转发拖动、松手及控件内部滚轮事件 |
| `shared/src/main/java/com/november/mcphone/core/script/layout/NodeType.java` | 注册 textarea 类型和文本输入节点判定 |
| `shared/src/main/java/com/november/mcphone/core/script/layout/NodeParser.java` | 旧 IR 识别新类型，保留旧长度策略 |
| `shared/src/main/java/com/november/mcphone/core/script/layout/LayoutEngine.java` | 默认四行内容高度 |
| `shared/src/main/java/com/november/mcphone/core/script/layout/Style.java` | 复用文本输入默认样式 |
| `shared/src/main/java/com/november/mcphone/core/script/sfc/PropRules.java` | 属性、绑定类型及 4096 单元上限检查 |
| `shared/src/main/java/com/november/mcphone/core/script/sfc/TemplateCompiler.java` | 保持输入节点身份，避免条件替换时串焦点 |
| `docs/ScriptTextAreaTest.java` | 新增编译、交互、预算、字体变化及实际落盘恢复检查 |
| `docs/ScriptNodeTest.java` | 更新组件能力表，纳入新组件 |
| `docs/samples/textarea.vue` | 可安装的本地便签示例 |
| `docs/script-app-author-guide.md` | 新控件用法、限制与开发分支标记 |
| `docs/release-notes/next.md` | 下一版本的中英文说明 |
| `docs/script-textarea-verification.md` | 本报告 |

## 自动验证

最终源码针对四端均执行了打包、多行与单行输入、节点解析、布局、SFC 编译、长文本、初始化、HTTP 回归，以及客户端隔离、平台中立性、依赖边界、平台和加载器孪生、SPI、接缝清单检查。最终针对性命令退出码均为 0。

| 目标 | 编译与打包 | 多行输入检查 | 相关回归与架构检查 |
|---|---|---|---|
| Forge 1.20.1 / Java 17 | 通过 | 64 项通过 | 通过 |
| NeoForge 1.21.1 / Java 21 | 通过 | 64 项通过 | 通过 |
| Fabric 1.21.1 / Java 21 | 通过 | 64 项通过 | 通过 |
| NeoForge 26.1.2 / Java 25 | 通过 | 64 项通过 | 通过 |

开发过程中四端也实际执行了 `build --continue`。更新组件清单后，这轮全量检查各端剩余失败均为既有 `EconomyDataTest.unreadableIsNotAbsent` 第 936 行在 Windows 调用 `Files.setPosixFilePermissions` 抛出 `UnsupportedOperationException`。该测试未修改或跳过，不能把全量构建表述为全部通过。最后两项编辑模型调整（字体度量变化重排、避免重复文本规范化分配）已另外通过上表中的最终打包与针对性检查。

2026-10-27 再次复核编辑模型、输入管理、布局与编译接入，未发现阻塞提交 PR 的问题。额外对真实编辑模型执行 72000 次组合操作，覆盖编辑、选区、剪贴板、滚动、尺寸与字体度量变化、外部赋值和拒绝写入，检查状态一致性、Unicode 边界、行范围及滚动范围，全部通过。此为本次本地复核，既有 64 项多行输入检查继续进入四端自动验收集。

日志、带目标名的验收包及 SHA-256 清单位于工作区 `script-textarea-preview/`。用户于 2026-10-27 反馈“验证没问题”，本轮游戏验收据此记为通过。下方保留游戏内复核步骤；Linux 完整构建结果以 PR 对应提交的 CI 为准。

## 游戏内验收

1. 使用对应加载器的验收 JAR 替换游戏中的 MCphone 包。验收包的本体版本仍是 `1.12.0`，按文件名中的 `textarea-preview` 区分。
2. 打开应用商店右上角的文件夹入口，将 `textarea.vue` 放进当前游戏的 `mcphone/apps/`，重新进入商店并安装“本地便签”。
3. 输入超过 64 单元的中文，Enter 换行，粘贴含表情符号的多段文字；文字应完整保留，超过可见区后可在编辑框内部滚动。
4. 测试方向键、Shift 选区、Home / End、跨行拖选及 Ctrl / Command + A/C/X/V；自动折行处的行尾定位应正确，选区替换不应误删未选中的后文。
5. 点击保存，关闭 App 再重开，应恢复正文、换行和表情符号。“清空编辑区”后不保存，重开仍恢复上次记录；保存空正文后重开为空。
6. 分别检查手机缩放 75%、150%、300% 时的文字裁剪、光标与点击位置；切换 App 后，旧便签不能继续接收输入。

本轮游戏验收已收到用户通过反馈。
