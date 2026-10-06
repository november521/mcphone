# 收件箱、货币托管与通知验收

本文是已实现链路的人工测试任务。自动检查覆盖契约、精度、分块、容量及恢复状态；没有启动用户的客户端或服务器，游戏内验收尚未执行。

## 脚本收件箱

```js
// 在一次获许可的后端动作内搬入第 0 格的真实物品。
var refs = ctx.player.inventory(0).items;
var result = ctx.mailbox.deposit(ctx.player.uuid, [refs[0]], 'inventory-roundtrip');
if (result !== 'OK') return ctx.fail('INVALID_ARGUMENT', {result: result});
return ctx.ok({count: ctx.mailbox.count(ctx.player.uuid)});
```

需要声明 `read.self.inventory` 和 `item.take.self`。空格的空句柄不能提交。数量与附加数据来自宿主快照，修改 id/count/slot 均不能改变实际搬入的物品。背包已经变化、同批重复背包格或收件箱已满时，整批不扣除。只能存入或查询自己的收件箱。

`ctx.mailbox.deposit(ctx.player.uuid, ctx.loot.roll(tableId), reason)` 可以把战利品直接放入统一邮箱，须审批 `loot.roll` 和 `item.give` 并通过可赠送标签。仅掷表不会自动发奖。一次至多 27 个引用，搬入不得排在已有落地意图之后；成功搬入后本次求值不能再执行另一项货币或物品发放操作。计数跨 App 共用，不同 App 不各建一份邮箱。

物品转移先保存 RECEIVING，再清空对应背包格、保存玩家并确认落盘，最后变成 AVAILABLE。领取先保存 DELIVERING，再发放并确认玩家存档。中断的两个方向均进入 UNKNOWN，禁止自动领取或重试。

人工核对命令 `/mcphone script mailboxResolve <玩家UUID> <物品ID> delivered|retry` 必须在核对存档后使用。`delivered` 表示该记录描述的转移确实发生：搬入记录保留物品供领取，领取记录删除物品；`retry` 表示该转移未发生：搬入记录删除，领取记录恢复可领取。响应列表中的 incoming 字段区分方向。管理者必须同时拥有 OP 3 和 deployment_approvers 中的 UUID，控制台可核对；审计写盘失败时拒绝修改。

## 货币托管

托管号绑定创建者 UUID、App、完整作者公钥和币种，记录创建时的包摘要与版本。获批准的同作者新版可以结算旧托管；换作者不能接管旧托管。放款和退款的目标由钱包创建时固定，参数不能改目标。结算开始前保存 SETTLING，异常或重启后转 UNKNOWN，禁止自动重试。

`/mcphone script currencyEscrowPending` 列出不明结果。管理员核对钱包流水之后，用 `/mcphone script currencyEscrowResolve <托管UUID> occurred|retry` 明确确认已结算，或恢复为可结算。确认前保存完整审计。此入口不会直接给玩家加钱。

原生服务器管理页的统一核对入口同时处理脚本归属账和原生结算日志，推荐用它处理定时退款的不明结果。原生/SDK/脚本的放款、退款和定时退款共用 `mcphone/economy/settlements.json`。进入钱包前保存 STARTED，异常或强杀恢复 UNKNOWN；provider 回成功但世界账仍未结清时也停止重试。确认已发生保留持久确认，并标记原账，不能靠重启重新退款。

## 通知

新宿主同步至多 128 个脚本 App 加两个内建到期提醒，订阅每片 16 项，完整批次才替换。片段绑定玩家、连接和随机令牌，60 秒超时；同步间隔 250 ms。角标按快照版本分块，到齐才替换，旧片不能覆盖新快照。旧宿主保持 32 项的兼容通道。

通知与 KV、保险箱、共享数据共用全服计量；每玩家的 KV、保险箱和通知合计受 data.per_player 限制。满额时通知被丢弃并合并日志，不能靠切换机制绕过容量。卸载 App 释放对应通知。HIGH 仍受每 App 每小时一次的规则，之后降为 NORMAL；合格 HIGH 进入最多四条的本机原生提醒，并在手机时钟页显示最多两条。关闭通知、读取通知、到期或换服会释放提醒。

## 需要你执行的测试任务

| 任务 | 操作 | 预期 |
| --- | --- | --- |
| M1 完整物品往返 | 分别准备命名/附魔装备、带内容的潜影盒及支持的模组物品；App A 搬入，重启，从内建收件箱领取 | 数量与附加数据保持，背包不留下复制品 |
| M2 跨 App 共用 | A 存入，B 查询 count；退回满背包奖励 | 件数相同，都进入同一个内建收件箱 |
| M3 拒绝与伪造 | 满箱、空格、改 id/count/slot、重复同格、另一玩家 UUID、旧请求句柄 | 整批不扣除，不造物品 |
| M4 崩溃窗口 | 使用测试服，在搬入记录保存后、背包保存后、领取保存前分别强制结束 | 重启为 UNKNOWN，按转移方向人工核对后不会重复发放 |
| C1 托管归属 | A 创建托管；另一玩家、另一个 App、换作者或换币种尝试放款/退款 | 钱包余额不变；原创建者仍能按固定方向结算 |
| C2 版本与未知结果 | 托管期间重启或升级同作者 App；模拟钱包已入账后抛异常 | 保留创建版本，普通重启可结算；不明结果不自动重试 |
| N1 订阅与角标 | 把 apps.per_player 调至 64/128，安装超过 32 个 App，给最后一个 App 发通知 | 末尾 App 可接收、角标正确；内建到期提醒继续可用 |
| N2 提醒生命周期 | 发 HIGH 后重复 HIGH、标记已读、关通知、换服、卸载 | 第一条原生展示，小时内重复降级，失效提醒消失 |
| N3 合并容量 | 接近 data.per_player 时分别写 KV、保险箱、通知，再删除部分数据 | 总量共同限制，失败不改已有值，释放后可继续写 |
| X1 三平台对照 | 在 Forge 1.20.1、NeoForge/Fabric 1.21.1 分别完成以上适用任务 | 平台使用真实物品数据；Fabric 不伪造不存在的 Forge 能力 |

新增原生管理与退款重启任务见 `NATIVE-ADMIN-TESTS.md`。远程 PNG、服务端跟随作者更新及最终验收包仍有收尾工作，详见 COMPLETION-PROGRESS.md。不要把本阶段 JAR 当作全部方案已经验收的正式版。
