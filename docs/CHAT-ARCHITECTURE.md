# 美西螈 APP 架构与维护审查

审查日期：2026-10-08。本次范围包括聊天客户端、好友与消息业务、存档、消息模型、网络处理、图片仓、平台启动挂载、公开聊天 API，以及最近的布局、贴图和头像缓存修改。对应分支 `codex/chat-layout`。

## 结论

现有文件组织基本合理，能继续维护。没有理由把 `shared/`、版本层和加载器层混到一个目录。此次已拆开会话页的消息、输入和图片查看职责，修复图片请求的会话归属，分离通知与上传确认，并约束上传任务和消息缓存的生命周期。主要剩余压力在服务端业务职责、后台图片文件操作和客户端静态缓存清理。编译与静态闸门不能代替游戏内验收，也不能证明没有其他竞态。

## 从入口读代码

以下路径均相对仓库根目录；Java 包前缀为 `com/november/mcphone/`。

| 位置 | 职责与建议阅读顺序 |
| --- | --- |
| `shared/src/main/java/.../feature/chat/client/ChatApp.java` | 注册聊天 APP、进入手机页面、读取未读角标 |
| `shared/src/main/java/.../core/client/PhoneScreen.java` | 手机导航与输入分发；消费各页的打开、返回、附件选择请求 |
| `shared/src/main/java/.../feature/chat/client/ChatList.java`、`ChatAddContact.java`、`ChatConversation.java` | 会话列表、好友操作页、聊天页；读快照、绘制、发操作请求 |
| 同目录 `ChatLayout.java`、`ChatUi.java`、`ChatGlass.java` | 纯布局计算、字体/图标绘制、透明面板形状与调色板；点击和绘制共用几何 |
| 同目录 `ChatMessageLayout.java`、`ChatMessagePane.java`、`ChatScrollState.java` | 快照排版、消息绘制/可见图片命中、纯滚动状态；页面不再承担这些细节 |
| 同目录 `ChatComposer.java`、`ChatAttachment.java` | EditBox、发送按钮和附件菜单；以发送回调和附件语义交给页面，不依赖会话协调者 |
| 同目录 `ChatImageViewer.java` | 图片放大、动图取帧及保存；打开期间由页面阻断下层输入 |
| 同目录 `ChatImageCache.java`、`ChatImageRequests.java` | 缓存像素与贴图；独立纯调度器按当前帧/对端的可见集合批量取图 |
| 同目录 `ChatClientEvents.java`、`ChatNotifier.java` | 前者路由上传确认与通知，后者只处理提醒条件和合并通知 |
| `shared/src/main/java/.../feature/chat/net/ChatClientCache.java` | 客户端数据快照和纯数据回调；没有客户端类型，专用服务器可以安全加载 |
| `platforms/<target>/src/main/java/.../feature/chat/net/ChatNetworking.java` | 注册消息、限流、线程切换、调用业务、送回结果 |
| `shared/src/main/java/.../feature/chat/ChatService.java`、`FriendGuard.java`、`TeleportService.java` | 消息/好友业务、权限校验、好友传送；客户端按钮不能代替这些校验 |
| 同目录 `ChatData.java`、`FriendData.java`、`ChatReadState.java`、`FriendGraph.java`、`ConversationKey.java` | 双人会话、好友、申请、已读进度与归一化键；存于世界 SavedData 或玩家数据 |
| `layers/version/1.20.5+/src/main/java/.../feature/chat/` | 消息正文、版本相关 Codec 和协议；Forge 1.20.1 的对应类型在平台目录 |
| `layers/loader/{forge,neoforge}/.../feature/chat/ChatImageStore.java`、`ChatImageUploads.java` | 图片文件、切片拼接与加载器事件；Fabric 对应实现位于平台目录 |
| `shared/src/main/java/.../api/chat/` | 附属调用入口；强制服务端主线程并校验当前玩家实体 |

文本链路：聊天页 → `SendChatMessagePacket` → 平台网络处理 → `ChatService` 校验/清洗 → `ChatData` 落库 → `ChatDelivery` 推送双方 → `ChatClientCache` 快照 → 界面及通知。客户端不做乐观插入，消息以服务端回声为准。

会话页内的关系是 `ChatConversation` → `ChatMessagePane` / `ChatComposer` / `ChatImageViewer`。三个组件由同一个页面实例拥有，开关或切换会话时统一重置；组件不反向引用协调者，也不直接导航到其他页面。`ChatComposer` 通过 `Consumer<String>` 交出发送内容，附件选择通过 `ChatAttachment` 由手机导航消费。这些客户端内部类型不是附属的稳定 API。

