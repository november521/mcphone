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
