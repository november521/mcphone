# mcphone Fabric 1.21.1 移植 —— 项目状态与待办交接（2026-09-07）

> 本文档是给下一个接手 agent 的独立交接材料。时间点：2026-09-07 10:43。
> 交接人：int酱（本喵）；用户：扭曲 / E33EPUS。

## 1. 项目一句话

把 NeoForge 1.21.1 的 **mcphone**（手机模组）移植成 **Fabric 1.21.1 独立 fork**，放在
`D:\Claude_ds\mcphone-fabric`，当前版本 `1.9.2-fabric.1`，已部署到用户的
`1.21.1-CCB` Fabric 整合包。

## 2. 仓库与构建现状

- 仓库：`D:\Claude_ds\mcphone-fabric`
- 分支：`main`，工作区干净
- 关键 commit：
  - `b394668` 上游 NeoForge 1.9.2 基线
  - `2aa9cb7` port: add Fabric 1.21.1 support as standalone Loom build
  - `664fe07` fix: use MixinExtras WrapOperation for SoundEngine stream hook
  - `7b9a8d5` fix: drop Forge-only book sources from Fabric About list
- 构建链：
  - Loom `1.15.3` + Gradle wrapper `9.2.1`
  - `loom.officialMojangMappings()`（源码全用 Mojang 官方名，和 NeoForge 上游一致）
  - fabric-loader `0.19.5`（开发期）/ 用户运行时 fabric-loader `0.17.2`
  - fabric-api `0.116.17+1.21.1`（编译）/ 用户运行时 `0.116.6+1.21.1`
  - `modCompileOnly`：Waystones `21.1.42+fabric`、Balm `21.0.65+fabric`、MCEF `2.1.6-1.21.1`、
    NetMusic `1.5.2-fabric`、Patchouli `1.21.1-93-fabric`
  - `include` JavaMP3
- 已部署 jar：
  `D:\Myworld\.minecraft\versions\1.21.1-CCB\mods\mcphone-1.9.2-fabric.1.jar`
  （当前部署版本对应 commit `7b9a8d5`）

## 3. 移植范围与已拍板的决策

- 范围：**core-App 全量移植**；P0 动画纯类（Easing/Animator/UiMotion）已带入。
- **Curios**：Fabric 1.21.1 无构建 → 不联动，`PhoneLocation` 只有 InHand / InInventory。
- **Integrated Dynamics / GuideME / Immersive Engineering**：Fabric 1.21.1 无版本 → 已删适配器和 About 来源。
- **FTB Quests**：走反射零编译依赖，Fabric 1.21.1 有 `ftb-quests-fabric`，可运行时直用。
- **Waystones 特供版（Wraith Waystones / fwaystones）**：用户已拍板 **不联动**，只保留官方
  Waystones（`waystones` + Balm）联动。理由：当前包用官方版、上游只有官方版、两个传送石碑 mod 同装会打架。
- 用户整合包 Waystones 重复 jar 已清理，现在 mods 目录只剩：
  `[传送石碑／指路石] waystones-fabric-1.21.1-21.1.42.jar`

## 4. 两个待处理问题

### 4.1 Waystones 传送石 App：“服务端未装或版本不兼容”【根因已定位，等重启验证】

现象（用户原话）：
> 只留了最新版 waystone，但 mcphone 的传送石 app 显示服务端未装或版本不兼容。

已拿到的日志证据（`.../logs/latest.log`）：

```
[10:36:00] [Server thread/ERROR]: 打开传送石选点界面失败（Waystones 版本可能不兼容）
java.lang.NoClassDefFoundError: net/blay09/mods/waystones/menu/WaystoneSelectionListBuilder
    at knot/com.november.mcphone.compat.WaystonesCompat.openSelectionInternal(WaystonesCompat.java:166)
Caused by: java.lang.ClassNotFoundException: net.blay09.mods.waystones.menu.WaystoneSelectionListBuilder
```

根因：

- 报错的这次游戏启动时，Fabric Loader 实际加载的是 **Waystones 21.1.19**（latest.log 的 mod list
  里写 `- waystones 21.1.19`），不是现在 mods 目录里那个 21.1.42。
