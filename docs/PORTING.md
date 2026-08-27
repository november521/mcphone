# 从 NeoForge 1.21.1 移植到 Forge 1.20.1

这份清单是**实测出来的**，不是凭印象列的。所有数字来自对
`MCphone Neoforge 1.21.1/src/main/java` 下 **215 个 java 文件、24,098 行**的扫描。

## 总览：一半的代码可以近乎照搬

| | 文件数 | 占比 |
| --- | ---: | ---: |
| 完全不碰加载器、也不碰 1.21 专有 API | **111** | 52% |
| 需要改动 | **104** | 48% |

那 111 个是纯业务逻辑与纯绘制代码——搜索算分、主屏网格布局、字体调色板、世界时钟、
问候语、代价系统、API 接口层。它们只依赖 `net.minecraft.*` 与 JDK，两个版本上
**大部分**一样成立（`GuiGraphics` 的少数方法签名要核，见下面第 7 条）。

**先搬这 111 个**。它们搬完就有了一半的代码量、而且几乎不会引入新 bug，
剩下的 104 个才是真正要动脑子的。

## 按工作量排序的 8 件事

### 1. 网络层 —— 34 个文件，最大的一块

那边用的是 NeoForge 的 payload 体系（**167 处** `CustomPacketPayload`）：

```java
record FooPacket(int x) implements CustomPacketPayload {
    static final Type<FooPacket> TYPE = new Type<>(id);
    static final StreamCodec<FriendlyByteBuf, FooPacket> STREAM_CODEC = ...;
}
registrar.playToServer(FooPacket.TYPE, FooPacket.STREAM_CODEC, FooHandler::handle);
PacketDistributor.sendToServer(new FooPacket(1));      // 15 处
```

1.20.1 上这套**完全不存在**。对应物是 Forge 的 `SimpleChannel`：

```java
CHANNEL.messageBuilder(FooPacket.class, id++, NetworkDirection.PLAY_TO_SERVER)
       .encoder(FooPacket::encode).decoder(FooPacket::new)
       .consumerMainThread(FooPacket::handle).add();
CHANNEL.sendToServer(new FooPacket(1));
```

差别不只是写法：

- **id 是手工分配的整数**，不是 `ResourceLocation`。加包、删包都要小心序号
- **`StreamCodec` 没有**，编解码要手写 `encode(FriendlyByteBuf)` / 构造函数解码
- **线程调度要自己声明**（`consumerMainThread`），payload 那边是由 `IPayloadContext` 给的
- **`IPayloadContext.player()` 没有对应物**，要从 `NetworkEvent.Context.getSender()` 拿，
  而它在客户端方向是 `null` —— 那边 5 处 `IPayloadContext` 都要重新想清楚方向

> 建议先移植**一条**最简单的包（比如设置同步），把 `SimpleChannel` 的骨架跑通，
> 再批量搬其余 33 个。别 34 个一起改，编译错误会糊成一片。

**第三刀已按这条走完，下面是实测补充。**

通道本身这么建（`ChannelBuilder` **在 47.4.23 上不存在**，那是 Forge 48+/1.20.2
才有的东西，网上按 1.20.2 写的教程照抄会找不到类）：

```java
NetworkRegistry.newSimpleChannel(id, () -> VERSION, VERSION::equals, VERSION::equals)
```

**三个必须每个包都记得、因而不该每个包都手写的东西**，已经收进
`core/net/MCphoneNetwork` 的两个注册函数里，上层只写"收到之后做什么"：

| 坑 | 漏了会怎样 |
| --- | --- |
| `ctx.setPacketHandled(true)` | Forge 每收一个包就在日志里报一次"未处理" |
| `ctx.enqueueWork(...)` | 默认在**网络线程**上跑，碰世界/玩家/物品即并发访问，偶发且不可复现 |
| `ctx.getSender()` 可能为 null | 客户端方向必然为 null；照 `IPayloadContext.player()` 直译过来就是空指针 |

所以 C2S 的处理函数签名是 `(包, ServerPlayer)` —— 玩家已确认非空、已在主线程；
S2C 的是 `(包)` —— **故意不给玩家参数**，那个方向不存在有意义的玩家，
给一个永远为 null 的参数只会诱人去用。客户端玩家自己从 `Minecraft` 取。

