# MCPhone 对抗组 · 五次审查（网络侧静态审查 + ADV-27 修复与验收）

基线：`mcphone-step21` 分支 `step21-s15hi-thread-attribution`，HEAD `3b5c170`（含 `840d70c` / `c2210bd` / `3b5c170`）。
本轮新增工作树改动（**未提交**）：`shared/.../core/script/engine/ScriptSandbox.java`、`SizeGate.java`；完整补丁另存 `.tmp-probe/evidence/r5-adv27-fix.patch`。

> **声明（先说清，免得日后被当成背书）**：上一轮报告里最大的"未验证面"是 `840d70c` 的网络侧 C2S 加固，本轮把它做了（静态）。
> 而 **ADV-27 的修复代码是我（对抗组）写的**。按分工这是越界的一次（用户明确要求），代价是：**这份修复的回归结论不再算独立证据**，将来它出问题不能引用"对抗组验过"。§3 给的验收命令任何人都能重跑，那才是凭证。

---

## 1. ADV-27 的机制：先解释清楚，再改

上一轮我只能给出"未包装的宿主函数上 `.apply` 可达"这个现象，没有解释**为什么**包装过的 `Array.from` 反而摸不到 `.apply`。机制不明就动安全边界，是这轮最不能做的事。本轮把它钉死了：

### 1.1 唯一共享节点

Java 侧走原型链 + JS 侧实测（`.tmp-probe/evidence/r5-apply-mechanism.out.txt`）：

```
String.fromCharCode  -> LambdaFunction AC -> LambdaFunction AC -> NativeObject
Array.from（我们的包装）-> LambdaFunction --                        ← 链到此为止
Object.getPrototypeOf(String.fromCharCode)
    === Object.getPrototypeOf(parseInt) === Object.getPrototypeOf(Object.keys) -> true
Function.prototype 的自有属性: length,name,arity,arguments,apply,bind,call,toSource,toString
```

关键：**所有未包装的宿主函数共享同一个 `Function.prototype`**，而包装过的（`Array.from` / `JSON.stringify` / `Math.max` …）是"裸的" `LambdaFunction`，原型为 null，所以它们根本没有 `.apply`。

→ 结论：装闸的位置只有一个，就是那个共享对象。逐个 holder 补是浪费。

### 1.2 被我证伪的三条（含我自己的怀疑）

| 怀疑 | 结果 | 证据 |
|---|---|---|
| `harden()` 第①步 `deleteProperty(p,"constructor")` 是空转（PERMANENT 删不掉），`Function` 运行时编译入口还活着 | **证伪** | `p.constructor.name` 是 `Object`（继承自 `Object.prototype`），`p.constructor('return 42')()` 返回的是 String **对象**、再调用抛 `TypeError: return 42 不是函数，它是 object`。编译入口确实堵住了 |
| `LambdaFunction` 与原生函数走同一个原型链 | **证伪** | 原生即 `LambdaFunction`，但**只有原生实例**在构造时挂上了 `Function.prototype`；我们自己 `new` 的没有 |
| 结构不变式第一版报"8 个错误构造器绕过闸" | **我的检测规则错了** | 我用了 `hasProperty`（**含继承**），把"能看见 apply"的原型结点误报成"持有 apply"。改成自有属性判定后 **0 漏网**；行为测试也证明这 8 个不 OOM |

### 1.3 新发现 ADV-28（中 · 休眠）：`Function.prototype` 未密封，脚本可写可删

```
p.zzProbe = 1        -> 1            ← 可加属性
delete p.apply       -> apply 变 undefined   ← 可以把宿主方法删掉
p.apply = 1          -> apply 变 number      ← 可以覆写
Java: isSealed()=false
```

**成因**：`harden()` 第③步是"按全局表逐个密封"，而 `Function` 这个全局在第②步被删了 → 这个对象**整个被跳过**。
**为什么它决定 ADV-27 修不修得成**：如果只装闸不密封，脚本一句 `delete p.apply` 就能让闸消失（虽然删掉 apply 反而没了放大面，但"边界依赖脚本不动手"本身就是错的；而覆写让语义变得不可预测）。