- 已实锤：下载并检查 `21.1.19+fabric-1.21.1` jar，里面 **没有**
  `net.blay09.mods.waystones.menu.WaystoneSelectionListBuilder`；
  而当前目录的 `21.1.42` jar **有**这个类。
- 说明报错时 21.1.19 和 21.1.42 两个 jar 同时存在，Loader 加载了旧版；后来用户删了旧 jar，
  但**游戏没重启**，内存里仍是旧版。

下一步动作（交给接手 agent 先让用户做）：

1. **完全退出 Minecraft**（不是退到标题界面），确认 mods 目录只有 21.1.42 一个 waystones jar。
2. 重新启动 `1.21.1-CCB`，进存档打开传送石 App。
3. 如果成功 → 关闭此 issue。
4. 如果仍失败 → 立刻收集：
   - `D:\Myworld\.minecraft\versions\1.21.1-CCB\logs\latest.log`
   - `D:\Myworld\.minecraft\versions\1.21.1-CCB\debug.log`
   - mods 目录 `ls -l` 列表
   重点看 `- waystones` 行和 `WaystoneSelectionListBuilder` 相关栈。

代码侧暂无改动需要：21.1.42 与编译依赖同版本，且类存在。

### 4.2 MCEF 浏览器打不开 B 站等网站【未复现、未定位，需要用户配合复现】

现象（用户原话）：
> mcef提供的游戏内浏览器，无法打开网站，比如bilibili。看上游是怎么做的

已完成的调查结论：

1. **mcphone 上游（NeoForge 1.21.1）的 MCEF 集成和 Fabric 版逐字相同。**
   对以下文件做 diff，除 `ModList.get().isLoaded()` → `FabricLoader.isModLoaded()` 外无差别：
   - `feature/browser/client/BrowserScreen.java`
   - `feature/browser/client/McefBackend.java`
   - `feature/browser/client/IBrowser.java` / `IBrowserBackend.java` / `BrowserApp.java`
   - `BrowserBackends.java` 只有 mod 判断那行不同。
2. **MCEF 自己的 `ExampleScreen` 也是同一套模式**：`MCEF.createBrowser(url, transparent, w, h)`、
   每帧用 `getRenderer().getTextureID()` 贴纹理、`sendMouse*` / `sendKey*` 转发。
   差异只有：Example 用 `transparent=true`、先创建后 resize、先 `super.render()`；mcphone 用
   `transparent=false`、创建时带尺寸、自己画背景避免二次暗化。这些不太可能导致“B 站打不开”。
3. **日志证明 MCEF 本身网络/渲染是通的**：`debug.log` 里有 Bing 首页的 CEF console 输出
   （`https://cn.bing.com/` 等），说明页面加载和 JS 都跑起来了。
4. 目前 **没有抓到任何 bilibili 访问记录**（latest.log / 历史 gz 里都没有 `bilibili` / `b23` /
   `ERR_` 相关行），所以还不能判断是白屏、加载失败、还是视频不能播。

MCEF 侧已知背景（给接手 agent 参考）：

- MCEF 源码（CinemaMod/mcef）里初始化固定加 switch：
  `--autoplay-policy=no-user-gesture-required`、`--disable-web-security`、`--enable-widevine-cdm`。
- 默认没自定义 user-agent 时，`CefUtil` 会设 `user_agent_product = "MCEF/2"`。
  已知 MCEF 因此被 Google 登录拦过（“This browser or app may not be secure”）。
- MCEF 2.x 维护者声称二进制已含 H.264 等 codec（issue #28 评论），所以“B 站视频不能播”不一定是 codec。
- 用户机器上 MCEF 配置在
  `D:\Myworld\.minecraft\versions\1.21.1-CCB\config\mcef\mcef.properties`：
  `user-agent=null`、`use-cache=true`、`skip-download=false`。

建议接手 agent 的排查顺序（需要用户配合，按层隔离）：

1. **先让用户说清症状**：是白屏？一直转圈？显示“无法访问此网站”？还是网页能开但视频不能播？
   有截图最好。
2. **换已知简单 HTTPS 站点做对照**：`https://example.com`、`https://www.bing.com`、
   `https://www.baidu.com`，确认只有 B 站类网站挂还是所有非 Bing 都挂。