**序号那条不是提醒，是真会静默出错。** `SimpleChannel` 认整数序号，由注册顺序
发放。在中间插入或删除一个包，它后面所有包的序号平移，旧客户端会把 A 包当成
B 包解码 —— 症状是字段值乱七八糟，**不是干脆的报错**。唯一护栏是通道的
`PROTOCOL_VERSION`（两端不一致直接拒绝连接），所以**动了顺序就必须把它 +1**，
往末尾追加则不用。

### 2. ~~ResourceLocation 构造 —— 46 个文件~~ —— **这一条作废，一个字都不用改**

> 原文说"**53 处** `ResourceLocation.fromNamespaceAndPath(ns, path)`，1.20.1 上要写成
> `new ResourceLocation(ns, path)`"，并说这是唯一能放心 sed 的一件事。
> **这是错的，而且方向是反的。** 第二刀真去编的时候撞出来了。

Forge 47.4.x **把 1.21 的静态工厂方法回移进 1.20.1 了**，同时把两个公开构造函数
标成了废弃待删。实测（`javac -Xlint:removal`，Forge 47.4.23 mapped jar）：

| 写法 | 结果 |
| --- | --- |
| `ResourceLocation.fromNamespaceAndPath(ns, path)` | ✅ 干净，无警告 |
| `ResourceLocation.parse(s)` | ✅ 干净，无警告 |
| `new ResourceLocation(ns, path)` | ⚠️ `deprecated and marked for removal` |
| `new ResourceLocation(s)` | ⚠️ 同上 |

所以**照搬 1.21.1 的写法就是对的**，而且是唯一不触警告的写法。按原文那样 sed 一遍，
反而会让 44 个文件平白与 main 分叉、外加 13 条 removal 警告。

**这条依赖的 Forge 下限已经查实**，不是猜的：

- 回移发生在 MinecraftForge `0b1eefc6`（2025-02-18，PR #10405，
  "Backport even more future ResourceLocation methods"）。同一个补丁里给构造函数
  打上了 `@Deprecated(forRemoval = true, since = "1.20.6")`
- Forge **47.4.0 发布于 2025-03-05**，在该提交之后 → 含这个回移
- 本工程 `forge_version_range=[47.4.0,)`，**下限正好站得住**

⚠️ 如果哪天要把 `forge_version_range` 的下限往 47.4.0 以下调，**这 45 个文件会当场
报 `cannot find symbol`**。真要调就得先按 gradle.properties 里写的那样
`-Pforge_version=<新下限>` 重编一遍验证。

**推论：凡是 1.21 的 vanilla API，先去 Forge 1.20.x 的 patches 里查一眼有没有被回移，
再动手改。** 别照着版本号想当然。

### 3. 玩家数据 —— 11 个文件

`core/ModAttachments.java` 用的是 NeoForge 的 Data Attachment。1.20.1 对应的是
**Capability**，两套模型不一样：

| | Attachment | Capability |
| --- | --- | --- |
| 声明 | `AttachmentType.builder(...)` | `@AutoRegisterCapability` + `CapabilityManager` |
| 附加 | 自动 | 监听 `AttachCapabilitiesEvent` |
| 序列化 | builder 里给 codec | 自己实现 `INBTSerializable` |
| 死亡/换维度保留 | `.copyOnDeath()` | 监听 `PlayerEvent.Clone` 手动拷 |

**最后一行是最容易漏的**：Forge 的 capability 在玩家死亡重生时默认**不保留**，
要自己在 `PlayerEvent.Clone` 里从 `event.getOriginal()` 拷过来，而且得先调
`reviveCaps()`。漏了的症状是"死一次好友列表就空了"。

**但上面这句只说对了一半**，第三刀查 NeoForge 源码时才发现。`AttachmentType`
的类注释原文：

> Serializable entity attachments are **not copied on death by default**
> (but they **are copied when returning from the end**).

也就是说 `.copyOnDeath()` 只管**死亡**这一种情况，**从末地返回时一律保留**，
不管标没标。而 Forge 的 `PlayerEvent.Clone` **两种情况都会触发**。所以：

| 那边 | 这边 `PlayerEvent.Clone` 里要怎么写 |
| --- | --- |
| 标了 `.copyOnDeath()` | 两种情况都拷 |
| **没标**（如 `WALLPAPER`） | **`!isWasDeath()` 时仍要拷**，只在死亡时不拷 |

