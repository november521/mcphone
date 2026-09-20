# MCPhone 对抗组 · 二次对抗验证（针对 `840d70c`）

> **验证对象**：`mcphone-step21` 分支 `step21-s15hi-thread-attribution`，HEAD = `840d70ce32fea64d428d43175b475541d56e9a84`（"fix: harden script and network boundaries"），工作树干净，改动 68 个文件（源码 53 个）。
> **对照基线**：本轮所有"修复前"的数字来自我对 `main@2a60a31` + 未提交改动（`mcphone` 工作树）的第一轮对抗。
> **方法**：把探针/套件切到修复分支源码上重编重跑；**行为套件为权威**，静态指纹只作辅助（原因见 §5）。
> **证据**：`.tmp-probe/evidence/r2-*.out.txt`（全部为修复分支上的原始输出）。

---

## 1. 结论速览

| 项 | 修复前 | 修复后 | 判定 |
|---|---|---|---|
| **ADV-01** 唱片循环 | 十万刻重放 **0** 次；界面永久显示"在放" | 回归套件 **137 项全过**（退出码 0） | **已关闭** |
| **ADV-06** 模块假循环 | 假循环 **319/400** 轮、结构异常 61 次 | **0/400**、**0** 次 | **已关闭** |
| **ADV-07** scope 竞态 | 两线程拿到不同 scope **200/200** | **0/200** | **已关闭** |
| **ADV-08** 丢脏键 | 丢键 **283/300** 轮、累计 186310 键 | **0/300**、**0** | **已关闭** |
| **ADV-13** TOCTOU | 谎报 length=1e10 → **210105 ms** 挂住 worker | **0–1 ms 被拒** | **已关闭** |
| **ADV-15** HOST 误伤 | 10 个 `Reason.HOST` 抛出点、记玩家过失 | 生产抛出点 **10 → 0**；`strikeFor` 把 SIZE/RETAINED/HOST 全映射为 NONE | **已关闭** |
| **ADV-16** 日志字符集 | 30 字符里 **20** 个原样穿出 | **2** 个（残留单代理项 `U+D800` 与 `U+FFFD`） | **已关闭**（残留见 §3） |
| **ADV-17** 环报错 | `b.js -> a.js -> a.js`（反序+重复） | `a.js -> b.js -> a.js` | **已关闭** |
| **ADV-18** DST 重叠 | 标签来回跳、边界落在过去 | 违反 **0** 次、单调性后退 **0** 次 | **已关闭** |
| **ADV-19** printable 绕过 | 与 `LogText` 口径不一致 | 同一段文字两边输出**完全相同** | **已关闭** |
| **ADV-20** 栈帧无界 | 1,000,000 帧照单全收 | **256** 帧 | **已关闭** |
| **ADV-21** `safe()` | `..` 逃一级、`NUL` 写盘必失败 | 纯点段→`_`、设备名→`_NUL` | **已关闭** |
| **AT-15** 模块名不校验 | 6 种畸形名都能进表 | 6 种**全部被拒** | **已关闭** |
| **AT-18** 幂等键消歧 | 相邻字段挪分隔符 → 同键（3/3） | **全不碰撞**；`null` 与 `""` 已区分 | **已关闭** |
| **ADV-12** 墙钟越界 | 越界 **110–270 倍**（2–5 s） | 越界约 **2–3 倍**（40–60 ms） | **部分关闭**（根因仍在，见 §2.1） |
| **ADV-23** `flat` 放大 | —— | **新增：3.5–4 s 内让 JVM OutOfMemoryError** | **新发现 · 高** |
| **ADV-09** `RESERVED` 永久 | 时钟推进 10 年仍 Full | 未变（开发自陈待产品定） | 已知取舍 |
| **ADV-22** 聚合配额 | 生产代码零引用 | 未变（开发自陈待 `KvBackend` 接线） | 已知取舍 |

**原始放大向量全部关闭**：`'x'.repeat(1e8)`、`padStart(1e8)`、`JSON.stringify(1e6 串)`、`replace(/x/g, 1e6 串)`、`new Array(5000).fill(0)`、4096 数组再 push —— 全部 **0–3 ms、在分配之前**被 `HostError` 拒。

---

## 2. 仍未关闭 / 新发现

### 2.1 ADV-23（新 · 高 · 休眠）`flat` / `flatMap` 不在预检覆盖内 —— 可让服务端 OOM

