# 脚本 App 作者指南

客户端目录与旧路径对照见 [游戏运行目录](runtime-file-layout.md)。本版本直接使用新目录，不自动迁移旧文件。

> 面向写 `.vue` / `.js` 的 App 作者。施工方案不在本仓库里，文中的 §编号指它。
> 这份指南只写**已经能用的部分**，随各步交付逐步补。

## 单行文本输入框

`text-input` 绑定当前页面 `state` 中已声明的字符串，输入即时更新状态与文字插值：

```html
<template>
  <column>
    <text-input id="name" bind="name" placeholder="输入名字" max-length="32" />
    <text>你好，{{ name }}</text>
  </column>
</template>
<script>
  state = { name: '' }
</script>
```

- `bind` 必填，只能绑定页面顶层字符串；不接受 `:bind` 或循环变量。
- `placeholder` 可选；`enabled` 默认 `true`，可用 `:enabled="editable"` 控制。
- `max-length` 默认 64，取值 1–64，保持单行输入框的 UTF-16 长度限制；正文的长文本上限不扩大输入框。
  初值、按钮赋值或上限变化超过这个限制时，输入框会截短并写回状态；换行、控制字符不进入单行内容。
- 点击获得焦点，支持原版的光标、选区、退格、复制和粘贴；Tab / Shift-Tab 在当前可见且启用的输入框之间循环。
  中文使用游戏收到的已提交文字，输入法候选窗的表现仍由游戏与输入法环境决定。
- 失焦、隐藏、禁用、完全滚出可见区、切页和关闭页面都会释放输入焦点。
  页面内数据沿用已有生命周期：返回本 App 的上一页可保留，关闭 App 后不持久化。
- 默认占满父容器可用宽度，适用普通 MSS 的尺寸、间距、颜色和边框；列表中的输入框应提供稳定的 `:key`，独立输入框建议提供 `id`。

输入框没有独立的提交事件；需要清空/设置文字时，可用已有按钮赋值，或下文的客户端函数。
完整可安装示例见 `docs/samples/text-input.vue`。点击应用商店右上角的文件夹按钮，可打开当前游戏目录下的 `mcphone/apps/`（不存在时自动创建）。把文件放入后，重新进入商店，从本地脚本来源安装。

## 多行文本输入框（开发分支）

`textarea` 绑定页面顶层字符串，可编辑多段正文。此组件是 `1.12.0` 发布后的新增能力，尚未随正式版发布。

```html
<template>
  <textarea id="note" bind="note" placeholder="写点什么…" max-length="1024" />
</template>
<script>
  state = { note: '' }
</script>
```

- `bind`、`placeholder`、`enabled` 的用法与单行输入一致；支持 `:max-length` 和 `:enabled`。
- SFC 页面的 `max-length` 默认 4096，允许 1–4096 个 UTF-16 单元；表情符号可能占两个单元。粘贴、删除、光标及选区边界不会拆开代理对。初值、函数赋值或上限变化会按控件上限规范化并写回状态。
- Enter / 小键盘 Enter 换行；方向键移动光标，Shift 扩展选区，Home / End 到当前显示行首尾，Ctrl / Command 配合 Home / End 到全文首尾。Page Up / Down 按可见行数移动；Ctrl 配合左右方向键或删除键按空白分隔的单词移动或删除。
- 支持 Ctrl / Command + A、C、X、V 全选、复制、剪切和粘贴，以及鼠标拖动选择。CRLF 和单独的 CR 统一为 LF，制表符替换为一个空格，过滤其他控制字符和格式控制符 `§`。
- 默认占满宽度，内容区约四行；可用 MSS 设置 `height` 或 `grow`。文字按可用宽度自动折行，折行不改变保存内容。滚轮在控件内部滚动，键盘编辑自动让光标进入可见区；右侧细条表示滚动位置。
- Tab / Shift-Tab 在单行与多行输入间循环；隐藏、禁用、完全滚出父级裁剪区、切页、关闭或宿主取消输入时，释放焦点和拖动捕获。失焦保留正文，保存到磁盘仍使用 `ctx.store`。
- 输入写入前检查现有页面总文本预算（16384 单元）；超限时保留原正文和选区。每个控件的上限不会扩大整页配额。旧 IR/JSON 使用 `textarea` 时仍沿用 64 单元的原有状态和属性上限。

