# MCphone 附属接口文档

给附属模组开发者：做一个手机 App、画在手机屏幕里、往应用商店里塞东西、给操作定个价。

**对外的只有 `com.november.mcphone.api` 这一个包。** 其余（`core`、`feature`、`compat`、`util`）
都是内部实现，随时会改名会消失，别引用。这条规矩写在
[`MCphoneApi`](../src/main/java/com/november/mcphone/api/MCphoneApi.java) 的第五条里。

当前 API 代号：**`MCphoneApi.VERSION = 1`**。本文描述的全部内容都属于它。

---

## 一张总表

"起于"指**在现在这个包名下**从哪一版开始有——包名也是 API，挪过包的类按挪完那一版算。

| 类型 | 干什么 | 怎么注册 | 哪一端 | 起于 |
|---|---|---|---|---|
| `client.app.IPhoneApp` | 一个 App | SPI | **仅客户端** | 1.0.46 |
| `client.app.RequiredMod` | 前置 / 联动模组的声明 | 记录，直接 new | 仅客户端 | 1.0.46 |
| `client.ui.IPhonePage` | 画在手机屏幕**里**的一页 | `IPhoneApp.openPage()` 返回 | 仅客户端 | 1.2.13 |
| `client.ui.PhoneCanvas` | 一帧的绘制上下文 | MCphone 传给你 | 仅客户端 | 1.2.13 |
| `client.ui.PhoneStyle` | 手机当前的配色 | `canvas.style()` | 仅客户端 | 1.2.13 |
| `client.store.IAppSource` | 商店里的 App 从哪来 | SPI | 仅客户端 | 1.0.46 |
| `client.store.AppInfo` | 商店列表里的一条 | `AppInfo.builder()` / `of()` | 仅客户端 | 1.2.12 |
| `cost.ICost` | 「要花点什么」 | 直接构造 | **两端** | 1.0.40 |
| `cost.ItemCost` / `cost.EmcCost` | ICost 的两个实现 | 直接构造 | 两端 | 1.0.40 |
| `cost.IAppPriceProvider` | 给 App 报价 | SPI | 两端 | 1.0.40 |
| `cost.IEmcWallet` / `cost.EmcWallets` | 接一个 EMC 来源进来 | `EmcWallets.set()` | 两端 | 1.0.40 |
| `MCphoneApi.VERSION` | API 代号 | 读常量 | 两端 | 1.2.12 |

**三个走 SPI 的接口**，服务文件放 `src/main/resources/META-INF/services/`：

```
com.november.mcphone.api.client.app.IPhoneApp
com.november.mcphone.api.client.store.IAppSource
com.november.mcphone.api.cost.IAppPriceProvider
```

文件名就是接口全名，内容是你的实现类全名，一行一个。

> 一个附属构造失败不会中断整个扫描（见 `util/SpiLoader`），但那个 App 就是没了，
> 而且只在日志里留一行。别指望它。

---

## 0. 三分钟：做一个能点开的 App

### 第一步：依赖

MCphone 是 NeoForge 模组，用 ModDevGradle（不是 ForgeGradle，没有 `fg.deobf` 这种东西）。
目前**没有公开 maven**，最省事的办法是把 jar 丢进你项目的 `libs/`：

```gradle
dependencies {
    compileOnly files("libs/mcphone-1.9.0.jar")
    // 想在开发环境里真跑起来，再加一行
    localRuntime files("libs/mcphone-1.9.0.jar")
}
```

再在 `neoforge.mods.toml` 里声明依赖，让加载顺序正确：

```toml
[[dependencies.yourmod]]
    modId = "mcphone"
    type = "required"          # 可选依赖写 "optional"
    versionRange = "[1.2.13,)" # 用到 IPhonePage 就是这一版起；只做图标可以 [1.0.46,)
    ordering = "AFTER"         # 必须 AFTER：你要用的注册表得先就位
    side = "BOTH"
```

### 第二步：实现 `IPhoneApp`

```java
public final class CalculatorApp implements IPhoneApp {

    @Override
    public ResourceLocation getId() {
        return ResourceLocation.fromNamespaceAndPath("mymod", "calculator");
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("mymod.app.calculator");
    }

    @Override
    public ResourceLocation getIconTexture() {
        return ResourceLocation.fromNamespaceAndPath("mymod", "textures/app/calculator.png");
    }

    @Override
    public void onPress() {
        Minecraft.getInstance().setScreen(new CalculatorScreen());
    }
}
```

