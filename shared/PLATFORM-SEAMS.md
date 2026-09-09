# 平台接缝清单

`platforms/<目标名>/` 下的每一个 java 文件都在本文的清单中，按**共用代码引不引用它**
分为两组：

| 组 | 含义 | 新平台的处理方式 |
|---|---|---|
| **共用代码引用了的** | `shared/` 或某个共享层里出现了这个全限定名 | **必须提供同名类型**，否则共用代码编译失败 |
| **平台内部的** | 只在该平台自己的代码里用到 | 不必提供同名类型 |

这个分组是**编译期事实**，不是判断：前者由「共用代码里有没有这个 import / 同包简单名」
算出来，答错了当场编不过。

### 这里原先还有一个「甲 / 乙」的分类，已经删掉

那个二分说的是「这是有意的接缝」还是「只是还没搬」，而做判断的是下文那四条判据 ——
**它们看不出来**。四个门面（`platform.Slots` / `platform.StackCodecs` /
`platform.CuriosInventories` / `platform.client.Draw`）判据一条都不中，于是全被归进
「还没搬」，而它们恰恰是最典型的有意接缝；反过来「必须提供」与「不得重新实现」
曾经同时挂在同一批条目上 —— 六十条。

现在只报事实：共用代码引不引用它（编译期可查），以及它命中了哪几条判据（证据，
不是判决）。该抄一份还是该自己写，打开那个文件看。

---

## 共用范围的层次

代码按共用范围分层，各层的准入条件不同：

| 目录 | 供谁使用 | 层中不得出现 | 校验任务 |
|---|---|---|---|
| `shared/` | 全部目标 | 四条判据均不得命中；第三方模组 API 须在声明表内 | `verifySharedIsTargetNeutral`<br>`verifySharedThirdPartyImports` |
| `layers/version/<层名>/` | 挂载该层的目标 | 绑定加载器的构造 | `verifyLayer<层名>` |
| `layers/loader/<层名>/` | 挂载该层的目标 | 绑定 Minecraft 版本的构造 | `verifyLayer<层名>` |
| `layers/both/<层名>/` | 挂载该层的目标 | —— （两个轴均已钉住） | `verifyLayer<层名>` |
| `platforms/<目标名>/` | 单个目标 | —— | —— |

中间三层的存在理由，以版本层为例：仅受版本约束的类型（手写编解码、各类网络包）与加载器无关。
若无该层，第二个同版本目标并入时它们将被复制三份，而无任何机制要求三份一致 ——
即按分支划分的同一问题缩小到目录之间，且失去「构建中直接暴露」这一性质。
设此层后不产生副本，也就无须一道「三份必须逐字相同」的校验。

迁错层由各层自身的校验接住：带加载器阻断的类型进入版本层，该层的 `verifyLayer<层名>`
报错并指出命中哪条判据、落在哪个轴；带任何阻断的类型进入 `shared/`，
`verifySharedIsTargetNeutral` 报错。`verifySharedIsTargetNeutral` 只认 `shared/` —— 迁入共享层是正常动作，不予拦截。

### 层由配置文件声明

- [`versions/layers.json`](../versions/layers.json) —— 有哪些层，各自的目录、谓词与状态
- [`versions/targets.json`](../versions/targets.json) —— 每个目标的 `layers` 字段

新增目标、新增层均只改 JSON，构建脚本不必改动。解析与校验集中在
`gradle/mcphone-layers.gradle`，由各平台的 `build.gradle` 先行 `apply`。

### 层是对目标的谓词

`since` 钉住版本，`loader` 钉住加载器。**该层禁哪个轴，取决于哪个轴未被钉住**：

| 声明 | 目录 | 供谁共用 | 层中不得出现 |
|---|---|---|---|
| 仅 `since` | `layers/version/` | 该批版本的三个加载器 | 绑定加载器的构造 |
| 仅 `loader` | `layers/loader/` | 该加载器的各个版本 | 绑定版本的构造 |
| 二者兼有 | `layers/both/` | 该加载器 × 该批版本 | ——（两个轴均已钉住） |
| 二者皆无 | —— | 全部目标 | 二者皆禁 —— 那即是 `shared/`，不是层 |

