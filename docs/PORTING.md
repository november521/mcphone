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

~~**绘制代码要逐个核签名**~~ —— **第四刀实测：这条担心基本落空了。**

原文预计 `blit` 的重载、`drawString` 的返回值、`fill` 的 z 参数都不一致。
实际把 140 多个界面/页面文件一次性挂着真实类路径编下来，**`GuiGraphics`
本身一处没改**。真正对不上的只有下面四个，而且都不在 `GuiGraphics` 上：

| 1.21.1 | 1.20.1 | 危险度 |
| --- | --- | --- |
| `Screen.renderBackground(g, mx, my, tick)` | `renderBackground(g)` | 低，编译报错 |
| `mouseScrolled(mx, my, scrollX, scrollY)` | `mouseScrolled(mx, my, delta)` | **高，见下** |
| `EditBox.moveCursorToEnd(boolean)` | `moveCursorToEnd()` | 低，编译报错 |
| `PlayerFaceRenderer.draw(g, PlayerSkin, ...)` | `draw(g, ResourceLocation, ...)` | 低，编译报错 |

**`mouseScrolled` 那一行是这批里唯一会静默出事的。** 它是覆盖父类方法：
签名从四参改成三参之后，如果只改调用点而忘了改自己的定义，**编译不会报错**，
那个方法只是【永远不会被调到】—— 症状是"滚不动"，而日志里什么都没有。
唯一的守卫是 `@Override`，所以覆盖原版方法时那个注解**一个都不能省**。

至于"编译器不会告诉你画错了位置"这句仍然成立，移植完每一页还是要肉眼看
一遍。只是要看的不再是签名差异，而是布局。

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

### 不只是语法 —— Java 21 新增的【标准库方法】同样过不去

第四刀撞出来的，前两版这条都漏了。语法能靠 grep 扫，库 API 不能：

```
java.lang.Math.clamp(...)   Java 21 新增   全仓 6 处 / 4 个文件
```

`Math.clamp` 在 17 上根本不存在，报的是 `cannot find symbol`，看着像打错字，
跟"Java 版本"四个字毫无关联。换成 `net.minecraft.util.Mth.clamp` 即可，
int/long/float/double 四个重载都在，行为一致。

顺带扫过、**确认没有命中**的其它 21 新增 API：`SequencedCollection` 那一组
（`getFirst`/`getLast`/`addFirst`/`removeLast`）、`Character.isEmoji`、
`String.splitWithDelimiters`、字符串模板。

⚠️ 全仓 5 处 `.reversed()` 全都是 `Comparator` 上的（Java 8 就有），
**不是** `SequencedCollection.reversed()`。扫这一组时别只看方法名。

**移植新文件时请复扫一遍**（比原来那句多抓 `case <Type> <ident>` 这一形）：

```bash
grep -rnE "case +[A-Z][A-Za-z0-9_.]*(\(|[[:space:]]+[a-z][A-Za-z0-9_]*[[:space:]]*(->|:))|case .+ when |STR\.\"" src/main/java
```

全仓跑下来只有 `PhoneLocation.java` 一个文件命中。而它眼下卡在网络层
（`ByteBufCodecs`），所以这一处的改动要等第 1 条一起做。

## 联动模组：六个，每一个都要单独查

**别假设 1.20.1 上有同名同版的对方。** 逐条确认，确认不了的就先不接——
接一个断的联动比不接更糟。

**第四刀把这张表逐条实测过了，第六刀把剩下三条也啃完了**，下面是结果而不是预期：