四个方法是必须实现的，其余全是 `default`。

**别继承 MCphone 内建的 `PhoneApp` 基类**——它把命名空间写死成 `mcphone`，是给内建 App 用的，
而且它在 `core` 包里，不是 API。直接实现接口。

### 第三步：SPI 注册

```
文件: src/main/resources/META-INF/services/com.november.mcphone.api.client.app.IPhoneApp
内容: com.yourmod.client.CalculatorApp
```

### 第四步：语言文件 + 贴图

```
语言: assets/mymod/lang/en_us.json → { "mymod.app.calculator": "Calculator" }
      中文另开 zh_cn.json，两边的键要对齐

贴图: assets/mymod/textures/app/calculator.png
      20×20、PNG-32。路径要和 getIconTexture() 返回的一致
```

不放贴图也能跑：缺图时原版会画成紫黑格，不崩，但玩家看得见。

---

## 1. `IPhoneApp` —— 一个 App 的全部方法

| 方法 | 默认 | 干什么 |
|---|---|---|
| `getId()` | **必须实现** | 唯一标识，命名空间用你自己的 modid |
| `getDisplayName()` | **必须实现** | 主屏图标下面那行字 |
| `getIconTexture()` | **必须实现** | 20×20 的图标 |
| `onPress()` | **必须实现** | 被点开时干什么。覆盖了 `openPage()` 就不会被调到 |
| `openPage()` | `null` | 返回一页画在手机**里**的界面，见第 2 节 |
| `renderIcon(g,x,y,size,partialTick)` | 画 `getIconTexture()` | 想自己画图标才覆盖 |
| `getBadgeCount()` | `0` | 图标右上角那个数，像未读红点 |
| `isAvailable()` | 按 `requiredMods()` 判 | false 则主屏与商店里都不出现 |
| `requiredMods()` | 空 | 硬前置：缺了整个 App 不可用 |
| `companionMods()` | 空 | 联动：沾了哪个模组的光 |
| `isPreinstalled()` | `true` | true 首次发现即上主屏；false 进商店等玩家下载 |
| `isSystemApp()` | `false` | true 则不可被玩家卸载 |
| `onUninstall()` | 空 | 玩家卸载时收尾 |
| `getVersion()` / `getAuthor()` / `getDescription()` | `"1.0.0"` / `""` / `""` | 详情页上的三行 |

### `openPage()` 与 `onPress()`

覆盖了 `openPage()` 之后 `onPress()` 不会被调用，但接口仍要求你实现它——留空，
或者写一条旧版 MCphone 上的退路（那时没有 `openPage`，只能自己 `setScreen`）。

### 快捷键：你不用做任何事（1.9.2 起）

玩家可以在「设置 → App 管理器 → 你的 App」里给它绑一个键，在世界里按一下就开机并直接
进你这一页。**你不需要注册任何东西**，绑定按 App id 走，附属和内建一视同仁。

有一点值得知道：快捷键走的是**和点图标完全同一条路**——先开机，再调你的 `openPage()`
／`onPress()`。所以你在 `onPress()` 里写
`if (Minecraft.getInstance().screen instanceof PhoneScreen ps)` 这种判断照样成立，
不必为快捷键补一条分支。

绑定存在玩家自己的客户端配置里（`config/mcphone-client.toml` 的 `appHotkeys`），
支持 Ctrl / Shift / Alt 的组合键，一个组合只能属于一个 App。已经被别的 App 或别的
键位占着时，界面会先说被谁占了，玩家再按一次同一个组合就照绑——**冲突不拦死**，
所以别假设你的 App 那个键一定是独占的。

### `requiredMods()` / `companionMods()` / `isAvailable()` —— 这里有个坑

```java
public record RequiredMod(String modId, String displayName) {}
```

`displayName` 要**写死**，别在运行时去查——要显示它的时候，那个模组多半正是没装的那一个。

三者的关系：

| 你的情况 | 怎么声明 | `isAvailable()` |
|---|---|---|
| 缺了对方，这个 App 连加载都不该加载 | `requiredMods()` | 不用管，默认实现会判 |
| 装了对方多一块内容，缺了照样能用 | `companionMods()` | 不用管 |
| **靠对方撑着，缺了这一格就没内容** | `companionMods()` | **必须自己覆盖** |