前两行对称：各自容纳的正是对方挡下的那一半。第三行为**交集层**，容纳既绑加载器
又绑版本的构造。二者皆无则不成其为层，构建与 CI 均拦。

此表因而是完整的：自「一个轴都不钉」（`shared/`）至「钉死单个目标」
（`platforms/<目标名>/`），中间三格即这三种层。

交集层当前无占用者 —— 现有两个目标既不共用加载器，也不共用版本档。规则先行放开，
目录待首个占用者出现时再建，不预置空目录。

版本层的层名为「该套 API **自哪个 Minecraft 版本起**存在」，而非某一具体版本：
1.21.1 与后续更高版本同挂 `1.20.5+`，因其内容相同。写成具体版本号，则每增一个
Minecraft 版本便多一层，而那些层的内容一致。

`layers` 是数组，层可组合，因此应按**单条**边界划分。将「某版本之后的全部内容」
并为一层，处于两条边界之间的目标即被挡在门外，那批代码只能退回各目标下重复实现 ——
而消除此类复制正是层存在的理由。**宁可层多：层多不产生成本，复制才产生成本。**

### `docs/` 同样分层

`docs/` 下是带 `main()` 的断言测试与附属接口文档的可编译副本，由**每个目标**编译并运行。
它原先只有仓库级一份，而其中数份系照 1.21.1 写成 —— 1.20.1 目标并入后，主源码编译通过，
余下 100 个错误全在 `docs/`。

分层判据是**被测对象住在哪一层**，而非测试文件自身有无版本标记：
`ChatMessageCodecTest` 与 `PhoneLocationCodecTest` 自身一个标记也没有，但其被测类位于
`1.20.5+` 层，1.20.1 不挂载该层，自然找不到。

因此每层均可有自己的 `docs/`，按 `targets.json` 的 `layers` 接入；仓库根的 `docs/`
只留与全部目标都成立的那几份。「该层有无内容」的判定亦计入 `docs/`——
`neoforge` 层当前正是只有 `docs/`。

> **跨层的调用关系会产生覆盖真空。** `Wire.writeBytes` / `readBytes` 的调用方仅在
> Forge 1.20.1，而能覆盖它们的编解码测试住在 `1.20.5+` 层，1.20.1 不挂载 ——
> 一边有调用方无测试，一边有测试无调用方，中间一格是空的。分层之后此形态会重复出现：
> 补测试时先问一句「哪个目标会跑到它」。该例已补 `docs/WireBytesTest.java`，
> 放在中立的 `docs/` 下，每个目标都跑。

### 待办：覆写签名，helper 门面接不住

已有的门面（`ModPresence` / `Slots` / `StackCodecs` / `CuriosInventories` /
`Draw`）都是**静态 helper**：调用点换成一句门面调用，差别关进方法体。这一招对
「调用某个换了签名的方法」有效，对**覆写某个换了签名的方法**无效 ——
子类的方法签名必须与父类一致，没有中间层可插。

现存两处：

| 位置 | 差异 | 波及 |
|---|---|---|
| `Screen.mouseScrolled` | 1.21 多一个 `scrollX` 参数 | `PhoneScreen`、`BrowserScreen`、`PhoneHudEditor` |
| `SavedData.save` | 1.20.5 起多收 `HolderLookup.Provider` | `ChatData`、`FriendData` |

**做法是平台侧的抽象基类**：基类替各目标写那个覆写，转调一个中立的抽象方法，
子类只实现中立那一半，于是子类可以进共用层。

没有立刻做，是因为第一处会动到 `PhoneScreen`——一千四百行的中心类，
它的 `mouseScrolled` 里串着十几个页面的分发。那一刀值得单独做、单独验，
不该跟别的改动混在一次提交里。

在那之前，这五个类留在各自该在的层/平台，**不是判据没看出来，是有意留的**。

### 现有的层

