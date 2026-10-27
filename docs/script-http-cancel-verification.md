# 单个 HTTP 请求取消：交付与验证

日期：2026-10-27。分支：`codex/script-http-cancel`。基线：导航传参提交 `60f0d558`（PR #83，依赖多行输入 PR #82）。

本步只增加请求编号与单个请求取消。未新增第三方依赖、服务端协议或设置项；`mod_version` 保持 `1.12.0`，交付文件使用 `http-cancel-preview` 后缀。用户已于 2026-10-27 确认游戏内检查无误；本轮未发布新版本。

## 开发者能做什么

```js
state = { requestId: 0, loading: false, answer: '' }

function send() {
  state.requestId = ctx.http.request({url: 'http://127.0.0.1:18765/delay', method: 'POST', body: '{"question":"hello"}'}, 'received')
  state.loading = true
}

function cancelRequest() {
  ctx.http.cancel(state.requestId)
  state.requestId = 0
  state.loading = false
  state.answer = '已取消，可以重新发送'
}

function received(status, body, error) {
  state.requestId = 0
  state.loading = false
  state.answer = error ? error : body.slice(0, 4096)
}
```

- `request` 返回当前客户端运行期间不重复的正整数。编号可放入页面的整数 state；无须保存到磁盘。已发布 1.12.0 的返回值仍是 `undefined`，新返回值属于开发分支能力。
- `cancel(id)` 返回 `undefined`；接受非负的 32 位整数。`0`、已完成和未知编号无操作，非法类型终止整次脚本操作。只在当前 App 会话中查找，不影响其他 App；关闭、重开和账号切换不会复用旧编号。
- 取消仍走原有 state / 本地保存事务，成功提交后才移除待回调记录并尝试中止传输。操作失败时原请求继续，页面等待状态和磁盘保持原样。
- 已收到但尚未调用的响应也会丢弃。同次操作新建再取消的请求不发出；四个等待名额占满时，可先取消一个再发一个。重复取消不增加额外名额。
- App 自己在取消函数中恢复按钮、等待状态和提示。已执行的回调不撤回；导航仍保留请求，关闭 App 和账号变化仍取消全部。
- 这里只能中止本机等待和传输，不能保证远端操作、AI 生成或计费停止。

## 改动文件（11 个）

| 文件 | 改动 |
|---|---|
| `shared/src/main/java/com/november/mcphone/core/script/http/HttpRequests.java` | 分配不复用的编号；事务中暂存取消和待发请求；计算取消后的可用名额；保留原构造器与 void add 签名 |
| `shared/src/main/java/com/november/mcphone/core/script/http/HttpSession.java` | 按会话和编号定位请求；先移除待回调记录再取消传输，避免排队响应及 poll 快照重新交付 |
| `shared/src/main/java/com/november/mcphone/core/script/engine/ClientScript.java` | request 返回整数；新增有严格类型校验的 ctx.http.cancel |
| `shared/src/main/java/com/november/mcphone/core/script/client/ScriptPage.java` | 点击、初始化和回调的批次绑定当前 HTTP 会话 |
| `docs/ClientHttpCancelTest.java` | 77 项真实 SFC 事务、生命周期、回调竞争、满容量重发、编号边界、实际示例和真实本地 HTTP 检查 |
| `docs/ClientStorageTest.java` | 宿主能力清单加入 cancel，方法名排序后比较 |
| `docs/samples/http-json.vue` | 演示版本 1.1.0；保存请求编号、取消按钮与等待状态处理 |
| `docs/samples/http-demo.py` | 增加延迟 4 秒的 /delay，保留原有超时及错误端点 |
| `docs/script-app-author-guide.md` | 新旧版本返回值、取消规则、失败处理与远端效果边界 |
| `docs/release-notes/next.md` | 双语下一版本说明 |
| `docs/script-http-cancel-verification.md` | 本报告 |

## 验证

四端执行完整 `build --continue`，包括 HTTP、存储、导航、初始化、函数参数、输入、布局、协议、平台架构和专用服务器隔离检查。最终结果及原始日志见交付目录 `script-http-cancel-preview/REPORT.md`。

Windows 上既有 `EconomyDataTest.unreadableIsNotAbsent` 第 936 行使用 POSIX 权限接口，会抛 `UnsupportedOperationException`；不能把本机完整 build 表述为全绿，不修改或跳过它。另执行打包和取消 / HTTP / 存储 / 导航针对性检查，单独记录退出码与通过项数。

新增检查包含实际本地 HTTP：远端处理仍被测试闩锁暂停时，取消已经释放客户端传输名额，且不会触发旧回调。这验证了本地资源回收，也明确不把本地取消解释为远端终止。测试不使用任何外部账号或付费接口。

## 游戏验收

1. 替换对应目标的验收 JAR。运行交付目录中的本地演示启动文件，再把 `http-json.vue` 放到游戏 `mcphone/apps/`，在 AppStore 安装或更新“网络问答演示”。
2. 接口地址改为 `http://127.0.0.1:18765/delay`，输入“旧问题”，发送后立即取消。按钮与输入应恢复可用，并显示取消提示。
3. 输入“新问题”，再次发送。等待后应只显示新问题的回复；旧回复和旧错误提示都不应覆盖页面。
4. 再发送一次并取消，等待超过 4 秒，取消提示应保留；之后仍能重新发送。
5. 地址换回 `/answer` 验证正常回复；再检查 `/error` 的错误和 `/slow` 的超时后能恢复操作。等待中关闭 App，再打开不应收到旧回复。

用户已确认游戏内检查无误。自动化检查和游戏验收记录分别保留，后续提交 PR 以对应提交的 Linux CI 为准。