⚠ 第三种最容易写错：**联动声明不参与默认的可用性判断**（默认实现只看 `requiredMods()`）。
照默认走就是"永远可用"，于是对方没装时主屏上会多一个点了没反应的图标。内建的「任务书」
（FTB Quests）就是这么写的，可以照抄。

声明之后玩家在两个地方看得到：「设置 → 关于」的联动模组列表**一律列出**，告诉他装没装；
商店的「联动 App」页**只在你的 App 当前不可用时**收它，并写明缺的是哪个模组。

### `isAvailable()` 只问一次

在目录构建时问一次，之后不复查。别把会随时间变化的条件放进去——那属于 `onPress()`。

### `getBadgeCount()`

**每帧、每个图标各问一次。** 只能读现成的值。要现拉数据就自己按时间间隔限流，
别在这里发网络包或读文件。

### 动态图标

`renderIcon` **每帧都被调用**，所以图标可以是动的——覆盖它，自己按时间挑一帧画出来就行：

```java
private static final int FRAMES = 8;
private static final int FRAME_MS = 100;

@Override
public void renderIcon(GuiGraphics g, int x, int y, int size, float partialTick) {
    int frame = (int) ((System.currentTimeMillis() / FRAME_MS) % FRAMES);   // 一张横向雪碧图
    RenderSystem.enableBlend();
    RenderSystem.defaultBlendFunc();
    g.blit(SHEET, x, y, size, size, frame * size, 0, size, size, size * FRAMES, size);
    RenderSystem.disableBlend();
}
```

`partialTick` 是本帧的插值系数，做平滑动画（旋转、缩放这类不按整 tick 跳的）时用得上。
按墙上时间挑帧则不需要它——像上面这样。

**换肤那条路做不了动画**：皮肤贴图是一张独立纹理，不进图集，原版 `.mcmeta` 那套动画机制
对它不生效。给 `app/xxx.png` 配一个 `.mcmeta` 不会有任何反应。要动就得写代码。

### 覆盖 `renderIcon()` 的话，记得自己开混合

默认实现替你开了。你要是自己画，**别直接 `g.blit(ResourceLocation, ...)`**：原版那条
blit 从头到尾没碰过混合状态，而 GUI 里每画完一次 fill 或一行字，RenderType 收尾都会
`disableBlend`。结果就是轮到你的贴图时混合基本是关的，**半透明像素被当成不透明画**——
抗锯齿的边缘变硬发脏，整块半透明的图变成实心。

```java
@Override
public void renderIcon(GuiGraphics g, int x, int y, int size, float partialTick) {
    RenderSystem.enableBlend();
    RenderSystem.defaultBlendFunc();
    g.blit(myTexture, x, y, size, size, 0, 0, size, size, size, size);
    RenderSystem.disableBlend();     // 收尾关掉：GUI 代码普遍假定画完是关着的
}
```

（MCphone 内部有个 `GuiUtil.drawTexture` 就是干这个的，但它在 `core` 包里，不是 API，
所以这几行得你自己写。）

---

## 2. 画在手机屏幕里：`IPhonePage` + `PhoneCanvas` + `PhoneStyle`

不覆盖 `openPage()` 的话，你的 App 被点开只能 `setScreen` 整个跳出手机——状态栏没了、
导航栏没了、壁纸没了、返回键要自己实现、关掉之后回哪儿也要自己记。而内建的聊天、
记事本、相册全都是手机屏幕里的一页。这个接口就是把那扇后门变成正门。

### 最小的一页

```java
public final class CalculatorApp implements IPhoneApp {
    @Override public IPhonePage openPage() { return new CalculatorPage(); }
    @Override public void onPress() {}          // 覆盖了 openPage，这里不会被调到
    // ... 另外三个必须实现的方法
}

public final class CalculatorPage implements IPhonePage {
    @Override
    public void render(PhoneCanvas c) {
        c.graphics().drawString(c.font(), "1 + 1 = 2",
                c.x() + 4, c.y() + 4, c.style().bodyColor(), false);
    }
}
```

**只有 `render` 是必须实现的**，其余全是 `default`。这不只是图省事：按兼容承诺，
以后往这个接口加能力也只会加 `default` 方法，你的页面不会因为 MCphone 升级而编译不过。

### `IPhonePage` 的方法

