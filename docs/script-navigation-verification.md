# 页面导航传参交付与验证

日期：2026-10-27。分支：`codex/script-navigation-params`。基线：已验收的多行输入提交 `ae81f1b7`。

本步只增加脚本 App 的页面导航传参，尚未发布。本体 `mod_version` 仍是 `1.12.0`，验收文件名另加 `navigation-preview`。

## 使用与生命周期

```html
<button @click="nav('detail', { id: note.id, title: note.title })">打开详情</button>
```

目标 `pages/detail.vue` 的客户端函数通过只读 `ctx.params` 读取参数。参数与 state 分开，最多 4 个字段，值为字符串、32 位整数或布尔值；字符串最多 256 个 UTF-16 单元，拒绝不完整 Unicode、数组、嵌套对象和 null。

- 参数在点击执行到 nav 时求值，使用当前 state 和点击行的循环变量。
- 参数相同复用该页的最新缓存；参数改变新建 state，并在第一帧前执行无参 `onInit()`。
- 返回恢复原页的参数、state 和滚动位置，不重新初始化。返回栈沿用 8 层上限。
- 每个页名只缓存最近的一组参数，返回栈另持有原页；关闭时清理返回记录。草稿长期保留仍使用已有本地存储。
- HTTP 回调仍进入发起请求的原 state；改变参数打开另一页实例，不会把旧结果写入新实例。
- 非法参数撤回整次点击的 state、本地保存、待发 HTTP 和导航；无参导航、现有服务端 RPC 和页面接口保留。

实现使用一份跨四端共享代码，不新增第三方依赖，不增加 Minecraft 服务端协议。完整作者说明见 [脚本 App 作者指南](script-app-author-guide.md)。

## 改动清单（16 个文件）

| 文件 | 变化 |
|---|---|
| `shared/src/main/java/com/november/mcphone/core/script/layout/PageParameters.java` | 不可变参数值与统一类型、长度、Unicode 检查 |
| `shared/src/main/java/com/november/mcphone/core/script/layout/UiState.java` | 保存只读参数元数据，事务副本保留原参数 |
| `shared/src/main/java/com/november/mcphone/core/script/engine/ClientScript.java` | 每次调用注入无原型、封闭的只读 ctx.params |
| `shared/src/main/java/com/november/mcphone/core/script/sfc/Statements.java` | 解析和求值 nav 第二参数，失败时整体撤回，保留旧 Outcome 构造签名 |
| `shared/src/main/java/com/november/mcphone/core/script/sfc/PageStates.java` | 参数变化初始化、相同参数复用、精确恢复旧实例 |
| `shared/src/main/java/com/november/mcphone/core/script/client/ScriptPage.java` | 有界返回记录保存原实例与滚动快照，同页可换参数，关闭清理 |
| `docs/ClientNavigationTest.java` | 新增 82 项参数、事务、真实宿主、回调及可安装示例检查 |
| `docs/ClientStorageTest.java` | 更新宿主字段清单，纳入 params，保持存储与 HTTP 方法检查 |
| `docs/samples/navigation-params/manifest.json` | 多页示例清单 |
| `docs/samples/navigation-params/app.vue` | 列表、列表草稿和携带行数据的跳转 |
| `docs/samples/navigation-params/pages/detail.vue` | 读取参数、独立便签保存、同页换参数和返回 |
| `docs/samples/navigation-params/assets/icon.png` | 复用原有笔记图标 |
| `docs/samples/navigation-params/README.md` | 打包说明与验收步骤，排除在安装 ZIP 之外 |
| `docs/script-app-author-guide.md` | 新能力、初始化与缓存规则及开发分支状态 |
| `docs/release-notes/next.md` | 下一版本双语说明 |
| `docs/script-navigation-verification.md` | 本报告 |

## 验证记录

四端均实际执行 `build --continue`。调整新增 ctx 字段对应的既有清单测试，并排除示例 ZIP 中的 README 后，最终完整构建每端仅剩既有 `EconomyDataTest.unreadableIsNotAbsent` 第 936 行在 Windows 调用 `Files.setPosixFilePermissions` 的 `UnsupportedOperationException`；该测试没有修改或跳过。

最终另外执行打包、导航和存储针对性检查，退出码均为 0。完整构建记录中的原有初始化、函数传参、HTTP、输入、SFC、布局、协议、客户端隔离和平台架构检查均通过。

| 目标 | 编译与打包 | 导航检查 | 存储回归 | 完整 build |
|---|---|---|---|---|
| Forge 1.20.1 / Java 17 | 通过 | 82 项通过 | 122 项通过 | 仅上述 Windows 失败 |
| NeoForge 1.21.1 / Java 21 | 通过 | 82 项通过 | 122 项通过 | 仅上述 Windows 失败 |
| Fabric 1.21.1 / Java 21 | 通过 | 82 项通过 | 122 项通过 | 仅上述 Windows 失败 |
| NeoForge 26.1.2 / Java 25 | 通过 | 82 项通过 | 122 项通过 | 仅上述 Windows 失败 |

不能把本机全量 build 表述为全绿；提交 PR 后以该提交的 Linux CI 为准。用户已于 2026-10-27 确认游戏内检查完毕，并授权复核后提交 PR。日志、四端验收包、示例安装 ZIP、源码快照及哈希清单位于工作区 `script-navigation-preview/`。

## 游戏验收步骤

1. 使用对应目标的验收 JAR，安装交付目录的 `navigation-params.zip`（放入游戏的 `mcphone/apps/`，从应用商店安装“便签导航演示”）。
2. 列表输入一段草稿，点击“便签 1”，核对详情标题与编号。
3. 编辑多段正文并保存，再改成一段未保存的草稿；点击“下一条”应打开编号 2 自己的内容。
4. 点击返回，应恢复编号 1 的未保存草稿；再次返回，应恢复列表草稿与打开次数。
5. 关闭 App 后重开，再打开便签 1，应恢复最后保存的正文，而不是未保存草稿。编号 2 的保存记录应与编号 1 分开。
6. 另用列表项目较多的页面检查返回时滚动位置保留；确认切页后旧输入框释放焦点。

游戏内验收已由用户确认完成；自动化测试中的真实宿主与存储流程也覆盖上述数据与返回规则。