完整示例 [本地便签](samples/textarea.vue) 使用 1024 单元的编辑上限，保证编辑后的中文正文也能放进现有存储单值 4 KiB UTF-8 配额。点击保存后关闭 App，再次打开通过 `onInit()` 恢复；“清空编辑区”只改当前内容，保存后才覆盖旧记录。与单行输入一样，中文输入法候选窗由游戏和输入法环境决定。

## 客户端按钮函数

`<script>` 可以在字面量 `state` 初值之外声明普通函数，按钮用 `@click="process(draft)"` 调用：

```html
<template>
  <column>
    <text-input bind="draft" max-length="32" />
    <button @click="process(draft)">整理文字</button>
    <text>{{ result }}</text>
  </column>
</template>
<script>
state = { draft: '', result: '' }
function process(text) {
  state.result = text.trim()
}
</script>
```

- 按钮调用函数；另可声明下文的无参 `onInit()`，在页面首次创建时自动执行。装包、编译、绘制模板都不会执行作者函数。顶层只接受字面量 `state` 和普通函数声明。
- 函数可使用局部变量、分支、循环、受限的 JS 内建方法，也可调用本页其他函数并传参。读写状态使用 `state.draft` 等字段；模板仍用 `draft`。
- 模板插值和 `:属性` 仍走原有表达式系统，不支持调用函数；按钮可从 `@click` 调用客户端函数，宿主另支持 `onInit()` 和下文的 HTTP 回调，返回值均忽略。普通无参调用 `process()` 继续可用。
- 每个顶层函数可声明 0–4 个简单且不重复的形参，名称遵循状态键规则，不能占用宿主、内建名称或 `arguments`；不接受默认参数、剩余参数、解构或生成器。`onInit` 必须保持无参。
- `@click` 实参与形参数量必须相同；实参使用已有模板表达式，可传字符串、32 位整数、布尔值，以及循环索引或循环项的这些基本字段。不传整个数组、对象、`null`、事件对象或 Java 对象；动态类型不合规时整次点击失败，不隐式转换。字符串参数沿用当前页面长度限制。
- 实参在执行到该语句时求值，能读取同次点击中前面语句更新的页面状态。循环变量取自点击节点绑定的那一项。原有单条语句 256 字符、表达式节点上限与一次点击最多 4 条语句的限制继续生效。
- 状态只包含已声明的字段，保持初始类型和形状。整数为 32 位；SFC 页面字符串最多 4096 个 UTF-16 代码单元，所有字符串叶子合计最多 16384 个（含数组和对象中的字符串）；数组最多 32 项，对象最多 16 个字段，沿用现有嵌套限制。不接受函数、getter/setter、空位数组、循环对象、`null` 或 `undefined` 作为状态值。
- 每次调用使用最新状态的独立副本；成功后通过校验才提交。一次 `@click` 中其他语句失败，也会撤回这次函数的状态修改以及待发出的 `call(...)`。失败会显示提示，客户端日志包含文件与位置；页面可继续操作。
- 状态按原有页面生命周期保留。局部变量、额外全局变量和闭包不会跨调用保存；函数应把需要保留的数据显式写进 `state`。
- 每页最多 16 个顶层函数；含函数的脚本最多 65,536 个 UTF-16 代码单元。函数名遵循状态键名规则，不能占用内建操作、宿主名称或 JS 标准全局名称。
- 复用 Rhino 1.9.1 的现有沙箱、尺寸检查和执行预算。每次调用最多 200 万指令单位、50 ms 观察器墙钟预算；超限会中止并撤回状态修改。原生内建操作仍依赖沙箱的尺寸闸，墙钟不是系统级抢占期限。
- 函数可以操作页面 `state`，通过 `ctx.store` 读写本机字符串，通过下文的 `ctx.http.request` 发起客户端网络请求；没有 `phone`、`fetch`、定时器、模块加载或 Java/Minecraft 对象。已有 `@click="call('动作')"` 继续可用，函数内部没有 RPC 接口。

完整可安装示例见 `docs/samples/client-functions.vue`（“名字检查器”），安装方式与输入框示例相同。

列表可把索引传给同一个函数，无需先把选中项写进额外状态，也无需为每行单独声明函数：

```html
<button v-for="(task, index) in tasks" :key="task.id" @click="toggleTask(index)">{{ task.title }}</button>
```

```javascript
function toggleTask(index) {
  state.tasks[index].done = !state.tasks[index].done
}
```

完整可安装示例见 `docs/samples/function-args.vue`（“待办清单”1.0.0）：点击每行只切换该项，重置按钮用 `setAll(false)` 复用有参函数。这是客户端本页函数传参；服务端 `call('动作')` 的协议和参数能力保持原样。