| 方法 | 默认 | 说明 |
|---|---|---|
| `render(PhoneCanvas)` | **必须实现** | 每帧调用 |
| `mouseClicked(x,y,button)` | `false` | 屏幕**绝对**坐标。返回 true 表示我处理了 |
| `mouseScrolled(x,y,amount)` | `false` | 同上 |
| `keyPressed(key,scan,mods)` | `false` | ESC 不走这里，见下 |
| `charTyped(ch,mods)` | `false` | 输入框要用 |
| `capturesKeyboard()` | `false` | **有输入框就必须返回 true**，见下 |
| `onBack()` | `false` | 导航栏的 ◁。返回 true 表示你自己处理了 |
| `onOpen()` | 空 | 这一页被打开 |
| `onClose()` | 空 | 这一页被切走。**一定会被调到** |

### 三个非踩不可的点

**一、`mouseClicked` 里的空点击建议返回 `true`。**
返回 false 会落到 MCphone 的默认处理，而默认处理里"点手机外面＝关机"。

**二、有输入框就一定要覆盖 `capturesKeyboard()` 返回 `true`。**
原版的背包键默认是 `E`。玩家在你的输入框里打字，一按到 e 就命中背包键，手机当场关掉、
内容全丢——而打拼音时一定会按到 e，中文用户躲都躲不开。返回 true 之后按键先给你的页面。
反过来，**没有输入框就别返回 true**：那会把背包键也吃掉，玩家没法用它关手机。

**三、收尾写在 `onClose()`，不是 `onBack()`。**
ESC 直接关机，页面拦不住（玩家按了退出却什么都没发生是最糟的一种失败）。所以
"退出前存草稿"挂在 `onBack()` 上，玩家按 ESC 时它不会响。`onClose()` 则一定会被调到——
从 ◁ 走、按 ESC 走、关掉手机走、甚至断线走，都会走到它。

### `PhoneCanvas` —— 一帧的上下文

| 访问器 | 给的是 |
|---|---|
| `graphics()` / `font()` | 原版的 `GuiGraphics` 与 `Font` |
| `x()` `y()` `width()` `height()` | **内容区**矩形，已经扣掉状态栏与导航栏 |
| `mouseX()` `mouseY()` | 鼠标位置，与上面同一套坐标 |
| `partialTick()` | 插值系数 |
| `style()` | 手机当前配色，见下 |
| `hovered(rx,ry,rw,rh)` | 鼠标在不在这个矩形里。**矩形也用绝对坐标**（参数名里那个 r 是历史遗留，不是 relative） |
| `hoveredContent()` | 鼠标在不在整个内容区里 |

**坐标是屏幕绝对坐标**，可以直接传给 `GuiGraphics`，不用再加偏移。直接在
`x/y/width/height` 那个矩形里画就行——不用知道状态栏和导航栏有多高，我们改了它们的
高度你也不受影响。

**别把 canvas 存起来。** 每帧新建一个，只在那一帧里有效。存下来的那个对象里，鼠标位置和
`GuiGraphics` 下一帧就过期了，拿它画东西的后果是画在错的地方，或者直接对着已经关掉的
渲染状态动手。

> 为什么是一个对象而不是十个参数：以后想多给一个信息（比如"现在是不是横屏"），
> 加一个访问器方法就行，所有附属都不用改。十个位置参数的话，那天所有人一起编译不过。

### `PhoneStyle` —— 让你的页面长得像手机里的东西

```java
c.style().titleColor()              // 标题、当前选中项，最亮的一档
c.style().bodyColor()               // 正文
c.style().subtleColor()             // 次要信息、时间戳
c.style().accentColor()             // 强调、可点的东西
c.style().screenBackground()        // 屏幕底色
c.style().pressedOverlay()          // 按下去时盖的那一层
c.style().buttonColor()             // 按钮底
c.style().buttonHoverColor()        // 按钮悬停
c.style().buttonDisabledColor()     // 按钮禁用底
c.style().buttonDisabledTextColor() // 按钮禁用字
```

全是 ARGB（`0xAARRGGBB`），直接传给 `fill` 与 `drawString`。

**为什么是接口而不是一堆 `public static final int`**：常量会被编译器内联进你的 class 文件。
你编译时手机是深色的，之后我们换了配色，你那边的字面量还是旧值——拿到的是编译那天的颜色，
而且没有任何迹象表明哪里不对。走接口是一次真实调用，永远拿到当下的值。

### 异常兜底