---

## 2. 修复（已改代码，未提交）

### 2.1 改了什么

| 文件 | 位置 | 内容 |
|---|---|---|
| `ScriptSandbox.java` | `harden()` 第①步 | 在原"删 constructor"处 **加装 `apply` 尺寸闸**，并 **`sealObject()`**；三件事都必须在第②步删掉 `Function` 全局之前做完 |
| `ScriptSandbox.java` | `wrapAll` 尾部 | 抽出 `wrapOne(target, scope, where, name, inner)`，供 `apply` 复用同一套"先过闸→调原件→量返回值"结构（无行为变化，纯重构） |
| `SizeGate.java` | `checkNativeCall` | 新增 `Function.prototype` 分支（**只装 `apply`**） |
| `SizeGate.java` | 新增 `checkApplyArgArray` | `apply` 第二参的 TOCTOU 安全判据；`safeArrayLikeLength` 加 `where` 参数（`Array.from` 调用点同步更新，报错文案不变） |

### 2.2 判据（与 `Array.from` 同一套口径，只读数据属性）

放行：`null`/`undefined`（零参数）、`NativeArray` 且长度 ≤ 4096、字符串长度 ≤ 4096、普通对象且**数据属性** `length` ≤ 4096 且索引不是访问器、Number/Boolean/BigInteger（无 length → 零参数）。
拒绝：长度超限、`length` 是访问器（谎报长度）、原型被改过的接收者、iterable/其它宿主值。

### 2.3 三个刻意的取舍

1. **只装 `apply`，不装 `call`/`bind`**：`call`/`bind` 的参数表来自解释器自己的实参数组，脚本要撑大它得先真的写出那么多实参，指令预算拦得住。唯一能把 array-like 灌进原生代码的是 `apply` 的第二参。
2. **没有把 `String`/`Object`/`Number`/`Boolean`/`BigInt` 加进 `EXTRA_WRAPPED_HOLDERS`**（我上一轮建议过）：现在**不再必要**——`apply` 那一处已经覆盖它们的全部静态方法（§3.3 结构不变式实测）。加进去只会改掉这些 holder 静态方法的行为，而 `checkNativeCall` 对未知 holder 是直接 return 的，加了也拿不到参数维度的检查。**若将来有人往沙箱里加新的"接受 array-like 的宿主入口"，要补的是那处入口的判据，而不是再加一层 holder 包装。**
3. **`Math.max.apply` 仍然是 `undefined`**：这是 Rhino 1.9.1 把 `max` 实现成裸 LambdaFunction 的结果，与本次改动无关，也不是我们能控制的（这也正说明"能不能 `.apply`"取决于 Rhino 内部实现，不能把边界建在逐个函数上）。

---

## 3. 验收（可直接当门禁）

### 3.1 命令

```powershell
$jar=(Get-Content .tmp-probe\rhino-path.txt).Trim()          # rhino-1.9.1.jar
$w=".tmp-probe\adv27"
# 探针树里的 engine 源码是从 mcphone-step21 现拷的（改一次拷一次，别用旧副本）
javac -encoding UTF-8 -cp "$jar;$w\out" -d "$w\out" (Get-ChildItem -Recurse "$w\src" -Filter *.java).FullName
java "-Dfile.encoding=UTF-8" -Xmx512m -cp "$jar;$w\out" Adv27AcceptanceProbe
```

输出：`.tmp-probe/evidence/r5-adv27-acceptance.out.txt`，**38 项通过 / 0 失败 / 退出码 0**。

### 3.2 攻击面（修前 → 修后）