| 模组 | 结论 | 实测到的东西 |
| --- | --- | --- |
| **Curios** | ✅ 已接 | `5.14.1+1.20.1`。**API 有一处真差异**：`CuriosApi.getCuriosInventory` 在 5.x 返回 Forge 的 `LazyOptional`，不是 `java.util.Optional`，**没有 `flatMap`**，得先 `.resolve()`。9.x 那边已经改成返回 `Optional` 了 |
| **NetMusic** | ✅ 已接 | `1.5.1-forge+mc1.20.1`，确实存在（清单原先写的是"要先确认存不存在"）。接触面照旧只有 `ItemMusicCD` |
| **Patchouli** | ✅ 已接 | `1.20.1-80-forge`。仍然刻意挑**最老**的那个，理由与那一支相同 |
| **JavaMP3** | ✅ 已接 | 非联动，是必需依赖。jarJar 已配好，见下面"构建侧" |
| **Waystones + Balm** | ✅ **第六刀接上** | 编译对 `14.0.0+forge-1.20` / `7.0.1+forge-1.20`（都取下限）。**与 1.21 完全是另一套**：没有 `WaystoneSelectionListBuilder`、没有 `ModMenus.warpStoneSelection`、没有 `TeleportFlags`，"这次算哪种来源"改由 `WarpMode` 枚举表示；Balm 是 `getNetworking().openGui(...)` 不是 `networking().openMenu(...)`。菜单照着对方 `WarpStoneItem.containerProvider` 手写一份 `BalmMenuProvider`。**还多出一条钩子**，见下面第六刀 |
| **MCEF** | ✅ **第六刀接上** | `2.1.6-1.20.1`（1.20.1 上唯一标了 forge 的版本；自带 `org.cef.*`，不必另加 jcef 依赖）。**MCEF 自己的 API 两边一模一样**，`McefBackend` 是逐字搬的；真要改的只有 `BrowserScreen.drawBrowser` 那十几行渲染管线，见下面第六刀 |
| **GuideME** | ✅ **本来就接着**（第六刀核实） | **原先记的"1.20.1 上基本可以确定没有"是错的** —— 它有 `20.1.0` 到 `20.1.15` 共 16 个 1.20.1 版本。`GuideMeSource` 走反射、不带编译依赖，所以早在第二刀就作为"干净文件"搬过来了，也一直登记在 `BookSources` 里，只是没人核实过对面还在不在。实测 `20.1.0` 与 `20.1.15` 两头：`guideme.internal.GuideMEProxy` 的 `instance` / `getAvailableGuides` / `getGuideDisplayName` / `openGuide` 与 `guideme.Guides.createGuideItem` **五个签名全对得上** |
| **FTB Quests** | ✅ **第六刀核实并登记** | `dev.ftb.mods.ftbquests.client.FTBQuestsClient.openGui()`，`public static void`、零参，在 2001.x 的**头尾两版**（`2001.1.1` 与 `2001.4.22`）上签名一字未变，字节码也仍是那三行。走反射，**不加编译依赖**，理由见 `FtbQuestsBook` 的类注释 |

**这张表上没有一条是"没接"的了。**

GuideME 那条是被自己的预判坑了一次：清单第一版凭印象写了"1.20.1 上基本可以确定
没有"，第四刀实测时又照抄了这句结论 —— 而实际上它一直都在，源文件也一直在工程里
躺着。**反射型的联动没有编译依赖，所以"搬过来了"与"能用"是两件事**，前者编译器
会告诉你，后者必须自己去对面的 jar 里查一遍。FTB Quests 也是同一类，一起查了。

`ExternalBookSource` 的白名单（沉浸工程手册）、`BookQuirks`（新生魔艺）是另一回事：
它们走的是**按注册名查表**，不引用对方任何类型，所以搬过来就成立；要重查的是
那些 id 在 1.20.1 版上还叫不叫这个名字，而那只能拿整合包实测。

## 构建侧已经做完的

- ForgeGradle 6.0.54 + Forge 1.20.1-47.4.23，**Gradle 8.8**
  （**不能**用 NeoForge 那支的 Gradle 9，ForgeGradle 6 不支持，配置阶段就炸）
- Java **17**（不是 21）
- `mods.toml`（不是 `neoforge.mods.toml`），版本号从 `gradle.properties` 注入
- 配方目录是 `data/<ns>/recipes/`（复数），1.21 改成了 `recipe/`（单数）；
  配方里的 `result` 字段是 `item`，1.21 改成了 `id`
