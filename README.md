# MCphone

把一部能用的智能手机塞进 Minecraft —— 拍照、翻相册、换壁纸、听歌、装 App，还能给自己那一部手机起个名。

这个仓库放着**两个加载器 / 两个 Minecraft 版本**的工程，各自独立编译、独立发版：

| 工程目录 | Minecraft | 加载器 | 状态 |
| --- | --- | --- | --- |
| [`MCphone Neoforge 1.21.1/`](MCphone%20Neoforge%201.21.1/) | 1.21.1 | NeoForge 21.1.200+ | **正式版**，功能完整 |
| [`MCphone Forge 1.20.1/`](MCphone%20Forge%201.20.1/) | 1.20.1 | Forge 47.4.0+ | **移植中**，还不能用 —— 见下方 |

**要下载模组的看这里**：去 [Releases](../../releases)，按 tag 前缀挑版本。

## 为什么分成两个工程而不是一套代码

1.20.1 与 1.21.1 之间隔着 Minecraft 的两次大改，不是加几个 `if` 能兜过去的：

- **物品数据**：1.20.5 起从 NBT 换成了数据组件（Data Components），存取方式完全不同
- **网络层**：NeoForge 的 `CustomPacketPayload` 在 1.20.1 上不存在，那边是 Forge 的 `SimpleChannel`
- **玩家数据**：NeoForge 的 Data Attachment 对应 Forge 的 Capability，是两套模型
- **加载器本身**：`net.neoforged.*` 与 `net.minecraftforge.*` 从事件总线到注册表全都不一样

多加载器工程常见的做法是抽一层 common、各加载器写薄薄一层适配（Architectury 那一套）。这里没那么做，是因为差异不在"薄薄一层"里——上面四条每一条都穿透到业务代码。与其为了共用而把两边都写扭曲，不如各写各的，让每一支都长成它那个版本该有的样子。

代价是修一个 bug 要修两遍。认这个代价。

## 发版 tag 怎么打

一个仓库两个工程，tag 必须说清是哪一支，所以带前缀：

```
neoforge-v1.8.18     →  MCphone Neoforge 1.21.1/
forge-v0.1.0         →  MCphone Forge 1.20.1/
```

`.github/workflows/release.yml` 按前缀挑目录、并校验 tag 里的版本号与那个工程的 `gradle.properties` 一致——打了 `neoforge-v1.9.0` 而里头还写着 `1.8.18` 会直接失败，不会发出一个版本号对不上的包。

**`v1.8.18` 这类不带前缀的老 tag** 是分工程之前的，全部属于 NeoForge 1.21.1 那一支，留着不动。

## 许可

[MIT](LICENSE)。两个工程同一份许可。