修前（`r4-apply-amplify.out.txt`，`-Xmx512m`）：`String.fromCharCode.apply(null,{length:1e8})` 2129 ms `instr=0` **OOM**；`fromCodePoint` 962 ms、`parseInt` 1220 ms、`isNaN` 1197 ms、`Object.keys` 1505 ms、`Object.assign` 1896 ms、`Number.isFinite` 1928 ms，全部 OOM。
修后：**全部在 0–29 ms 内被闸拒绝**（`HostError: Function.prototype.apply: 预估输出 100000000，上限 4096`），并且探针逐条标注 **[BY GATE]** —— 断言落在"是闸拒的"，不是"它抛了"。

覆盖：9 个具名函数 + `new Array(4097)` + 谎报 length 的访问器 + 1e9 访问器 + 被改原型的接收者 + 写入尝试之后的复测，共 14 条攻击全部 **[BY GATE]**。

### 3.3 结构不变式（"这一族关闭"的判据）

探针遍历沙箱里从顶层 scope 可达的每一个函数，断言：**要么它的原型链里没有任何 `.apply`，要么那个 `.apply` 恰好落在被装闸并密封的那一个对象上**。

```
distinct functions reached      : 363
of those, reach an .apply       : 107
reaching an UNGATED .apply      : 0
```

**这个测试绿灯 = 这一族关闭。** 它不依赖"我想得到哪些函数"——新增宿主函数、Rhino 升版带来的新内置，只要它摸到 `.apply`，这条就会红。

### 3.4 兼容性（10 条全过）

```
String.fromCharCode.apply(null,[65,66,67])                 -> ABC     ✓
parseInt.apply(null,['42',10])                             -> 42      ✓
Object.keys.apply(null,[{a:1}])                            -> a       ✓
isNaN.apply(null,['x'])                                    -> true    ✓
Number.isFinite.apply(null,[1])                            -> true    ✓
String.fromCharCode.apply(null,{length:0})                 -> ""      ✓
String.fromCharCode.apply(null,{length:3,0:65,1:66,2:67})  -> ABC     ✓
parseInt('42',10) / String.fromCharCode(65,66) / Object.keys({a:1}) 不变 ✓
```

即：**"apply 传真数组/小类数组"这个最常见写法没有被误伤**，被拒的只是超限那一档。

### 3.5 闸自身不可削弱

```
p.zz = 1        -> THREW EvaluatorException: 无法修改密封对象的属性: zz
delete p.apply  -> THREW EvaluatorException: 无法修改密封对象的属性: apply
p.apply = 1     -> THREW EvaluatorException: 无法修改密封对象的属性: apply
之后复测 apply(null,{length:1e8}) -> 仍被闸拒绝
```

注意：这是**行为变化**——以前写 `Function.prototype` 是静默生效，现在会抛一个 Java 侧 `EvaluatorException`（脚本的 try/catch 接不住它，按 `RhinoEvaluator.classify()` 会记一次处分/STRIKE）。与 String/Array 等已密封全局的行为一致，但作者指南里最好写一句。

### 3.6 回归

- **脚本引擎自测（331 断言）**：见 §3.7。
- **我自己探针的两处错误（记在案，因为不记就会得出相反结论）**：
  1. 结构不变式第一版用 `hasProperty`（含继承）→ 误报 8 个"绕过闸"。
  2. 验收探针第一版全程只 `budget.begin()` 一次 → 跑到 D 段后半 250 ms 墙钟早已过期，后面 3 条"拒绝"其实是 `ScriptAbort(WALL_CLOCK)` 的**假通过**。已改为每条检查独立 begin/end，并把断言从"它抛了"改成"它带 `Function.prototype.apply` 字样"。

### 3.7 331 断言回归结果（**已跑，通过**）

```
cd platforms/1.21.1-neoforge && ./gradlew assertTestScriptEngineTest
→ 断言 331 条 / 全部通过 / BUILD SUCCESSFUL / 退出码 0
日志：.tmp-probe/evidence/r5-scriptengine-test.log
```