把"没标 copyOnDeath"读成"不用监听 Clone"，玩家打完末影龙走传送门回主世界
就会发现壁纸没了——那边不会。这是一处**只在特定路径上才发作**的分叉。

顺带记两条第三刀实测的 API 形状：

- `INBTSerializable<T>` 在 1.20.1 上是 `T serializeNBT()` / `void deserializeNBT(T)`，
  **不带** `HolderLookup.Provider` 参数（NeoForge 1.21 那边带）
- `AttachCapabilitiesEvent` 是**泛型事件**，监听器必须用
  `MinecraftForge.EVENT_BUS.addGenericListener(Entity.class, ...)` 注册。
  用普通的 `addListener` 不报错，只是**永远收不到事件** —— capability 静默不附加

### 一个 Attachment 一个 Capability？第三刀选了不

那边有 5 份玩家附着数据，直译过来是 5 个 capability，每个都要一套
token + provider + 附加监听。这边合成了**一个** `PhonePlayerData`
（`core/ModCapabilities`），格子里装什么自己定。

代价必须说清楚：**那 5 份数据的死亡保留策略互不相同**，合成一个之后不能再靠
注册时的一个开关表达，只能在 `onPlayerClone` 里逐字段写。**往
`PhonePlayerData` 加字段时必须回去补那一处**，漏了就是"死一次笔记没了"。

### 4. 物品数据 —— 8 个文件

`core/ModDataComponents.java`（**5 处** `DataComponentType`、**8 处** `DataComponents.`）
是 1.20.5 才有的东西。1.20.1 退回 NBT：

```java
// 1.21.1
stack.set(ModDataComponents.DEVICE_NAME.get(), name);
// 1.20.1
stack.getOrCreateTag().putString("DeviceName", name);
```

组件是**带类型和 codec** 的，NBT 是自由格式的——所以移植时**读的那一侧要自己做校验**：
组件读出来要么是对的类型要么是 null，NBT 读出来可能是任何东西（玩家用命令塞的、
老存档留下的）。那边的代码是靠类型系统兜住的，这边要靠手写的防御。

顺带：`NetMusicCompat` 里"只读一个数据组件"那句也在这一档，1.20.1 的 NetMusic
（如果有）用的必然是 NBT。

### 5. ModList —— 20 个文件

`net.neoforged.fml.ModList` → `net.minecraftforge.fml.ModList`。
**方法名与语义完全一样**（`isLoaded`、`getModContainerById`、`getModInfo().getDisplayName()`），
只换包名。

第 2 条作废之后，**这是全清单里唯一一件真能放心 sed 的事**，实测确认：

```bash
grep -rl 'net\.neoforged\.fml\.ModList' src/main/java \
  | xargs -r sed -i 's/net\.neoforged\.fml\.ModList/net.minecraftforge.fml.ModList/g'
```

第二刀里对搬过去的文件跑了这一条，命中 6 个，编译一次通过，无警告。

所有联动判断（`WaystonesCompat.isLoaded()` 那一层）都靠它，所以这条先做，
后面接联动模组时才不用回头。

### 6. 注册表 —— 9 个文件

| NeoForge 21.x | Forge 1.20.1 |
| --- | --- |
| `DeferredRegister.createItems(MODID)` | `DeferredRegister.create(ForgeRegistries.ITEMS, MODID)` |
| `DeferredHolder<Item, T>`（3 处）/ `DeferredItem<T>`（1 处） | `RegistryObject<T>` |
| 构造函数注入 `IEventBus` | `FMLJavaModLoadingContext.get().getModEventBus()` |
| `RegisterEvent` | 同名但在 `net.minecraftforge.registries` |

骨架里 `core/ModItems.java`、`core/ModCreativeTabs.java`、`MCphone.java`
**已经是 Forge 写法了，照着它们改其余的**。

### 7. 客户端事件与绘制 —— 5 个文件 + 全部绘制代码

事件类改名，逐个对：

| NeoForge | Forge 1.20.1 |
| --- | --- |
| `ClientTickEvent`（3 处） | `TickEvent.ClientTickEvent` |
| `RegisterMenuScreensEvent` | `FMLClientSetupEvent` 里调 `MenuScreens.register` |
| `RegisterKeyMappingsEvent` | 同名，包不同 |
| `RegisterClientReloadListenersEvent` | 同名，包不同 |
| `ScreenEvent` / `RenderGuiEvent` | 同名，包不同 |
| `IConfigScreenFactory` / `ConfigurationScreen` | Forge 没有内置配置界面，要么自己写要么去掉 |