**位置**：`SizeGate.checkArrayAmplification`（`SizeGate.java:151-169`）覆盖了 `push`/`unshift`/`concat`/`join`/`toString`，**没有 `flat` / `flatMap`**。而这两个内置在本仓的 Rhino 里**存在**（`typeof [].flat === "function"`）。

**构造（三行，构造成本只有几次 `fill`）**：

```javascript
new Array(1024).fill(new Array(1024).fill(new Array(1024).fill(0))).flat(2).length
```

**实测（`r2-flat-amplify2.out.txt`，`-Xmx512m`）**：

```text
n=1024 flat(2) 目标元素数 1073741824  ->  3996 ms  instr=0  **OutOfMemoryError**
n=2048 flat(2) 目标元素数 8589934592  ->  3843 ms  instr=0  **OutOfMemoryError**
n=4096 flat(2) 目标元素数 68719476736 ->  3495 ms  instr=0  **OutOfMemoryError**
```

**三道防线为什么都失效**：

1. **预检**：`flat` 不在名单里 → 没有任何估算；
2. **指令预算**：展开由原生 `flat` 完成，`instr=0`，观察器零回调；
3. **返回值闸**：`SizeGate.check(out,…)` 在**分配之后**才跑 —— 而这里根本回不到返回值，先 OOM。

**顺带**：连默认的 `flat()`（只展一层）也是先分配 **16,777,216** 个元素（615 ms、`instr=0`）才被返回值闸拒掉（`r2-flat-amplify.out.txt`）。`flatMap` 同样（353 ms）。

**为什么算高**：OOM 在 Minecraft 服务端不是"一个请求失败"，而是**整服进程死掉**（或至少该 worker 线程不可恢复）。而这条路径只需要一个脚本文本，不需要并发、不需要竞态。

**修复方向（供参考，不代改）**：把 `flat`/`flatMap` 纳入 `checkArrayAmplification`：按 `depth` 逐层估算展开后的元素数（对嵌套 `NativeArray` 递归求和，带深度与元素数上界），超 `MAX_ARRAY` 直接拒；同时**给尚未枚举到的"会造出新容器"的原生方法留一条兜底**——现在的做法是把已知方法一个一个列进名单，而 `flat` 证明"名单制"必然漏（这正是 `ScriptSandbox` 白名单注释里那条经验）。

### 2.2 ADV-12 只关了一半：根因仍在

- **量级确实降了**：原向量被前置预检封死，越界从 **110–270 倍**降到约 **2–3 倍**。
- **根因未动**：墙钟仍然**只在 `observeInstructionCount` 里采样**（`ScriptBudget.java` 的 `if (System.nanoTime() > b[2])` 位置未变）。用 `JSON.parse(64 KiB)` 复测（合法、昂贵、几乎不累计指令）：20 轮耗时 49 ms 而 `instr=0` → **这 49 ms 内墙钟一次都没被检查**；60 轮时第一次回调在约 40–59 ms 才把它掐掉。
- **ADV-23 正是这条根因被重新放大的实例**：只要存在"未被预检的昂贵原生调用"，越界就能回到秒级。所以这条建议**不要按"已修"结案**。

---

## 3. 修复引入的行为收窄（需产品/SPI 确认，不一定是错）

`SizeGate.checkNativeCall` 用**最坏情况**估算 + 硬拒接收者类型。安全上是强了，但有三处会拒掉**合法且常见**的写法（`r2-fix-regression.out.txt`）：

| # | 写法 | 实际 | 修复后 |
|---|---|---|---|
| 1 | `'a-b'.replace(/-/g, function(m){ return '_' })` | 结果 3 字符 | **被拒**：`String.replace: 函数替换器不可在受限沙箱中使用` |
| 2 | `'x'.repeat(5000).split('z')` | 结果**只有 1 段** | **被拒**：`预估输出 5001，上限 4096` |
| 3 | `JSON.stringify('x'.repeat(12000))` | 实际约 **12,002** 字符 | **被拒**：`预估输出 72002，上限 65536` |

- 第 1 条是**极常见的 JS 写法**（函数替换器）。若 SPI 没有明示禁止，第三方 App 会大面积踩到；若确实要禁，建议在 `script-app-author-guide.md` 里写明。
- 第 2、3 条是**估算保守**：`split` 用 `receiverLength+1`、`JSON.stringify` 用 6× 字符系数。建议改成"按实际分隔符/实际输出估算"，或**把这两条从预检降级为返回值闸**（它们的结果本来就会被 `SizeGate.check(out,…)` 覆盖，风险远低于 `repeat` 那类）。

