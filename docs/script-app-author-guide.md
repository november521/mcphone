# 脚本 App 作者指南

> 面向写 `.vue` / `.js` 的 App 作者。施工方案不在本仓库里，文中的 §编号指它。
> 这份指南只写**已经能用的部分**，随各步交付逐步补。

## 存储：三档，写的时候要显式选

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

`phone.call` 的完整形态要等 P1 的 `<script>`（函数、参数、回调都还写不了）。当前能用的只有：

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
  当前目录用 `/mcphone script capabilities` 列出来（首版开放 22 个）。
- 服主可以**全服关掉任意一项**（包括 `plain`）：关掉的调用会返回 `UNAVAILABLE`，
  与"你没有被授权"是两回事。App 要按 §13.7 的风格优雅降级。

### 现在能用的能力节点（S18 本批）

| 能力 | 写法 | 落地失败时 |
|---|---|---|
| `item.give` | `ctx.give('minecraft:diamond', 3)` | 背包满 → `INVENTORY_FULL`（先把背包腾出来，<b>不会掉地上</b>） |
| `loot.roll` | `ctx.loot.roll('myserver:daily_gift')` | 表不存在 → `INVALID_ARGUMENT`；只掷服主数据包里的表 |
| `attr.grant` | `ctx.attr.grant('minecraft:generic.movement_speed', 0.1)` | 属性认不得 → `INVALID_ARGUMENT`；修饰符是瞬时的，重登失效 |
| 同上（撤销） | `ctx.attr.revoke('minecraft:generic.movement_speed')` | 只撤这个 App 自己那条，别人的不碰 |

这些调用只登记"意图"：真正的落地在服务器主线程、落地前会重查授权与能力。
**回调里的 `OK` 才代表真的生效**；`UNKNOWN` 表示"可能已经生效"，**绝不要自动重试**。

## 装配期静态预检（S18 起）
装配后端时服务端**只编译、只解析模块，不执行任何一行代码**（零副作用）：

- `require(...)` 的参数**必须是字符串字面量**、以 `./` 或 `../` 开头：
  `require('./server/util.js')` 可以，`require('./server/' + n + '.js')` 会让预检不过。
- 模块缺失、语法错、依赖成环 → 整个 App 不装配（所有请求 `NOT_DEPLOYED`），修好重开服。
- **顶层代码不再在装配期运行**：它在第一次请求时执行一次（定义 `actions` 表）。
  顶层可以写 `var`/`function`，但别把"必须尽早发生"的事放在顶层 —— 没有请求就不会发生。
- 顶层直接抛错的包装配期能过，第一次请求时才报 `INTERNAL`。
