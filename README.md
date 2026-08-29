# MCphone

把一部能用的智能手机塞进 Minecraft —— 拍照、翻相册、听歌、聊天、装 App，还能给自己那一部手机起个名。

> **分支说明**：这个仓库按 Minecraft 版本 / 加载器分分支。
> 你现在看的 `1.20.1-forge` 是 **Minecraft 1.20.1 + Forge**。
> 主力版本在 [`main`](../../tree/main)（1.21.1 + NeoForge），两支功能等价。

**Minecraft 1.20.1** · **Forge 47.4.0+** · **Java 17** · 客户端与服务端都需安装

**可选依赖**（装了多点东西，不装一切照常）：

| 模组 | 装了会怎样 |
| --- | --- |
| [Curios](https://modrinth.com/mod/curios) | 手机多一个饰品栏槽位，可以挂在腰上 |
| [Waystones](https://modrinth.com/mod/waystones) + [Balm](https://modrinth.com/mod/balm) | 多一个「传送石」App |
| [MCEF](https://modrinth.com/mod/mcef) | 多一个「浏览器」App |
| [NetMusic](https://modrinth.com/mod/net-music) | 它刻出来的 CD 能塞进手机的唱片仓，走到哪儿放到哪儿 |
| [Patchouli](https://modrinth.com/mod/patchouli) | 多一个「阅读」App，整合包里的教程手册收进一个书城 |
| [GuideME](https://modrinth.com/mod/guideme) | 「阅读」里多出用它做手册的那几本（**AE2**、**现代化工艺**都硬依赖它），自动发现 |
| [Immersive Engineering](https://modrinth.com/mod/immersiveengineering) | 「阅读」里多一本《工程师手册》 |
| [FTB Quests](https://www.curseforge.com/minecraft/mc-mods/ftb-quests-forge) | 多一个「任务书」App |

**MCphone 自己一个前置都没有。** 没装对应模组时，依赖它的 App 不会出现在主屏和应用商店的普通列表里——商店里躺着一个点了会报错的东西，比它不存在更糟。但商店末格的「联动App」页照样列着它、写明缺的是哪个模组。

> [!IMPORTANT]
> **这一支还年轻。** 功能已从 1.21.1 那支完整移植，但**界面只在真游戏里点过一部分**：
> 14 个 App 里验过 3 个，MCEF / NetMusic / FTB Quests 三个联动一次都没实际跑过。
> 建议开新存档试，别拿主力存档。逐条见[最新发版说明](../../releases/latest)。

---

## 怎么拿到手机

工作台合成，一次一部：

```
玻璃   玻璃   玻璃
红染料 黄染料 蓝染料
铁锭   红石   铁锭
```

拿在手上右键开机，按 `Esc`、或点机身外的区域关机。手机放在背包或饰品槽里时按 **H** 也能开机。

`Esc` 不分层级，开到哪一页按下去都是直接关机；退回上一层走屏幕底部导航栏的 ◁。

按键都能在原版「选项 → 按键设置 → MCphone」里改：**H** 开机、**V** 拍照、**X** 退出相机。

## 功能

| App | 说明 |
| --- | --- |
| 📷 相机 | 取景框 + 分帧截图。默认 **V** 拍照、**X** 退出。照片存进游戏目录的 `screenshots/` |
| 🖼 相册 | 缩略图网格、分页、大图查看、左右切换、删除（二次确认） |
| 🎵 音乐 | 播自己的音乐（OGG / MP3 / WAV），三种循环模式、进度条、音量。另有**唱片仓**——把唱片放进手机，声音跟着你走，周围的人也听得见 |
| 🏪 应用商店 | 安装 / 卸载 App，状态按存档持久化；系统 App 标灰不可卸载 |
| ⚙️ 设置 | 更换壁纸、字体颜色、设备命名、App 管理器、关于 |
| 💬 美西螈 | 手机上的聊天 App。双向好友、会话列表、气泡界面，消息存进世界存档，对方离线也能发。在线好友那一行右下角点一下就传送过去 |
| 📝 记事本 | 随手记点东西，跟着玩家走，死了不会丢，写完能印成一本书 |
| 📦 末影箱 | 随身开自己的末影箱，就是原版那一个。**付费 App**，售价 1 × 末影箱 |
| 🗿 传送石 | 打开传送石碑的选点界面。**需要 Waystones**，**付费 App**，售价 1 × 传送石 |
| 🌐 浏览器 | 在手机里上网，点开是一块占屏幕九成的面板。**需要 MCEF** |
| 🕐 时钟 | 世界时间、游戏内日期、本局游玩时长 |
| ⛅ 天气 | 当前天气与所在生物群系 |
| 📖 阅读 | 整合包里的教程书全在这儿，常翻的收进「书架」。**需要任意一个提供手册的模组**（Patchouli / GuideME / 沉浸工程） |
| ✅ 任务书 | 整合包的任务书，开机点一下就是。**需要 FTB Quests** |

**主屏**：一页 4×5＝20 格，装多了自动分页。按住图标直接拖就能换位置，其余图标实时让位——是**插入**不是对调。拖着停在屏幕左右边上，边条亮起、停满 0.4 秒自动翻页。顺序按存档记在 `config/mcphone/installed/<存档>.json` 里。

**聊天与传送**：加好友要对方点头，聊天限定在好友之间。在线好友那一行右下角有个小图标，点它人就过去了，落点与 `/tp 玩家 玩家` 一致，跨维度也传。**服主可以关掉**：存档的 `serverconfig/mcphone-server.toml` 里 `allowFriendTeleport = false`。

上限：好友 100 人，每人待处理申请 50 条，每对会话保留最近 100 条消息，单条 256 字。

**音乐的两条路**：曲库里点一首是「耳机」——只有你听得见，能暂停、能看进度，曲库里只有你自己放进 `config/mcphone/music/` 的文件。唱片仓是「外放」——手持唱片点一下放进去，**周围人都听得见，声音跟着你走**。两条路可以同时响。

支持 OGG / OGA、MP3 / MP2 / MP1、WAV / AIFF / AU。进音乐 App 会自动重扫，不用重启。**MP3 只认 MPEG-1**（44100 / 48000 / 32000 Hz）；低采样率的 MPEG-2 放不了，曲库里那一行会变灰并写明原因。

**阅读**：三个书源任意一个在场，这一格就出现——Patchouli、GuideME、以及靠白名单单独适配的沉浸工程《工程师手册》。底部两个页签——「书城」是整合包里全部的书，「书架」是你收下的那几本，点开默认停在书架。每行右端那颗 ☆ 收藏 / 取消。顶上是搜索框，**进这一页就已经拿着焦点，直接打字**，书名 / 模组名 / 模组 id 三样都能搜。翻书仍然是 Patchouli（或 GuideME）自己在翻，进度与右键实体书完全一致。

**浏览器**：地址栏兼搜索框，打网址就开网址，打关键词就搜。MCEF 自带的快捷键可用（`Ctrl`+滚轮缩放、`Alt`+`←`/`→` 前进后退、`Ctrl`+`R` 刷新）。**第一次用要等它下载约 200 MB 的原生库**，这期间显示「MCEF 还没准备好」，不是坏了。

**壁纸 / 字体 / 设备名**：把任意尺寸的 PNG 放进 `config/mcphone/wallpapers/` 就能在设置里选；壁纸由服务端保存并同步，多人游戏中每人各自独立。字体颜色六个预设，是**客户端**设置，存在 `config/mcphone-client.toml`。设备名上限 24 字符，起过名的手机在物品栏显示该名。

**中文输入**：输入框用的就是原版聊天框那个控件，能不能打中文与原版完全一致。Minecraft 不会把输入法候选窗定位到光标处（原版聊天框也一样），打拼音基本是盲打——想稳妥就用 **Ctrl+V** 粘贴。

---

## 与 1.21.1 那一支的差别

两支功能等价，但有**三处玩家能感觉到**的不同，都是 1.20.1 的天花板：

**唱片判定变严了。** 1.21 那边查的是 `JUKEBOX_PLAYABLE` 数据组件，所以别的模组、甚至只靠 json 定义的唱片它都认得。1.20.1 上没有那个组件，曲长与音效直接挂在 `RecordItem` 这个类上，只能按类型判——**别的模组若没继承 `RecordItem` 而是自己实现一套，这边就认不出来**。

**没有配置界面。** 1.21 那边挂的是 NeoForge 自带的配置界面。Forge 1.20.1 没有内置的，暂时得手改 `config/mcphone-client.toml` 与存档里的 `serverconfig/mcphone-server.toml`。

**传送石按「拿着传送石」计价。** 服主怎么配传送石，这个 App 就怎么走（经验、冷却都按 `warpStoneXpCostMultiplier` / `warpStoneCooldown`），但**不消耗任何东西**——手机本身就当作那块石头。

其余差别（网络层、玩家数据、注册表）玩家看不见，逐条记在 [`docs/PORTING.md`](docs/PORTING.md) 里。

---

## 换肤（资源包）

界面上每个视觉元素都能用贴图替换，**放了贴图就用贴图，没放就用内置配色**，不会出现缺图变紫黑格子的情况。把 PNG 放进资源包的 `assets/mcphone/textures/` 即可，改完按 **F3+T** 重载。

**贴图位清单与两支完全相同**，四十多个位的路径、建议尺寸、缺图时退回什么颜色，见 [`main` 分支 README 的「换肤」一节](../../blob/main/README.md#换肤资源包)。也可以自己生成一张 HTML 清单：`python3 docs/make_texture_manifest.py`。

App 图标在 `app/` 下，文件名就是 App 的短名，内建十四个都是 20×20。

## 给附属模组作者

App 系统通过 SPI 开放，你的模组不需要被 MCphone 感知也能往手机里装 App。内建 App 走的是同一套机制，没有走后门。**这三个接口两支完全相同**，为 1.21.1 写的附属 App 源码在这边照样编得过：

| 做什么 | 实现 | 在 `META-INF/services/` 下登记为 |
| --- | --- | --- |
| 加一个 App | `com.november.mcphone.api.client.app.IPhoneApp` | 同名文件 |
| 自定义商店来源 | `com.november.mcphone.api.client.store.IAppSource` | 同名文件 |
| 给 App 定价 | `com.november.mcphone.api.cost.IAppPriceProvider` | 同名文件 |

前两个在 `api.client` 下，签名里有 `GuiGraphics` 这类客户端类型，**只能被客户端加载**；`IAppPriceProvider` 不在 `client` 下——价格两端都要用，实现类里不许出现客户端类型。

依赖别的可选模组时覆盖 `IPhoneApp.requiredMods()`，声明一次三件事自动发生：对方没装时你的 App 不进目录、它出现在商店的「联动App」页并写明缺什么、「设置 → 关于」里也带上它。显示名要写死，别在运行时从 `ModList` 查——要显示它的那一刻，那个模组正好没装。

**唯一与那一支不同的是网络层**：这边没有 `CustomPacketPayload`，包走 Forge 的 `SimpleChannel`。附属模组如果要自己发包，用自己的通道即可，与 MCphone 无关。

## 服主须知

**装了 [Integrated Dynamics](https://modrinth.com/mod/integrated-dynamics) 时**，MCphone 会在方块注册的末尾把 ID 全部方块的掉落表提前解析一次。这不改变任何掉落行为，也不给你多出任何功能。

在 1.21.1 那一支上它有实际作用：NeoForge 的注册回滚会在 ID 的墙上火把那里踩到空指针，把真正的错误顶掉，于是崩溃报告只剩一句 `Trying to access unbound value: integrateddynamics:menril_torch_stone`——看起来像 ID 的锅，其实 ID 只是最后一个倒下的。

**这一支上它是预防性的**：Forge 1.20.1 的回滚路径不长那样（`BlockCallbacks` 没有那个陈旧集合，`onBake` 遍历的是注册表本身），真正的错误本来就报得出来。留着是为了万一 Forge 哪天改成那种形状。细节在 `compat/IntegratedDynamicsCompat.java` 的类注释里。没装 ID 时这段代码一行都不会执行。

## 从源码构建

```bash
./gradlew build          # jar 出在 build/libs/
./gradlew runClient      # 开发环境启客户端
```

**Java 17、Gradle 8.8**，两条都与 `main` 分支不同，而且不能"统一"：

- 1.20.1 跑在 Java 17 上。用 21 编出来的 class 文件版本低版本客户端加载不了，而报的错不会提到 Java 版本
- ForgeGradle 6 不支持 Gradle 9（`main` 那支用的是 9.x），配置阶段就会炸

第一次构建要下 Minecraft、下 MCP、反编译整个 Minecraft，本机实测约 33 分钟（瓶颈是网络不是 CPU），之后每次十几秒。

`./gradlew build` 会顺带跑两道校验：`verifyDistIsolation`（非 client 包的类不许引用客户端类型，专用服务器启动即崩的那种坑）与 `verifyServiceFiles`（SPI 名单里写的类必须真的存在）。

**发版** tag 写成 `forge-v<版本>`（例：`forge-v0.10.2`）。带前缀是因为 tag 在 git 里是仓库级的、不属于任何分支，两支都用 `v1.0.0` 就撞了。

## 许可

[MIT](LICENSE)。jar 里嵌着 [JavaMP3](https://github.com/delthas/JavaMP3)（MIT，用于解码 mp3），许可证全文见 jar 内的 `THIRD-PARTY.txt`。

仓库根目录的 `TEMPLATE_LICENSE.txt` 是另一回事：那是 MDK 模板文件的 MIT 声明，按其署名要求保留，与本模组自身的许可无关。