即：**打上本补丁后，你们自己的脚本引擎测试仍然全绿**（断言数与本轮之前一致，331 条）。日志里那些 `ERROR ... provider ... AssertionError` 是测试故意注入的 provider 故障，不是失败。
注：gradle wrapper 需要写工作区之外的 `C:\Users\31286\.gradle`（wrapper 锁 + 缓存），在只读/工作区写沙箱下会以 `gradle-9.2.1-bin.zip.lck 拒绝访问` 失败——那是环境限制，不是代码问题。`fabric` / `forge` 两个目标的 `compileJava` 我仍未跑。

---

## 4. 本轮其他发现（网络侧 `840d70c`，全部静态·未起服）

级别：高/中/低；置信度：已确认/推测。**只列现象与证据，不替开发决定怎么改。**

### A1（中 · 已确认 · 本次修复新引入）SCREEN_STATE 限流会**永久**吃掉最后一次亮屏变更
`platforms/*/.../core/net/NetworkHandler.java` 的 `handlePhoneScreenOn` 现在第一句就是
`if (!PhoneItem.isCarriedBy(player) || !RequestThrottle.allow(player, Kind.SCREEN_STATE)) return;`（250 ms 窗口）。
而客户端 `shared/.../core/client/PhoneScreenOnSync.java:55-62` **只在状态变化时发一次**，并且**先**把本地记忆 `lastSent` 改成新值、失败**不重发**。
→ 两次翻转相隔 <250 ms 时第二个包被静默丢弃，服务端亮屏位停在旧值，且状态不再变就永不补发（重登/换世界才自愈）。表现：别人看到一部"关着却发亮"的手机。forge 上这一位还落盘。

### B1（中 · 已确认 · 本次修复新引入）每片门禁被放在唯一限流器**之前**
`handleSendImage` 的新顺序是 `maySendImage → chunkCount → (仅 index==0) 限流`。
`maySendImage` 里有 `PhoneItem.isCarriedBy`（**全背包 + Curios 扫描**）与 `FriendData` 查询，且全在服务端主线程（`enqueueWork`）。
→ 伪造客户端按任意频率发 `chunkIndex≠0`（或本身非好友/无手机）的包时，每个包都做一次全背包扫描，**限流一次都不消耗**。改动前非首片是直接进 `accept()` 的廉价查表，所以这条放大面是本次新引入的。

### A2（低 · 已确认）`chunkCount` 超量的拒绝路径不再消耗额度
同上顺序：改动前是 `index==0 → gate → 限流 → chunkCount`，超量包也扣 2 秒额度；现在是 `gate → chunkCount → 限流`，而 `chunkCount > maxChunks()` 路径会 `tell()` 回一句 actionbar。→ 20 包/秒的虚假 `chunkCount` 可以一直逼服务端回包（放大 1:1，打不服，但限流在这条路径上是空的）。

### A3（低偏中 · 已确认）"做事情"的包被静默丢弃，与 `RequestThrottle` 自己写的规矩相反
类注释（`RequestThrottle.java:25-33`）明写"只拦要数据、不拦做事情……把玩家刚打的一条消息静默丢掉，比慢一点糟得多"，而本次新增的 `PURCHASE/FRIEND_ACTION/NOTE_ACTION/SETTINGS/SCREEN_STATE` 全是"做事情"且共用同一个 250 ms 额度。
最具体的一处：`NotesNetworking.handleSaveNote` 被丢弃时**既不回列表也不提示**，而注释写的是"无论成败都回发列表"；客户端 `NoteEditor` 发完立刻退出编辑页 ⇒ 正文静默丢失、界面已关。同理 `WallpaperPicker` 快速连点会被吞。

### B2（低 · 已确认）门禁失败时上传会话不清理
`handleSendImage` 在 `accept()` **之前** return，`ChatImageUploads.SESSIONS` 里那半截 session（≤ `maxBytes()`，默认 512 KB）只能等 15 s 超时或下线。每玩家 1 份，属有限残留。

