# 便签即时输入演示

需要支持 `@input` 的本轮开发构建。App id 为 `example:input_events`，与原有页面恢复演示分开保存数据。

将交付的 `input-events.zip` 放入游戏 `mcphone/apps/`，在 AppStore 安装“便签即时输入演示”。自己打包时，ZIP 根目录包含 manifest.json、app.vue、pages/detail.vue 和 assets/icon.png；README 不放入 ZIP。

1. 列表输入“2”，不用点击按钮，应立即只显示便签 2；删空输入，恢复三条。
2. 输入不存在的标题或正文，列表应为空；清空后打开便签 1。
3. 在多行编辑框输入、粘贴、删除、换行，下面的长度和预览应立即更新；预览把换行显示为空格，长度按页面配额的 UTF-16 单元计算，emoji 可能占两个单元。
4. 只移动光标或选择文字不触发内容变化。修改草稿后点击保存，再返回列表，摘要应更新。
5. 关闭重开应读取已保存便签。未保存的编辑不自动落盘。
6. 手机、平板均检查输入框、正文编辑区和保存按钮能看到且能点击。

单行筛选使用 `@input="applyFilter()"`，多行预览使用 `@input="updatePreview()"`。回调读取最新绑定 state；初始化读取正文后由示例显式调用 updatePreview，代码赋值本身不自动触发输入回调。
