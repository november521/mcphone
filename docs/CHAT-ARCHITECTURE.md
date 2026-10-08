# 美西螈 APP 架构与维护审查

审查日期：2026-10-08。本次范围包括聊天客户端、好友与消息业务、存档、消息模型、网络处理、图片仓、平台启动挂载、公开聊天 API，以及最近的布局、贴图和头像缓存修改。对应分支 `codex/chat-layout`。

## 结论

现有文件组织基本合理，能继续维护。没有理由把 `shared/`、版本层和加载器层混到一个目录。主要压力是会话页面职责过多、客户端静态状态依赖退出清理、异步任务容易跨生命周期完成。此次修复两处可复现的生命周期漏洞，并补回归测试；较大的拆分和协议调整列为后续工作。编译与静态闸门不能代替游戏内验收，也不能证明没有其他竞态。

## 从入口读代码

以下路径均相对仓库根目录；Java 包前缀为 `com/november/mcphone/`。

| 位置 | 职责与建议阅读顺序 |
| --- | --- |
| `shared/src/main/java/.../feature/chat/client/ChatApp.java` | 注册聊天 APP、进入手机页面、读取未读角标 |
| `shared/src/main/java/.../core/client/PhoneScreen.java` | 手机导航与输入分发；消费各页的打开、返回、附件选择请求 |
| `shared/src/main/java/.../feature/chat/client/ChatList.java`、`ChatAddContact.java`、`ChatConversation.java` | 会话列表、好友操作页、聊天页；读快照、绘制、发操作请求 |
| 同目录 `ChatLayout.java`、`ChatUi.java`、`ChatGlass.java` | 纯布局计算、字体/图标绘制、透明面板形状与调色板；点击和绘制共用几何 |
| `shared/src/main/java/.../feature/chat/net/ChatClientCache.java` | 客户端数据快照和纯数据回调；没有客户端类型，专用服务器可以安全加载 |
| `platforms/<target>/src/main/java/.../feature/chat/net/ChatNetworking.java` | 注册消息、限流、线程切换、调用业务、送回结果 |
| `shared/src/main/java/.../feature/chat/ChatService.java`、`FriendGuard.java`、`TeleportService.java` | 消息/好友业务、权限校验、好友传送；客户端按钮不能代替这些校验 |
| 同目录 `ChatData.java`、`FriendData.java`、`ChatReadState.java`、`FriendGraph.java`、`ConversationKey.java` | 双人会话、好友、申请、已读进度与归一化键；存于世界 SavedData 或玩家数据 |
| `layers/version/1.20.5+/src/main/java/.../feature/chat/` | 消息正文、版本相关 Codec 和协议；Forge 1.20.1 的对应类型在平台目录 |
| `layers/loader/{forge,neoforge}/.../feature/chat/ChatImageStore.java`、`ChatImageUploads.java` | 图片文件、切片拼接与加载器事件；Fabric 对应实现位于平台目录 |
| `shared/src/main/java/.../api/chat/` | 附属调用入口；强制服务端主线程并校验当前玩家实体 |

文本链路：聊天页 → `SendChatMessagePacket` → 平台网络处理 → `ChatService` 校验/清洗 → `ChatData` 落库 → `ChatDelivery` 推送双方 → `ChatClientCache` 快照 → 界面及通知。客户端不做乐观插入，消息以服务端回声为准。

图片链路：`ChatMediaPicker` / 拖放选择文件 → `ChatImageSender` 后台压缩与排队 → 分片上传 → `ChatImageUploads` 拼接 → `ChatImageStore` 后台写文件 → `ChatService` 再校验并落消息 → 收件人按需取像素 → `ChatImageCache` 后台解码、主线程上传贴图。正文只存图片 ID 与尺寸/帧信息，像素放在世界的 `mcphone/chat-images/`，不会塞进 SavedData 常驻内存。

## 合理的依赖

- 页面通过待消费请求交给 `PhoneScreen` 导航，不直接控制其他页面。
- 网络层依赖业务层；业务层不直接构造平台网络包，推送通过 `ChatDelivery.Push` 注入。
- `ChatClientCache` 用回调隔离客户端通知和贴图类型。它位于 `net/` 是专用服务器加载边界的需要，并非文件放错了。
- 好友图、会话键、布局和透明面板形状能脱离游戏窗口测试。快照使用不可变拷贝，避免异步编码时遍历可变消息列表。
- 各平台的同名文件确有维护成本，但加载器/API 差异由目录轴和孪生校验管理；挪成共享文件前必须先确认目标 API 兼容。
- 相册和表情选择共用 `ImageFolder` 与 `PhotoGridPainter`。聊天选择页与相册查看页分开，避免将“发送”和“删除/查看”塞进同一个模式开关。

