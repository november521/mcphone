# MCPhone 对抗组 · 四次审查（针对 `3b5c170`）

> **审查对象**：`step21-s15hi-thread-attribution`，HEAD = `3b5c170e3ec17f57c26ed7eb03ef95819f50967e`（"fix: gate Array.from inputs"），4 个文件 / +139 / -1。
> **上一轮基线**：`c2210bd`（报告 `adversarial-r3-verification.md`）。
> **证据**：`.tmp-probe/evidence/r4-*.out.txt`。

---

## 1. 任务完成情况（对开发回执的逐条核验）

| 开发回执的说法 | 我的独立核验 | 判定 |
|---|---|---|
| 脚本引擎 331/331 断言通过 | 在 neoforge 上复跑 `assertTestScriptEngineTest`：**输出「断言 331 条 / 全部通过」，BUILD SUCCESSFUL，退出码 0** | **复现成功** ✓ |
| `Array.from({length:1e8})` 0 ms 前置拒绝、无 OOM | 复测：`5 ms` 前置拒绝「预估输出 100000000，上限 4096」；**无 OOM** | **复现成功** ✓ |
| 无限 iterable 1 ms 前置拒绝 | 复测：`0 ms` 拒绝「不接受 iterable/生成器输入」 | **复现成功** ✓ |
| 无限生成器 3 ms 前置拒绝、无 FAILED ASSERTION | 复测：`1 ms` 拒绝，**stderr 不再出现 `Kit.codeBug` / `FAILED ASSERTION`** | **复现成功** ✓ |
| 三端编译通过 | neoforge 的脚本引擎套件我跑绿了（见上一行）；**fabric / forge 的 `compileJava` 我未能独立核验** —— 不是编译失败，是我这边的环境跑不起来：`~/.gradle` 在会话工作区之外、沙箱拒绝写入（`gradle-8.8-bin.zip.lck 拒绝访问`），改用工作区内的 `.gradle-home` 后 Gradle 又拒绝在其中新建缓存文件（`Access to the path …\caches\8.8\fileHashes\probe.txt is denied`，能建目录、不能建文件）。**降级依据**：本轮改动全部在 `shared/` 与 `docs/`（`SizeGate`、`ScriptSandbox`、测试、指南），**没有平台专有文件**；三个目标按 `versions/targets.json` 都用 `official` 映射编译同一份 `shared/`，而 neoforge 编译通过 —— 所以剩余风险低，但**这不等于我验过**。要独立跑需一次工作区外的写权限批准 | **未核验** |
| 既有的 Windows/POSIX 权限用例失败，与本轮无关 | 失败的是 `assertTestEconomyDataTest`（`EconomyDataTest.java:929/939` 调 `Files.setPosixFilePermissions`）；**该文件不在本轮改动名单**（本轮改的是 `Amounts`/`ProviderFailure`/`TxnLog`/`MoneyLedger`/`VaultTest`）；而且**我在基线那一轮就记录过这条失败**（Windows 无 POSIX 权限 → `UnsupportedOperationException`） | **确认与本轮无关** ✓ |

**已关闭的洞（三条，全部独立复测）**：

```
Array.from({length:4097})            -> REJECTED「预估输出 4097，上限 4096」（原来是 OK -> 4097）
Array.from({length:1e6})             -> REJECTED 0 ms（原来 OK -> 1000000）
Array.from({length:1e8})             -> REJECTED 5 ms，无 OOM（原来 OOM 2.4 s）
Array.from('a'.repeat(65536))        -> REJECTED（原来 OK -> 65536）
Array.from({length:4096})            -> OK -> 4096          （合法边界保留）
Array.from({length:4096}, callback)  -> REJECTED「不允许映射回调」
Array.from(无限 iterable)             -> REJECTED 0 ms（原来 38.7 s + OOM）
Array.from(无限生成器)                -> REJECTED 1 ms，无 FAILED ASSERTION
flat 合法用法 6 例（含 flat(Infinity)/全空洞） -> 全部 OK    （无回归）
```

---

## 2. 修法评价：方向对，且这一处做得比上一轮更结构化