| 层 | 种类 | 边界 |
|---|---|---|
| `1.20.5+` | 版本层 | 1.20.5 起原版才有 `net.minecraft.network.codec`、`CustomPacketPayload`、数据组件 |
| `1.21+` | 版本层 | 数据包格式 48 将目录名由复数改为单数（`recipes`→`recipe` 等） |
| `forge` | 加载器层 | Forge 专有 |
| `neoforge` | 加载器层 | NeoForge 专有 |
| `fabric` | 加载器层 | Fabric 专有，尚未开始填（`populated: false`） |

**此处不记条数。** 每层的文件数由 `verifyLayer<层名>` 在每次构建时打印
（「共享层 1.20.5+ 校验通过：56 个文件」），那是算出来的；抄进本表的数字无人维护，
而本文正文正是在论证手写清单会烂 —— 这张表此前写 `1.21+` 4 个、两个加载器层各 5 个，
实际是 1 个与各 6 个，在三个提交里烂掉了。

进加载器层的条件是**证出来的**：其正文跨 1.20.1 与 1.21.1 **逐字相同**，
差异仅在加载器 `import` —— 即同一段代码已在两个 Minecraft 版本上各自编译通过，
版本中立系证得，余下那一行 `import` 正是加载器轴。

前两层是「按单条边界划分」的实例。目录改名发生于 1.21，与 1.20.5 那条 codec/组件
边界不是同一条，故分为两层：数据包文件若并入 `1.20.5+`，一个 1.20.6 的目标挂载该层
后将取得单数目录名，而其所需为复数 —— **且不报错**，那条配方只是不存在。

数据包目录改名表在 [`versions/datapack-renames.json`](../versions/datapack-renames.json)，
共 13 条，Gradle 与 CI 读同一份。该文件的注释说明了作用域收到何处为止，并区分
「已核实、不属改名范围」与「未核实」两类。

`fabric` 层为空（`populated: false`），机制先行建立，内容待定：本仓尚无 Fabric 目标，
没有可供逐文件比对的第二方，无从判断某段代码在 Fabric 的两个版本之间是否一致。
待第一个 Fabric 目标并入后，按上述同一办法处理 —— 逐文件比对，**逐字相同者方可迁入**。

`layers/both/` 下目前一个层也没有：现有两个目标既不共用加载器，也不共用版本档，
交集层没有占用者。规则先行放开，目录待第一个占用者出现时再建。

### `populated`

每层须声明 `populated`，即该层当前是否应有内容。声明为有却扫不到文件时报错，
声明为无却扫到内容时同样报错。「空」是显式声明的状态，而非默认容忍 —— 否则本应有
内容的层被清空、或层名与目录名对不上而指向一个恰好存在的空目录，都只会在日志中留下一行。

`populated: false` 的层在检出中可以完全没有目录（git 不跟踪空目录），这是正常的。

---

## 判据

判据是「该类中是否存在其他目标上不存在的构造」，共四条。它是 `shared/` 那道闸的判据，
本文只把命中结果当注解印出来。与
`verifySharedIsTargetNeutral` **共用同一份定义**（`TargetNeutrality`）：

| 判据 | 版本轴 | 加载器轴 |
|---|:---:|:---:|
| 1.20.5+ 原版类型 | ● | |
| `ItemStack` 的组件读写 | ● | |
| 加载器导入 | | ● |
| 注入到原版类型上的方法 | ● | ● |

**版本轴**指「在 1.21.1 上成立、在 1.20.1 上不成立，与加载器无关」；**加载器轴**指
「绑定于某一加载器，与 Minecraft 版本无关」。

一条判据可同时落在两个轴上。`player.getData(...)` 即是：Data Attachment 仅 NeoForge
提供（加载器轴），而它自 NeoForge **20.3** 引入（版本轴）—— 20.1.x 那条线即
Minecraft 1.20.1，在其之前，那一侧只能使用能力。`getCapability(...)` 同理。
因此 `PhonePlayerData` 与 `TerminalCharger` 是「两轴都有」，而非「仅加载器轴」。

