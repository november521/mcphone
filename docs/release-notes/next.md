# 下一版本

## 简体中文

### 新增

- 脚本 App 增加 `ctx.store.remove(key)`，在现有事务中删除当前账号、当前 App 的单个本地键，并释放存储额度；失败时保留原数据。本地便签演示增加“删除已保存便签”。
- 脚本 App 的 `ctx.http.request` 返回请求编号，可通过 `ctx.http.cancel(id)` 取消单个请求，丢弃尚未执行的回调并释放本机等待名额。取消与页面状态、本地保存一起提交，失败时原请求继续；网络问答演示增加取消按钮。
- 脚本 App 支持页面导航传参：`nav('detail', { id: 1 })` 可将少量字符串、整数、布尔值带给目标页，通过只读 `ctx.params` 读取；参数变化重新初始化，返回恢复原页状态与滚动位置。提供多页便签演示。
- 脚本 App 增加 `textarea` 多行文本输入组件，支持换行、自动折行、键盘与鼠标选区、复制粘贴和内部滚动；与单行输入共用焦点管理，遵守页面文本预算。提供可保存并在重开后恢复的本地便签示例。

## English

### Added

- Script apps can remove one local key with `ctx.store.remove(key)`, within the current account/App transaction, freeing its storage quota. Failed operations preserve the original data. Added a delete-saved-note action to the notes example.
- Script apps receive a request ID from `ctx.http.request` and can cancel one request with `ctx.http.cancel(id)`, suppressing undelivered callbacks and freeing its local pending slot. Cancellation commits with page state and local storage; a failed transaction leaves the original request active. Added a cancel button to the HTTP example.
- Script apps can pass small primitive parameters with `nav('detail', { id: 1 })` and read them through readonly `ctx.params`. Changed parameters create a fresh page state; going back restores the original state and scroll positions. Added a multipage note example.
- Script apps can use a `textarea` component for multiline editing, wrapping, keyboard and mouse selection, clipboard operations and internal scrolling. It shares focus handling with single-line inputs and respects page text budgets. Added a local note example with explicit saving and restore on reopen.
