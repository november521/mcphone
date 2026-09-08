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

## 分支

| 分支 | 版本 | 状态 |
|---|---|---|
| `main` | Minecraft 1.21.1 + NeoForge | **当前主力**，wiki 跟的是这一支 |
| [`1.20.1-forge`](../../tree/1.20.1-forge) | Minecraft 1.20.1 + Forge | 功能已完整移植，文档以该分支为准 |

## 从源码构建

```bash
./gradlew build      # 产物在 build/libs/
./gradlew runClient  # 开发环境启动
```

映射使用 Mojang 官方名 + Parchment，其许可见
<https://github.com/NeoForged/NeoForm/blob/main/Mojang.md>。

## 许可

[MIT](LICENSE)。

根目录的 `TEMPLATE_LICENSE.txt` 是另一回事：那是 NeoForged 给 MDK 模板文件的 MIT 声明，
按其署名要求保留，与本模组自身的许可无关。