**绘制代码要逐个核签名**，这一条影响的是那 111 个"干净文件"里的绘制部分：
1.20.1 也有 `GuiGraphics`，但 `blit` 的重载、`drawString` 的返回值、
`fill` 的 z 参数在两个版本上不完全一致。编译器会告诉你，但**不会告诉你画错了位置**——
移植完每一页都要肉眼看一遍。

### 8. 配置 —— 2 个文件

`ModConfigSpec` → `ForgeConfigSpec`。builder API 几乎一样，注册方式不同
（`ModLoadingContext.get().registerConfig(...)`）。

### 9. Java 语言版本 —— 21 → 17，只有 1 个文件（但比原先记的严重）

这一条原来漏了，是移植开始后才扫出来的。两边的 JDK 不同（那边 21，这边 17），
所以**源码里不能出现 21 才有的语法**。实测全仓只有一处：

```
core/PhoneLocation.java:150,153,157   case InHand(InteractionHand hand) ->
                                      case InInventory(int slot) -> {
                                      case InCurio(String slotId, int index) -> {
```

**第一版这条只写了"record pattern 要退回 `case InHand h ->`"，那个改法本身也编不过。**
第二刀实编时报的是：

```
patterns in switch statements are a preview feature and are disabled by default.
```

也就是说，问题不止于**记录模式解构**（JEP 440），而是 **switch 里的类型模式整体**
（JEP 441）——两者都是 Java 21 才转正的，17 上只有预览版。所以
`case InHand h ->` 同样不成立，**整个 switch 都得拆掉**，换成 `instanceof` 链
（那是 16 就转正的，17 上安全）：

```java
if (value instanceof InHand h) { ... h.hand() ... }
else if (value instanceof InInventory inv) { ... inv.slot() ... }
else if (value instanceof InCurio c) { ... c.slotId(), c.index() ... }
else { throw new IllegalStateException(...); }   // 这句不能省，见下
```

**代价是穷尽性检查没了，这是真正要留意的地方。** 原来 switch 在 sealed 接口上
是由编译器保证穷尽的：以后给 `PhoneLocation` 加第四种实现，1.21.1 那支会编译失败。
换成 `instanceof` 链之后编译器不再管，会静悄悄走到最后的 `else`。**所以那个
`else` 必须抛异常** —— 它是这里唯一还剩的守卫，把一个编译期保证降级成了运行期保证。
1.21.1 那支不需要这句，两边这一处的代码**注定不能逐字相同**。

`sealed` / `permits` 不受影响，那是 17 就转正的。文本块、`instanceof` 模式
也都安全。扫的时候顺带确认了没有 `case ... when` 守卫子句和字符串模板。

**移植新文件时请复扫一遍**（比原来那句多抓 `case <Type> <ident>` 这一形）：

```bash
grep -rnE "case +[A-Z][A-Za-z0-9_.]*(\(|[[:space:]]+[a-z][A-Za-z0-9_]*[[:space:]]*(->|:))|case .+ when |STR\.\"" src/main/java
```

全仓跑下来只有 `PhoneLocation.java` 一个文件命中。而它眼下卡在网络层
（`ByteBufCodecs`），所以这一处的改动要等第 1 条一起做。

## 联动模组：六个，每一个都要单独查

**别假设 1.20.1 上有同名同版的对方。** 逐条确认，确认不了的就先不接——
接一个断的联动比不接更糟。

| 模组 | 1.20.1 上的状况 | 备注 |
| --- | --- | --- |
| Curios | 有，但坐标与 API 版本不同 | 饰品栏槽位那套 json 格式也变了 |
| Waystones + Balm | 有，API 差异较大 | `WaystoneSelectionListBuilder` 那条路要重查 |
| MCEF | 有 1.20.1 版 | 原生库下载那条路不变 |
| NetMusic | **要先确认存不存在** | 它本来就是小众模组 |
| Patchouli | 有 | `BookRegistry` 内部结构要重查 |
| **GuideME** | **基本可以确定没有** | AE2 是在 1.21 才把它拆成独立模组的 |
| FTB Quests | 有（2001.x） | `FTBQuestsClient.openGui()` 要在 2001.x 上重新核实 |