仅受版本轴阻断的类型，在同一 Minecraft 版本的三个加载器目标间可逐字共用。

### 三层盲区

1. **导入** —— `net.neoforged` / `net.minecraftforge` / `net.fabricmc`。检查 import 即可。
2. **注入到原版类型上的方法** —— `player.getData(...)`（NeoForge 装在 `Entity` 上）、
   `stack.get(ModDataComponents...)`（1.20.5+ 装在 `ItemStack` 上）、
   `getCapability(...)`（Forge）。**这些不以 import 出现，仅检查导入语句无从发现。**
3. **签名漂移** —— 方法名不变而签名变更，客户端渲染一类尤多。名称与导入均无差异，
   只有以另一版本实际编译才能发现。**这一层目前无法覆盖。**

因此：

> **一条判据都不中，只意味着「自动判据未找到阻断」，不意味着「确认可以迁移」。**

将一个类型迁入 `shared/`，仍须人工核对，并通过 `verifySharedIsTargetNeutral` 与
两个目标的实际编译。

### 枚举基准是全量

清单枚举 `platforms/` 下的全部文件，而非仅被共用代码引用者；引用来源为 `shared/`
**与所有共享层**。两处都放宽是必要的，否则两类文件不出现在任何一类中：

- **从未被引用的**。它们可能本就应迁入某个层，却因未被引用而不进入视野。
- **仅被共享层引用的**。`core.net.Wire` 曾被 `1.20.5+` 层中两个文件 `import` 而不被
  `shared/` 引用；第二个同版本目标挂载该层后即编译失败，届时只能就地复制 ——
  而这正是层要消除的复制。

现在 `platforms/` 下的每个文件都必然出现在上面两组之一中。

---

## 1.21.1-neoforge

算的是该目标 `platforms/1.21.1-neoforge/` 下的类型。

### 契约

**共用代码引用了的**那一组，其**全限定名不可变更**：共用代码中的调用点写的就是该名称，
编译时由各平台的源码集接入。改名等于同时改动全部调用点，而避免此事正是这一层存在的目的。

签名以本仓现有的 NeoForge 1.21.1 实现为准。新平台照此实现，不得反向修改它以迁就
新平台。确需变更签名时，两侧与共用代码中的调用点一并修改，于一次提交内完成。

**方法体是抄现成的一份还是自己写，本文不作判断** —— 打开那个文件看。判据命中记在条目
后面，那是证据不是判决：命中「加载器导入」通常意味着必须自己写；一条都不中也不等于
可以直接抄，签名漂移那一层判据看不见。

<!-- 下面这段由 ./gradlew updateSeamsDoc 生成，别手改：1.21.1-neoforge -->

#### 共用代码引用了的（35）

**新平台必须提供同名类型**，否则 shared/ 与共享层编不过。全限定名是硬约束；方法体是抄现成的一份还是自己写，看那个文件。

- `MCphone`　—— 加载器导入
- `compat.WaystonesCompat`
- `core.ModDataComponents`　—— 1.20.5+ 原版、加载器导入
- `core.ModItems`　—— 加载器导入
- `core.ModSounds`　—— 加载器导入
- `core.PhonePlayerData`　—— 注入的方法
- `core.ServerConfig`　—— 加载器导入
- `core.client.AppHotkeys`　—— 加载器导入
- `core.client.ClientConfig`　—— 加载器导入
- `core.client.PhoneHud`　—— 加载器导入
- `core.menu.ModMenus`　—— 加载器导入
- `core.net.MCphoneNetwork`　—— 1.20.5+ 原版、加载器导入
- `core.net.NetworkHandler`　—— 加载器导入
- `feature.camera.client.CameraFlash`
- `feature.music.DiscService`　—— 1.20.5+ 原版、加载器导入
- `feature.music.net.MusicNetworking`　—— 加载器导入
- `feature.notes.client.NoteEditor`
- `feature.terminal.TerminalCharger`　—— 加载器导入、注入的方法
- `feature.terminal.integration.Terminals`　—— 加载器导入
- `feature.terminal.integration.ae2.Ae2Integration`
- `feature.terminal.integration.refinedstorage.RefinedStorageIntegration`
- `feature.terminal.integration.refinedstorage.TerminalSlotReference`
- `platform.CuriosInventories`
- `platform.ModPresence`　—— 加载器导入
- `platform.Slots`
- `platform.StackCodecs`
- `platform.client.CameraGui`
- `platform.client.DiscSongs`
- `platform.client.Draw`
- `platform.client.EditBoxes`
- `platform.client.KeyModifiers`　—— 加载器导入
- `platform.client.PhoneScreenBase`
- `platform.client.PlayerSkins`
- `platform.client.SystemFiles`
- `platform.client.VanillaAudio`

