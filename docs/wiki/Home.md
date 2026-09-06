# MCphone

把一部能用的智能手机塞进 Minecraft —— 拍照、翻相册、换壁纸、听歌、聊天、装 App。

## 从哪儿开始

| 我想…… | 看这页 |
|---|---|
| 知道这个模组能干什么、怎么用、有哪些配置 | **[使用手册](Manual)**（仓库 README 的镜像） |
| 给它做一个附属 App | **[附属接口文档](Addon-API)** |
| 换掉手机的皮肤（贴图） | [使用手册 → 换肤](Manual) 那一节 |
| 看每一版改了什么 | [Releases](https://github.com/november521/mcphone/releases) |
| 报个 bug / 提个想法 | [Issues](https://github.com/november521/mcphone/issues) |

## 下载

- [Modrinth](https://modrinth.com/mod/mcphone)
- [CurseForge](https://www.curseforge.com/minecraft/mc-mods/mcphone)

## 版本分支

这个 wiki 跟的是 **`main` 分支 = NeoForge 1.21.1**。

Forge 1.20.1 在 [`1.20.1-forge`](https://github.com/november521/mcphone/tree/1.20.1-forge)
分支上，功能与这边对齐，但那边的文档自成一套，以那个分支里的 README 为准。

## 这个 wiki 是自动生成的

每一页都是从仓库里的 markdown 同步过来的，**在 wiki 里改会被下一次同步覆盖掉**。
要改就改仓库里的源文件（每页顶上都写着是哪一个），推到 `main` 之后 CI 自己会把这里刷新。

为什么这么麻烦：文档一旦有两份就会分叉，而分叉之后人们信的是 wiki——它更显眼。
让它当生成物，仓库当唯一真源，两边就永远不会打架。
