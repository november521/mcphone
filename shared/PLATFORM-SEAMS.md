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

### 未决：中间层

当前结构只有一层共享目录，各平台以一行 `srcDir '../../shared/src/main/java'` 接入。
没有按版本或按加载器的中间层。

后果在第二个 1.21.1 目标并入时发生：仅版本轴的那一批将在
`platforms/1.21.1-fabric/` 下复制一份，`platforms/1.21.1-forge/` 下再复制一份，
而**没有任何机制要求这三份保持一致**。这是多分支的病换了个尺度 —— 从跨分支缩到
跨目录，并且失去了「当场可见」：三份在各自的构建里各编各的，互不参照。

两条路，尚未选定：

| 方案 | 做法 | 代价 |
|---|---|---|
| 加一层按版本的共享目录 | `shared-<mc 版本>/`，该版本的三个目标各加一行 `srcDir` | 目录层级多一级；不需要新的闸，因为只有一份 |
| 接受复制，加闸约束 | 三份保留，另加一道「这几份必须逐字相同」的校验 | 闸本身要维护，且复制仍是复制 |

**眼下不必动**：只有一个目标建得起来，一份都还没复制。这一节的作用是在复制发生
之前把选择摆出来 —— 复制一旦发生，撤回的成本就不是改一行 `srcDir` 了。

后面的清单按轴分组，分组即数据。

<!-- 下面这段由 ./gradlew updateSeamsDoc 生成，别手改：甲 -->

#### 仅版本轴（30）

同一 Minecraft 版本的三个加载器可逐字共用；与 1.20.1 之间必然分叉。

- `core.PhoneItemData` —— 组件读写
- `core.PhoneLocation` —— 1.20.5+ 原版
- `feature.chat.ChatMessage` —— 1.20.5+ 原版
- `feature.chat.ImageBody` —— 1.20.5+ 原版
- `feature.chat.TextBody` —— 1.20.5+ 原版
- `feature.chat.net.ChatImageDataPacket` —— 1.20.5+ 原版
- `feature.chat.net.ConversationSummary` —— 1.20.5+ 原版
- `feature.chat.net.FriendRequestPacket` —— 1.20.5+ 原版
- `feature.chat.net.MarkReadPacket` —— 1.20.5+ 原版
- `feature.chat.net.OnlinePlayer` —— 1.20.5+ 原版
- `feature.chat.net.Relation` —— 1.20.5+ 原版
- `feature.chat.net.RemoveFriendPacket` —— 1.20.5+ 原版
- `feature.chat.net.RequestChatImagePacket` —— 1.20.5+ 原版
- `feature.chat.net.RequestConversationsPacket` —— 1.20.5+ 原版
- `feature.chat.net.RequestMessagesPacket` —— 1.20.5+ 原版
- `feature.chat.net.RequestOnlinePlayersPacket` —— 1.20.5+ 原版
- `feature.chat.net.RespondFriendRequestPacket` —— 1.20.5+ 原版
- `feature.chat.net.SendChatMessagePacket` —— 1.20.5+ 原版
- `feature.chat.net.TeleportToFriendPacket` —— 1.20.5+ 原版
- `feature.enderchest.net.OpenEnderChestPacket` —— 1.20.5+ 原版
- `feature.music.NetSong` —— 1.20.5+ 原版
- `feature.notes.Note` —— 1.20.5+ 原版
- `feature.notes.NotePrinter` —— 1.20.5+ 原版、组件读写
- `feature.notes.NoteSummary` —— 1.20.5+ 原版
- `feature.notes.net.PrintNotePacket` —— 1.20.5+ 原版
- `feature.notes.net.RequestNoteListPacket` —— 1.20.5+ 原版
- `feature.store.PurchasedApps` —— 1.20.5+ 原版
- `feature.store.net.PurchaseAppPacket` —— 1.20.5+ 原版
- `feature.store.net.RequestPurchasedAppsPacket` —— 1.20.5+ 原版
- `feature.terminal.integration.refinedstorage.TerminalSlotReferenceFactory` —— 1.20.5+ 原版

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