值得记下来的三点：

1. **把 `Array` 整个 holder 纳入包装**（`EXTRA_WRAPPED_HOLDERS` 从 `{JSON}` 扩到 `{Array, JSON}`）——这比我上轮建议的"逐个列方法"更根治：今后 `Array` 上新增任何静态方法都自动过闸。这是本轮最有价值的结构性改动。
2. **`Array.from` 的两条输入路径都验**：array-like 用**数据属性的 `length`** + 拒 index/length accessor；iterable/生成器**直接拒绝**，理由写在注释里（"数一遍要消费它，可能跑两遍脚本、可能永不终止、可能把生成器留在 Rhino 的坏中止状态"）——这正是我 ADV-25/26 两条的结论。
3. **用 `ScriptableObject.hasProperty(object, SymbolKey.ITERATOR)` 探迭代器**，而不是 `get`——避免执行脚本 getter。这是把 ADV-13 的 TOCTOU 教训用对了。
4. 另外补了 `thisObj != target` 检查，堵住 `var f = Array.from; f(...)` 那种"取出来再调"的旁路。

---

## 3. 新发现 ADV-27（高 · 休眠）：未包装的宿主函数上 `.apply` 仍可撑出任意大小的参数表

**普遍性实测（`r4-apply-amplify.out.txt`，`-Xmx512m`）**：

```
typeof String.fromCharCode.apply   -> function      String.fromCharCode.apply(null,{length:1e8}) -> 2129 ms  instr=0  **OutOfMemoryError**
typeof String.fromCodePoint.apply  -> function      String.fromCodePoint.apply(null,{length:1e8})->  962 ms  instr=0  **OutOfMemoryError**
typeof parseInt.apply              -> function      parseInt.apply(null,{length:1e8})            -> 1220 ms  instr=0  **OutOfMemoryError**
typeof isNaN.apply                 -> function      isNaN.apply(null,{length:1e8})               -> 1197 ms  instr=0  **OutOfMemoryError**
typeof Object.keys.apply           -> function      Object.keys.apply(null,{length:1e8})         -> 1505 ms  instr=0  **OutOfMemoryError**
typeof Object.assign.apply         -> function      Object.assign.apply(null,{length:1e8})       -> 1896 ms  instr=0  **OutOfMemoryError**
typeof Number.isFinite.apply       -> function      Number.isFinite.apply(null,{length:1e8})     -> 1928 ms  instr=0  **OutOfMemoryError**
```

**对照（说明"包住 holder"这个办法本身是有效的）**：

```
typeof Array.from.apply / Array.of.apply / Array.isArray.apply
     / JSON.stringify.apply / JSON.parse.apply
     / String.prototype.trim.apply / Array.prototype.join.apply   -> 全部 undefined
```

**根因**：`EXTRA_WRAPPED_HOLDERS` 现在只有 `{Array, JSON}`。`String` / `Object` / `Number` / `Boolean` / `BigInt` 以及全局函数（`parseInt`/`isNaN`…）仍是**裸的原生函数**；未包装的原生函数上 `Function.prototype.apply` 可达，而 `apply` 会把一个 array-like 直接物化成 `Object[]` —— 那一步在原生代码里完成，所以 **`instr=0`**（指令预算不触发）、墙钟也只在观察器回调里采样（ADV-12 的老根因）。构造 `{length:1e8}` 只要几条指令。

**一个顺带查到的既有事实（与本轮无关，但很能说明问题）**：`Math.max.apply` / `Math.min.apply` 是 **`undefined`** —— 于是 `Math.max.apply(null, arr)` 这个极常见写法在这个沙箱里**本来就不工作**（`TypeError: 找不到函数 apply`）。原因是 Rhino 把 `max`/`min` 实现成了 `LambdaFunction`。也就是说：**"能不能 `.apply`"取决于 Rhino 的内部实现方式，而不取决于安全策略**——这正是不能把安全边界建在"逐个 holder 包装"上的理由。

**修复建议（两条，建议都做）**：

