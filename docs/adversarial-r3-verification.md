# MCPhone 对抗组 · 三次审查（针对 `c2210bd`）

> **审查对象**：`step21-s15hi-thread-attribution`，HEAD = `c2210bdefc15bcbb09094425c175a96791b91686`（"fix: preflight array flattening"）。
> **改动范围**：3 个文件 / +89 行 —— `SizeGate.java`（+70）、`docs/ScriptEngineTest.java`（+12）、`docs/script-app-author-guide.md`（+7）。
> **上一轮基线**：`840d70c`（我上一份报告 `adversarial-r2-verification.md`）。
> **证据**：`.tmp-probe/evidence/r3-*.out.txt`。

---

## 1. 结论

| 项 | 判定 |
|---|---|
| **ADV-23**（`flat`/`flatMap` → OOM） | **已关闭** ✓（行为验证 + 修法质量见 §2） |
| **ADV-24**（新 · 高）`Array.from` 完全无闸，1e8 → OOM | **新发现** |
| **ADV-25**（新 · 高）`Array.from` + 无限迭代器 → 38.7 s、无闸可停、OOM | **新发现** |
| **ADV-26**（新 · 低中）`Array.from(生成器)` 中止时触发 Rhino 内部断言 `Kit.codeBug` | **新发现** |
| **ADV-12** 根因（墙钟只在观察器回调采样） | **未变** |
| 三条兼容性收窄（函数替换器 / `split` 估算 / `JSON.stringify` 估算） | **未变**（文档只补了 flat/flatMap 两条） |

---

## 2. ADV-23 判定：已关闭，而且修法质量好

**行为验证（`r3-flat2.out.txt`、`r3-flat.out.txt`）**：

```text
修复前：new Array(1024).fill(new Array(1024).fill(new Array(1024).fill(0))).flat(2)
        -> 3996 ms  instr=0  **OutOfMemoryError**
修复后：同一条 ->   48 ms  被拒：Array.flat: 预估输出 5120，上限 4096
        n=2048 ->    1 ms  被拒：6144      n=4096 -> 3 ms  被拒：8192
默认 flat()（一层）：修复前 615 ms 分配 16,777,216 个元素后才被拒 -> 修复后 21 ms 分配前被拒
flatMap(x=>x)      ：直接禁用（"无法在执行前估算回调输出"）
```

**修法为什么是对的（逐行核过 `SizeGate.flattenedLength` / `flatDepth`）**：

- **按路径判环**：`path` 集合在退出时 `path.remove(array)`，所以**同一个数组被引用多次会被逐次计数** —— 正是我那个 `fill` 共享引用的花招，没有漏算（1024×5=5120 的估算数字正好印证）。
- **早退**：累计 `total > MAX_ARRAY` 立刻抛，单次预检的工作量有上界（不是先走完整棵树）。
- **不执行脚本 getter**：逐索引 `getGetterOrSetter` 检查后拒绝 —— 把 ADV-13 那条 TOCTOU 的教训用对了地方。
- **递归深度封顶** `MAX_FLAT_DEPTH = 32`，`flat(Infinity)` 映射成 33 后由嵌套深度兜住，不会用递归本身当攻击面。
- **`flatDepth`** 对 number/boolean/string 都做了取整与 NaN/∞ 处理，对象直接拒。

**合法用法没被误伤**（6/6）：`[1,[2,3]].flat()`、`[[1],[2],[3]].flat(1)`、`[1,[2,[3,[4]]]].flat(2)`、`.flat(Infinity)`、`[].flat()`、全空洞数组 —— 全部正常。
**他们自己的新测试也覆盖到位**：深层拒绝、小型可用、`flatMap` 拒绝、**循环数组拒绝**、**预检不得执行数组 getter**（`Object.defineProperty(a,'0',{get:...})` 后 `n` 必须为 0）。这一条测试写得比我的探针还细。

---

## 3. 新发现

### 3.1 ADV-24（高 · 休眠）`Array.from` 等**静态方法**完全不在闸的覆盖内

**根因**：`ScriptSandbox.wrapAll` 只包 `String.prototype`、`Array.prototype` 与 `JSON`（`wrapOwn` 目前只用于 JSON）。**构造函数上的静态方法从不经过 `checkNativeCall`**，因此没有任何尺寸/放大预检。

**实测（`r3-unwrapped.out.txt`、`r3-arrayfrom.out.txt`）**：

```text
Array.from({length:4096}).length        -> OK -> 4096      （合法边界）
Array.from({length:4097}).length        -> OK -> 4097      ** 越过 MAX_ARRAY **
Array.from({length:1000000}).length     -> OK -> 1000000   228–365 ms  instr=0   ** 越过 244 倍 **
Array.from('a'.repeat(65536)).length    -> OK -> 65536     23 ms       instr=0   ** 越过 16 倍 **
Array.from(new Array(1000000)).length   -> OK -> 1000000   205 ms       instr=0
Array.from({length:100000000}).length   -> **OutOfMemoryError**  2399 ms  instr=0
```

**构造比 ADV-23 还便宜**：一个对象字面量 `{length: 1e8}`，约十条指令；`flat` 那条至少还要三次 `fill`。

**为什么三道防线都不管**：不在包装层 → 无预检；分配由原生 `from` 完成 → `instr=0`、指令预算不触发；返回值闸在**分配之后** → OOM 时根本回不到它。与 ADV-23 同源（**名单制必然漏**），但入口是"静态方法"，是名单制的另一个盲区。

