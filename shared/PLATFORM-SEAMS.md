# 平台接缝清单

`shared/` 里的代码会引用一批**只定义在 `platforms/` 下**的类型。写一个新平台的人
第一件要做的事，就是把它们分成两类：

| | 是什么 | 新平台该怎么办 |
|---|---|---|
| **甲** | 有意的接缝 | **必须自己写一份**：全限定名与签名相同、方法体各写各的 |
| **乙** | 只是还没搬 | **不要重新实现**，它迟早要搬进 `shared/` |

判错的代价不对称：把甲当成乙，新平台编不过（当场就知道）；把乙当成甲，两份实现
从此各自漂移，而两边都是绿的。

## 这张清单为什么存在

它原先不存在，树上也看不出来。`PhoneItemData` 因此被误搬进 `shared/`：它和
`PhonePlayerData` 是同一个设计的两半 —— 一个存物品堆、一个存玩家，两份类注释
写的是同一段论证「签名两支相同，方法体注定不同」—— 一个搬了，一个没搬。搬进去
之后构建全绿，因为当时**没有任何机器判据管这件事**。

## 下面两段是生成的

跑 `./gradlew updateSeamsDoc` 重写，`./gradlew check` 会校验它们跟源码对得上
（`verifySeamsDocCurrent`）。**别手改那两段** —— 手写的清单会烂，这仓库已经栽过一次。

分类判据与 `verifySharedIsTargetNeutral` 那道闸**共用一份定义**（`TargetNeutrality`）——
命中任何一条 → 甲，一条都不中 → 乙。原先是两份、隔着一百行，而拦门的那份少两条，
于是这份清单里写着 `ModPresence` 是接缝，闸却对它一声不吭。

判据是「这个类里有没有别的目标上不存在的东西」，四条：**加载器导入**、
**1.20.5+ 原版类型**、**注入到原版类型上的方法**、**`ItemStack` 的组件读写**。
它有三层盲区：

1. **导入** —— `net.neoforged` / `net.minecraftforge` / `net.fabricmc`。扫 import 就够。
2. **注入的方法** —— `player.getData(...)`（NeoForge 装在 `Entity` 上）、
   `stack.get(ModDataComponents...)`（1.20.5+ 装在 `ItemStack` 上）、
   `getCapability(...)`（Forge）。**这些没有 import，扫 import 一个都看不见。**
   `PhonePlayerData` 实测就是这么从甲漏进乙的。
3. **签名漂移** —— 方法名没变、签名变了，客户端渲染那套尤其多。名字和导入都一样，
   只有拿另一个版本真编一遍才知道。**这一层现在补不上。**

所以：

> **乙 的意思是「自动判据没找到阻断」，不是「确认可以搬」。**

要把一个类型从乙挪进 `shared/`，仍然得人看一眼，并且过
`verifySharedIsTargetNeutral` 那道闸。

---

## 甲 · 每个平台各写一份

**这一类混着两个轴，不要当成一个平面来读。**

| 判据 | 版本轴 | 加载器轴 |
|---|:---:|:---:|
| 1.20.5+ 原版类型 | ● | |
| `ItemStack` 的组件读写 | ● | |
| 加载器导入 | | ● |
| 注入到原版类型上的方法 | ● | ● |

**版本轴**指「在 1.21.1 上成立、在 1.20.1 上不成立，与加载器无关」；**加载器轴**指
「绑在某一个加载器上，与 Minecraft 版本无关」。

一条判据可以同时落在两个轴上。`player.getData(...)` 即是：Data Attachment 只有
NeoForge 有（加载器轴），而它是 NeoForge **20.3** 引入的（版本轴）—— 20.1.x 那条线
即 Minecraft 1.20.1，在它之前，那边只能用能力。`getCapability(...)` 同理。
因此 `PhonePlayerData` 与 `TerminalCharger` 属于「两轴都有」，而非「仅加载器轴」。

只被**版本轴**挡住的类型，在同一个 Minecraft 版本的 Forge / NeoForge / Fabric 三个
目标下是可以逐字共用的 —— 手写编解码的那一批（`Note`、`ChatMessage`、`TextBody`、
各种网络包）就是典型：它们与加载器无关，只是与 1.20.1 不同。

### 三层结构

