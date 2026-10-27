# 下一版本

## 简体中文

### 新增

- 脚本 App 支持页面导航传参：`nav('detail', { id: 1 })` 可将少量字符串、整数、布尔值带给目标页，通过只读 `ctx.params` 读取；参数变化重新初始化，返回恢复原页状态与滚动位置。提供多页便签演示。
- 脚本 App 增加 `textarea` 多行文本输入组件，支持换行、自动折行、键盘与鼠标选区、复制粘贴和内部滚动；与单行输入共用焦点管理，遵守页面文本预算。提供可保存并在重开后恢复的本地便签示例。

## English

### Added

- Script apps can pass small primitive parameters with `nav('detail', { id: 1 })` and read them through readonly `ctx.params`. Changed parameters create a fresh page state; going back restores the original state and scroll positions. Added a multipage note example.
- Script apps can use a `textarea` component for multiline editing, wrapping, keyboard and mouse selection, clipboard operations and internal scrolling. It shares focus handling with single-line inputs and respects page text budgets. Added a local note example with explicit saving and restore on reopen.
