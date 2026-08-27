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

### 2. ResourceLocation 构造 —— 46 个文件，最机械的一块

**53 处** `ResourceLocation.fromNamespaceAndPath(ns, path)`，1.20.1 上要写成
`new ResourceLocation(ns, path)`。还有 `ResourceLocation.parse(s)` → `new ResourceLocation(s)`。

这是**唯一一件可以放心用 sed 批量替换**的。做完立刻编译，剩下的报错就都是别的问题了。

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
只换包名。这一条和第 2 条一样可以批量替换。

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

## 建议的推进顺序

1. 那 111 个干净文件搬过来，编译，把绘制 API 的差异清掉
2. `ModList` 与 `ResourceLocation` 两轮批量替换（第 2、5 条）
3. 注册表与物品（第 6、4 条）—— 到这里手机应该能拿在手里了
4. **加 `verifyDistIsolation`**，趁代码还少
5. 界面壳与主屏（`PhoneScreen`、`HomeGrid`、`PhoneChassis`）—— 到这里能开机了
6. 网络层骨架，先通一条包（第 1 条）
7. 玩家数据 Capability（第 3 条）—— 聊天、好友、商店都压在它上面
8. App 逐个搬，从不依赖网络的开始（时钟、天气、相册）
9. 联动模组，逐个确认后再接

每一步都能编译、能进游戏再走下一步。24k 行一次性搬过来然后调编译错误，
是这种移植最常见的翻车方式。