- `pack_format` 15（1.20.1），1.21.1 是 34

## 还没做的构建侧

- ~~**jarJar**~~ ✅ **第四刀做完了**。`fr.delthas:javamp3` 走 `jarJar(implementation(...))`
  嵌进 jar，区间写法 `[1.0.1,2.0)` 是 jarJar 的要求（多个模组都嵌同一个库时好挑一个共用）。

  ⚠️ **开了 jarJar 会多出一个 jar，这件事差点出事故。** `build/libs` 下同时有
  `jar` 任务出的（不含内嵌依赖）和 `jarJar` 出的（含）。两条 CI 都是
  `find build/libs -name "*.jar" | head -1` 取件的 —— **那个 `head -1` 挑到谁
  完全看文件系统顺序**，挑错就发出一个放 mp3 必崩的包，而构建全绿、测试全绿，
  没有任何东西会告诉你。

  现在的做法：`jar` 改名带 `-slim`，`jarJar` 占住默认名字，两份 workflow 的
  `find` 也加了 `! -name "*-slim.jar"`。**双保险，缺一不可** —— 只改 classifier
  的话，将来谁把它改回去就又静默出事了。

- ~~**dist 隔离校验**~~ ✅ **第六刀做完了**。`verifyDistIsolation` 与
  `verifyServiceFiles` 两个任务都从 `main` 搬了过来，都挂在 `check` 上，
  也就是每次 `./gradlew build` 都跑。两个任务的实现与那一支**逐字相同**——
  它们扫的是自家的 class 目录与自家的文本文件，与加载器无关。

  规则一条：**路径里有 `/client/` 的类才准引用客户端类型**，其余一概不准。
  白名单只有 `MCphoneClient.class` 一个（类名里有 Client、路径里却没有 `/client/`）。

  这一支比那一支更需要它：NeoForge 的客户端入口是 `@Mod(dist = Dist.CLIENT)`
  的第二个 mod 类，加载器保证专用服务端读都不读；1.20.1 的 `@Mod`
  **没有 dist 参数**，这条保险不存在，靠的全是 `DistExecutor` 加"签名里不出现
  客户端类型"的自觉——自觉需要一道断言兜着。

  接上 MCEF 之后 `com/cinemamod/mcef` 与 `org/cef/` 也进了标记表，与那一支一致。

  当前：**132 个非 client 类，无一引用客户端类型**；`verifyServiceFiles`
  16 个类全部存在。

- **Parchment 映射**：现在用的是官方混淆表（没有参数名）。要加就得挂 librarian 插件

- **`BuiltInRegistries.ITEM` 的废弃告警**：`BuiltinAppPrices`、`ExternalBook`、
  `ExternalBookSource`、`WaystonesCompat` 四处（最后一个是第六刀新增的，刻意跟着
  前三个走同一种写法，而不是单独换成 `ForgeRegistries`）。Forge 在 1.20.1 上把它标了废弃（推荐走
  `ForgeRegistries`），NeoForge 那边没标，所以那三个文件与 `main` 逐字相同时
  这边会多三条告警。`./gradlew build` 默认不开 `-Xlint:deprecation` 所以看不见，
  这里记一笔免得下次有人当成新问题查。

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

### ✅ 第四刀：界面壳与 App 页面（+91 个文件，共 174 个）

**手机能开机了。** 目标是把第三刀验不到的那半截补上 —— 有客户端才能真按下按钮。

界面壳没法单独搬：`PhoneScreen` 直接 import 了 27 个页面类（导航是写死在它
里面的），所以"壳"与 App 是一整块。闭包 71 个文件，加上同包引用与 SPI 类，
这一刀实际搬了 91 个。

**做完之后能用的**：开机（右键手机 / 快捷键）、主屏、时钟、天气、相册、
设置（换壁纸、改设备名、字体配色、关于）。壁纸与设备名走的是真网络往返 ——
第三刀那条链路现在有界面按得动了。