### C1（低 · 已确认）解除好友只清"主动方"的已读
`FriendGraph.remove` 是双向的，但 `ChatService.removeFriend` 只对自己 `read.without(targetId)` ⇒ 被动方永久留一条非好友条目，占满 100 槽后按**最旧时间**淘汰**当前好友**的进度（该会话历史重新计未读）。与 `ChatReadState` 注释的意图相违。需 ≥100 次增删，只自伤。

### D1（中 · 不变量缺失已确认 / 利用性推测·低）壁纸不变量不在类型里
`WallpaperData` 的构造与 CODEC **都不校验**，唯一防线是 3 个 C2S 处理函数调用 `sanitize` 这个 static 约定；`setWallpaper` 三平台都是 public。`sanitize` 放行 `:`、Windows 保留设备名、结尾点/空格。
**利用性**：追到消费端是"扫描目录后精确匹配名字"（`WallpaperStore.findEntry`），名字从不拼进路径 ⇒ 目前打不动。风险在将来有人把名字拼成路径。

### D2（低 · 已确认）壁纸只写不回读
`PhonePlayerData.wallpaper()` 全仓 **0 个调用点**；`SyncWallpaperPacket` 只在 set 的处理函数里构造，登录/重生/换维度都没有重发 ⇒ 存档里的值是死状态，重启后壁纸不回客户端。附带：`SyncWallpaperPacket.encode` 用的是无上限 `writeUtf(...)`（当前无活路径）。

### D3（低 · 推测）客户端回包入口不 sanitize
`handleSyncWallpaper` 直接写客户端字段。因该包只发给本人，当前无跨玩家路径，属缺一道纵深防御。

### A4（低 · 已确认）文档漏改**只在 forge 侧改了**
`platforms/1.20.1-forge/.../SetWallpaperPacket.java` 的类注释仍写"`writeUtf/readUtf` 不带参数时上限 32767"，而同一文件已是 `MAX_FILE_NAME`(=255)；共享层编解码清单 `layers/version/1.20.5+/docs/MiscPacketCodecTest.java:61-62` 也仍写 32767。对照：forge 的 `WallpaperPacketTest.java` 本次已改并新增 `serverSanitizesFileName()` —— **只有 forge 那一份改了**。

### A5（低 · 已确认）fabric 的 `handleSafely` 是三份里唯一没有 player 判空的
fabric `MCphoneNetwork` 直接 `handleSafely(packet, ctx.player(), ...)`，catch 里 `player.getUUID()`；同文件注释却写着"玩家为空……静默丢弃即可"。只有"player 为 null 且处理函数同时抛异常"时才会让日志行自己抛 NPE（按 Fabric 实现实际很难发生）。

### A6（低 · 已确认）fabric `TerminalNetworking` 注释与同平台门面注释互相矛盾
前者说"enqueueWork 不能省：处理函数在网络线程上被调到"，后者说"play 阶段回调本来就跑在主线程上"。**门面是对的**（`ServerPlayNetworkAddon.receive` 内部就是 `server.execute(...)`）。

### ADV-29（低 · 已确认 · 我本轮的发现）`tickDiscLoop` 这个"每 tick × 每个玩家"扇出没有异常隔离
`840d70c` 在 `MCphone.java` 新挂了 `ServerTickEvent.Post` → `tickDiscLoop(所有在线玩家)`：
```java
for (ServerPlayer player : players) {
    if (DiscService.tickLoop(player)) DiscService.syncState(player);   // 没有 try/catch
}
```
同一提交刚刚给 C2S 路径加了 `handleSafely`（catch Throwable），**而这个新增的扇出一个都没有**。一个玩家的 `disc()`/`JukeboxSong.fromStack` 抛异常 → 该 tick 里**排在后面的玩家全部被跳过**，而且每 tick 复现（跨玩家影响）。触发需要该玩家自己存档里有一个会让 `fromStack` 抛的物品栈，属**推测**，但"扇出没有隔离"是静态确认为事实。
另附一条性能观察（非缺陷）：`tickLoop` 每 tick 对**正在外放**的玩家调 `PhoneItem.isCarriedBy`（= `getInventory().contains(...)`，全背包 41 格扫描）；`lengthInTicks` 也每 tick 重算而不是缓存进 state。只有正在放歌的玩家付这个成本，量级不大。