## 客户端异步 HTTP / JSON 请求

按钮函数、`onInit()` 和 HTTP 回调可以请求外部 HTTP/HTTPS 接口。请求由玩家自己的客户端发出，不经过 Minecraft 服务端，也不需要脚本服务端握手。这与尚未开放的服务端 `net.fetch` 能力无关。

```js
state = { question: '你好', answer: '', loading: false, requestId: 0 }

function send() {
  state.loading = true
  state.requestId = ctx.http.request({
    url: 'http://127.0.0.1:18765/answer',
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ question: state.question }),
    timeoutMs: 30000
  }, 'received')
}

function received(status, body, error) {
  state.requestId = 0
  state.loading = false
  if (error) {
    state.answer = '请求失败：' + error
    return
  }
  try {
    var data = JSON.parse(body)
    state.answer = typeof data.answer === 'string' ? data.answer.slice(0, 4096) : '响应缺少 answer 字段'
  } catch (e) {
    state.answer = '响应格式不符合预期'
  }
}
```

`ctx.http.request(options, callbackName)` 在开发分支返回正的 32 位整数请求编号，可存入事先声明为整数的 state 字段；已发布的 1.12.0 返回 `undefined`。旧 App 忽略返回值的调用方式继续可用。第二个参数是本页声明的**三个简单形参**的函数名字符串，不能传函数对象、闭包或 `onInit`。回调在新的沙箱环境中执行，读取该页的最新 `state`；局部变量不会从发起请求时保留到回调。

开发分支支持取消单个请求：

```js
function cancelRequest() {
  ctx.http.cancel(state.requestId)
  state.requestId = 0
  state.loading = false
  state.answer = '已取消，可以重新发送'
}
```

- `ctx.http.cancel(id)` 返回 `undefined`，只接受非负的 32 位整数。`0`、已完成或未知的编号无操作；负数、小数、字符串等非法参数会终止整次操作。编号只用于当前打开的 App，不应保存到磁盘或当成持久身份；其他 App 的编号不能取消它自己的请求。
- 取消与 state、本地保存和待发请求一起提交。脚本、状态校验或保存失败时，取消也撤回，原请求继续。整次操作成功后，取消释放该 App 的等待名额并尝试中止本机传输；可在同次操作中先取消再发送新请求，即使原来四个名额全满。同一次操作中新建又取消的请求不会发出。
- 取消后不再调用该请求尚未执行的回调，也包括已经收到但仍在排队的响应。已经执行的回调不会撤回；等待状态、按钮和提示由 App 在取消函数中更新。切换页面仍保留请求，是否显式取消由 App 决定。
- 取消不能撤回远端已执行的操作，也不保证远端 AI 停止生成或停止计费；需要远端支持的停止协议由 App 另行调用。

| 选项 | 规则 |
|---|---|
| `url` | 必填，完整 HTTP/HTTPS URL，最多 2048 字符；不接受 URL 内的账号、密码或片段。支持本机接口。 |
| `method` | `GET` 或 `POST`，默认 `GET`。 |
| `headers` | 可选的普通对象，字符串键值，最多 16 项，合计最多 8 KiB；名称最多 64 字符，值最多 4096 个无控制字符的 Latin-1 字符。禁止重复名称及 Host、Content-Length 等传输层请求头。 |
| `body` | 可选的字符串，最多 64 KiB UTF-8 字节；JSON 用 `JSON.stringify` 生成。GET 不接受非空正文。POST 默认补上 JSON Content-Type，占用请求头配额。 |
| `timeoutMs` | 1000–120000 的整数，默认 30000 毫秒；包含等待响应正文的时间。 |

请求选项和请求头只读取自身字段，不接受 getter/setter、Symbol 或未知选项，不对对象做隐式字符串转换。输入框仍最多 64 个 UTF-16 单元；较长的固定 URL 或凭据可以在脚本中组合或由现有本地字符串存储读取，网络接口本身不扩大输入框限制。

回调形式为 `received(status, body, error)`：

| 结果 | `status` | `body` | `error` |
|---|---:|---|---|
| HTTP 2xx | 实际状态码 | 完整 UTF-8 正文 | 空串 |
| HTTP 非 2xx（含重定向） | 实际状态码 | 完整 UTF-8 正文 | `HTTP_STATUS` |
| 超时 / 连接失败 / 正文超限 / 非法 UTF-8 / 全局请求数已满 | `0` | 空串 | `TIMEOUT` / `NETWORK_ERROR` / `RESPONSE_TOO_LARGE` / `INVALID_UTF8` / `BUSY` |