**做不了的**：聊天、记事本、音乐、应用商店、末影箱 —— 它们的**客户端那一半
已经搬过来并编过**，缺的是服务端处理包。所以这几个 App 暂时不写进 SPI 清单
（`resources/META-INF/services/` 下那份 IPhoneApp 名单），登记了点进去只会
发出一个没人接的包。每搬完一个功能的服务端，在 `NetworkHandler` 加注册、
在那份清单里加一行即可，**不需要改任何界面代码**。

相机缺 `CameraHandler`；浏览器与传送石整个联动没接，理由见上面的联动表。

### 三处 1.20.1 特有的入口形状

1. **`@Mod` 没有 `dist` 参数。** 那边客户端入口是带
   `dist = Dist.CLIENT` 的第二个 mod 类，由加载器保证专用服务端读都不读它。
   1.20.1 上没这个参数，改成 `MCphone` 里用
   `DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> MCphoneClient.init(context))`。

   **那个双层 Supplier 不是啰嗦。** 写成一句直接调用，专用服务端在加载
   `MCphone` 时就会连带解析 `MCphoneClient`，而它引用着 `Minecraft`、`Screen`，
   当场 `NoClassDefFoundError` —— 光是写着就会崩，那句根本不需要被执行到。

2. **`ModLoadingContext.get()` 已标 `forRemoval`。** 注册配置要用注入进来的
   `FMLJavaModLoadingContext` **实例上**的 `registerConfig`。这与 `MCphone`
   类注释里说的是同一件事 —— 网上绝大多数 1.20.1 教程都还是那个静态写法。

3. **`TickEvent.ClientTickEvent` 要判 `phase`。** 那边事件本身分 `Pre`/`Post`
   两个类，订阅 `ClientTickEvent.Post` 就只收结束那一次。1.20.1 上只有一个类，
   两个阶段都从这儿进来，**不判 `event.phase != Phase.END` 就会一 tick 触发两次**。

### 验收

1. `./gradlew build` → `BUILD SUCCESSFUL`，**零 error、零 removal 告警**
2. 6 个断言测试仍是 **122,201 条全绿**
3. `./gradlew runServer` → `Done (6.770s)!`，零 ERROR，且日志里没有客户端那半边
4. 产物 `mcphone-0.1.0.jar` 内含 `META-INF/jarjar/javamp3-1.0.1.jar`、
   227 个类、40 张贴图、218 条语言条目

**验不到的**：界面本身。这台机器上没有显示环境（无 `DISPLAY`、无 `Xvfb`），
`runClient` 跑不起来。**布局、配色、点击响应都要在本机 `./gradlew runClient`
肉眼过一遍** —— 编译器管得了签名，管不了画得对不对。

### ✅ 第五刀：服务端补齐，基本功能全部就位（+37 个文件，共 211 个）

**211 / 215。** 缺的 4 个是浏览器那一支，连同传送石一起是【故意没接】的
（见上面的联动表）；`ModAttachments` 与 `ModDataComponents` 则是被
`PhonePlayerData` 与 `PhoneItemData` 取代了，不会再出现。

聊天、记事本、音乐、应用商店、末影箱、相机的服务端全部补齐，SPI 清单从
5 个 App 放开到 **11 个**。三十多个网络包的注册与处理都接上了。

### 又一批只在这个版本上成立的差异

**SavedData 的 API 变了三处**（`ChatData`、`FriendData`）：

| 1.21.1 | 1.20.1 |
| --- | --- |
| `computeIfAbsent(new SavedData.Factory<>(ctor, loader, null), name)` | `computeIfAbsent(loader, ctor, name)` |
| `save(CompoundTag, HolderLookup.Provider)` | `save(CompoundTag)` |
| `load(CompoundTag, HolderLookup.Provider)` | `load(CompoundTag)` |