### 已验证**成立**的部分（对抗后仍站得住）

- **三平台一致性**：`840d70c` 改动的 7 个文件在三平台逐行比对**未发现逻辑不一致**，阈值/常量全部一致（17 个 `Kind` 的 ordinal 顺序逐项核过——它决定 `long[]` 下标）；确有一处"只改了一份"，但只在注释/清单层面（A4）。fabric 有自己的 `RequestThrottle` 拷贝且三份已挂登出钩子。
- **身份来源**：四类状态全部取自连接上的 `ServerPlayer`（`ctx.player()`），包内没有任何身份字段，不存在"客户端自称是谁"。
- **`markRead` 写前检查**：先 `areFriends` 再写；`setChatRead` 全仓只有两处调用点。
- **图片硬上限**：分片解码期限 `CHUNK_BYTES`；请求 id 列表限 4；`Session.buffer = min(maxBytes, chunkCount*CHUNK_BYTES)` + `size+len <= buffer.length` ⇒ 单次上传 ≤ `maxBytes`；`looksLikePng` 查签名 + IHDR 宽高 ≤1024；`width/height/frames/frameMs` 在 `ImageBody` 构造里 clamp。读取侧 `unique` 去重与 `responseBytes` 上限都落了地。
- **`accept()` 的序号强校验**：`nextChunk == chunkIndex` 严格递增 + 五个字段全等 + 超时 ⇒ **重复/乱序片不会让缓冲区无界增长**（我原本怀疑的"重复发 index=1 撑大 buffer"不成立）。
- **壁纸长度**：包解码 `readUtf(MAX_FILE_NAME)` 与 `sanitize` 双重，且非空非法在**写之前**被拒。
- **终端卡槽**：未发现"客户端递交 ItemStack 进卡槽"的注入路径（`SyncTerminalSlotPacket` 均为 S2C）。
- **脚本 RPC**：`pipeline` 恒为 null ⇒ 一律 `NOT_DEPLOYED`，当前不构成任何旁路。

---

## 5. 仍然没验的（不是我判断为没问题，是我没有条件）

真服/真客户端才能验：`ADV-05`/`G1` 的真实菜单与补电、崩溃恢复、真实网络压力、经济"扣款后抛异常"注入、A1/A3 的真实触发频率（连按手机键、快速连点壁纸）、图片乱序/解码炸弹、恶意 S2C。
环境限制：仓库内无 `run/`、无缓存服务端产物、无测试服地址与账号；本会话沙箱是 workspace-write，gradle 需要写工作区之外的 `~/.gradle`（§3.7 的处理见下）；`fabric`/`forge` 的 `compileJava` 仍未跑。
另：`.tmp-probe` 下的探针与证据都在仓库之外，不进开发树。

---

## 6. 下一步顺序（比单个 bug 重要）

本轮追加修复之后，状态变成：

1. **已由我修掉（见 §8）**：ADV-27、ADV-28、A1、A5、ADV-29，以及 ADV-12 的"宿主边界采样点"（并更正了 ADV-12 的定性）。
2. **仍需要你们定的（不是我该替你们决定的）**：
   - **B1 / A2（限流的放置）**：非首片该按什么频率限流？你们的类注释已经把"为什么只拦要数据"写清楚了，而这次又给"做事情"的包加了 250 ms —— 与你们自己写的规矩冲突（A3 就是它的一个实例）。要么给一个数，要么换一条"既能限流又不丢最后一次状态"的机制。
   - **A3**：笔记保存被静默丢弃时给不给提示；`NOTE_ACTION` 要不要拆成 save/delete/print 三个 kind。
   - **C1**：解除好友时怎么清被动方的已读（对方可能离线，需要一个数据层入口）。
   - **D1**：壁纸名允许哪些字符（`:`、Windows 保留设备名要不要拒）。**D2/D3 更像"功能没做完"**（`wallpaper()` 全仓 0 调用点），修它等于实现功能，我不替你们补。
   - **兼容性三条阈值**（`split` / `JSON.stringify` 系数 / `replace` 函数替换器）：我没有证据说该改成多少 → **建议只改文档、不动数字**（在作者指南里写清"实际上限是 X"）。
