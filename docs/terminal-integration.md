# 「终端」App：三家存储模组的接入

这一页记的是**查实过的 API 入口**和**每一家的坑**，省得下次再翻一遍 jar。
为什么这么设计写在代码注释里（`feature/terminal/` 下每个类顶上都有），这里不重复。

代码位置：`src/main/java/com/november/mcphone/feature/terminal/`

---

## 一句话结构

```
TerminalApp.onPress()                     客户端：卡槽有终端就发 OPEN_TERMINAL，否则开卡槽界面
  └─ TerminalActionPacket
       └─ TerminalOpener.open(player)     服务端：卡槽 → 背包，挑一台
            └─ Terminals.owner(stack)     谁认领它
                 └─ integration.open(player, stack, 在哪儿)   调对方自己的入口
```

`Terminals` 是唯一的分派点，**它的字节码里不许出现任何外部模组的类型**。验一遍：

```bash
javap -c -p -classpath build/classes/java/main \
  com.november.mcphone.feature.terminal.integration.Terminals \
  | grep -oE "// (class|Method|Field) [^ ]+" \
  | grep -viE "mcphone|java/|net/minecraft|net/neoforged"
```

输出为空才算对。三家的 modid 是 `public static final String`，会被 javac 内联成 `ldc`
常量，所以 `Terminals` 引用它们**不会**触发那几个联动类的加载——没装的那一家一个类都碰
不到，这正是它不会 NoClassDefFoundError 的原因。

---

## 查实过的 API 入口

全部是从对应版本的 jar 里 `javap` 出来的，不是从文档抄的。**现在只用到每张表最上面
那两三行**，其余是哪天真要在手机里画点什么时用得上的。

### AE2 19.2.17（`maven.modrinth:ae2:19.2.17`）

| 要干的事 | 入口 | 在 api 包里？ |
| --- | --- | --- |
| **认出玩家的无线终端** | `appeng.items.tools.powered.WirelessTerminalItem` | ✗ |
| **打开它** | `openFromInventory(player, locator)` | ✗ |
| **说明它在哪儿** | `appeng.menu.locator.ItemMenuHostLocator` + `MenuLocators.register` | ✗ |
| 终端能看到的存储 | `appeng.api.storage.ITerminalHost.getInventory()` → `MEStorage` | ✓ |
| 连上没有 + 为什么（现成的 Component） | `appeng.api.storage.ILinkStatus` | ✓ |
| 物品/流体的统一表示 | `appeng.api.stacks.AEKey` / `AEItemKey` / `KeyCounter` | ✓ |
| 画一个 AEKey | `appeng.api.client.AEKeyRendering` | ✓ |
| 自动合成 | `appeng.api.networking.crafting.ICraftingService` 等 | ✓ |

⚠ AE2 的快捷键通道（`HotkeyPacket`）**替代不了我们这个包**：AE2WTLib 只注册了
restock / magnet / stow 三个快捷键，**没有**"打开终端"那一条。

⚠ 不能用 `MenuLocators.forStack`：它造的 `StackItemLocator` **压根没被注册**，一序列化
就抛 IllegalArgumentException。所以我们自己注册了一种（`PhoneSlotLocator`）。它的线上格式
是**类的全名字符串**——反过来说，**挪那个类的包名就等于改了线上格式**。

### AE2WTLib 19.5.1（`de.mari_023:ae2wtlib_api:19.5.1`，modmaven.dev）

| 要干的事 | 入口 |
| --- | --- |
| **认出它的终端 / 正确打开** | `de.mari_023.ae2wtlib.api.terminal.ItemWT` + `tryOpen(player, locator, false)` |
| 装了量子桥接卡吗 | `AE2wtlibAPI.hasQuantumBridgeCard(...)` |
| 是不是通用终端 | `AE2wtlibAPI.isUniversalTerminal(...)` |

⚠ **这一条不能省，而且错了不报错。** 它的终端也是 `WirelessTerminalItem` 的子类，但没
覆盖 0 参的 `getMenuType()`：走 AE2 的 `openFromInventory` 会开出**普通** ME 终端界面
（没有合成格），不崩、不报错，玩家只看到一个少了格子的界面。附属时代的 0.2.0 带着这个
bug 发过一版。教训：**"它继承同一个基类所以行为一样"是猜，不是查。**