没有 `Factory` 这个类，而且**参数顺序是 loader 在前** —— 写反了编译不过，
但很容易看花眼。

**注册表的入口不是同一个东西**（第 6 条的补充）：`Registries.SOUND_EVENT`
（原版 `ResourceKey`）→ `ForgeRegistries.SOUND_EVENTS`（Forge 的包装）；
`Registries.MENU` → `ForgeRegistries.MENU_TYPES`。名字像，类型不同。

**其它逐个撞出来的：**

| 位置 | 1.21.1 | 1.20.1 |
| --- | --- | --- |
| `Slot.setByPlayer` | `(新栈, 旧栈)` | `(新栈)` |
| `IEventBus.addListener` | `(优先级, 类, 消费者)` | **没有这个三参重载**，要用四参那个（中间多一个 `receiveCancelled`） |
| 菜单界面注册 | `RegisterMenuScreensEvent` | `FMLClientSetupEvent` 里手调 `MenuScreens.register`，**且必须包在 `enqueueWork` 里** —— 那个注册表不是线程安全的，而该事件是并行派发的 |
| 唱片判定 | `JukeboxSong.fromStack(...)` | `stack.getItem() instanceof RecordItem` |

### 唱片判定这一处是【功能上的降级】，不是等价替换

那边查的是 `JUKEBOX_PLAYABLE` 数据组件（1.21 才有），所以别的模组、甚至只靠
json 定义的唱片它都认得。1.20.1 上没有那个组件，曲长与音效直接挂在
`RecordItem` 这个**类**上，只能按类型判 —— **别的模组若没继承 `RecordItem`
而是自己实现一套，这边就认不出来，那边能。**

这是 1.20.1 的天花板，不是偷懒。真要覆盖到那种唱片，得另开一个按 item tag
判的口子。记在这里免得日后当成 bug 查。

### 数据包目录：两处是复数/单数之差，改错了不报错只是不生效

| | 1.21.1 | 1.20.1 |
| --- | --- | --- |
| 物品标签 | `data/<ns>/tags/item/` | `data/<ns>/tags/items/` |
| 进度 | `data/<ns>/advancement/` | `data/<ns>/advancements/` |
| 配方 | `data/<ns>/recipe/` | `data/<ns>/recipes/` |

物品谓词的写法也不同：1.21 是 `"items": "minecraft:redstone"`（字符串），
1.20.1 是 `"items": ["minecraft:redstone"]`（数组）。

**Curios 的槽位定义反而没变**：`data/<ns>/curios/slots/<name>.json`
与 `curios/entities/<name>.json` 两边同构，字段也一样（实测对着
Curios 5.14.1 jar 里自带的 `belt.json` 核过）。清单原先写的
"饰品栏槽位那套 json 格式也变了"——**只变在标签目录那一处**。

### 编解码方法的参数顺序，统一过一次

自动转换出来的是 `encode(值, buf)`，早期手写的四个是 `encode(buf, 值)`。
两种混着放在一个编解码层里迟早出事，已全部统一成 **`encode(值, buf)`** ——
与 `MCphoneNetwork.registerToServer` 要的 `BiConsumer<T, FriendlyByteBuf>`
方向一致，包类的 `encode` 可以直接当方法引用传进去。全仓 42 处。

### 测试架子够不到的地方，说清楚

`docs/` 下这套断言测试的前提是"不需要 Minecraft"。`PhonePlayerData` 自从
带上唱片仓字段（`DiscState` 含 `ItemStack`）之后，**光是 new 一个就会触发
`ItemStack` 的静态初始化去查 `BuiltInRegistries`**，抛 "Not bootstrapped"，
而且是在类初始化阶段抛，栈里看不出跟测试有任何关系。

补 `Bootstrap.bootStrap()` 也救不回来——它会连带初始化 Forge 的事件总线，
在 FML 之外起不来。所以玩家数据**容器**那一层只能进游戏验，测试退而直接测
它调用的 `WallpaperData.CODEC`，序列化逻辑本身仍然覆盖。

