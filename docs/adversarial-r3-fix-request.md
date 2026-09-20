# 修复要求 · 脚本沙箱"放大预检"带来的兼容性收窄

> 来源：对抗组三次审查（针对 `c2210bd`）。依据是实测输出，不是推断。
> 先说清楚：**这次要求不是"安全性有问题"，而是"安全修得过头、且口径不统一"**。已核验的放大向量都堵住了，下面要改的是**误拒**与**一致性**。

## 0. 一句话

`SizeGate.checkNativeCall` 用**最坏情况估算**做前置拒绝，其中三处会拒掉**合法且常见**的写法；同时"容器构造入口"的覆盖口径不统一（`flat` 严、`Array.from` 完全不查）。这两件事一起修。

---

## 1. 必修一：三处误拒（有精确阈值，可直接写断言）

### 1.1 `String.prototype.split` —— 阈值取的是字符串长度，不是实际段数

**现象（实测）**：

```
'x'.repeat(4095).split('z').length  -> OK -> 1
'x'.repeat(4096).split('z').length  -> REJECTED「String.split: 预估输出 4097，上限 4096」
'x'.repeat(5000).split('z').length  -> REJECTED「预估输出 5001」
```

即**长度 ≥ 4096 的字符串在未给 `limit` 时一律被拒，与它实际切出几段无关**（上面三例的真实结果都是 1 段）。

**为什么这条最要紧**：它是**数据相关**的失败——App 用短文本测通了，玩家粘一段 4096 字符以上的笔记/聊天记录就报 `INVALID`。这类"平时好、偶发坏"的问题作者最难自查。

**位置**：`SizeGate.checkStringAmplification` 的 `split` 分支（现用 `Math.min(receiverLength + 1, limit) > MAX_ARRAY` 判定）。

**建议方向（择一）**：
- 按**实际分隔符出现次数**估算（一次 O(n) 扫描即可，和后面的真实 split 同阶）；或
- **降级为返回值闸** —— `split` 的结果本来就会被包装层末尾的 `SizeGate.check(out, …)` 覆盖，前置估算在这里收益很低（它不会像 `repeat`/`padStart` 那样在分配前就爆炸）；或
- 若坚持保守，至少**写进作者指南并给出可用写法**：`split(sep, limit)` 在 `limit ≤ 4095` 时是放行的（实测 `'x'.repeat(5000).split('z', 10)` → OK；`'x'.repeat(65536).split('', 100)` → OK）。

**验收**：`len=4095` 与 `len=4096`、`len=65536` 三例都不给 `limit` 时，只要真实结果 ≤ 4096 就必须放行；给 `limit` 的行为不变。

### 1.2 `JSON.stringify` —— 6× 系数把实际天花板压到约 10.9 KB

**现象（实测，严格对齐公式 `2 + 6×字符数 ≤ 65536`）**：

```
JSON.stringify('x'.repeat(10922)).length  -> OK -> 10924
JSON.stringify('x'.repeat(10923)).length  -> REJECTED「预估输出 65540」
JSON.stringify({s:'x'.repeat(11000)})     -> REJECTED「预估输出 65537」
```

**要澄清的边界**（这条决定了影响面多大）：**`ctx.ok(data)` 不受影响** —— `CtxBuilder.json` 直接调 `org.mozilla.javascript.NativeJSON.stringify`，**绕过了包装层**。所以收窄只命中"**脚本自己调 `JSON.stringify`**"的场合（例如拼串写进 `ctx.store`、算 key）。改动时**不要把 `CtxBuilder.json` 也接进这条估算**，否则会把 `ctx.ok` 的 64 KiB 契约一并收紧——那是文档承诺的下游契约。

**位置**：`SizeGate.jsonCost` 里 `if (value instanceof CharSequence cs) return 2L + 6L * cs.length();`。