- 每个打开的 App 最多等待 4 个请求；额外请求在脚本操作提交前失败。全客户端最多有 16 个传输中的请求。完成回调按就绪顺序处理，不保证与发送顺序相同。
- 后台只负责网络收发；回调在客户端界面线程处理，受原有执行预算约束。等待网络不占用脚本的执行预算，也不阻塞绘制。没有 Promise、`async/await`、流式输出或回调闭包。
- 整次点击、初始化或回调的脚本与状态校验、本地保存均成功后才发出该次暂存的请求。失败时这些请求不会发出；已经发出的 HTTP 请求及远端效果不能由后来的回调失败撤回。
- 响应正文最多 64 KiB **字节**，超限中止读取；只按严格 UTF-8 解码，不自动跟随重定向，不自动携带浏览器 Cookie 或 Minecraft 登录凭据。需要认证的接口由 App 显式提供请求头；宿主错误不包含 URL、请求头或响应正文。
- 回调的 `body` 可以暂时超过页面字符串的 4096 单元限制，方便解析 JSON；写回 `state` 时仍执行原有单字符串、整页长度和类型检查。回调失败时撤回本次状态、存储及后续请求，并显示提示；原有等待状态也会保留，作者应在回调中处理预期的解析失败。
- 本 App 内导航会保留原页状态，响应仍交给发起请求的那一页，不会写到当前的另一页。关闭 App、重新打开或切换登录账号会取消旧请求并丢弃旧结果，不再调用回调。

可安装示例为 `docs/samples/http-json.vue`（开发分支示例 1.1.0，需要支持取消的新构建）。先运行 `python docs/samples/http-demo.py` 启动只监听 `127.0.0.1:18765` 的本地演示接口，再把 `.vue` 放入 `mcphone/apps/` 安装。它演示“发送 → 取消 → 再次发送 → 只显示新回复”，不调用付费服务，也不生成 AI 回答。把示例 URL 的 `/answer` 换成 `/delay` 可等待 4 秒测试取消；`/error`、`/slow`、`/large` 可检查 HTTP 错误、正文超时和响应超限。

实现使用 JDK 自带的 [HttpClient](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpClient.html) 和受限的响应订阅器，无额外网络依赖。

## 页面导航传参（开发分支）

多页 ZIP App 可用 `nav('detail', { id: note.id, title: note.title })` 打开 `pages/detail.vue`。第二个参数可省略，原有 `nav('detail')` 与 `nav('')`（入口页）继续可用；省略参数等同传入 `{}`。此能力是 `1.12.0` 发布后的开发分支新增内容，尚未随正式版发布。

目标页通过客户端函数里的只读 `ctx.params` 读取参数，例如：

```javascript
state = { id: 0, title: '' }
function onInit() {
  state.id = typeof ctx.params.id === 'number' ? ctx.params.id : 0
  state.title = typeof ctx.params.title === 'string' ? ctx.params.title : '详情'
}
```

- 参数对象最多 4 个字段，键名与 state 键名规则一致。值只接受字符串、32 位整数和布尔值；每个字符串最多 256 个 UTF-16 单元，拒绝不完整的 Unicode 字符。数组、嵌套对象、`null` 和其他类型不接受；超限明确失败，不截断。
- 参数表达式在执行到 `nav` 时求值，可读取同次点击之前语句更新的 state，也可使用点击行的循环变量。对象字面量或对象表达式均可，仍受原有单条语句长度、表达式复杂度与整组语句数量限制。
- `ctx.params` 与页面 `state` 分开，不自动添加或覆盖 state 字段。它只在 JavaScript 客户端函数中使用；模板显示所需值可在 `onInit()` 中复制到已声明的 state。业务字段是否必填、编号范围等由 App 自己检查。
- `ctx.params` 的键和值均只读，不能替换、添加、删除或重新定义；修改尝试可能被忽略或抛错，但不会更改参数。页面重排、按钮函数与 HTTP 回调都使用该页实例自己的参数。
- 同一页名、同一组参数再次进入时复用当前缓存状态，不重复初始化；字段书写顺序不影响参数相等性。当前页跳到相同页名和参数时直接保持原页，不增加返回层级。
- 页名相同但参数改变时创建新状态并执行 `onInit()`，避免打开第二条笔记时沿用第一条内容。初始化失败仍沿用既有默认值与失败提示规则，该参数版本不在绘制或返回时自动重试。
- `back()` 和手机返回键恢复返回栈中原页面的参数、state 与滚动位置，不重新初始化；最多保留原有 8 层返回。切页释放输入焦点；本步不跨页面保存输入光标或选区。
- 每个页名只缓存最近使用的一组参数，返回栈另外保留其原页面。不会无限缓存每个编号的草稿；需要跨关闭或任意切换保留的内容应显式保存到 `ctx.store`。关闭 App 会释放所有页面和返回记录。
- 导航参数校验失败会撤回整次点击的 state、本地写入、待发 HTTP 和导航。目标页不存在时沿用既有行为，保留当前页面并记日志。已发出的 HTTP 结果继续只写回发起它的原页实例，不覆盖后来打开的其他参数版本。