**往 `PhonePlayerData` 加字段时留意这条。**

### 验收

1. `./gradlew build` → `BUILD SUCCESSFUL`，零 error
2. 6 个断言测试 **122,204 条全绿**
3. `./gradlew runServer` → `Done (5.497s)!`，**零 ERROR**；`Loaded 7 recipes`、
   `Loaded 1272 advancements`（新加的数据包文件都被吃下了），
   服务端配置 `mcphone-server.toml` 正常生成
4. 产物 270 个类、内嵌 javamp3、3 份 SPI 名单、5 个 data json

**验不到的还是界面与实际收发。** 三十多个包这一刀第一次挂上通道，
序号分配、两端握手、每个处理函数的实际行为都只能进游戏点一遍。

第一刀剔掉的那 6 个，**回来了 3 个**：`util/SpiLoader`、`api/client/ui/IPhonePage`、
`feature/reader/client/ShelfStore`。剩下 3 个（`ChatClientCache`、`NotesClientCache`、
`MusicSources`）全都卡在网络层 —— 又一条指向第 1 条的证据。

### ✅ 第六刀：三个联动接上，移植收尾（+7 个文件，共 219 个）

**213 / 215 搬齐。** 没搬的两个是 `ModAttachments` 与 `ModDataComponents`，
它们被这一支的 `PhonePlayerData` 与 `PhoneItemData` 取代了，不会再出现。
反过来这一支多出 6 个那边没有的（`MCphoneNetwork`、`ModCapabilities`、
`ModItems`、`PhoneItemData`、`PhonePlayerData`、`WaystonesWarpItemModule`）。

SPI 清单从 11 个 App 放开到 **14 个**：浏览器、传送石、任务书三格补齐。

#### 传送石：菜单能照抄，计价方式得重想

菜单本身不难 —— 对方 `WarpStoneItem` 里那个 `containerProvider` 是个匿名
`BalmMenuProvider`，三个方法照抄一遍就是：

```java
getDisplayName()          Component.translatable("container.waystones.waystone_selection")
createMenu(id, inv, p)    WaystoneSelectionMenu.createWaystoneSelection(id, p, WARP_STONE, null)
writeScreenOpeningData()  buf.writeByte(WarpMode.WARP_STONE.ordinal())
```

最后那一句**必须写**：客户端工厂第一行就是 `WarpMode.values[buf.readByte()]`，
不写就是从空 buf 里读，玩家那边直接抛异常。也**只能**写这一个字节 ——
只有 `WAYSTONE_TO_WAYSTONE` 那一支会再读一个 `BlockPos`。

难的是"按哪种来源计价"。1.21 那边靠 `withTargetsForItem(warpStoneStack())`
把 warpItem 显式塞进去，14.x 的菜单**不收这个参数**，只有两个现成的选择：

| | WARP_STONE | CUSTOM |
| --- | --- | --- |
| 经验 | 服主配的 `warpStoneXpCostMultiplier` | **恒为 0** |
| 冷却 | 服主配的 `warpStoneCooldown` | 无 |
| 要有传送石 | **要**（只翻主手与副手） | 不要 |

CUSTOM 省事，代价是这个 App 变成一部**无限次的免费传送器**，服主为传送石配的
规则一条都不命中 —— 那不是取舍，是把平衡拆了。
（INVENTORY_BUTTON 更糟：`canUseInventoryButton` 要求玩家先在配置里绑一个
传送点，没绑就是死按钮。）

所以走 WARP_STONE，缺的那一件自己补：`WaystonesWarpItemModule` 在
`FMLCommonSetupEvent` 里往 `WaystoneTeleportEvent.Pre` 上挂一条钩子，
在它取 warpItem 之前塞一块传送石进去。字节码里的顺序是

```
fireEvent(Pre) → isCanceled? → getWarpItem() → canUseWarpMode(...)
```