### Refined Storage 2.0.9（`maven.modrinth:refined-storage:2.0.9`）

| 要干的事 | 入口 |
| --- | --- |
| **认出能被远程唤起的随身物件** | `common.api.support.slotreference.SlotReferenceHandlerItem` |
| **打开它** | `use(ServerPlayer, ItemStack, SlotReference)` |
| **说明它在哪儿** | `common.api.support.slotreference.SlotReference` |
| **注册一种位置** | `RefinedStorageApi.INSTANCE.getSlotReferenceFactoryRegistry().register(id, factory)` |
| 扫描"玩家身上哪些地方可能有" | `SlotReferenceProvider` + `addSlotReferenceProvider`（Curios 那条路走这个） |

谁实现了 `SlotReferenceHandlerItem`（2.0.9 实测）：`AbstractNetworkEnergyItem`（→ 无线
网格、无线自动合成监视器）、`PortableGridBlockItem`（便携网格）。**接口比继承稳**：别人给
RS 写的附属物件挂上这个接口也自动认得。

⚠ `RefinedStorageApi.INSTANCE` 是**代理**，delegate 由 RS 自己的初始化挂上。太早调拿到的
是空壳——所以注册放在 `FMLCommonSetup`，不放在模组构造里。

⚠ `InventorySlotReference` 的构造函数是**包内可见**的，对外只有 `of(Player, InteractionHand)`，
表达不了"背包第 n 格"。所以两种情况都用我们自己的 `PhoneSlotReference`，`-1` 表示卡槽。
它的线上格式是 **ResourceLocation**（`mcphone:phone_slot`），比 AE2 那边稳（挪包名不影响），
代价是**那个 id 一旦发出去就不能改**。

### Tom's Simple Storage 2.4.2（`maven.modrinth:toms-storage:1.21-2.4.2`）

| 要干的事 | 入口 |
| --- | --- |
| **认出它的无线终端** | `com.tom.storagemod.item.WirelessTerminal`（接口） |
| **问它能不能隔空开** | `canOpen(ItemStack)` |
| **打开它** | `open(Player, ItemStack)` |

⚠ **基础无线终端远程打不开**，而且是设计如此：

| | `canOpen(stack)` | `open(player, stack)` |
| --- | --- | --- |
| 无线终端（基础） | 恒为 `false` | **方法体是一句 `return`** |
| 高级无线终端 | 恒为 `true` | `activateTerminal(...)`，用物品上存的维度+坐标 |

基础那把的设计是"瞄准范围内的终端方块右键"（`use` 里是一次 `player.pick` 射线）。从手机上
点它，字节码层面就是什么都不会发生——不崩、不报错、没有任何反馈。这就是
`TerminalIntegration.canOpen` 这个方法存在的全部理由：卡槽不收它，背包里遇到也跳过并
告诉玩家原因。**用 `canOpen` 而不是 `instanceof AdvWirelessTerminalItem`**：前者是 Tom's
自己给的答案，它哪天让基础终端也支持远程，我们跟着变。

⚠ **两个版本号不一样**：maven 坐标上是 `1.21-2.4.2`，`mods.toml` 里自报的是 `2.4.2`，
依赖范围比的是后者。写混了的表现是"装了它却说没装"。

---

## 许可：什么能抄，什么只能调

| | 许可 | 我们能做什么 |
| --- | --- | --- |
| AE2 的 `appeng.api` 包 | **MIT** | 调用、抄代码都行 |
| AE2 其余部分（`appeng.items`、`appeng.menu`…） | **LGPLv3** | 调用随便调，别整段复制实现 |
| AE2 的贴图与模型 | **CC BY-NC-SA 3.0** | **一张都不能抄** |
| AE2WTLib（含 api 模块） | **MIT** | 调用、抄代码都行 |
| Refined Storage 2 | **MIT** | 调用、抄代码都行 |
| Tom's Simple Storage | **MIT** | 调用、抄代码都行 |

一句话：**调用没有限制；复制代码只从 MIT 的地方复制；贴图一张都不碰。**「终端」那张
App 图标是自己画的。

---

## 怎么测

### 一、不需要真的搭起一个存储网络