**对照：日常用法全部通过**（B1/B2 共 22 条）：`trim/toUpperCase/split/indexOf/slice/charAt/replace(字符串)/startsWith/repeat/padStart/concat`、`indexOf/join/slice/concat/sort/map`、`JSON.stringify/parse` 全部 OK；`'x'.repeat(65536)`（恰好等于上限）OK。

---

## 4. 待真服（我这边没有隔离环境，与第一轮相同）

开发回执列的三项与我第一轮的 §5 一致，这里只补充**本轮新增**的：

- **ADV-23** 的 OOM 需要在一个受控服务端上确认"是否真能把整服带走"（我的证据是独立 JVM 上的 OOME，不是服务端）。
- **ADV-01** 的手机离身停止分支（`tickLoop` 新增的 `!PhoneItem.isCarriedBy` 早退，`DiscService.java:127-132`）我**只能静态读到，无法驱动** —— 需要真服验：播放中丢手机、死亡、换维度、掉线。
- 第一轮那三项（NetMusic 循环、终端第三方入口、图片乱序/解码炸弹、恶意 S2C、经济故障注入）不变。

---

## 5. 我自己的方法学问题（必须写出来）

本轮我**三次**因为"探针/指纹里含旧代码副本"而差点给出错误的"未修复"结论：

1. **`AdversarialSuite`（v1）** 里硬编码了**旧调用方**的判据（`if (now < periodStart + length)`）与我自己的 `playingUntil` 仿真 → 在修复分支上跑出 **22 条"违反"**。逐行读新实现后重写为 v2（镜像 `DiscLoop.isDue` + 新的 `playingUntil`），结果 **137 项全过**。**那 22 条全部是我的工具过时，不是开发回归。**
2. **`SafePathProbe`** 里的 `safe()` 是我从旧源码**抄进去的副本** → 报"`..` 仍逃一级"。读新 `LocalStore.safe` 后确认已修（纯点段→`_`、设备名→`_NUL`）。
3. **29 条静态指纹**里 15 条 FAIL，逐条核对后**大多数是"形状变了"**而非"没修"：`synchronized` 包裹后的 `HashMap`/`dirty.clear()`、检查下沉到 `TerminalOpener`、执行前预检与返回值闸并存的 `SizeGate`、`flat` 的返回值闸。开发在回执里预警了这一点，**预警是对的**。

**结论（写进方法学）**：
- **代码形状断言在修复后必须逐条人工核对，退出码不能当结论**；
- **行为用例才是权威** —— 本轮真正有分量的是 `AdversarialSuite2`（137 项）、`ConcurrencyProbe`、`FixRegressionProbe`、`FlatAmplifyProbe2`、`WallClockProbe` 这几支；
- **探针里凡是被测逻辑的副本，都是隐患**：跨轮次复用时要么重新生成，要么改成直接驱动真实类。

**本轮真正有价值的产出不是"哪些通过了"，而是**：ADV-23（名单制必然漏，已实证）、ADV-12 只关一半、以及三条兼容性收窄。前两条是**新问题**，第三条是**修复自身带来的**。

---

## 6. 证据索引

| 项 | 证据文件 |
|---|---|
| ADV-01 回归（137 项） | `r2-adversarial-suite2.out.txt` |
| 旧套件的假 FAIL（保留作方法学证据） | `r2-adversarial-suite.out.txt` |
| ADV-06/07/08 | `r2-concurrency.out.txt` |
| ADV-13/14 | `r2-toctou.out.txt` |
| ADV-16 | `r2-LogTextProbe.out.txt` |
| ADV-19/20 | `r2-ProviderFailureProbe.out.txt` |
| ADV-17 / AT-15 | `r2-ScriptModulesProbe.out.txt` |
| ADV-18 | `r2-cyclelabels.out.txt` |
| ADV-21 | `r2-safepath.out.txt`（**注意其中 `safe()` 是旧副本，结论以读源码为准**） |
| AT-18 | `r2-idempotencykey.out.txt` |
| **ADV-23（新）** | `r2-flat-amplify.out.txt`、`r2-flat-amplify2.out.txt` |
| **ADV-12 根因** | `r2-wallclock.out.txt`、`r2-overshoot.out.txt` |
| **兼容性收窄** | `r2-fix-regression.out.txt` |
| 形状指纹（辅助，需人工核对） | `gate-check-r2.out.txt`（29 条，15 FAIL） |
