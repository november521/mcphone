# 本地存储单键删除：交付与验证

日期：2026-10-27。分支：`codex/script-storage-remove`。基线：已合并 HTTP 取消功能的 main，提交 `d16c2c27`（PR #85）。

本步只增加 `ctx.store.remove(key)`，并在现有本地便签示例中提供删除按钮。不增加依赖、服务端协议、配置或公开 Java API 版本；`mod_version` 保持 `1.12.0`。此接口尚未发布，已发布 v1.12.0 不提供 remove；本轮验收文件名带 `storage-remove-preview`。

## 接口与事务

```js
function deleteNote() {
  ctx.store.remove('note')
  state.note = ''
  state.status = '已删除保存的便签，重开不会恢复。'
}
```

- 严格接受一个键名字符串，规则与 getString / setString 一致：1–64 个 ASCII 字母、数字或 `_ . : -`。返回 `undefined`；不做类型转换，不接受额外参数。
- 只删除当前登录账号、当前 App 的一个键，不接收路径或其他账号信息。账号改变后的旧页面、已结束的事务均不能继续删除。
- 在原 `LocalStore.Transaction` 的副本中调用已有 remove；同次操作后续读取立即返回默认值，腾出的键数和字节额度可以用于后续写入。
- 点击、初始化和 HTTP 回调沿用同一套提交规则。脚本错误、状态校验、额度或保存失败时，删除与本次 state、本地写入和待发请求 / RPC 一起撤回，原数据保留。
- 删除不存在的键无操作，不创建新文件或重写已有文件。删除最后一个键保存为空 JSON 对象，不删除整个文件、目录或其他数据。
- 损坏或超限文件仍明确报错，不会借删除接口覆盖；参数错误不能被脚本 try/catch 吞掉后留下部分删除。

## 改动文件（8 个）

| 文件 | 改动 |
|---|---|
| `shared/src/main/java/com/november/mcphone/core/script/server/store/LocalStore.java` | Transaction 增加单键删除，复用底层 remove，仅实际删除时标记待保存 |
| `shared/src/main/java/com/november/mcphone/core/script/engine/ClientScript.java` | 增加有严格参数校验的 ctx.store.remove 宿主方法 |
| `docs/ClientStorageRemoveTest.java` | 新增真实 SFC、存储事务、持久化、配额、回滚、账号/App 隔离、钩子及实际便签示例检查 |
| `docs/ClientStorageTest.java` | 更新宿主方法清单，保留原存储回归 |
| `docs/samples/textarea.vue` | 本地便签 1.1.0，新增独立“删除已保存便签”，保留“清空编辑区”的草稿语义 |
| `docs/script-app-author-guide.md` | 方法说明、开发分支状态、额度与失败处理、删除和清空的区别 |
| `docs/release-notes/next.md` | 下一版本双语说明 |
| `docs/script-storage-remove-verification.md` | 本报告 |

## 验证记录

四端均通过 103 项删除检查、122 项原存储回归及 64 项多行输入回归，针对性检查退出码均为 0；四端完整构建中的打包任务均通过。命令结果、原始日志与包体哈希见工作区 `script-storage-remove-preview/REPORT.md`。新增验证直接执行 Rhino/SFC 和真实文件操作，并验证删除后重开、同批读写顺序、64 键额度和 UTF-8 字节额度释放。

四端均实际执行 `build --continue --offline`；每端只有下述既有 Windows 权限测试失败，其余初始化、函数传参、HTTP 取消（77 项）、HTTP（165 项）、导航（82 项）、输入、布局、协议和平台边界检查通过。提交后以该功能 PR 对应提交的 Linux CI 为准。

完整 build 保留既有检查。Windows 上 `EconomyDataTest.unreadableIsNotAbsent` 第 936 行使用 POSIX 文件权限接口会抛 `UnsupportedOperationException`，本轮不修改或跳过该测试，不能把受此影响的全量构建表述为全绿。Forge 首次在线配置遇到外部 Maven 仓库证书检查失败，随后离线针对性检查成功、完整构建完成打包；最后一次重复配置又触发同一证书预检失败。因此 Forge 使用本轮已经成功生成的 JAR 与此前成功的针对性检查记录，另核对包内接口，并比较重映射后的删除方法及宿主调用指令与编译输出。未关闭证书验证或修改构建配置。

## 游戏验收

1. 使用对应目标的验收 JAR，把交付目录的 `textarea.vue` 放入游戏 `mcphone/apps/`，从 AppStore 安装或更新“本地便签”1.1.0。保留原 App id，因此可读取之前保存的便签。
2. 输入多段中文与表情并保存，关闭重开，应恢复内容。
3. 点击“清空编辑区”，关闭重开，保存内容仍应恢复。这只是清空草稿。
4. 点击“删除已保存便签”，编辑区与提示更新；关闭重开，旧便签不再恢复。
5. 空便签再点删除应正常；输入新内容并保存，关闭重开仍可恢复新内容。
6. 检查手机和平板布局：删除按钮可见，编辑区仍可输入和滚动。

用户已于 2026-10-27 确认游戏内测试无误，并授权复核后提交 PR。本轮未发布新版本。
