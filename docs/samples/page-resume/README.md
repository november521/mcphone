# 页面恢复刷新演示

本示例需要支持 `onResume()` 和 `ctx.store.remove` 的开发构建。保留原来的“便签导航演示”，本示例使用独立 App id `example:page_resume`。

安装交付目录的 `page-resume.zip`，或将 manifest.json、app.vue、pages/detail.vue、assets/icon.png 放在 ZIP 根目录；不要把本 README 或外层文件夹放进安装 ZIP。

1. 在列表输入“便签”并点击筛选，再把输入框改成一段尚未应用的文字。
2. 打开便签 1，编辑多段正文并保存，返回列表。
3. 摘要应立即更新，恢复刷新次数增加一次；已应用的筛选条件和输入框中未应用的草稿都保留。
4. 再次打开便签 1，删除保存内容并返回，摘要应显示“暂无正文”，筛选和草稿继续保留。
5. 返回详情页时仍保留编辑草稿；本示例只在列表声明 onResume，不自动覆盖详情草稿。
6. 关闭后重开 App，列表重新初始化并读取已保存内容，恢复刷新次数从 0 开始。

首次创建页面只调用 onInit；缓存页面再次显示或从返回栈恢复时才调用 onResume。列表只刷新 notes 和 refreshes，不覆盖 query / activeQuery。最多三条便签，每条最多 1024 个 UTF-16 单元，保存仍受单值 4 KiB UTF-8 配额约束。