每个回调都被兜住了：你这一页抛异常只会让这一页被关掉并记一条日志，不会拖垮手机界面，
更不会崩游戏。**但别指望这个**——被兜掉的异常对玩家来说就是"点开这个 App 自己弹回去了"，
一样难用。

---

## 3. 往应用商店里塞 App：`IAppSource` + `AppInfo`

商店的 App 可以不来自 SPI 扫描——远程仓库、数据包、你自己造的，都行。实现
`IAppSource`，SPI 注册，商店里就多一个分组。

| 方法 | 默认 | 说明 |
|---|---|---|
| `getId()` | **必须** | 形如 `mymod:official_repo` |
| `getDisplayName()` | **必须** | 分组标题上的名字 |
| `listAvailable(callback)` | **必须** | 列出**尚未安装**的 App |
| `install(info, onSuccess, onError)` | **必须** | 让它真正可用，交出实例或一条错误信息 |
| `isReady()` | `true` | false 时商店把这个来源标成不可用（离线、未登录、加载中） |

**线程：** `listAvailable` / `install` 可以在后台线程干活，**但回调必须切回客户端主线程**
（回调里会碰注册表和 GUI 状态）：

```java
Minecraft.getInstance().execute(() -> callback.accept(list));
```

`AppInfo` 是商店列表里的一条，只给 builder（记录的构造函数不对外，这样以后加字段不会
把所有人的调用打断）：

```java
AppInfo info = AppInfo.builder(id, displayName, sourceId)
        .icon(iconTexture)
        .version("1.0.0")
        .author("你")
        .description("一句话")
        .build();

// 已经有 IPhoneApp 实例时更省事
AppInfo info = AppInfo.of(app, sourceId);
```

---

## 4. 要花点什么：`ICost` 家族

这一套是**两端安全**的：签名里没有客户端类型，服务端代码可以放心引用。

```java
public interface ICost {
    boolean canAfford(Player player);   // 付得起吗
    boolean consume(Player player);     // 真扣。扣不动返回 false
    Component describe();               // 给玩家看的一句话
}
```

三个现成的造法：

```java
ICost.of(Items.DIAMOND, 3)                        // 三颗钻石
ICost.emc(1000)                                   // 1000 EMC，见下
ICost.matching(stack -> stack.is(Tags.Items.INGOTS), 5, // 任意五个锭
        Component.translatable("mymod.cost.ingots"))
```

`ItemCost` 与 `EmcCost` 是这两种的记录实现，也可以直接 new。

### 给 App 报价：`IAppPriceProvider`

```java
public interface IAppPriceProvider {
    Map<ResourceLocation, ICost> prices();   // App id → 价钱
}
```

SPI 注册（文件名 `com.november.mcphone.api.cost.IAppPriceProvider`）。内建 App 的价钱也是
这么来的，见 `feature/store/BuiltinAppPrices`。

### 接一个 EMC 来源：`IEmcWallet` + `EmcWallets`

`EmcCost` 自己不知道 EMC 从哪来。想让它有意义，就实现 `IEmcWallet`
（`isAvailable` / `unavailableReason` / `canAfford` / `withdraw` / `describeBalance`），
在加载阶段挂一次：

```java
EmcWallets.set(new ProjectEWallet());
```

**全局只有一个位置**，已经有人接上时再 set 会被拒绝并告警，不静默顶掉前一个。
没人接就是 `EmcWallets.NONE`——永远不可用、永远付不起（返回 true 会变成"没接 EMC 反而白送"）。

钱包实现**两端都会加载**（客户端画余额、服务端真扣），所以里面**不许**出现客户端类型，
否则专用服务器启动即崩。

---

## 5. 版本与兼容

```java
public static final int VERSION = 1;   // MCphoneApi
```

改 API 之前守的五条：

1. **已发布的接口不加抽象方法** —— 新增能力一律走 `default` 方法或新接口；
2. **已发布的记录不改构造函数** —— 对外只给 builder；
3. **不改已发布的方法签名** —— 要改就新增重载，老的标 `@Deprecated` 至少留一个大版本；
4. **包名也是 API** —— 挪一个类的包等于删了它再新建一个；
5. **这些规矩只管 `api` 包**，其余都是内部实现。

`VERSION` 每次往 API 里加东西就 +1，只增不减。附属可以 `MCphoneApi.VERSION >= n` 判断
新能力在不在——**但它只能判断语义，不能替代类加载**：