完整源码示例见 [便签导航演示](samples/navigation-params/README.md)。将示例目录内容打成 ZIP（根目录直接包含 `manifest.json` 和 `app.vue`），放进游戏的 `mcphone/apps/` 安装；验收交付目录也提供已打包 ZIP。

## 页面初始化 onInit

在 `<script>` 中声明普通无参 `function onInit()`，宿主会在该页面首次创建 `state` 后、第一次绘制前调用它：

```javascript
state = { draft: '' }
function onInit() {
  state.draft = ctx.store.getString('draft', '').slice(0, 64)
}
```

- 每个页面状态实例只自动执行一次。进入新子页执行该页的钩子；相同参数复用缓存页、返回原页时保留内容，不再初始化。导航参数改变会创建新实例并初始化。关闭 App 会释放这些页面，重新打开后再次执行。
- 没有声明 `onInit` 的页面继续使用字面量默认状态，不读取存储、不执行其他函数。初始化只发生在页面打开阶段，重排、缩放、绘制和手机/HUD 刷新不重复调用。
- `onInit` 本身保持无参，使用与按钮函数相同的 `state` 校验、沙箱和执行预算；可调用本页其他函数并传参，通过现有 `ctx.store` 读取或暂存字符串。它计入每页 16 个顶层函数上限。
- 初始化的状态与存储整体成功才提交。脚本、状态校验或存储失败时保留页面默认值与原文件，显示初始化失败提示并记录日志，页面仍可使用。失败也记为已尝试，不在返回或绘制时自动重试；关闭 App 后重开可再次尝试。
- `onInit` 仍是普通函数，作者显式从按钮或其他函数调用时会再次执行；“一次”指宿主自动调用。宿主不向它传递事件参数。
- 输入框保持 64 个 UTF-16 单元上限。上面的草稿示例按输入框上限截取；只读正文可使用下面的长文本能力。

## 长文本阅读

SFC `.vue` 页面的字符串状态与 `<text>` 正文支持最多 **4096 个 UTF-16 单元**。可用既有换行和滚动组件显示完整正文：

```html
<scroll class="article">
  <text :text="body" />
</scroll>
```

给 `.article` 设置固定高度（例如 `height: 100`），正文保留默认自动换行与不限行数，即可滚动阅读。`<text>{{ body }}</text>`、静态正文和 `text="…"` 使用同一上限。中文通常占一个 UTF-16 单元，常见 Emoji 占两个。

- 初值、按钮函数、`onInit()` 与按钮赋值使用同一页面规则；超过单字符串或整页 state 的 16384 单元总预算时，整次操作失败，保留原状态和本地文件。
- 实例化后的整页 `<text>` 正文也有 16384 单元预算，重复列表展开也计入。超出部分不显示并产生警告，避免同一篇长文重复展开耗尽布局资源；截断不拆开代理对。
- 滚动时只绘制当前裁剪区域附近的文字行，换行结果继续复用现有布局缓存。
- 单行输入框仍为 1–64 个单元；长正文使用 `<text>` 阅读，开发分支新增的 `<textarea>` 可用于多行编辑。
- 按钮文字、开关标签等原有控件仍用 512 单元限制；旧 IR/JSON 的 64 单元状态和 512 单元文字规则保持原样。Java 侧创建 SFC 页面状态使用 `UiState.page(...)`，原 `UiState.of(...)` 保持旧策略。
- 本地存储单值仍为 4 KiB **UTF-8 字节**，与页面长度单位不同。较长的中文正文可能先达到存储配额；超额会明确失败，不截断保存。

完整示例见 `docs/samples/long-text.vue`（“长文阅读器”1.0.0）：可滚动到末尾，保存正文，切换短文后读取，关闭 App 重开后自动恢复。此轮提供阅读与已有存储衔接，不包含网络请求或多行编辑。

