# 物品引用与只读上下文

本阶段已经接入真实服务端物品与只读视图，游戏内保存、物品组件和跨维度行为仍需在最终验收中测试。

## `loot.roll` 的方案契约

生产 `ctx.loot.roll(tableId)` 返回冻结的 `ItemRef[]`，不会自动发物品。需要实际发放时使用：

```js
var refs = ctx.loot.roll('minecraft:chests/simple_dungeon');
ctx.give(refs);
```

这修正了旧阶段“只调用 roll 就发放”的行为。旧示例须补上 `ctx.give(refs)`，并声明、审批 `loot.roll` 与 `item.give`。`ctx.give('minecraft:diamond', 1)` 继续可用。

ItemRef 的 `id`、`count` 是显示投影，`opaque` 是服务器随机句柄。不得靠改写投影生成物品；实际 NBT/组件始终留在服务器。句柄绑定玩家 UUID、连接 epoch、App、部署、动作和本次请求，最多存活 30 秒，请求完成或断线就释放。不能跨请求保存，不可用 UUID、物品 id 或另一个玩家的句柄替代它。

`ctx.give` 接受单个引用或至多 27 个引用的数组。同一请求重复提交同一引用会被拒；整批预检结束前不产生发放。物品仍须符合服主的可赠送标签与背包/邮箱容量。读取背包得到的引用也不授予复制权限，实际 give 仍要求 `item.give`。

`ctx.item.matches(ref, '#namespace:tag')`、`displayName(ref)`、`isDamaged(ref)` 通过源能力再次核对句柄。此面不暴露 NBT、容器内容或 Java 对象。统一邮箱已有 `ctx.mailbox.deposit/count`，详见 [MAILBOX-CURRENCY-NOTIFICATIONS.md](MAILBOX-CURRENCY-NOTIFICATIONS.md)。A 类 SDK 的真服互通仍需验收。

## 只读入口

| 入口 | 返回内容 | 能力 |
| --- | --- | --- |
| `ctx.player.position` | x/y/z、yaw/pitch、dimension | read.self.position |
| `ctx.player.stats` | health/maxHealth、food/saturation、level/xpProgress/totalExperience、air | read.self.stats |
| `ctx.player.inventory(offset)` | 每页至多 16 个 `{slot,id,count,opaque}`，加 total | read.self.inventory |
| `ctx.world.time` | 本维度 dayTime 的十进制字符串，保留 long 精度 | read.world.time |
| `ctx.world.weather` | raining/thundering | read.world.weather |
| `ctx.players.onlineCount` | 在线人数 | read.players.online_count |
| `ctx.players.list(offset)` | 每页至多 16 个玩家名字，加 total | read.players.list |

空背包格返回 air、count 0、空句柄，不能用于 give。在线列表不给 UUID，名字不能转换成获授权的转账/转物目标。分页只接受非负整数。所有视图在主线程读取前重新检查原连接、部署、许可、吊销与能力。

`ctx.message.self(text)` 只给本人发字面消息，最多 256 个码点，禁止控制字符；所有 App 合计每玩家 10 秒最多 4 条。`ctx.giveTo(uuid,itemId,count)` 同时要求 `item.give` 与 `item.give.other`，只允许在线收件人，默认拒绝跨维度。当前单次给他人的发放不能与其他效果混合；不应把它当成多收件人事务。