**修复方向（供参考）**：①`wrapOwn(s, "Array")` 把 `Array` 上的静态方法也纳入包装（现在只对 `JSON` 用了这一步）；②或至少在 preflight 里覆盖 `Array.from` 的两条路径（array-like 的 `length`、iterable）；③更根本的是把"凡是从脚本可控长度构造容器的入口"列成一张**必须覆盖**的清单（prototype 方法 + 静态方法），而不是逐个补——`flat` 与 `Array.from` 连着两次证明逐个补会漏。

### 3.2 ADV-25（高 · 休眠）`Array.from` + **无限迭代器**：没有闸能停下它

```text
有界迭代器（1e6）  ->    41 ms  instr=30422  REJECTED WALL_CLOCK: 188 毫秒     （墙钟生效）
有界迭代器（1e8）  ->    21 ms  instr=160506 REJECTED WALL_CLOCK: 5 毫秒
无限迭代器         -> 38756 ms  instr=0      **OutOfMemoryError**              （没有任何闸介入）
```

**同一段脚本、同一个 `next()` 形状**，加了"到 1e6 就 done"的分支就会被墙钟拦住，不加上就能跑 **38.7 秒**直到 OOM。

**必须说明的不确定性**：这个 `instr=0` 与有界情形**自相矛盾**（有界时 41 ms 内就累计了 30422 单位），**机制我还没查清**，可能是探针/JVM 状态的影响，也可能确实是 `Array.from` 迭代器路径吞掉了观察器异常。**所以我把"38.7 s + OOM + 无中止"作为观察事实上报，机制标注为待确认。** 但无论机制如何，修 ADV-24 时把 iterable 输入一并限住（按"已产出元素数"计闸，或直接拒绝 iterable），这条就一起关了。

### 3.3 ADV-26（低中 · 休眠）拼预算中止会把 Rhino 内部断言打进日志

```text
function* g(){ while(true) yield 1 } Array.from(g()).length
```

中止时 Rhino 从 `IteratorLikeIterable.close()` → `ES6Generator.js_return` → `Interpreter.resumeGenerator` 走到
`Kit.codeBug(Kit.java:352)` → **`IllegalStateException: FAILED ASSERTION`**，完整栈打进 stderr/日志。

**更正我自己的一个错误判断**：我上一支探针里说"进程直接被打死"——**那是错的**。定向复测（`r3-generator-abort.out.txt`）显示：`ScriptAbort: WALL_CLOCK: 142 毫秒` **被正常接住**，`budget.end()`、`Context.exit()` 都正常返回，进程活到打印"探针结束"。断言是**另外**被抛/打印出来的（跑到 stderr），不是替代了原异常。

**影响**：①**脚本可控地把一条"FAILED ASSERTION"内部错误栈刷进服务端日志**（可被用来做日志洪水、让运维误判模组坏了）；②`Kit.codeBug` 的含义是"Rhino 认为这个内部状态不该出现"，说明该中止路径让解释器的生成器状态变得不一致（这正是断言存在的理由）。**建议**：把生成器/迭代器输入挡在 `Array.from` 之外（与 ADV-24/25 同一处修），顺带绕开这条 Rhino 内部路径。

---

## 4. 未变项（上一轮已报，本次未修）

1. **ADV-12 根因**：墙钟仍只在 `observeInstructionCount` 里采样。新证据：`Array.from({length:4096}, ()=>new Array(4096).fill(0))` **429 ms、`instr=0`**（分配了 4096×4096 个引用）—— 又是一次"秒级以下但完全不受时间约束"的求值。
2. **三条兼容性收窄**（`r3-fix-regression.out.txt`，与上一轮逐字一致）：
   - `'a-b'.replace(/-/g, function(m){...})` → 拒（函数替换器）；
   - `'x'.repeat(5000).split('z')` → 拒（预计 5001，实际 1 段）；
   - `JSON.stringify('x'.repeat(12000))` → 拒（预计 72002，实际约 12002）。
3. **文档只补了两条**：作者指南新增了 `flat`/`flatMap` 的限制（写得很清楚，包括"请改写成有明确上限的循环并逐项 push"）；但上面三条**没有写进指南**。

---

## 5. 建议（按性价比排序）

1. **补一轮 `Array.from`**（含 array-like 与 iterable 两条输入路径）——便宜、且是唯一还能打死服务端的入口。顺带关掉 ADV-25/ADV-26。
2. **给"容器构造入口"立一张必须覆盖的清单**（prototype 方法 + 静态方法 + JSON），并让 `ScriptSandbox` 的包装点与之对齐 —— 否则下一轮还会有第三个漏项。
3. `ADV-12` 的根因**拍一个板**：要么在原生边界补 deadline 检查，要么明写"实际约 2–3 倍且依赖清单完整"。
4. 三条兼容性收窄**拍板并写进作者指南**（尤其函数替换器那条）。
5. 其余（`ADV-09`/`ADV-22`/`G9`/§6）维持你们的判断，不必动。

---

## 6. 方法学备注

本轮是本次对抗中**第 4 次**靠复测纠正我自己的结论：前三次是 `AdversarialSuite` 旧仿真、`SafePathProbe` 旧副本、15 条形状指纹的假 FAIL；这一次是 ADV-26 的"进程被打死"（实际没有）。教训不变：**探针里凡是被测逻辑或环境结论的副本，复用前必须重验；行为证据优先于形状证据。**
