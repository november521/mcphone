# MCphone

把一部能用的智能手机塞进 Minecraft —— 拍照、翻相册、换壁纸、听歌、聊天、装 App。

**Minecraft 1.21.1** · **NeoForge 21.1.200+** · 客户端与服务端都需安装

[下载：Modrinth](https://modrinth.com/mod/mcphone) ·
[CurseForge](https://www.curseforge.com/minecraft/mc-mods/mcphone) ·
[更新日志](../../releases)

## 文档在 wiki

**[📖 使用手册](../../wiki/Manual)** —— 每个 App 怎么用、设置、换肤、服主须知

**[🔌 附属接口文档](../../wiki/Addon-API)** —— 做一个自己的手机 App

wiki 是文档的唯一真源，仓库里不再另存一份。

## 装了什么

十五个内建 App：相机、相册、音乐、聊天、时钟、天气、记事本、末影箱、传送石、浏览器、阅读、任务书、终端、应用商店、设置。
手机放进副手可挂到画面上；主屏可拖动排序与分页；界面可缩放；整套 UI 可用资源包换肤。

**MCphone 自己一个前置都没有。** 装了 Curios、Waystones、MCEF、NetMusic、Patchouli、GuideME、
沉浸工程、FTB Quests、AE2 / Refined Storage / Tom's Simple Storage 会各多一块内容，不装一切照常。
详见 [wiki → 可选依赖](../../wiki/Getting-Started#可选依赖)。

## 支持的版本

一个分支，一次发版所有版本各出一个 jar —— 不按版本分分支。多分支的毛病是老版本
永远慢半拍：功能落在主力分支上，再靠一轮轮「追平」补过去，而那一轮什么时候来没人保证。

支持哪些目标由 [`versions/targets.json`](versions/targets.json) 声明：

| 目标 | Minecraft | 加载器 | 状态 |
|---|---|---|---|
| `1.21.1-neoforge` | 1.21.1 | NeoForge | 可构建 |
| `1.20.1-forge` | 1.20.1 | Forge | 工程尚未并入 |
| `1.21.1-fabric` | 1.21.1 | Fabric | 尚未开始 |

代码是分块共用的：与 Minecraft 版本、加载器都无关的部分在 `shared/`，各目标自己的
部分在 `platforms/<目标名>/`。各目标的 Gradle、Java 版本可以不同，由 CI 逐个构建。

## 从源码构建

在目标自己的工程目录下跑，不是仓库根：

```bash
cd platforms/1.21.1-neoforge
./gradlew build      # 产物在 platforms/1.21.1-neoforge/build/libs/
./gradlew runClient  # 开发环境启动
```

映射使用 Mojang 官方名 + Parchment，其许可见
<https://github.com/NeoForged/NeoForm/blob/main/Mojang.md>。

## 许可

[MIT](LICENSE)。

根目录的 `TEMPLATE_LICENSE.txt` 是另一回事：那是 NeoForged 给 MDK 模板文件的 MIT 声明，
按其署名要求保留，与本模组自身的许可无关。