```java
// ✗ 错的：旧版上 JVM 校验这个方法时就抛 NoClassDefFoundError，轮不到那句 if
if (MCphoneApi.VERSION >= 2) {
    new SomeNewApiType().doThing();
}

// ✓ 对的：把新能力关进单独一个类，判断通过再碰它
if (MCphoneApi.VERSION >= 2) {
    NewFeatureBridge.doThing();     // 这个类里才引用新类型
}
```

---

## 6. 两端安全：哪些能在服务端碰

| 包 | 端 |
|---|---|
| `api.client` 及其子包（app / ui / store） | **仅客户端** |
| `api.cost` | 两端 |
| `MCphoneApi` | 两端 |

`api.client` 的接口签名里带 `GuiGraphics` 这类客户端类型，**实现类只能在客户端加载**。

不要从物品、方块、菜单、网络包、服务端事件处理里引用它们，也别让实现类被服务端的类
顺带加载到——后果是**专用服务器启动即崩，而且崩溃信息不会指向你的 App**。

建议：实现类放 `yourmod.client` 包下，只由客户端代码碰它。你的主类也可以直接声明成
客户端专用：

```java
@Mod(value = "yourmod", dist = Dist.CLIENT)
```

服务端要触发客户端行为时，把客户端逻辑单独放一个类、用静态方法调过去，**且方法签名里
不许出现客户端类型**——`invokestatic` 的属主类是第一次执行到时才解析的，校验期不碰它。
可照抄 `core/client/PhoneScreenOpener`。

---

## 7. 一个不在 `api` 包里的扩展点：换浏览器后端

上面说"只有 `api` 包对外"，有一个例外要交代清楚：**浏览器后端**。

`com.november.mcphone.feature.browser.client` 下的 `IBrowser` / `IBrowserBackend` /
`BrowserBackends` 是一层**不含 MCEF、JCEF 类型**的抽象——画面以 GL 纹理 id 交出来
（`IBrowser.textureId()`），输入按鼠标键盘事件转发进去。想接一个别的浏览器实现：

```java
BrowserBackends.set(new MyBackend());   // 在 MCphone 装上默认后端之前
```

与 `EmcWallets` 同一个规矩：**全局一个位置，先接上的算数**，后来者被拒绝并告警，
不静默顶掉；没人接就是 `BrowserBackends.NONE`（永远不可用，调用方不必判 null）。

```java
public interface IBrowserBackend {
    boolean isAvailable();
    Component unavailableReason();
    IBrowser create(String url, int width, int height);
}
```

`IBrowser` 那一面是 19 个方法：`textureId` / `resize` / 六个鼠标键盘事件 /
`loadUrl` / `currentUrl` / 前进后退（`canGoBack` `goBack` `canGoForward` `goForward`）/
`reload` / `isLoading` / `setFocus` / `close`。

**它为什么还在 `feature` 下**：这一层是为了把 MCEF 从浏览器 App 里隔出去而抽的，
目前只有内建的 MCEF 后端一个实现，还没经过第二个实现的检验。等它真被第二个人用过、
形状稳定了，再挪进 `api` 包——按第四条，挪包等于换一次 API，所以宁可晚挪，不要挪两次。
在那之前用它要有心理准备：**它不受上面那五条兼容承诺保护。**

---

## 这份文档不会烂掉

上面每一段示例代码都躺在 [`docs/AddonApiExamples.java`](AddonApiExamples.java) 里，
照抄一份。**它只要求编得过**，跑不跑无所谓：

```bash
CP="build/classes/java/main:build/moddev/artifacts/neoforge-<版本>-merged.jar:$(tr '\n' ':' < build/moddev/serverLegacyClasspath.txt)"
javac -cp "$CP" -d /tmp/doccheck docs/AddonApiExamples.java
```

谁改了 API 的签名，这个文件当场编不过——那就是"该回来改文档了"的信号。
文档里的方法名、参数顺序、返回类型全靠它守着，不靠人记得。

## 有没有现成的例子

有：[november521/mcphone-deepseek](https://github.com/november521/mcphone-deepseek)，
MCphone 的第一个附属，一个 DeepSeek 对话 App。它用到了本文的 `IPhoneApp` + `IPhonePage`
+ `PhoneCanvas` + `PhoneStyle`，纯客户端，独立仓库，可以整个抄结构。
