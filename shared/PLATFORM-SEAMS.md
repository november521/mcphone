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

后面跟的是判据在这个类里找到的阻断。

<!-- 下面这段由 ./gradlew updateSeamsDoc 生成，别手改：甲 -->

- `MCphone` —— 加载器导入
- `core.PhoneItemData` —— 组件读写
- `core.PhoneLocation` —— 1.20.5+ 原版
- `core.PhonePlayerData` —— 注入的方法
- `core.ServerConfig` —— 加载器导入
- `core.client.ClientConfig` —— 加载器导入
- `core.menu.ModMenus` —— 加载器导入
- `core.net.MCphoneNetwork` —— 1.20.5+ 原版、加载器导入
- `feature.chat.ChatImageStore` —— 加载器导入
- `feature.chat.ChatMessage` —— 1.20.5+ 原版
- `feature.chat.ImageBody` —— 1.20.5+ 原版
- `feature.chat.TextBody` —— 1.20.5+ 原版
- `feature.chat.client.ChatImageSender` —— 加载器导入
- `feature.chat.net.ConversationSummary` —— 1.20.5+ 原版
- `feature.chat.net.OnlinePlayer` —— 1.20.5+ 原版
- `feature.chat.net.Relation` —— 1.20.5+ 原版
- `feature.enderchest.net.OpenEnderChestPacket` —— 1.20.5+ 原版
- `feature.music.DiscService` —— 1.20.5+ 原版、加载器导入
- `feature.music.NetSong` —— 1.20.5+ 原版
- `feature.music.net.MusicNetworking` —— 加载器导入
- `feature.notes.Note` —— 1.20.5+ 原版
- `feature.notes.NoteSummary` —— 1.20.5+ 原版
- `feature.terminal.TerminalCharger` —— 加载器导入、注入的方法
- `feature.terminal.integration.Terminals` —— 加载器导入
- `feature.terminal.integration.refinedstorage.TerminalSlotReferenceFactory` —— 1.20.5+ 原版
- `platform.ModPresence` —— 加载器导入

<!-- 甲 结束 -->

### 契约

这一类的**全限定名不能变**：`shared/` 里的调用点写的就是这个名字，编译时由各平台
的源码集接进来（`sourceSets.main.java.srcDir '../../shared/src/main/java'`）。改名
等于把所有调用点一起改，而那正是这一层要避免的事。

签名以本仓现有的 NeoForge 1.21.1 实现为准 —— 新平台照着它写，别反过来改它去迁就
新平台。真要改签名，两边一起改，并且 `shared/` 里的调用点跟着改，一次提交做完。

## 乙 · 自动判据没找到阻断

**这不是「可以搬」的清单**，见上面第 3 层盲区。

<!-- 下面这段由 ./gradlew updateSeamsDoc 生成，别手改：乙 -->

- `api.client.app.IPhoneApp`
- `api.client.ui.PhoneCanvas`
- `compat.WaystonesCompat`
- `core.client.ImageCodec`
- `core.client.ImageFolder`
- `core.client.PhoneApp`
- `core.client.PhoneScale`
- `core.client.PhoneScreen`
- `core.client.PhoneScreenOpener`
- `core.client.PhoneScreenRegistry`
- `core.client.PhoneSkin`
- `core.client.PlayerAvatar`
- `feature.music.client.playback.PcmAudioStream`
- `feature.music.client.source.LocalFileSource`
- `feature.music.client.source.MusicSource`
- `feature.reader.client.source.ExternalBookSource`
- `feature.reader.client.source.PatchouliSource`
- `feature.store.AppPriceRegistry`

<!-- 乙 结束 -->