`ExternalBookSource` 的白名单（沉浸工程手册）、`BookQuirks`（新生魔艺）
同理，各自的 1.20.1 版 API 都要重查。

## 构建侧已经做完的

- ForgeGradle 6.0.54 + Forge 1.20.1-47.4.23，**Gradle 8.8**
  （**不能**用 NeoForge 那支的 Gradle 9，ForgeGradle 6 不支持，配置阶段就炸）
- Java **17**（不是 21）
- `mods.toml`（不是 `neoforge.mods.toml`），版本号从 `gradle.properties` 注入
- 配方目录是 `data/<ns>/recipes/`（复数），1.21 改成了 `recipe/`（单数）；
  配方里的 `result` 字段是 `item`，1.21 改成了 `id`
- `pack_format` 15（1.20.1），1.21.1 是 34

## 还没做的构建侧

- **jarJar**：javamp3（MP3 解码）是必需依赖，要嵌进 jar。ForgeGradle 的 jarJar
  配置与 ModDevGradle 不一样，等音乐那一支移植时再弄
- **dist 隔离校验**：NeoForge 那支有个 `verifyDistIsolation` 任务，扫非 client 类
  有没有引用客户端类型（专用服务器启动即崩的那种坑）。这一支**必须也加上**，
  而且要早加——等代码堆起来再加就有一堆存量要清
- **Parchment 映射**：现在用的是官方混淆表（没有参数名）。要加就得挂 librarian 插件

## 进度

### ✅ 第一刀：纯逻辑层（22 个文件）

不 import 任何 Minecraft / NeoForge / Mojang 类型、且不引用本工程其它未移植
类的那一批，**原样搬过来，一个字没改**。

筛法不是靠肉眼，是靠编译器：先按 import 筛出 28 个候选，再用
`javac --release 17` 实编一遍，编不过的剔掉。剔掉了 6 个——它们表面上不碰
Minecraft，实际引用着本工程里还没搬的东西：

| 剔掉的 | 卡在哪 |
| --- | --- |
| `util/SpiLoader` | 要 `MCphone.LOGGER` |
| `api/client/ui/IPhonePage` | 要 `PhoneCanvas` |
| `feature/reader/client/ShelfStore` | 要 gson、`MCphone`、`BookRef` |
| `feature/chat/net/ChatClientCache` | 要本工程其它未移植类 |
| `feature/notes/net/NotesClientCache` | 同上 |
| `feature/music/client/source/MusicSources` | 要 `MusicSource` |

**5 个断言测试也一并搬了**，它们测的正好是这一层，而且不需要 Minecraft：

```bash
javac --release 17 -encoding UTF-8 -d /tmp/t $(find src/main/java -name '*.java' | grep -vE 'MCphone.java|core/Mod|core/PhoneItem')
javac --release 17 -encoding UTF-8 -cp /tmp/t -d /tmp/t docs/*.java
java -cp /tmp/t com.november.mcphone.feature.reader.BookSearchTest
```

在 Java 17 上跑出来 **122,174 条断言全绿**（28 + 80007 + 12301 + 91 + 29747）。
这一层的行为与 1.21.1 那支逐字相同，不是"看着差不多"。

### ✅ 第二刀：真实类路径下的批量筛选（+51 个文件，共 77 个）

这一刀第一次**挂着真正的 Forge 1.20.1 类路径**编译，而不是只用 JDK。做法是把
gradle 的 `compileClasspath` 导出来（100 条，含 mapped forge jar），之后全程用
`javac --release 17` 直接迭代，绕开 gradle 的启动开销。

**筛法：候选 → 实编 → 剪枝到收敛。**

| 阶段 | 数量 |
| --- | ---: |
| 未移植 | 189 |
| 不碰 neoforge（或只碰 `ModList`） | 157 |
| 依赖闭包也干净 | 142 |
| 连同基底 26 个一起送编译 | 168 |
| **编译收敛后保留** | **77** |
| 净新增 | **51** |

剪枝跑了 6 轮才收敛（168 → 97 → 93 → 83 → 79 → 77），因为**级联要一层层剥**：
A 编不过，引用 A 的 B 这一轮才暴露出来。一轮就收工会漏掉后面几层。