`ChatMessageLayout` 只负责测量与构造不可变排版块，正文位置也随块缓存；`ChatMessagePane` 只在消息快照、可用宽度、字体实例或自身 UUID 改变时重排。图片尺寸来自消息元数据，不会在像素到达后突然改变。`ChatScrollState` 保留原有贴底和翻历史规则，并可不启动游戏窗口独立验证。

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
4. **会话页职责集中。** 将原来 952 行的 `ChatConversation` 缩为 170 行的生命周期与输入协调者，拆出消息排版/绘制/滚动、输入栏和图片查看器；没有把页面状态改为静态工具状态。图片查看器打开时吞掉键盘、字符和滚轮，避免隐藏的输入框按 Enter 发送草稿。保存图片的后台结果也只向原连接提示。
5. **旧会话图片进入新对端请求。** `beginFrame(peer)` 建立当前帧的可见集合，绘制时登记 ID，`flushRequests` 只筛选这一集合；切到 B 后不会带上缓存中 A 的 LOADING 条目。放大图遮住消息区时只登记放大图。保留 600ms 全局间隔、6s 重试和协议批量上限，READY/GONE/BROKEN 不请求。`ChatImageRequestsTest` 覆盖切换、去重、重试与批量边界。
6. **通知承担上传确认。** 三个平台把消息监听器挂到 `ChatClientEvents`，先确认上传回声再走通知；`ChatNotifier` 不再依赖发送器，通知是否显示不会改变上传完成路径。
7. **气泡正文偏左、偏下。** `ChatLayout.bubbleText` 统一默认字体的行尾间距、可见高度与上方笔画偏移校准。正文块水平居中，多行仍共用左边缘；相较旧规则向右约 0.375–0.75 个逻辑像素、向上 0.75 个逻辑像素。尺寸与位置保持浮点精度，不能将此默认校准视为所有第三方字体的真实笔画度量。

## 后续维护重点

| 优先级 | 位置与现象 | 建议与边界 |
| --- | --- | --- |
| 中 | `ChatService` 同时处理好友、名字解析、文本与图片清理；依赖 `net` 摘要 DTO | 后续按好友业务与消息业务拆分，先稳定数据契约；此次不改变存档和协议，避免视觉 PR 同时引入迁移 |
| 中 | 图片文件读写与删除/孤儿扫描分散到后台执行，没有完整的事务协调 | 大规模图片功能扩展前补异步读写/清理竞态测试，尤其重复内容重新发送与后台删除交错、服务器启动扫描完成前的写入；这是需要验证的竞态风险，并非此次已证实的数据损坏 |
| 中 | 客户端多个静态缓存依赖各平台退出世界回调；原版皮肤资料又是异步加载 | 新增缓存必须声明清理时机与失效规则。头像仅记住当前客户端已加载的皮肤，重启后的离线头像仍需要后续持久化方案 |
| 低 | 字体光学居中按默认字体行框留白估算，资源包能替换字形；主屏/会话/通知的文字规则有重复 | 后续统一字体度量与视觉测试；不要宣称所有第三方字体都会像默认字体一样居中 |
| 低 | 个别历史注释仍描述旧图片上限；公开方法数量较多但并非都属于稳定 API | 以常量和配置为准，逐步清理旧说明。附属只使用 `api/chat`，不要依赖客户端内部实现 |

未来加群聊需要明确群会话键、成员权限、发件人昵称、已读/未读语义与协议演进；目前只预留昵称和按钮位置，现有双人 `ConversationKey` 不能直接当作群 ID 使用。

## 验证与验收

新增 `ChatMessageLayoutTest`（15 条）、`ChatBubbleTextTest`（240 条）、`ChatScrollStateTest`（9 条）、`ChatImageRequestsTest`（12 条），加上 `ChatUploadSessionTest`、`ChatClientCacheTest`、`ChatLayoutTest`、`ChatGlassTest`、`ChatSurfaceTest`、`RememberedPlayerSkinsTest`，分别覆盖排版、滚动、请求归属、生命周期、缓存上限、布局/点击、形状透明度、素材兼容和头像失效。排版测试使用确定的字体测量替身，不加载 GL 或字体资源；各平台完整构建及仓库闸门由 PR CI 验证。

游戏内仍需检查中英文/多行消息、不同 UI 缩放、输入光标与选区、图片/表情上传与退出重连、好友离线头像、传送按钮命中；自动化检查不等同于完成这些实测。

本机为 Windows，完整 `build --continue` 中既有 `EconomyDataTest` 会在 `Files.setPosixFilePermissions` 处抛 `UnsupportedOperationException`，该测试代码未在本次修改。聊天回归与其他检查分别记录结果，Linux PR CI 继续跑完整测试，不能把本机这一轮称为全绿。