#### 平台内部的（14）

只在这个平台自己的代码里用到。新平台不必提供同名类型。

- `MCphoneClient`　—— 加载器导入
- `api.client.ui.PhoneMultiLineEditBox`
- `compat.CompatModules`　—— 加载器导入
- `compat.IntegratedDynamicsCompat`　—— 加载器导入
- `core.ModAttachments`　—— 加载器导入
- `core.ModCreativeTabs`　—— 加载器导入
- `feature.camera.client.CameraHandler`　—— 加载器导入
- `feature.chat.net.ChatNetworking`　—— 加载器导入
- `feature.notes.net.NotesNetworking`　—— 加载器导入
- `feature.store.net.StoreNetworking`　—— 加载器导入
- `feature.terminal.integration.ae2.Ae2wtlibSupport`
- `feature.terminal.integration.ae2.TerminalSlotLocator`
- `feature.terminal.net.TerminalNetworking`　—— 加载器导入
- `platform.client.ClientTicks`　—— 加载器导入

<!-- 1.21.1-neoforge 结束 -->

---

## 1.20.1-forge

这一节与上面同构，算的是该目标 `platforms/1.20.1-forge/` 下的类型。
两个目标的数字不同是正常的：1.20.1 不挂 `1.20.5+` 与 `1.21+` 两层，那些类型它自己带一份。

<!-- 下面这段由 ./gradlew updateSeamsDoc 生成，别手改：1.20.1-forge -->

#### 共用代码引用了的（72）

**新平台必须提供同名类型**，否则 shared/ 与共享层编不过。全限定名是硬约束；方法体是抄现成的一份还是自己写，看那个文件。

