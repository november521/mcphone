# 聊天图标原稿

来自用户提供的 `chat-ui-black-white-24px-corrected.zip`，原始 SVG 取自包内 `original/`，游戏贴图取自 `white/`，均保持文件内容不变。图标库为 [Pixelarticons](https://github.com/halfmage/pixelarticons)，MIT 许可及版权说明见 `shared/src/main/resources/THIRD-PARTY.txt`。

| 原稿 | 使用位置 | 游戏贴图（相对 textures/） |
| --- | --- | --- |
| message.svg | 聊天栏目 | chat/ui/message.png |
| contact.svg | 联系人栏目 | chat/ui/contact.png |
| compass.svg | 发现占位 | chat/ui/compass.png |
| user.svg | 个人页占位 | chat/ui/user.png |
| search.svg | 搜索占位 | chat/ui/search.png |
| plus.svg | 保留旧版原稿；实际加号见 material-96px/add.svg | — |

PNG 为 24×24 透明白色图标，绘制尺寸由 `ChatLayout.ICON_SIZE` 管理。白色底图乘界面颜色，支持粉色活动项、灰色禁用项及玩家设置的字体颜色；资源包仍可覆盖相同 PNG 路径。

本次仅使用已有页面对应的六个图标。包里的群聊、支付、公众号等素材没有对应页面，尚不接入。返回、更多、加号、笑脸和语音已换成用户提供的 96×96 Material Symbols / Iconoir 素材，见 `material-96px/`。分别使用 back、more、add、mood 和 voice-circle；白色 PNG 原样打包，代码只调整显示尺寸及着色，缺图时仍有几何兜底。原稿与许可保留，黑色版本及未使用的麦克风、替代表情不打入模组。

首页的半透明底板由 `ChatGlass` 统一绘制：上下渐变、细分圆角、细描边、顶沿高光和一像素轻阴影。图标 PNG 本身不加底色；头像框、会话卡片、搜索框与栏目栏各用对应的透明调色板。首页加号恢复为独立透明图标，搜索放大镜单独按 6×6 绘制并与文字居中对齐。深色字体预设会切换浅色面板，其余预设用莓紫面板，不额外加载模糊着色器。

尺寸与卡片间距集中在 `ChatLayout`，页面组装和点击范围在 `ChatList`。栏目图标与文字共用同一条中心轴，文字按原始字宽乘缩放比例定位，不先取整再居中。搜索、发现与个人页仍为占位，外观调整没有引入新业务。

会话卡片为一行名称与一行消息摘要，长内容省略，不折成第二行摘要。`ChatLayout.Card` 统一计算头像、名称、时间和底行操作的位置：名称与时间居中对齐，摘要、传送图标和未读角标共用底行中心。传送的纵向点击范围只占底行，不能跨到名称与时间；横向点击边距也从摘要宽度中预留。默认字体下卡片高 20 逻辑像素。

圆角轮廓使用八倍局部坐标，绘制时等比缩回，命中与排版仍按逻辑像素。`ChatGlass` 只细分弧线与描边，直线区域保持普通步距，并最多缓存 64 组轮廓；不放大整部手机的渲染缓冲。发送按钮使用同一套轮廓。

聊天气泡与输入框的矢量原稿在 `surfaces/`，PNG 可在仓库根运行 `java tools/ui/GenerateChatSurfaces.java .` 重建。气泡、输入框与未读角标共四张 PNG，基础 RGBA 共 825344 字节，未计引擎缓存或驱动开销。八倍图的元数据为 `{"mcphone_skin":{"border":24,"scale":8}}`，四角在界面上仍占三逻辑像素。资源包声明 `scale` 时应同时提供 PNG 与 mcmeta；旧包没有该属性按倍率 1 处理，只覆盖 PNG 时也不会误继承本体的八倍倍率。

消息头像、气泡和图片共用 `ChatLayout.MessageRow` 定位：顶部预留昵称行，短内容底边对齐头像，长内容向下延伸。实际群聊数据与业务尚未实现，预留区域不显示虚构昵称。

首页头像外框使用方角细边框，玩家头部保持方形，不裁圆。在线状态用外框边角的 2×2 小方点表示，减少对脸部的遮挡。皮肤由各平台 `PlayerSkins` 读取原版资料，共用 `RememberedPlayerSkins`：每秒记录在线玩家资料，最多保留 512 项，好友下线后继续使用最后已加载的皮肤。原版的异步加载占位不覆盖旧的真实皮肤；自己断线时释放旧 PlayerInfo，但当前客户端内已知的皮肤引用仍可用于重新进服。没有见过资料的离线玩家仍使用默认头像，不额外查询 Mojang，也不向磁盘保存玩家图片。

输入框高度为 12 逻辑像素，偏黑半透明渐变配浅粉白文字。默认 mcmeta 的 `text_color` 为 `#FFF2F7`；只覆盖旧 PNG 的资源包不会继承本体的文字颜色，仍使用原有回退。