| 目录 | 谁用 | 允许什么 | 校验 |
|---|---|---|---|
| `shared/` | 全部目标 | 一条判据都不许中 | `verifySharedIsTargetNeutral` |
| `versions/<层名>/` | 挂了这一层的目标 | 版本轴可以有，加载器轴不行 | `verifyLayer<层名>` |
| `platforms/<目标名>/` | 单个目标 | 都可以 | —— |

**层由配置文件说了算**，不写死在构建脚本里：

- [`versions/layers.json`](../versions/layers.json) —— 有哪些层、各自的目录
- [`versions/targets.json`](../versions/targets.json) —— 每个目标的 `layers` 字段

层名是「这套 API 从**哪个 Minecraft 版本开始**有」，不是某一个具体版本。1.21.1 与
将来的 26.x 都挂 `1.20.5+`，因为它们装的是同一份代码 —— 写死成某个版本号的话，
每加一个版本就要多一层，而那些层里是一样的东西。

加一个目标、加一层，都只改 JSON，`build.gradle` 不用动。

**层是可组合的**：`layers` 是数组，一个目标可以同时挂好几层。所以层要按**一条**
版本边界切，不要按版本切成大块 —— 把「某个版本之后的所有东西」塞进一层，处在两条
边界之间的目标就会被挡在门外，那批代码只能退回各目标下复制粘贴，而消掉那种复制
正是层存在的理由。**宁可层多：层多不花钱，复制才花钱。**

**层分两种，各挡各的轴**，二选一，由 `since` / `loader` 哪个字段在决定：

| 种类 | 字段 | 谁共用 | 层里不许有 |
|---|---|---|---|
| 版本层 | `since` | 同一批 Minecraft 版本的三个加载器 | 绑加载器的东西 |
| 加载器层 | `loader` | 同一个加载器的各个 Minecraft 版本 | 绑版本的东西 |

两者对称：各自装的正好是对方挡掉的那一半。

现有的层：

| 层 | 种类 | 边界 | 内容 |
|---|---|---|---|
| `1.20.5+` | 版本层 | 原版才有 `net.minecraft.network.codec`、`CustomPacketPayload`、数据组件 | 30 个 |
| `1.21+` | 版本层 | 数据包格式 48 把目录名从复数改成单数（`recipes`→`recipe` 等） | 5 份资源 |
| `forge` / `neoforge` / `fabric` | 加载器层 | 该加载器专有 | **空** |

三个加载器层现在是空的（`populated: false`），机制先立着。原因是**没有可证的候选**：
本仓只有一个目标建得起来，没有第二个同加载器的目标可以逐文件比对，
「这段 NeoForge 代码在 20.1 和 21.1 上是不是一样」猜不出来 ——
加载器的 API 在大版本之间改得比原版还多。等第二个同加载器目标并进来，
按与版本层同一个办法办：逐文件比对，**逐字相同的才搬**。

两条边界不是同一条，所以是两层。数据包那批文件若塞进 `1.20.5+`，一个 1.20.6 的
目标挂上那层就会拿到单数目录名，而它那儿要的是复数 —— **并且不报错**，那条配方
只是静默地不存在。

每层还要声明 `populated`：这一层现在该不该有内容。声明了有却扫不到文件要红，
声明了没有却扫到了也要红。「空」是显式声明的状态，不是默认容忍 —— 本该有内容的
层被清空、或者 `dir` 写错指到一个恰好存在的空目录，否则都只会在日志里留一行。

**为什么要中间层。** 只被版本轴挡住的类型（手写编解码、各类网络包）与加载器无关。
若无这一层，第二个同版本目标并入时它们将被复制三份，而没有任何机制要求三份一致 ——
那是多分支的病换了个尺度，从跨分支缩到跨目录，并且失去「当场可见」：三份在各自的
构建里各编各的，互不参照。加一层目录之后根本不产生三份，也就不需要一道「这几份必须
逐字相同」的校验。

数据包资源同理放在这一层：目录名本身是版本专有的（1.21 把 `recipes` 改成 `recipe`、
`advancements` 改成 `advancement`、`tags/items` 改成 `tags/item`），但与加载器无关。