## 客户端本地字符串存储

按钮函数和 `onInit()` 可以使用以下两个方法，数据只保存到当前客户端：

```javascript
function saveName() {
  ctx.store.setString('name', state.draft)
  state.result = '名字已保存'
}

function loadName() {
  state.draft = ctx.store.getString('name', '')
}
```

| 方法 | 含义 |
|---|---|
| `ctx.store.getString(key, fallback)` | 读取字符串；没有该键时返回默认值。`fallback` 可省略，默认为空串。读取不创建文件或键。 |
| `ctx.store.setString(key, value)` | 暂存一个字符串；本次按钮操作或初始化整体成功后自动落盘，返回值为 `undefined`。 |

- 键名为 1–64 个 ASCII 字母、数字或 `_ . : -`；值和默认值必须是字符串，不做隐式类型转换。
- 沿用 local 配额：每 App、每账号最多 64 个键，单值最多 4 KiB UTF-8，总量最多 16 KiB（按键名及 JSON 编码值计量）。覆盖已有键按替换后的占用量判断。超限明确失败，不截断。
- SFC 页面字符串支持 4096 个 UTF-16 单元；存储单值上限仍为 4 KiB UTF-8 字节。较长存储值可先读入局部变量处理；写入页面后仍需满足单字符串与整页预算，输入框仍为 64 单元。
- 同一次 `@click` 中，多个函数共享暂存数据，后续读取能看见前面的写入。所有函数、原有按钮语句和状态校验通过后，才保存文件并提交页面状态；保存失败或任一步出错时，页面状态和待发 RPC 都撤回，原文件保留。一次 `onInit()` 及它调用的函数也共享独立暂存，初始化成功后提交。
- 每次函数调用使用独立的内建对象环境，按钮操作或初始化结束后存储接口立即失效并释放快照；不能缓存旧接口供下次调用或其他账号使用。
- 存储 API 的参数、配额、读取错误会终止本次按钮操作或初始化，不能用脚本 `try/catch` 吞掉错误后留下部分写入。磁盘错误不会伪装成“保存成功”；失败提示和客户端日志会说明本次操作未完成。
- 保存采用同目录临时文件、同步文件内容、原子替换。损坏、重复键、非字符串、非法 Unicode 或超限文件会明确报错并保持原样；不会当成空数据覆盖。不支持原子替换的文件系统会报告保存失败。
- 按游戏客户端的登录账号 UUID 与 App id 隔离，不使用服务器发来的玩家 UUID，也不需要脚本服务端握手。同一账号的同一 App 在不同服务器上共享这份本机数据；不同账号或 App 不共享。
- 数据以明文 JSON 保存在 `mcphone/data/appdata/client/<账号 UUID>/<App id 的 SHA-256>.json`。App 不能选择其他账号、App 或文件路径，数据不会由存储接口上传。
- App 打开时绑定账号；账号改变后，旧页面的存储操作会失败，重新打开 App 后使用新账号的数据。文件独立于页面状态，关闭 App、重启游戏或更新 App 后仍保留。

“名字检查器”示例保留保存和读取按钮，并在打开时自动恢复名字（示例版本 1.2.0）。“清空输入”只重置当前页面，之后仍可读取已保存的名字；关闭重开也会恢复。存储目前只提供字符串读取与保存，没有删除接口。

## 存储：三档，写的时候要显式选

上面的客户端 `ctx.store` 只连接 local 档。下文的 shared / sealed 是已有服务端及保险箱机制，不表示客户端函数可以直接访问这些档位。旧的按服务器隔离的 local 目录保持原样，本次不迁移它。

三类数据的敏感度差得很远，用同一种存放会两头不讨好：
"上次选的标签页"加密了服主没法运维，"我的 API token"不加密就等于交出账号。

| 档 | 存在哪 | 谁看得到 | 用来放 |
|---|---|---|---|
| `local` | 玩家自己机器上的文件 | 只有玩家自己 | **默认档**。偏好、缓存、token |
| `shared` | 服务端存档，明文 | 服主看得到也改得了 | 积分、进度 —— 服主本来就该能处理作弊 |
| `sealed` | 服务端存档，密文 | 服主只看得到密文 | 真的要跨设备的秘密 |

### ⚠ 两条一定要记住的

**一、token 一律用 `local`，除非你真的需要跨设备。**