**剔掉的 91 个，按根因：**

| 根因 | 个数 |
| --- | ---: |
| 网络层：`StreamCodec` / `CustomPacketPayload`（1.20.5+ vanilla，1.20.1 没有） | 43 |
| 级联：引用了本轮被剔掉的本工程类 | 41 |
| 联动模组依赖还没加进 `build.gradle` | 6 |
| 物品数据 `DataComponent`（第 4 条） | 1 |

**这张表把优先级问的很清楚：网络层不是"第 6 步"，它是总闸。** 43 个直接卡在它上面，
另外 41 个级联文件的根也大半在那儿。级联根按被引用次数排：

```
12 次  core/client/PhoneScreen.java          ← 界面壳，第二大的闸
 4 次  core/client/PhoneScreenRegistry.java
 3 次  feature/chat/ChatMessage.java          ← 网络层
 2 次  feature/browser/client/BrowserBackends.java
 2 次  feature/reader/client/source/BookSources.java
```

**验收（三道，都是实测）：**

1. `./gradlew build` → `BUILD SUCCESSFUL`，产出 `mcphone-0.1.0.jar`，**0 条警告**
2. 5 个断言测试在 Java 17 上跑出 **122,174 条全绿**（28 + 80007 + 12301 + 91 + 29747），
   与第一刀逐条一致，纯逻辑层没有回归
3. **51 个新文件里 45 个与 `main` 逐字相同**，另 6 个只差 `ModList` 一个包名

第 3 条是这一刀最值得留意的结果：原以为要改 44 个文件（第 2 条），实测**一个都不用改**。

### ✅ 第三刀：网络层通了一条路（+6 个文件，共 83 个）

按上面重排后的顺序，先开总闸。**只通一条路**：壁纸的设置（C2S）与同步（S2C）。
挑它是因为它同时覆盖两个方向，正好撞上方向不对称那个坑；而它的服务端依赖
只有一份小数据，不会把整个子系统拖进来（对比：`RequestOnlinePlayers` 那一对
看着简单，实际要拖进好友关系、限流、`ChatService` 一整套）。

| 新增 | 干什么 |
| --- | --- |
| `core/net/MCphoneNetwork` | `SimpleChannel` 通道、序号分配、把三个坑收进注册函数 |
| `core/net/NetworkHandler` | 注册与处理，与那边同名同职责 |
| `core/ModCapabilities` | capability 注册、附加、重生拷贝 |
| `core/PhonePlayerData` | 玩家数据本体与存档读写 |
| `feature/settings/net/SetWallpaperPacket` | C2S，Forge 形状 |
| `feature/settings/net/SyncWallpaperPacket` | S2C，Forge 形状 |

**先查 API 再动手，没照网上的教程写。** 逐条 `javap` 过 Forge 47.4.23 的实际
签名，捞出两处与常见教程不一致的地方：`ChannelBuilder` 在这个版本上**不存在**；
`NetworkEvent.Context` 的 `getSender()` 在客户端方向**必然为 null**。

**验收：**

1. `./gradlew build` → `BUILD SUCCESSFUL`
2. **新增 `docs/WallpaperPacketTest.java`，27 条断言全绿。**测的是线格式往返
   （空串、含空格、非 ASCII、控制字符、路径样式的串）、读写字节数必须相等、
   长度上限确实存在（32767 过、32768 拒）、存档往返、坏存档退回默认、
   `copyFrom` 拷贝后互不影响
3. 原有 5 个测试仍是 122,174 条全绿，合计 **122,201 条**
4. **`./gradlew runServer` 真把专用服务端拉起来了** —— `Done (35.888s)!`，
   日志里有 `[MCphone] Forge 1.20.1 骨架已加载`，**全程零 ERROR、零异常**。
   这一条比编译过管用：它证明构造期的通道注册没炸、capability 的两个监听器
   挂上去了，而且**这套代码在没有客户端类的环境里能加载** —— 是一次非正式的
   dist 隔离检查（正式的 `verifyDistIsolation` 任务还没加，见第 4 步）

**这一刀验到哪儿为止，说清楚：**线格式、存档往返、服务端加载是**真跑过**的；
"包在实际连接上流动"验不了 —— 那需要一个客户端去点那颗按钮，而界面壳还没搬
（第 6 步）。所以序号分配、`PROTOCOL_VERSION` 握手这两件事目前只是**看着对**，
等第 6 步进游戏点一下才算数。

