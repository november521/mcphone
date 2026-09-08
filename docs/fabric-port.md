# MCphone Fabric Port (1.21.1)

This directory is a standalone **Fabric 1.21.1** port of
[november521/mcphone](https://github.com/november521/mcphone) (NeoForge 1.21.1),
based on upstream `main` at v1.9.2 (`b394668`).

## What is included

- Full Fabric Loom build: `./gradlew build` produces `build/libs/mcphone-1.9.2-fabric.1.jar`.
- Uses **official Mojang mappings** (`loom.officialMojangMappings()`), so the Java
  sources stay byte-for-byte close to the NeoForge upstream — only loader glue differs.
- Zero server/client split source sets: client-only code stays in `/client/` packages,
  and a Gradle `verifyDistIsolation` task enforces that dedicated servers never load it.
- SPI app system (`META-INF/services`) works unchanged (Java ServiceLoader).

## What changed vs upstream (summary)

| Area | NeoForge upstream | This Fabric port |
|---|---|---|
| Build | ModDevGradle + neoforge.mods.toml | Fabric Loom + `fabric.mod.json` |
| Entry | `@Mod` MCphone / `@Mod(dist=CLIENT)` MCphoneClient | `ModInitializer` / `ClientModInitializer` |
| Registry | `DeferredRegister` | `Registry.register(...)` at init |
| Networking | `PayloadRegistrar` + `PacketDistributor` | `PayloadTypeRegistry` + `ServerPlayNetworking` / `ClientPlayNetworking` |
| Config | NeoForge `ModConfigSpec` (TOML) | Lightweight JSON under `config/` + a server→client sync packet |
| Player data | NeoForge `AttachmentType` | Fabric `fabric-data-attachment-api-v1` (`AttachmentRegistry`) |
| Key bindings | NeoForge `KeyMapping` + `KeyConflictContext` | Vanilla `KeyMapping` + `KeyBindingHelper` |
| Key modifier | NeoForge `KeyModifier` | Vendored `core/client/KeyModifier.java` (same semantics) |
| Key events | NeoForge `InputEvent.Key` | `KeyboardHandlerMixin` forwarding raw GLFW |
| Custom audio stream | NeoForge `SoundInstance.getStream()` override | `NetSongSound` unique `Sound` path + `SoundEngineMixin` redirect |
| Optional deps | Curios, Waystones+Balm, MCEF, NetMusic, Patchouli, IntegratedDynamics, FTB Quests | Waystones+Balm, MCEF, NetMusic, Patchouli compile in; FTB Quests via reflection; **Curios and Integrated Dynamics omitted** (no 1.21.1 Fabric build) |

## Known differences / honest gaps

1. **Curios (belt slot) unavailable**: Curios has no Fabric build for 1.21.1.
   `PhoneLocation` only supports hand/inventory; `PhoneItem.isCarriedBy` only checks inventory.
2. **Integrated Dynamics**: no Fabric build and its NeoForge-specific crash workaround is gone.
3. **Config files changed format** from NeoForge TOML to JSON (`config/mcphone-client.json`,
   `<world>/serverconfig/mcphone-server.json`). Keys are unchanged.
4. **In-game NeoForge "Configuration" screen** does not exist on Fabric; all client config is
   changed from inside the phone UI, same as NeoForge's gameplay paths.
5. **Back-navigation** upstream v1.9.2 already covers the newer pages; the earlier P0 static
   route-table refactor was therefore **not** carried.
6. **Carried P0 value-add**: `core/client/anim/{Easing,Animator,UiMotion}.java` (pure classes)
   + `SettingsList` hover fade using `UiMotion.HOVER_MS` (90 ms). `docs/AnimMotionTest.java`
   passes 18 checks.

## Build & verify

```bash
./gradlew build            # compile + SPI verify + dist-isolation + remapJar
./gradlew runClient        # interactive client (needs a display)
./gradlew runServer        # dedicated server smoke (accept EULA in run/eula.txt first)
```

Pure animation unit test:

```bash
javac -d /tmp/animtest src/main/java/com/november/mcphone/core/client/anim/*.java docs/AnimMotionTest.java
java -cp /tmp/animtest AnimMotionTest
```

## Layout notes

- Shared networking classes contain only server (C2S) handlers; all S2C client receivers live
  in `/client/` classes (`ClientNetworking`, `*NetworkingClient`) so dedicated servers never
  load `ClientPlayNetworking`.
- Mixins (`KeyboardHandlerMixin`, `KeyMappingAccessor`, `SoundEngineMixin`) are client-only,
  declared under `client` in `mcphone.mixins.json`.

## 1.10.1-beta.1 同步（2026-09-08，merge 6f4947a）

上游 v1.9.3→v1.10.1-beta.1 已合入（终端 App、副手 HUD、3D 模型、快捷键直达、界面大小页）。
上游 1.10.1 的加载器隔离重构（MCphoneNetwork / PhoneItemData / PhonePlayerData /
ModPresence 门面）把 1.9.2 移植时的接缝补丁收编了：调用面代码逐字取上游，Fabric 差异
只剩门面实现——

- `MCphoneNetwork.registerToClient`：候车室模式（共享阶段登记编解码 + 挂起接收器，
  `core/client/ClientNetworking` 在客户端启动时领走注册）。S2C 处理函数回到共享
  `*Networking` 类，四个 feature `*NetworkingClient` 类删除。
- `PhonePlayerData`：Fabric 附件直转（getAttachedOrCreate / setAttached）；
  PHONE_TERMINAL 的客户端同步 NeoForge 靠附件 sync()，这里手工补
  `SyncPhoneTerminalPacket`（写入时发 + JOIN 推初值）。
- 终端 App 的 Fabric 真实后端是 **RS 与 Tom's Storage**（AE2/ae2wtlib 在 1.21.1
  没有 Fabric 构建，其集成只是 compileOnly 死代码）；TR Energy 4.1.0 取自 RS 发布包
  内嵌 jar（libs/energy-4.1.0.jar）。
- 断言测试挂进 check（上游做法原样采纳，11 份随 build 跑）。

完整决策与遗留见 `D:\Claude_ds\mcphone-fabric-handoff-1.10.1-beta.1-fabric.1.md`。

## 1.10.1-beta.1-fabric.2：两处修复 + 文件选择器

- **NetMusic 1.2.x 放不出声**：1.2.x 的客户端播放类在 `netmusic.audio`，1.5.x 挪到了
  `netmusic.client.audio`；我们按 1.5.2 编译，写死了新包名，于是 1.2.x 上
  `MusicPlayManager` 抛 NoClassDefFoundError（只有日志里看得见）。`NetMusicPlayback`
  改为反射依次探测两代包名（两代签名一致）。服务端的 `ItemMusicCD` 两代同路径，
  所以 CD 认得出、只是不响——这条差异正好解释了症状。
- **Windows 上「打开文件夹」无反应**：`Util.getPlatform().openPath` 走
  `rundll32 url.dll,FileProtocolHandler`，对目录静默失败（实测：0 个资源管理器窗口，
  而 `explorer.exe` 打开 1 个）。上游同一行代码，非移植引入。新增 `FolderOpener`
  在 Windows 走 explorer.exe、其他平台保留 openPath、兜底 AWT Desktop；
  壁纸/相册/表情三处统一走它。
- **新功能：文件选择器**（移植 AtomChat）：系统原生选择器抬不到 Minecraft 全屏窗口之上，
  所以用 `JFileChooser` + 自带 always-on-top `JFrame` + FlatLaf（内嵌 jar-in-jar），
  带行内缩略图与右侧实时预览。接在壁纸页的「选择图片」上，
  `WallpaperStore.importFile` 复制进目录且不覆盖同名文件。
  `docs/ImagePickerTest.java`（19 项）钉住后缀白名单与"不放大"两条静默规则。
