# 游戏运行目录

客户端内容与本地记录集中放在当前游戏实例的 `mcphone/`。位置统一定义于 `PhonePaths`，客户端通过 `ClientPaths` 绑定实际游戏目录，不依赖启动器的工作目录。

直接启用新布局，不自动搬移、不兼容读取旧路径。旧文件留在原位置，需要保留的内容可按表手动放入新位置。App 包目录不变，安装记录换到新位置后需重新安装，或自行放入原有安装记录。

| 用途 | 新位置（相对游戏目录） | 以前的位置 |
|---|---|---|
| 脚本 App 包 | `mcphone/apps/` | 不变 |
| 音乐 | `mcphone/music/` | `config/mcphone/music/` |
| TXT 小说 | `mcphone/books/` | `config/mcphone/reader/books/` |
| 壁纸 | `mcphone/wallpapers/` | `config/mcphone/wallpapers/` |
| 表情 | `mcphone/stickers/` | `config/mcphone/stickers/` |
| 阅读进度、书架 | `mcphone/data/reader/` | `config/mcphone/reader/progress.json`、`shelf.json` |
| App 安装记录 | `mcphone/data/installed/` | `config/mcphone/installed/` |
| App 本地数据 | `mcphone/data/appdata/` | `config/mcphone/appdata/` |
| 聊天收藏 | `mcphone/data/chat/favorites/` | `config/mcphone/chat/favorites/` |
| 更新日志已读状态 | `mcphone/data/changelog/` | `config/mcphone/changelog/` |
| 作者密钥导出备份 | `mcphone/backups/author-keys/` | `mcphone-author-key-backup/` |

账号、App 和存档/服务器对应的子目录名称、摘要及文件格式保持原样。收藏包含索引和内容目录，手动搬移时应保留完整相对结构。极旧的全局 `installed.json` 不再自动转换。

配置和签名密钥继续放在 `config/mcphone/`；加载器配置沿用其规定的位置。世界数据及世界配置跟随各自存档，原版截图仍放在 `screenshots/`。内存缓存不额外创建磁盘目录；未来需要落盘的缓存可放在 `mcphone/cache/`。

商店、音乐、书架、壁纸和表情的文件夹按钮打开各功能实际使用的新位置。目录按功能首次使用时创建；路径计算本身不创建文件，也不扫描、移动或删除旧数据。