⚠️ 跑 `runServer` 别加 `--offline`：`srgutils` 这个依赖没在缓存里，离线模式
直接解析失败。另外它会在**工程根**下生成 `logs/`（不是 `run/logs/`），
已补进 `.gitignore`。

### ⬜ 下一刀

第一刀剔掉的那 6 个，**回来了 3 个**：`util/SpiLoader`、`api/client/ui/IPhonePage`、
`feature/reader/client/ShelfStore`。剩下 3 个（`ChatClientCache`、`NotesClientCache`、
`MusicSources`）全都卡在网络层 —— 又一条指向第 1 条的证据。

## 建议的推进顺序

**这个顺序按第二刀的实测数据重排过。** 原来把网络层排在第 6 步，但归因表显示
它卡着 43 + 大半级联 —— 界面和 App 再怎么搬也绕不过去。

1. ~~那 111 个干净文件搬过来~~ ✅ 已完成（第一、二刀，77 个）
2. ~~`ModList` 与 `ResourceLocation` 两轮批量替换~~ ✅ `ModList` 已做；
   `ResourceLocation` **查实为无需改动**（第 2 条）
3. ~~**网络层骨架，先通一条包**~~ ✅ 骨架已通（第三刀，壁纸那一对）。
   **剩下 32 个包照着搬**，往 `NetworkHandler.register()` 末尾追加即可，
   序号自然递增、不用动 `PROTOCOL_VERSION`。`PhoneLocation` 的 Java 17
   语法改动（第 9 条）跟着这批一起做，它卡在 `ByteBufCodecs` 上
4. 注册表与物品（第 6、4 条）—— 到这里手机能拿在手里且带数据
5. **加 `verifyDistIsolation`**，趁代码还少
6. 界面壳与主屏（`PhoneScreen`、`HomeGrid`、`PhoneChassis`）—— 第二大的闸，
   光它一个就挡着 12 个文件。到这里能开机了
7. 玩家数据 Capability（第 3 条）—— 聊天、好友、商店都压在它上面
8. App 逐个搬，从不依赖网络的开始（时钟、天气、相册）
9. 联动模组，逐个确认后再接（那 6 个 compat 文件等这一步）

每一步都能编译、能进游戏再走下一步。24k 行一次性搬过来然后调编译错误，
是这种移植最常见的翻车方式。

## 复现这套筛法

第二刀用的工具链，下一刀直接照用（gradle 只用来导类路径，之后全走 javac）：

```bash
# 1. 导出 Forge 1.20.1 编译类路径（gradle 必须用 JDK 21 跑，8.8 不认 JDK 25）
cat > /tmp/dumpcp.gradle <<'EOF'
allprojects { afterEvaluate { p -> p.tasks.register('dumpCp') { doLast {
    new File(System.getProperty('cpOut')).text =
        p.sourceSets.main.compileClasspath.files.collect{it.absolutePath}.join(':') } } } }
EOF
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-arm64 \
  ./gradlew -q --offline -I /tmp/dumpcp.gradle -DcpOut=/tmp/cp.txt dumpCp

# 2. 实编（-Xmaxerrs 必须放大，默认 100 条会截断，看不到全貌）
J17=~/.gradle/jdks/eclipse_adoptium-17-aarch64-linux/jdk-17.0.20.1+1
$J17/bin/javac -Xmaxerrs 100000 -nowarn -encoding UTF-8 \
  -cp "$(cat /tmp/cp.txt)" -d /tmp/out @filelist.txt 2> /tmp/err.txt

# 3. 剪枝一轮：把出错的文件剔出去，重编，直到 exit=0
grep -oE '^\./[^:]+\.java' /tmp/err.txt | sort -u > /tmp/bad.txt
```

两个坑，都踩过：

- **语法错误会掩盖语义错误。** javac 在 parse 阶段失败就不做属性分析了，
  所以第一轮只报了 `PhoneLocation` 的 9 条语法错。修掉它才看到真正的 332 条
- **zsh 默认开 `noclobber`**，剪枝循环里 `>` 重定向到已存在的文件会静默失败，
  导致后几轮读的是上一轮的陈旧错误文件、看着像"不收敛"。循环前先 `set +o noclobber`