`14.0.0` 与 `14.1.20` 上都是这个顺序，**两头都核过**。

钩子只在 warpItem **空着**时才动手 —— 真拿着石头的那条路它必然非空，
所以这条钩子实际只对本 App 开出来的菜单生效。

那块石头不会被消耗：`WARP_STONE.consumesItem` 是 false，而整个 Waystones 14.x
的 jar 里**一处 `hurtAndBreak` 都没有**（这一支的传送石付的是冷却，不是耐久，
1.21.1 那边才是耐久）。何况它本来就是临时造的一只 `ItemStack`，不在任何容器里。

**为什么必须挂钩子，而不是让玩家拿着石头**：`findWarpItem` 只翻主手与副手，
而手机可以躺在背包里、也可以挂在饰品栏里（`PhoneScreenOpener` 三处都找）。
玩家开着手机时手上多半就是手机。照原样接上去，这个 App 在多数情况下都是
"点了没反应"。

#### 浏览器：只有十几行要改

MCEF 自己的 API 两边**一模一样** —— `MCEF.isInitialized/createBrowser`、
`MCEFBrowser` 那一串 `send*`、`MCEFRenderer.getTextureID`，连 jcef 的
`loadURL/getURL/canGoBack/...` 都在同一个位置。所以 `McefBackend` 是逐字搬的，
`BrowserApp` 也是，`BrowserBackends` 只换了 `ModList` 的包名。

真要改的是 `BrowserScreen`，三处：

| | 1.21.1 | 1.20.1 |
| --- | --- | --- |
| 贴纹理 | `Tesselator.begin(mode, fmt)` 直接给 BufferBuilder，`addVertex().setUv().setColor()` | `getBuilder()` 再 `begin()`，`vertex().uv().color()` **且每个顶点末尾必须 `endVertex()`** |
| 暗化背景 | `renderBackground(g, mouseX, mouseY, partialTick)` | `renderBackground(g)` |
| 滚轮 | `mouseScrolled(x, y, scrollX, scrollY)` | `mouseScrolled(x, y, delta)` |

后两条漏了都**不报错**：`renderBackground` 是编译错误还好说，
`mouseScrolled` 写成四参能编过，只是那个方法**永远不会被调到** ——
网页滚不动却查不出原因。`@Override` 是这里唯一的守卫。

第一条还有一个只在运行期发作的坑：属性的调用顺序必须与
`POSITION_TEX_COLOR` 声明的顺序（Position → UV0 → Color）一致，
调换不是编译错误，是运行时抛"顶点没填满"。

#### 任务书：只是核实，代码一个字没动

`FtbQuestsBook` 走的是反射，本来就不带编译依赖，所以移植时它是"干净文件"
早就搬过来了，只差核实对面那个方法还在不在。

拿 `maven.ftb.dev` 上 2001.x 的**头尾两版**（`2001.1.1` 与 `2001.4.22`）实测：
`FTBQuestsClient.openGui()` 都是 `public static void`、零参，字节码也仍是
"调 `ClientQuestFile.openGui()` 再把返回值丢掉"那三行。签名一字未变，
于是 `QuestsApp` 直接登记进 SPI 清单。

**没有往 `mods.toml` 里加 ftbquests 依赖** —— 与 `main` 保持一致：纯反射、
无编译依赖，反射发生在玩家点图标那一刻而不是加载期，没有 ordering 的需要。

#### GuideME：文档错了，代码一直是对的

清单里写着"1.20.1 上基本可以确定没有"，**这句是错的**。它有 16 个 1.20.1 版本
（`20.1.0` ~ `20.1.15`）。

而 `GuideMeSource` 走的是反射，不带编译依赖，所以第二刀就作为"干净文件"搬进来了，
也一直登记在 `BookSources` 里 —— 也就是说这个联动**从来就是通的**，只是没人核实过。
拿 `20.1.0` 与 `20.1.15` 两头实测，五个反射目标全部对得上：