`local` 在玩家自己的电脑上，永远不上传、任何网络包里都不出现。
`sealed` 虽然服主解不开，但它仍然经手服主的机器 —— 写入时间、写入频率、
哪个 App、哪个玩家，这几样**没法缓解**。能不经手就不经手。

**二、`sealed` 的 key 名不要放敏感信息。**

服主看不到值，但**看得到 key 的名字**。所以：

服务端脚本目前只暴露 `ctx.sealed.get(key)`；写入端尚无真实消费方，因此不会挂一个永远失败的
`ctx.sealed.put` 空壳。将来写入链路接通后，key 仍必须使用不泄密的代号（例如 `k1`，不要用
`anthropic_api_key`）。

值的长度已经靠 64 字节对齐填充缓解了；key 名没有任何东西替你挡。

### `sealed` 还有一条：服务端解不开，所以它不能替你判断

```javascript
if (ctx.sealed.get('token') === '...') { }   // ❌ 服务端拿到的是密文
```

当前服务端脚本只提供 `ctx.sealed.get(key)`；解密只发生在客户端。

### 忘了保险箱口令 = 数据永久丢失

没有找回、没有提示问题、服主也重置不了。这不是偷懒，这正是"服主读不到"的来源。
请在你的 App 里也把这句话说给玩家听一次。

## 配额

超限一律**显式报错**，不会静默截断、也不会静默丢 key —— 你会当场知道，而不是等用户投诉。

| | local | shared |
|---|---|---|
| 每 App | 16 KiB | 8 KiB（每玩家） |
| 单值 | 4 KiB | 2 KiB |
| key 数 | 64 | 64 |
| 写入频率 | —— | 10 次/秒 |

## 后端脚本的语法

### 原生容器操作的容量边界

字符串和数组的原生方法也受 64 KiB 字符串、4096 元素数组的执行前检查约束。
小型 `array.flat(depth)` 可以使用；如果展开后可能超过 4096 项、存在 getter、循环引用或超过
32 层嵌套，会在分配前拒绝。`array.flatMap(...)` 的输出取决于脚本回调，无法可靠预检，
当前在受限沙箱中禁用；请改写成有明确上限的循环并逐项 `push`。

`Array.from(...)` 只接受最多 4096 项的原生数组、字符串，或带数值型普通 `length`
数据属性的普通 array-like 对象。自定义 iterable、生成器、`length`/元素 getter、映射回调，
以及改写过迭代器或原型的数组/字符串会在执行前拒绝；同样请改写成有明确上限的循环。

Rhino 1.9.1，**没有** `class` / `for...of` / `export` / `import` / `async` / `await`。
用 `var` 与 `function`，箭头函数可以用。`BigInt` 可以用，而且是大数的正路
（`ctx.num.*` 已经砍掉了）。

前后端唯一的通道是 `phone.call(actionId, params, callback)`，回调风格不是 Promise。

## 客户端：现在能写的那一截（S17 Stage 2）

`phone.call` 的完整形态尚未接入客户端。当前按钮函数仅能处理页面状态；带参数及回调的 RPC 还不能写。现有调用方式为：

```html
<button @click="call('claim_daily')">领取</button>
```

- 参数是空的，结果由宿主在屏幕底部弹一条提示（成功 / 错误码的本地化文案）。
- 同一个 App 同时最多 4 个未完成调用，超出立刻提示"正在处理"，不排队。
- `epoch` / `deployRev` / 前端摘要由宿主回填，**作者改不了** —— 伪造这些字段是客户端的对抗测试，
  不是 App 的能力。

### 宿主注入的只读上下文 `backend`（§13.8）

模板里可以直接读，**不能赋值、不能写进 state**：

```html
<column v-if="!backend.available">
  <text>这个 App 需要「{{ backend.serverName }}」才能使用</text>
</column>
<column v-else>
  <button v-if="backend.actions.length > 0" @click="call('claim_daily')">领取</button>
</column>
```

| 名字 | 类型 | 含义 |
|---|---|---|
| `backend.available` | bool | 本服有没有这个 App 的已批准部署 |
| `backend.serverName` | string | 部署它的服务器显示名 |
| `backend.actions` | string[] | 本服已批准、且**你被授权**的动作 id |

⚠ `backend.actions` 是 **UX，不是边界**：你可以无视它照样发请求，服务端每次都会重新判。
拿它灰按钮用，别拿它当权限结论。

不写 `backend.available` 分支时，宿主会在商店详情页顶部插一条横幅兜底 —— 但那只覆盖详情页，
App 里最好还是自己把降级画出来。

## 能力声明（S18 起）