**建议方向**：把系数降到有依据的值（实际输出约为 `len + 引号 + 少量转义`；若担心转义，只对 `"`、`\`、控制字符加权即可），或者干脆**只按实际输出长度在返回值闸判定**（`stringify` 的结果同样会被 `out` 闸覆盖）。

**验收**：`JSON.stringify('x'.repeat(n))` 在 `n ≤ 65530` 量级应放行（实际输出 ≈ n+2）；`{s:'x'.repeat(60000)}` 放行；真超 64 KiB 的仍拒。

### 1.3 `String.prototype.replace/replaceAll` 的**函数替换器**被一律禁止

**现象（实测）**：`'a-b'.replace(/-/g, m => '_')` → `REJECTED「函数替换器不可在受限沙箱中使用」`。

**影响**：这是 JS 里最常用的写法之一（格式化、转义、模板替换）。好消息是**有等价写法且都放行**：字符串替换器（含 `$1`）、`replaceAll(sep, str)`、`split().join()`。

**位置**：`SizeGate.checkStringAmplification` 的 `replace`/`replaceAll` 分支。

**建议方向（择一）**：
- 保留禁令（理由成立：回调的调用次数与返回长度在执行前无法估算，且结果是分配后才查），但**必须写进作者指南**（现在只写了 `flatMap`）；或
- 改为**可估算的保守上界**：限制匹配次数（例如按 `receiverLength / 最短匹配长度` 估上界）并据此判定，放行小规模替换。

**验收**：无论选哪条，都必须有一条断言钉住所选口径；若保留禁令，指南里必须有"改用字符串替换器 / split+join"的示例。

---

## 2. 必修二：统一"容器构造入口"的口径（含一个仍能打死服务端的洞）

同一个"造数组"的语义，现在有三套态度，作者无法从 SPI 推断：

| 写法 | 现状 |
|---|---|
| `[...]` / `new Array(n)` | 宽（`new Array(1e8)` 稀疏、便宜，放行——合理） |
| `arr.flat(depth)` | 严（展开超 4096 / 有 getter / 环 / 超 32 层都拒——**这次修得对，保留**） |
| **`Array.from(...)`** | **完全不查** |

**`Array.from` 的实测**（这就是上一轮报的 ADV-24，与本节是同一件事）：

```
Array.from({length:4097}).length        -> OK -> 4097      越过 MAX_ARRAY
Array.from({length:1000000}).length     -> OK -> 1000000   越过 244 倍，365 ms，instr=0
Array.from('a'.repeat(65536)).length    -> OK -> 65536
Array.from({length:100000000}).length   -> OutOfMemoryError（2.4 s，instr=0）
Array.from(无限迭代器)                   -> 38.7 s、instr=0、OutOfMemoryError（没有任何闸介入）
```

**根因**：`ScriptSandbox.wrapAll` 只包 `String.prototype`、`Array.prototype` 与 `JSON`（`wrapOwn` 现在只对 `JSON` 用）。**构造函数上的静态方法从不经过 `checkNativeCall`。**

**建议方向**：
1. 把 `Array` 这个 holder 的静态方法也纳入包装（`wrapOwn(s, "Array")`，与 `JSON` 同一手法），并在 `checkNativeCall` 里为 `Array.from` 处理两条输入路径：array-like 的 `length`、以及 iterable（按**已产出元素数**计闸，或直接拒绝 iterable 输入）；
2. 顺带关掉两个连带项：`Array.from(无限迭代器)`（ADV-25，无闸可停）与 `Array.from(生成器)` 中止时触发的 Rhino 内部断言 `Kit.codeBug`（ADV-26，`FAILED ASSERTION` 栈会进日志，且脚本可控）；
3. **立一张必须覆盖的清单并配断言**：凡"能从脚本可控长度构造新容器"的入口（prototype 方法 + 静态方法 + JSON）逐个列出，并写一条测试遍历该清单、断言每个入口都被 `checkNativeCall` 或显式闸覆盖。`flat` 与 `Array.from` 连着两次漏，说明**逐个补必然漏第三个**——清单 + 遍历断言才是根治。

**验收**：
- `Array.from({length:4097})` 必须被拒（与 `MAX_ARRAY` 一致）；
- `Array.from({length:1e8})` 必须在**分配之前**被拒；
- `Array.from(无限迭代器)` 必须被某个闸停下（拒绝输入或按元素数中止），不得 OOM；
- `Array.from([1,2,3])`、`Array.from('abc')` 等小型合法用法保持可用；
- 清单断言：每个已登记入口各有一条"必须被拒"的用例。

---

## 3. 文档要求（作者指南）

`docs/script-app-author-guide.md` 这次补了 `flat`/`flatMap`，**另外三处没写**。请补：

- `String.split` 在未给 `limit` 时的**长度阈值**（改前是 4096，改后按新口径写）；
- **脚本自己调 `JSON.stringify`** 时的实际上限（与 `ctx.ok` 的 64 KiB 是两回事，请分开写清楚，避免作者误以为 `ctx.ok` 也被收紧）；
- `replace`/`replaceAll` **不接受函数替换器**，以及替代写法（字符串替换器 / `$1` / `split+join`）。

---

## 4. 不要改坏（已核验正确，动这一块时请保持）

- **`SizeGate` 的 `flat` 预检**（`flattenedLength`/`flatDepth`）：按路径判环（同一数组被多次引用会逐次计数）、逐索引拒 accessor、累计超限早退、深度封顶 32 —— 我复测过原 OOM 攻击已在 1–48 ms 内被前置拒，**保留原样**；
- **`String.prototype` / `Array.prototype` 的接收者类型校验**（拒非 `NativeArray` 接收者）—— 这是堵 ADV-13 那条 210 秒 TOCTOU 的关键，**不要为了让作者好写而放宽**；
- 小型合法用法（`[[1],[2]].flat()`、`flat(Infinity)`、`[].flat()`、全空洞数组、`replace` 字符串替换器、`JSON.stringify` 小对象）—— 回归时必须仍然放行；
- 拒绝一律用 `HostError`（可接住、`Disposition.NONE`、**不记玩家过失**）—— 这个形态是对的，别改成 `ScriptAbort`。

---

## 5. 建议的验收方式

把下面这组数字直接写成断言（阈值以改后的口径为准，但"过/不过"的边界必须显式钉住）：

```
split:           len=4095 放行 / len=4096 与 65536 在真实结果<=4096 时放行
split(+limit):   len=5000 limit=10 放行 / limit=99999 仍拒
JSON.stringify:  'x'.repeat(10922) 放行 → 改后应放宽到接近 65530
                 {s:'x'.repeat(11000)} 放行
                 真超 64 KiB 的仍拒
