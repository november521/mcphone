# 点击打开网页：交付与验证

日期：2026-11-01。分支：`codex/script-open-url`。基线：已验收、尚待合并的 #89 `da3c94ab`，保留该轮延迟回调能力。模组版本仍为 1.12.0。

## 行为

新增内建点击动作 `openUrl(url)`，在整次点击及本地保存成功后调用系统默认浏览器。地址只接受 HTTP/HTTPS，最多 2048 字符，允许查询和片段，禁止账号密码及其他协议；每次点击最多一个网页。校验或事务失败不打开链接，也不发布同次暂存的 HTTP；浏览器打开不清空 App 状态。

动作只存在于点击语法，不安装到 JS 全局或 ctx，初始化、恢复、HTTP、输入和计时器回调没有自动打开网页的接口。旧 App 已声明的同名本页函数保持原语义。Outcome 原有六参数、七参数构造器和 ScriptPage 原有构造器保留。

MC 百科示例仍只接收搜索页，在各条结果标题下增加“查看原文”按钮；目标为该行自己的地址，原文由系统浏览器加载。搜索间隔、64 KiB 接收上限、脚本和页面预算均保留。

## 修改文件（11 个）

| 文件 | 用途 |
|---|---|
| shared/src/main/java/com/november/mcphone/core/script/sfc/WebLinks.java | 无客户端依赖的网页地址边界校验 |
| shared/src/main/java/com/november/mcphone/core/script/sfc/Statements.java | 点击语法、单次限制、事务结果和旧函数兼容 |
| shared/src/main/java/com/november/mcphone/core/script/client/ScriptPage.java | 成功点击后的系统浏览器分发；测试隔离 OS 边界 |
| shared/src/main/resources/assets/mcphone/lang/zh_cn.json | 打开异常提示 |
| shared/src/main/resources/assets/mcphone/lang/en_us.json | 英文异常提示 |
| docs/ClientOpenUrlTest.java | 编译、事务、真实鼠标分发、兼容和样例检查 |
| docs/samples/mcmod-search.vue | 搜索及按条目打开原文的独立 App |
| docs/samples/mcmod-search-README.md | 安装和游戏验收 |
| docs/script-app-author-guide.md | 作者接口与时序说明 |
| docs/release-notes/next.md | 双语更新说明 |
| docs/script-open-url-verification.md | 本报告 |

## 验证

NeoForge 1.21.1 本地：新功能 56 项、已有计时器 131 项、客户端函数 119 项、HTTP 165 项、导航 82 项及 SFC 编译器 534 项检查通过。覆盖链接类型/长度/协议、每次一个、同次赋值、后续失败、状态/存储/HTTP 回滚、磁盘冲突、旧同名函数和旧构造器、自动回调隔离、真实鼠标点击、禁用按钮、列表行链接和手机/平板布局。

自动测试捕获 OS 打开动作，不实际启动浏览器，也不访问 MC 百科；模拟网页仅用于确定性验证提取与逐行绑定。此前对三个真实搜索词的可行性验证属于前一轮记录。本轮四端 CI、产物来源与校验以工作区交付报告为准，不用本地 Windows 完整 build 代替 Linux CI。

游戏验收：替换本轮目标 JAR，安装/更新 mcmod-search.vue，搜索后点击任意“查看原文”；默认浏览器应打开该行地址，返回游戏后输入和结果保留，第二条应打开不同的对应地址。两次查询至少间隔 10 秒，手机和平板分别检查按钮和滚动。

系统是否把浏览器切到前台由操作系统决定；平台接口接受请求后的浏览器启动结果不由本体确认。尚未发布，等待游戏验收。