manifest 里的 `capabilities` 是一个字符串数组，元素必须是**服务端能力目录里的 id**：

```json
"capabilities": ["loot.roll", "item.give"]
```

> 带参数的形态（`{ "id": "loot.roll", "tables": ["myserver:daily_gift"] }`）是后续卡片的落点；
> **S18 只认字符串数组**，多写的对象会被拒。

- **档位由服务端查表**：`plain` 免审批、`granted` 要 OP 逐条批准、`restricted` 首版全部不开放。
  **App 在 manifest 里自称什么档都不作数**，写 `plain` 不会让一个 `item.give` 免审批。
- **目录外的名字入队即拒**：`economy.pay` 这种自造 id 会让整个包进不了审批队列。
  当前目录用 `/mcphone script capabilities` 列出来（首版开放 23 个；其中标 `[可关]` 的
  是服主 `disabled` 真能关掉的，标 `[本步无调用点]` 的还没有执行路径、关了只影响目录展示）。
- 服主可以**全服关掉任意一项**（包括 `plain`）：关掉的调用会返回 `UNAVAILABLE`，
  与"你没有被授权"是两回事。App 要按 §13.7 的风格优雅降级。
- **granted 必须被 OP 显式批准**：`/mcphone script approve <摘要>` 的能力轴省略时只自动批
  `plain`；声明里有 `item.give`/`loot.roll`/`attr.grant`/`effect.give` 时，服主要显式写
  `-`（都不批）、`all`（全批）或逐个列。**不存在"顺手把造物权限批了"这条路。**

### 现在能用的能力节点（S18 本批）

| 能力 | 写法 | 落地失败时 |
|---|---|---|
| `item.give` | `ctx.give('minecraft:diamond', 3)` | 背包满 → `INVENTORY_FULL`（先把背包腾出来，<b>不会掉地上</b>）；物品不在 `#mcphone:giftable` 里 → `INVALID_ARGUMENT`（服主的数据包说了算，改清单不用重新审批） |
| `loot.roll` | `ctx.loot.roll('myserver:daily_gift')` | 表不存在 → `INVALID_ARGUMENT`；只掷服主数据包里的表 |
| `attr.grant` | `ctx.attr.grant('minecraft:generic.movement_speed', 0.1)` | 属性认不得 → `INVALID_ARGUMENT`；修饰符是瞬时的，重登失效 |
| 同上（撤销） | `ctx.attr.revoke('minecraft:generic.movement_speed')` | 只撤这个 App 自己那条，别人的不碰 |
| `effect.give` | `ctx.effect.give('minecraft:speed', 30, 1)` | 效果认不得 → `INVALID_ARGUMENT`（`mcphone.script.effect.unavailable`）；最长 3600 秒；有时限、到期自然消失 |
| （plain）谓词 | `ctx.predicate.test('myserver:is_vip')` | 认不得的谓词 → `UNAVAILABLE`（是服主还没写这个数据包文件）；求值出错按 `false` 算；服主可用 `disabled: ["predicate.test"]` 关掉 |
| （plain）计分板 | `ctx.score.get/set/add('days')` | objective 名自动带 App 前缀（`example_app_` → 服主看到 `example_app_days`）；主线程忙/名字被只读 objective 占了 → `UNAVAILABLE` |

这些调用只登记"意图"：真正的落地在服务器主线程、落地前会重查授权与能力。
**回调里的 `OK` 才代表真的生效**；`UNKNOWN` 表示"可能已经生效"，**绝不要自动重试**。

谓词是**服主数据包里的** `predicate/*.json`（原版格式），不要在 App 里自造条件语言：
位置、维度、时间、天气、手持物品这些服主本来就能写，改谓词**不改 App 的 digest**。

## 装配期静态预检（S18 起）
装配后端时服务端**只编译、只解析模块，不执行任何一行代码**（零副作用）：

- `require(...)` 的参数**必须是字符串字面量**、以 `./` 或 `../` 开头：
  `require('./server/util.js')` 可以，`require('./server/' + n + '.js')` 会让预检不过。
- 模块缺失、语法错、依赖成环 → 整个 App 不装配（所有请求 `NOT_DEPLOYED`），修好重开服。
- **顶层代码不再在装配期运行**：它在第一次请求时执行一次（定义 `actions` 表）。
  顶层可以写 `var`/`function`，但别把"必须尽早发生"的事放在顶层 —— 没有请求就不会发生。
- 顶层直接抛错的包装配期能过，第一次请求时才报 `INTERNAL`。