3. **ADV-09 / ADV-22 / G9 / §6 生产接线**：维持你们"待产品定 / 待接线"的判断，我认同。**§6 接线必须排在 ADV-27 与 ADV-12 之后**（这条不变）。

---

## 7. 补记

- **331 断言回归**：通过（§3.7）。
- 本轮我自己的探针错误已在 §3.6 记录（结构不变式的 `hasProperty` 误报、验收探针的单次 `begin()` 假通过），另有一次**报告层面的过度断言**在 §8.4 更正。连同前几轮，这是第 7、8、9 次靠复测纠正自己。
- 本轮我的改动**未提交**，就在工作树里：`git -C mcphone-step21 diff` 是全部改动（**9 个文件 / 174 增 / 43 删**，不含 `HostFn.java`——那一份试过后已逐字还原）；完整补丁另存 `.tmp-probe/evidence/r6-fix-full.patch`。要全部撤掉：`git -C mcphone-step21 checkout -- .`（只会丢我这几处改动；`docs/adversarial-r*.md` 是未跟踪文件，不受影响）。

---

## 8. 追加修复（"一口气修"这一批）

### 8.1 这一批改了什么

| 编号 | 文件（×平台份数） | 改法 |
|---|---|---|
| ADV-27 | `ScriptSandbox` + `SizeGate`（shared，1 份） | `Function.prototype.apply` 装闸（只认数据属性 `length`）+ 密封 `Function.prototype`（ADV-28 同一处） |
| A1 | `NetworkHandler.handlePhoneScreenOn`（3 份） | **去掉 SCREEN_STATE 上的 250 ms 限流**。理由：该包是绝对状态而非增量，客户端失败不重发，丢一次就永久停在旧值；代价只是每次一次全背包谓词扫描（微秒量级） |
| A5 | fabric `MCphoneNetwork`（1 份） | 注册回调里先取 `ctx.player()` 并判空，与类注释和另外两份平台一致（顺带把 `ctx.player()` 从求值两次改成一次） |
| ADV-29 | `MCphone.tickDiscLoop` / fabric 的 tick 扇出（3 份） | 每玩家 try/catch，`VirtualMachineError` 仍重抛 —— 与同一提交给 C2S 加的 `handleSafely` 同一套 |

### 8.2 没改的，以及为什么（不是遗漏）

`B2`（门禁失败时那半截上传会话）：残留是**每玩家 1 份、≤ `maxBytes()`、≤15 秒自动过期**，且 `ChatImageUploads` 已有登出清理。为它加一个 `abort()` 要动 6 处（三平台各一份实现 + 一份调用点）——**收益小于改动面**，我选择不做并写在这里，而不是偷偷塞进去。
`A3 / A4 / A6 / C1 / D1 / D2 / D3 / B1`：见 §6 第 2 条（需要你们的数值或设计）。

### 8.3 验证

- ADV-27 验收：**38/38，退出码 0**（§3.1–§3.5 的命令；拒绝全部标 `[BY GATE]`）。
- 三平台编译 + 331 断言：见 §8.3 结果。
- ADV-12：**试过、已撤销**，理由见 §8.5。

**结果（三平台全绿）**：

```
1.21.1-neoforge  assertTestScriptEngineTest  -> 断言 331 条 / 全部通过 / BUILD SUCCESSFUL / 退出码 0
1.20.1-forge     compileJava                 -> BUILD SUCCESSFUL / 退出码 0
1.21.1-fabric    compileJava                 -> BUILD SUCCESSFUL / 退出码 0
日志：.tmp-probe/evidence/r6-scriptengine-test.log / r6-compile-forge.log / r6-compile-fabric.log
```