- `MCphone`　—— 加载器导入
- `compat.WaystonesCompat`
- `core.ModItems`　—— 加载器导入
- `core.ModSounds`　—— 加载器导入
- `core.PhoneItem`
- `core.PhoneItemData`
- `core.PhoneLocation`
- `core.PhonePlayerData`　—— 加载器导入
- `core.ServerConfig`　—— 加载器导入
- `core.client.AppHotkeys`　—— 加载器导入
- `core.client.ClientConfig`　—— 加载器导入
- `core.client.PhoneHud`　—— 加载器导入
- `core.menu.ModMenus`　—— 加载器导入
- `core.net.MCphoneNetwork`　—— 加载器导入
- `core.net.NetworkHandler`
- `core.net.PhoneScreenOnPacket`
- `feature.camera.client.CameraFlash`
- `feature.chat.ChatData`
- `feature.chat.ChatMessage`
- `feature.chat.FriendData`
- `feature.chat.ImageBody`
- `feature.chat.TextBody`
- `feature.chat.net.ConversationSummary`
- `feature.chat.net.FriendRequestPacket`
- `feature.chat.net.MarkReadPacket`
- `feature.chat.net.OnlinePlayer`
- `feature.chat.net.Relation`
- `feature.chat.net.RemoveFriendPacket`
- `feature.chat.net.RequestChatImagePacket`
- `feature.chat.net.RequestConversationsPacket`
- `feature.chat.net.RequestMessagesPacket`
- `feature.chat.net.RequestOnlinePlayersPacket`
- `feature.chat.net.RespondFriendRequestPacket`
- `feature.chat.net.SendChatImagePacket`
- `feature.chat.net.SendChatMessagePacket`
- `feature.chat.net.TeleportToFriendPacket`
- `feature.enderchest.net.OpenEnderChestPacket`
- `feature.music.DiscService`　—— 加载器导入
- `feature.music.NetSong`
- `feature.music.net.DiscActionPacket`
- `feature.music.net.MusicNetworking`
- `feature.music.net.OpenDiscBayPacket`
- `feature.notes.Note`
- `feature.notes.NotePrinter`
- `feature.notes.NoteSummary`
- `feature.notes.client.NoteEditor`
- `feature.notes.net.PrintNotePacket`
- `feature.notes.net.RequestNoteListPacket`
- `feature.settings.net.SetDeviceNamePacket`
- `feature.settings.net.SetWallpaperPacket`
- `feature.store.PurchasedApps`
- `feature.store.net.PurchaseAppPacket`
- `feature.store.net.RequestPurchasedAppsPacket`
- `feature.terminal.TerminalCharger`　—— 加载器导入、注入的方法
- `feature.terminal.integration.Terminals`　—— 加载器导入
- `feature.terminal.integration.ae2.Ae2Integration`
- `feature.terminal.integration.refinedstorage.RefinedStorageIntegration`
- `feature.terminal.net.TerminalActionPacket`
- `feature.waystone.net.OpenWaystoneSelectionPacket`
- `platform.CuriosInventories`
- `platform.ModPresence`　—— 加载器导入
- `platform.Slots`
- `platform.StackCodecs`
- `platform.client.CameraGui`
- `platform.client.DiscSongs`
- `platform.client.Draw`
- `platform.client.EditBoxes`
- `platform.client.KeyModifiers`　—— 加载器导入
- `platform.client.PhoneScreenBase`
- `platform.client.PlayerSkins`
- `platform.client.SystemFiles`
- `platform.client.VanillaAudio`

#### 平台内部的（35）

只在这个平台自己的代码里用到。新平台不必提供同名类型。

- `MCphoneClient`　—— 加载器导入
- `compat.CompatModules`　—— 加载器导入
- `compat.IntegratedDynamicsCompat`　—— 加载器导入
- `compat.WaystonesWarpItemModule`　—— 加载器导入
- `core.ModCapabilities`　—— 加载器导入、注入的方法
- `core.ModCreativeTabs`　—— 加载器导入
- `core.PhoneScreenOnCleanup`　—— 加载器导入
- `feature.camera.client.CameraHandler`　—— 加载器导入
- `feature.chat.MessageBody`
- `feature.chat.MessageKind`
- `feature.chat.net.ChatImageDataPacket`
- `feature.chat.net.ChatNetworking`
- `feature.chat.net.NewMessagePacket`
- `feature.chat.net.SyncConversationsPacket`
- `feature.chat.net.SyncMessagesPacket`
- `feature.chat.net.SyncOnlinePlayersPacket`
- `feature.music.net.PlayNetSongPacket`
- `feature.music.net.StopNetSongPacket`
- `feature.music.net.SyncDiscStatePacket`
- `feature.notes.net.DeleteNotePacket`
- `feature.notes.net.NotesNetworking`
- `feature.notes.net.RequestNotePacket`
- `feature.notes.net.SaveNotePacket`
- `feature.notes.net.SyncNoteListPacket`
- `feature.notes.net.SyncNotePacket`
- `feature.settings.net.SyncWallpaperPacket`
- `feature.store.net.StoreNetworking`
- `feature.store.net.SyncPurchasedAppsPacket`
- `feature.terminal.TerminalSlotSync`　—— 加载器导入
- `feature.terminal.client.TerminalSlotClient`
- `feature.terminal.integration.ae2.Ae2wtlibSupport`
- `feature.terminal.integration.ae2.TerminalSlotLocator`
- `feature.terminal.net.SyncTerminalSlotPacket`
- `feature.terminal.net.TerminalNetworking`
- `platform.client.ClientTicks`　—— 加载器导入

<!-- 1.20.1-forge 结束 -->