1. **前提性**：把 **`Function.prototype.apply` / `call` 纳入包装**。`Function.prototype` 现在**不在** `wrapPrototype` 的覆盖内（只包了 `String.prototype` 与 `Array.prototype`），而脚本可用 `Object.getPrototypeOf(function(){})` 拿到它。**一处闸覆盖所有函数**（原生的和脚本的），比逐个 holder 补更根治。判定可复用 `Array.from` 那一套 TOCTOU-safe 写法：第二参是真 `NativeArray`、或"带**数据属性** `length` 的普通对象"且 `length ≤ MAX_ARRAY` 时放行，其余拒绝。
2. **补充**：把 `String`/`Object`/`Number`/`Boolean`/`BigInt` 加进 `EXTRA_WRAPPED_HOLDERS`。**注意**：`checkNativeCall` 对**未知 holder 会直接 `return`、什么都不查**，所以只加进列表只能关掉 `.apply`、拿不到参数维度的检查；要两者兼得，还得为这些 holder 加分支（例如 `String.fromCharCode/fromCodePoint` 按"参数个数 ≤ MAX_ARRAY 且总长 ≤ MAX_STRING"判、`Object.assign` 按源个数判）。

**验收建议**：

```
typeof String.fromCharCode.apply  必须不再是 function（或 apply 被闸拦住）
String.fromCharCode.apply(null,{length:1e8}) / parseInt.apply / Object.assign.apply / Math.max.apply
    -> 必须前置拒绝，不得 OOM
String.fromCharCode.apply(null,[65,66,67]) / Object.keys({a:1}) / parseInt('42',10)
    -> 必须保持可用（apply 用真数组的小规模用法别一刀切禁掉）
```

---

## 4. 上轮 §1 的三处兼容性误拒：**本次未修**（逐条复测）

`3b5c170` 只动了 `SizeGate` 的 `Array.from` 一侧 + `ScriptSandbox` 的 holder 列表；`checkStringAmplification` 的 `split`/`replace` 与 `jsonCost` 的系数**逐字未变**：

```
'x'.repeat(4095).split('z').length    -> OK -> 1
'x'.repeat(4096).split('z').length    -> REJECTED「String.split: 预估输出 4097，上限 4096」
JSON.stringify('x'.repeat(10922))     -> OK
JSON.stringify('x'.repeat(10923))     -> REJECTED「JSON.stringify: 预估输出 65540，上限 65536」
'a-b'.replace(/-/g, function(){...})  -> REJECTED「函数替换器不可在受限沙箱中使用」
日常用法 22 条（trim/split/indexOf/slice/repeat/padStart/join/sort/map/JSON 小对象…）-> 全部 OK
```

同时**新增了两条**收窄（都合理，但需进文档）：`Array.from` 不接受映射回调、不接受 iterable/生成器输入。

**文档**：作者指南本轮 +4 行（应是补了 `Array.from` 的限制）。**上轮 §3 要求的那三条（`split` 阈值 / 脚本自调 `JSON.stringify` 的实际上限 / `replace` 不接受函数替换器）仍未写入**。

---

## 5. 仍未变的两项（沿用上轮结论）

- **ADV-12 根因**：墙钟仍只在 `observeInstructionCount` 里采样。本轮 `ADV-27` 就是它最新的实例（7 个函数的 OOM 全部 `instr=0`、1.2–2.1 秒）。
- **`ADV-09` / `ADV-22` / `G9` / §6**：维持开发"待产品定 / 待接线"的判断，我认同。

---

## 6. 我的方法学记录

- 本轮**第 5 次**靠复测纠正自己：上一轮我把生成器那条报成"进程被打死"，本轮定向复测证明 `ScriptAbort` 被正常接住、进程存活，断言是另外打到 stderr 的（已在 `adversarial-r3-verification.md` §3.3 更正）。
- `fabric`/`forge` 的 `compileJava` 我第一次尝试时因 **Gradle 服务初始化错误**（`Cannot create service of type ValueSnapshotter`）失败——**那是我的环境问题，不是代码问题**，我不会把它写成"另外两端编译不过"。
- 所有结论都是独立 JVM / 本地 gradle 上得出的；**我这边仍然没有隔离服**。