两点值得说明：
- **`forge` / `fabric` 的 `compileJava` 是本项目第一次在我这里跑通**（此前只跑过 neoforge）。这关掉了前几轮报告里"另两端编译未验证"的缺口——本轮对这三个平台源码的改动（A1 / A5 / ADV-29）确实编译得过。
- **这个门禁是真的**：ADV-12 那个改动就是被它拦下的（331 条里 2 条失败 → 退出码 1），不是"测试从来不会红"。

### 8.4 更正：ADV-12 的定性（我上一轮说过头了）

我 r4 报告把 ADV-12 列成"仍未变的母根因"，并在同一处把 ADV-27 说成"它最新的实例"。**前半句是过度断言。**本轮查证：

```
.tmp-probe/evidence/sandbox-overshoot-probe.out.txt   20 x 256 MB   2163 ms  OK -> 20   ← r3 的头条数字
.tmp-probe/evidence/r5-adv12-overshoot.out.txt        20 x 256 MB     10 ms  OK -> 20   ← 本轮复跑
git log -S checkStringAmplification -- SizeGate.java  ->  840d70c                       ← 字符串闸就是它加的
```

那个探针每轮是 `try { 'a'.repeat(268435456).length } catch(e){}` —— **脚本自己吞异常**。`840d70c` 给 `String.prototype.repeat` 加了 `checkProduct` 之后，每轮都在分配之前被拒（所以 `instr=0`、总耗时 10 毫秒），探针从此**测的只是一个空转循环**。

结论：**"一串合法原生分配撑出越界"这条可复现路径，在 `840d70c` 就被尺寸闸关掉了，关它的不是墙钟。**

顺带一条方法学结论：**探针会随被测代码一起"过期"**。`SandboxOvershootProbe` 的 `try/catch` 是为了让循环跑下去，代价是它把"被闸拒绝"和"分配成功"变成同一个结果——这类探针必须每次复跑并核对语义，不能把历史数字当现状引用。我已把 r3 那组数字在本文里标注为"闸之前"。

### 8.5 试过又撤掉的：ADV-12 的"宿主边界采样点"

我确实动手加了（`ScriptBudget.checkWallClock()` + 在 `HostFn.enter` 里调用），**然后被你们自己的测试否掉了**：

```
assertTestScriptEngineTest -> 331 条里 2 条失败
  pay 成功之后 balance 不能变，脚本接得住，而且 ctx.fail ...
      期待 OK|UNAVAILABLE: mcphone.economy.unavailable.main_thread_busy
      实际 ScriptAbort: WALL_CLOCK: 2 毫秒
  pay 只执行了一次   期待 1，实际 0
```

原因不是"测试写错了"，是**我改法的语义错了**：脚本的**编译时间**也算在 `begin()` 起的墙钟里，而编译期不产生任何指令回调。原来只有解释器分支点会看截止，所以"编译慢一点、然后进宿主调用拿到 UNAVAILABLE"是能正常走完的；我在宿主入口加了采样之后，脚本一进第一个宿主调用就被掐掉。你们那两条断言编码的正是这个意图（宿主等待用 `hostWaited` 单独记账补偿），我的采样点与它冲突。

**已撤销**（`HostFn.java` / `ScriptBudget.java` 现在与 HEAD 逐字一致，`git diff` 里没有这两个文件）。加上 §8.4 已经查明可复现路径早已被尺寸闸关闭，结论是：

> **ADV-12 不改。**机制描述成立（墙钟只在解释器分支点采样），但当前**没有可复现的放大路径**，而在宿主边界补采样会与你们的宿主等待记账相互干扰。风险按"已接受"记档：残留只有"**单次**原生调用自己跑很久"这一种，而它的入口（`apply` 的 array-like、`Array.from`、`flat`、`String`/`Array` 的放大方法）都已经被尺寸闸按输入维度限住。