**搬错层由各层自己的闸接住。** 带加载器阻断的进了中间层，
`verifyVersionLayerIsLoaderNeutral` 报红并指出是哪一条判据、落在哪个轴上；
任何有阻断的进了 `shared/`，`verifySharedIsTargetNeutral` 报红。
另有一道单独的闸拦「记在甲里的类型出现在 `shared/` 下」—— 它**只认 `shared/`**，
搬进中间层是正常动作，不拦。

后面的清单按轴分组，分组即数据。

<!-- 下面这段由 ./gradlew updateSeamsDoc 生成，别手改：甲 -->

#### 仅版本轴（0）

同一 Minecraft 版本的三个加载器可逐字共用；与 1.20.1 之间必然分叉。

（无）

#### 仅加载器轴（16）

同一加载器的各个 Minecraft 版本可共用；三个加载器之间必然分叉。

- `MCphone` —— 加载器导入
- `core.ServerConfig` —— 加载器导入
- `core.client.AppHotkeys` —— 加载器导入
- `core.client.ClientConfig` —— 加载器导入
- `core.client.MCphoneKeyBindings` —— 加载器导入
- `core.menu.ModMenus` —— 加载器导入
- `core.net.NetworkHandler` —— 加载器导入
- `feature.chat.ChatImageStore` —— 加载器导入
- `feature.chat.client.ChatImageSender` —— 加载器导入
- `feature.chat.net.ChatNetworking` —— 加载器导入
- `feature.music.client.playback.LocalPlayback` —— 加载器导入
- `feature.music.net.MusicNetworking` —— 加载器导入
- `feature.settings.client.AppManagerDetail` —— 加载器导入
- `feature.store.net.StoreNetworking` —— 加载器导入
- `feature.terminal.integration.Terminals` —— 加载器导入
- `platform.ModPresence` —— 加载器导入

#### 两轴都有（4）

六个目标各不相同。

- `core.PhonePlayerData` —— 注入的方法
- `core.net.MCphoneNetwork` —— 1.20.5+ 原版、加载器导入
- `feature.music.DiscService` —— 1.20.5+ 原版、加载器导入
- `feature.terminal.TerminalCharger` —— 加载器导入、注入的方法

<!-- 甲 结束 -->

### 契约

这一类的**全限定名不能变**：`shared/` 里的调用点写的就是这个名字，编译时由各平台
的源码集接进来（`sourceSets.main.java.srcDir '../../shared/src/main/java'`）。改名
等于把所有调用点一起改，而那正是这一层要避免的事。

签名以本仓现有的 NeoForge 1.21.1 实现为准 —— 新平台照着它写，别反过来改它去迁就
新平台。真要改签名，两边一起改，并且 `shared/` 里的调用点跟着改，一次提交做完。

## 乙 · 自动判据没找到阻断

**这不是「可以搬」的清单**，见上面第 3 层盲区。

这一类中绝大多数位于客户端渲染路径下，而第 3 层盲区（方法名不变、签名变更）正集中
在那里 —— 即「乙」这张单子几乎整个落在判据自己声明的失效范围内。按清单从第一条
往下搬，恰好先撞上最需要人工核对的一批，因此下面把客户端路径单独分组。

<!-- 下面这段由 ./gradlew updateSeamsDoc 生成，别手改：乙 -->

#### 非客户端（1）

判据在这批上相对可信。

- `compat.WaystonesCompat`

#### 客户端渲染路径（18）

**判据在这批上最不可信** —— 签名漂移正集中在这里，逐个人工核过再搬。

- `api.client.app.IPhoneApp`
- `core.client.GuiUtil`
- `core.client.ImageFolder`
- `core.client.PhoneScreen`
- `core.client.PhoneScreenOpener`
- `core.client.PlayerAvatar`
- `feature.camera.client.CameraFlash`
- `feature.camera.client.CameraMode`
- `feature.gallery.client.Gallery`
- `feature.music.client.NetSongSound`
- `feature.music.client.playback.OggDecoder`
- `feature.reader.client.compat.BookQuirk`
- `feature.reader.client.compat.BookQuirks`
- `feature.reader.client.source.BookSources`
- `feature.reader.client.source.ExternalBookSource`
- `feature.reader.client.source.GuideMeSource`
- `feature.reader.client.source.PatchouliSource`
- `feature.settings.client.PhoneHudEditor`

<!-- 乙 结束 -->