3. **直接访问裸域名 vs www**：让用户分别试 `https://bilibili.com` 和 `https://www.bilibili.com`。
4. **临时改 MCEF user-agent 试验**：把 `config/mcef/mcef.properties` 的
   `user-agent=null` 改成一条真实 Chrome UA（例如
   `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36`），
   重启后试 B 站。如果好了，说明是默认 UA 被站点策略拦。
5. **看 CEF 是否报加载错误**：mcphone 目前没有挂 `CefLoadHandler`，看不到 `onLoadError`。
   若需要诊断，可以在 `McefBackend` 里通过 `MCEF.getClient().addLoadHandler(...)` 打日志
   （onLoadError / onLoadEnd / httpStatusCode），或直接写个临时调试分支。
6. **清 MCEF Chromium 缓存试验**：删除
   `D:\Myworld\.minecraft\versions\1.21.1-CCB\mods\mcef-cache` 后重启再试。
   注意：这只删页面缓存/Profile，不会重新下载原生库（原生库在 `mods/mcef-libraries`）。
7. **和 MCEF 自身示例对照**：如果 MCEF 的开发版示例能开 B 站而 mcphone 不能，问题在 mcphone 的
   BrowserScreen 输入/导航；如果 MCEF 示例也不能，问题在 MCEF/环境/站点策略。
   （MCEF 示例只在开发环境注册，普通整合包不出现；接手 agent 可在 dev runClient 里验证。）

## 5. 尚未完成的遗留项（按优先级）

- [ ] **P0 实机验证 Waystones**：按 4.1 重启后确认传送石 App 能打开官方选点界面。
- [ ] **P0 复现/定位 MCEF B 站问题**：按 4.2 先拿到症状与对照结果。
- [ ] **NetMusic 运行路径实机验证**：编译期 NetMusic 1.5.2，用户包是 1.2.1-fabric；网络音乐播放
      （`NetSongSound` + SoundEngine `@WrapOperation`）还没在实机确认。
- [ ] **Waystones 21.1.42 + Balm 21.0.65 运行路径实机验证**：即 4.1 重启后验证。
- [ ] **MCEF / Patchouli / FTB Quests 运行路径冒烟**：MCEF 即 4.2；Patchouli 阅读 App 与
      FTB Quests 反射入口还没逐项实测。
- [ ] **文档与记忆回写**：`docs/fabric-port.md`、`~/.dsh/memory/project_mcphone-collab.md` 还停留在
      commit `2aa9cb7` 前后，需要补 `664fe07`、`7b9a8d5`、Wraith 不联动决策、Waystones 21.1.19
      根因、MCEF 调查结论。

## 6. 环境速查（给接手 agent）

- 用户整合包实例：`D:\Myworld\.minecraft\versions\1.21.1-CCB`
- mods：`D:\Myworld\.minecraft\versions\1.21.1-CCB\mods`
- 最新日志：`D:\Myworld\.minecraft\versions\1.21.1-CCB\logs\latest.log`
- CEF console：`D:\Myworld\.minecraft\versions\1.21.1-CCB\debug.log`
- MCEF 配置：`...\config\mcef\mcef.properties`
- Waystones 当前 jar：`...\mods\[传送石碑／指路石] waystones-fabric-1.21.1-21.1.42.jar`
- 构建命令（在 `D:\Claude_ds\mcphone-fabric`）：
  - `./gradlew --no-daemon build`
  - 产物：`build/libs/mcphone-1.9.2-fabric.1.jar`
- 部署 = 复制 jar 到 `...\1.21.1-CCB\mods\` 覆盖同名文件。
- 代码约束提醒：
  - 源码必须保持 **Mojang 官方映射名**。
  - 客户端专用类必须放 `/client/`（或 `/mixin/`）包，否则 `verifyDistIsolation` 会拦。
  - Mixin 方法统一 `mcphone$` 前缀。
  - MCEF / Waystones 等可选依赖的“判断是否加载”和“真正调 API”必须分两个方法，
    避免没装对应 mod 时 `NoClassDefFoundError`。