replace:         函数替换器 按选定口径（禁用则断言拒绝 + 指南有替代写法）
Array.from:      {length:4097} 拒 / {length:1e8} 分配前拒 / 无限迭代器 被停
                 [1,2,3] 与 'abc' 放行
flat（回归）:     深层嵌套拒 / 循环拒 / getter 不执行 / 小型放行
```

对抗组这边可以直接复跑：`.tmp-probe/evidence/r3-thresholds.out.txt`（阈值与绕法）、`r3-fix-regression.out.txt`（日常用法 22 条）、`r3-arrayfrom.out.txt`（`Array.from`）、`r3-flat2.out.txt`（`flat` 回归）。

---

## 6. 本要求未覆盖 / 我未验证的部分（请一并知悉）

- **我这边没有隔离服**：以上全部是在独立 JVM 上驱动不依赖 Minecraft 的类得出的，没有真服实测。
- **仓内自带的示例 App / SDK / `api/sdk` 是否已经用到这三处写法，我没有查**。如果你们要评估自家代码是否踩到，那是一次静态 grep 的事，建议在改之前先做——它决定这次修复是"防患"还是"已经在坏"。
- **`ADV-12` 的根因（墙钟只在指令观察器回调里采样）不在本要求内**，需要单独拍板：要么在原生边界补 deadline 检查，要么明写"实际约 2–3 倍且依赖清单完整"（`Array.from` 带回调实测 429 ms、`instr=0`）。