| 做什么 | 该看到什么 |
| --- | --- |
| 身上什么都不带，点「终端」 | 卡槽界面，槽是空的 |
| 拿一台**没绑定**的终端再点 | **那一家自己的**报错（AE2 的 `Device is not linked.` 之类） |
| 把它拖进卡槽，点「打开终端」 | 同上——卡槽这条路走的是我们注册的位置 |
| 关掉界面直接点「终端」 | 同上，卡槽优先 |
| 按住 **Shift** 点「终端」 | 卡槽界面，里面有那台终端 |

**看到对方自己的话就说明这一格是好的**——打不开的原因由它解释，不由我们编。拿着终端点了
**什么都没发生**才是 bug（Tom's 基础无线终端除外）。

### 二、各家单独验一次

| 模组 | 拿什么 | 该看到 |
| --- | --- | --- |
| AE2 | `ae2:wireless_terminal` | `Device is not linked.` |
| AE2WTLib | 无线**合成**终端 | 开出来的界面**有合成格**（没有＝走错入口了） |
| RS | `refinedstorage:wireless_grid` | RS 自己的没绑定/没电提示（验"没电"那一半要么从背包里开，要么先关 `terminalKeepPowered`） |
| RS | 便携网格 | 它自己的界面直接开出来 |
| Tom's | **高级**无线终端（先右键终端方块绑定） | 那个终端方块的界面 |
| Tom's | **基础**无线终端 | 放不进卡槽；背包里带着点「终端」→ 一句"不能隔空打开" |

### 三、"一家都没装"要单独验

三家全是软前置，所以这是**真会发生**的情况。把 `build.gradle` 里那三条 `localRuntime`
临时注掉再 `runClient`：

- 游戏正常启动，不崩（三条依赖都是 optional）
- 日志里有 `一家存储模组都没装，「终端」App 不会出现在主屏上`
- 主屏与商店里都**没有**这一格；「设置 → 关于」的联动模组列表里三家都列着、都标着没装

### 四、边界

- 终端放**副手**、**盔甲栏**都该能开（扫的是完整 41 格）
- 身上两台、卡槽空着 → 背包顺序第一台（快捷栏优先）；卡槽装了 → **永远开卡槽那台**
- 身上**两家的终端各一台** → 背包顺序第一台，不是"先装的那家优先"。要指定就装进卡槽
- 卡槽里的终端**用一会儿看电量**：真的在掉才对（掉不了＝写在副本上了）。
  ⚠ 这一条现在必须先把服务端配置里的 `terminalKeepPowered` **关掉**再验——开着的时候
  手机每秒把它补满（见 `TerminalCharger`），"电量不掉"是正常的，正好盖住了当年那个
  "写在副本上"的 bug 的唯一症状
- `terminalKeepPowered` 开着（默认）时反过来验一次：把终端**用到没电**再装进卡槽，
  点「终端」照样开得起来（开的那一刻补满）；开着界面连点一轮 RS 的存取，电量条不该见底。
  注意**界面全关着时才不补**：装好之后把所有界面关掉站着不动，电量不会自己往上涨。
  但**从卡槽界面拖进去的那一下例外**——那会儿卡槽界面本身就开着，一秒内就满了，不是 bug
- 拿 Tom's 的高级无线终端装进卡槽：不该有任何变化（它没有能量能力，充电这条路直接跳过）
- 装着终端退出重进、死一次：都还在
- 专用服务器连一次：卡槽界面里看得见那台终端（附件同步生效）
- 卡槽装着终端时，各家自己的快捷键找不到它了——**这是必然结果，不是 bug**

---

## 想再加一家要做什么

1. `feature/terminal/integration/<家>/` 下写一个类实现 `TerminalIntegration`
   （`claims` / `canOpen` / `open`，要注册"位置"就再覆盖 `setup`）
2. `Terminals.discover()` 里加一行 `isLoaded`
3. `gradle.properties` 加版本号、`build.gradle` 加 `compileOnly` + `localRuntime`
4. `mods.toml` 加一条 `optional` 依赖（`versionRange="[0,)"`、`ordering="AFTER"`）
5. `TerminalApp.COMPANIONS` 加一行

**不要**把判断挪进那个联动类里——判断一旦挪进去，判断本身就要求先加载那个类。

> 这一格原来是个独立附属（`november521/mcphone-terminal`，已归档），1.10.0 合并进本体。
> 合并的理由和当时权衡见那个仓库的历史。
