# SDK 契约与运行期接口

`MCphoneApi.VERSION=7`；A 档 item/player/economy/mailbox/notify/cycle 均为 1，resources 为 2。版本来自 `SdkVersions`；Java 常量在静态块中赋值，附属应使用运行期检查并把新版类型访问隔离到独立类。旧构造器与接口默认方法保留。

Java `api/sdk` 定义跨平台数据与提供者契约，不意味着每个契约都有一个全局静态游戏服务。脚本只能通过宿主注入的本次调用接口操作；不能访问 Java 类、世界对象、原始 NBT 或自行制造物品引用。

| 契约 | 真实运行链 | 数据约定 |
| --- | --- | --- |
| item | `ctx.player.inventory(offset)`、`ctx.loot.roll(table)`、`ctx.item.*`、`ctx.give(refs)`、`ctx.mailbox.deposit` | opaque 是本次请求签发的随机令牌，绑定 UUID/epoch/App/部署/动作；id/count/slot 是显示投影，修改它不会改变真实物品。空、过期或重复句柄被拒 |
| player | `ctx.player.uuid/name/dimension/gameMode/position/stats`，自身背包；在线列表限名字 | 身份来自登录连接；他人列表不公开完整 UUID。离线模式有原生身份警示，代理认证由部署者核对 |
| economy | `ctx.currency.default/list/balance/format/parse/pay/hold/release/refund/mint/burn` 与 `ICurrencyProvider` | 脚本金额 BigInt，Java long；RPC 十进制字符串。mint/burn 均需批准 `currency.mint`。UNKNOWN 禁止宿主自动重试 |
| mailbox | `ctx.mailbox.deposit/count` 与内建收件箱领取/核对 | 自身搬入完整物品，造物仍受 item.give/loot.roll；扣除与交付都须玩家存档确认，崩溃转 UNKNOWN |
| notify | `ctx.notify.self/subscribers`，内建通知中心与角标 | 只允许本 App lang 文件的本地化键；服务端填来源和时间，脚本不能冒充系统 |
| cycle | `ctx.cycle.label/nextBoundary`、`ctx.time.*` | 周期由世界服主配置决定，显式 IANA 时区；默认每日 04:00。时间在线格式使用十进制字符串 |
| resources 2 | `ctx.resource.list/default/readItem/readBlock/format`，`IResourceProvider` SPI | 只读，数量 BigInt/Java BigInteger，最多 128 位十进制数字；物品限本人，方块须已加载、距离不超过 8、通过服主谓词和边界开关 |

resources 2 保留 resources 1 的接口兼容性，新增读取返回明确状态。Fabric 流体为实际 droplets，Forge/NeoForge 为其原生单位，不能仅改单位名就声称等量。缺少适配器时返回不可用，不造零值假机器。

`groups/waypoints/escrow/stats` 的 Java 空接口按 §23.5 保留。后续扩展不能复用错误码或改已有语义。声明这些 SDK 版本只能检查契约版本，不能授予服务端能力。

保险箱是客户端 `phone.sealed.get/put` 加密链，明文最多 2 KiB；服务端只存密文。后端 `ctx.sealed.get` 只搬受限密文字节，不提供伪装为已经实现的明文写入接口。4096 字符输入上限与 KV/保险箱 2 KiB 值上限是不同的检查。

运行期互通任务：在三个目标各测试同一批物品的名字、附魔、耐久和容器内容；在两台不同默认币种的服务器运行同一钱包 App；携带外部经济或资源提供者重新检查返回状态、金额精度、加载顺序及不可对账说明。自动编译不能替代这些结果。