```
guideme.internal.GuideMEProxy   instance() / getAvailableGuides()
                                getGuideDisplayName(ResourceLocation)
                                openGuide(Player, ResourceLocation)
guideme.Guides                  createGuideItem(ResourceLocation)
```

**教训写在这儿**：反射型联动"搬过来了"与"能用"是两件事。编译器只管得住前者，
后者必须自己去对面的 jar 里查。这一刀查了三个（FTB Quests、GuideME，加上真要
重写的 Waystones），下次加反射联动照这个来。

#### 构建侧：两道校验补上

`verifyDistIsolation` 与 `verifyServiceFiles` 从 `main` 搬过来，都挂在 `check` 上。
详见上面"还没做的构建侧"那一节 —— 那条 ⚠️ 终于可以划掉了。

### 验收

1. `./gradlew build` → `BUILD SUCCESSFUL`，零 error
2. 6 个断言测试 **122,204 条全绿**（28 + 80007 + 12301 + 30 + 91 + 29747）
3. `verifyDistIsolation` → 132 个非 client 类无一引用客户端类型；
   `verifyServiceFiles` → 16 个类全部存在
4. `./gradlew runServer` → `Done (5.992s)!`，**零 ERROR**，
   `Found 7 mod requirements (2 mandatory, 5 optional)`、`0 missing`
   （新加的四条可选依赖声明都被吃下了），日志里**没有**任何客户端类的加载行

**验不到的还是界面。** 这台机器上没有显示环境（无 `DISPLAY`、无 `Xvfb`），
`runClient` 跑不起来。浏览器那块面板、取景框、传送石选点界面**都要在本机
`./gradlew runClient` 肉眼过一遍**。MCEF 还要额外注意：它首次运行会去下载
约 200 MB 的原生库，第一次点开浏览器多半会看到"MCEF 还没就绪"。

### ⬜ 下一刀

移植本身到此为止，剩下的都不是"搬代码"了：

1. **进游戏逐个 App 点一遍** —— 最要紧的一步，编译器管不了画得对不对
2. **配置界面**：那边挂的是 NeoForge 自带的 `ConfigurationScreen`，
   Forge 1.20.1 **没有内置的**，要自己写一整套。这是唯一一处
   `MCphoneClient` 上还缺的东西
3. **Parchment 映射**：现在用的是官方混淆表，没有参数名
4. ~~**GuideME**~~ ✅ 第六刀核实，本来就是通的（原先那句"1.20.1 上没有"是错的）

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
4. ~~注册表与物品~~ ✅ 物品数据已退回 NBT（`PhoneItemData`），设备名能存能同步
5. ~~界面壳与主屏~~ ✅ 第四刀做完，手机能开机
6. ~~玩家数据 Capability~~ ✅ 骨架已成（`ModCapabilities` / `PhonePlayerData`），
   壁纸与笔记两个字段已就位。**再加字段时必须回去补 `onPlayerClone`**
7. ~~服务端处理函数~~ ✅ 第五刀补齐，基本功能全部就位
8. **进游戏逐个 App 点一遍** —— **现在最要紧的、也是唯一挡在 1.0.0 前面的一步**。
   三十多个包与三个新联动都只在编译器与专用服务端上验过，画得对不对、
   点得动点不动，只能进游戏看
9. ~~加 `verifyDistIsolation`~~ ✅ 第六刀做完，连 `verifyServiceFiles` 一起
10. ~~联动模组：Waystones 与 MCEF 两条要照 1.20.1 的 API 重写~~ ✅ 第六刀做完，
    FTB Quests 与 GuideME 也逐个核实过了。**联动表上不再有"没接"的条目**
11. ~~版本号~~ ✅ 第六刀后升到 **0.10.0**。刻意不追 main 的 1.8.x：
    两支功能不等价时用同一个号等于骗人，理由写在 gradle.properties 里。
    **1.0.0 留给"进游戏验过"之后** —— 联动这一条已经不欠了

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