## 本次修复

1. **后台上传任务归属缺失。** 原代码压缩完成后只看当前连接是否存在；旧任务可能在换服务器后发包，或在超时后干扰新上传。新增纯 Java `ChatUploadSession`：每次任务使用独立票据并绑定原连接，取消、超时、重连及新任务都会使旧结果无效。`ChatUploadSessionTest` 覆盖这些情形。
2. **推送缓存没有容量上限。** 服务端历史保留 100 条，但客户端持续追加能无限增长，聊天页的重排成本随会话停留时间上涨。追加后保留最近 100 条且不修改旧快照；`ChatClientCacheTest` 覆盖 250 次推送、切换会话、关闭与过期历史响应。
3. **素材说明与最终实现不一致。** 更新图标原稿说明、Material/Iconoir 来源与许可、粉色主题、透明黑底和 RGBA 数据量。默认 PNG、SVG 与生成器放置明确；SVG 是维护原稿，运行时加载 PNG。

## 后续维护重点

| 优先级 | 位置与现象 | 建议与边界 |
| --- | --- | --- |
| 较高 | `ChatConversation` 接近 950 行，同时负责输入、气泡排版、滚动、图片查看/保存和附件菜单 | 增加群聊前先抽消息排版器与图片查看器，再抽输入栏。保留页面作为协调者，不将状态分散到多个静态工具类；每次拆分单独做交互验收 |
| 较高 | `ChatImageCache.flushRequests` 从全缓存搜 LOADING 条目，当前请求却只携带一个 peer；手机未关闭时切会话，旧会话的待取图仍可能占请求额度 | 后续按会话/可见集合筛选请求，补“会话 A 慢回包，切 B”回归。不要只把请求速度调快来掩盖问题 |
| 中 | `ChatNotifier.onMessage` 先确认自己发图的回声，再决定是否提醒，通知模块同时承担上传完成路由 | 将来集中客户端事件编排，上传完成和通知各订阅消息；目前这条短调用链有明确说明，暂不增加多层转发 |
| 中 | `ChatService` 同时处理好友、名字解析、文本与图片清理；依赖 `net` 摘要 DTO | 后续按好友业务与消息业务拆分，先稳定数据契约；此次不改变存档和协议，避免视觉 PR 同时引入迁移 |
| 中 | 图片文件读写与删除/孤儿扫描分散到后台执行，没有完整的事务协调 | 大规模图片功能扩展前补异步读写/清理竞态测试，尤其重复内容重新发送与后台删除交错、服务器启动扫描完成前的写入；这是需要验证的竞态风险，并非此次已证实的数据损坏 |
| 中 | 客户端多个静态缓存依赖各平台退出世界回调；原版皮肤资料又是异步加载 | 新增缓存必须声明清理时机与失效规则。头像仅记住当前客户端已加载的皮肤，重启后的离线头像仍需要后续持久化方案 |
| 低 | 字体光学居中按默认字体行框留白估算，资源包能替换字形；主屏/会话/通知的文字规则有重复 | 后续统一字体度量与视觉测试；不要宣称所有第三方字体都会像默认字体一样居中 |
| 低 | 个别历史注释仍描述旧图片上限；公开方法数量较多但并非都属于稳定 API | 以常量和配置为准，逐步清理旧说明。附属只使用 `api/chat`，不要依赖客户端内部实现 |

未来加群聊需要明确群会话键、成员权限、发件人昵称、已读/未读语义与协议演进；目前只预留昵称和按钮位置，现有双人 `ConversationKey` 不能直接当作群 ID 使用。

## 验证与验收

新增回归加上 `ChatLayoutTest`、`ChatGlassTest`、`ChatSurfaceTest`、`RememberedPlayerSkinsTest`，分别覆盖生命周期、缓存上限、布局/点击、形状透明度、素材兼容和头像失效。各平台完整构建及仓库闸门由 PR CI 验证。

游戏内仍需检查中英文/多行消息、不同 UI 缩放、输入光标与选区、图片/表情上传与退出重连、好友离线头像、传送按钮命中；自动化检查不等同于完成这些实测。

本机为 Windows，完整 `build --continue` 中既有 `EconomyDataTest` 会在 `Files.setPosixFilePermissions` 处抛 `UnsupportedOperationException`，该测试代码未在本次修改。聊天回归与其他检查分别记录结果，Linux PR CI 继续跑完整测试，不能把本机这一轮称为全绿。
