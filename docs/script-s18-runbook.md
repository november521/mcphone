# S18 验收 runbook（§13.6 第 1/2/6/7 行 + 五层实测 + disabled + 失败口径）

> 这份是**操作清单**，不是设计说明。能力目录与供给层的实现见 `docs/script-app-author-guide.md`
> 与 `docs/script-s17-stage1-model.md`。三平台实现一致，下面以 `1.21.1-neoforge` 为例；
> 换 forge/fabric 只改 `platforms/<目标>/` 的路径与数据包目录名（见 §2）。
> **每行都要真的跑一遍，记录"命令 / 期望 / 实际 / 原始输出"四列**（模板见 §8）。

## 0. 准备

- S17 Stage 2 的 runbook（`docs/script-s17-stage2-runbook.md`）先跑通，本清单假设你已经会：
  造包、签名、放进 `incoming/`、`/mcphone script approve`、`/mcphoneclient rpc`。
- 一个**真客户端窗口**（`runClient` + 单人世界或 `runServer` + 客户端进服），一个人够了。

## 1. 测试 App

目录（比如 `C:\temp\s18app`）：`manifest.json` / `app.vue` / `server.js` / `icon.png`。

`manifest.json`（能力清单要含本测试用到的每一项；`score.rw` 是 plain，不需要 OP 批）：

```json
{
  "format": 1,
  "id": "s18test:demo",
  "version": "1.0.0",
  "name": "S18 测试 App",
  "author": "tester",
  "description": "能力供给层验收用",
  "icon": "icon.png",
  "engine": "declarative-1",
  "deploy": "server",
  "actions": ["daily", "loot", "buff", "unbuff", "points", "vip", "steal"],
  "capabilities": ["item.give", "loot.roll", "attr.grant", "effect.give", "score.rw"]
}
```

`server.js`：

```javascript
actions.daily = function (ctx) { ctx.give('minecraft:diamond', 3); return ctx.ok({}); };
actions.loot = function (ctx) { ctx.loot.roll('myserver:daily_gift'); return ctx.ok({}); };
actions.buff = function (ctx) {
  ctx.attr.grant('minecraft:generic.movement_speed', 0.1);
  ctx.effect.give('minecraft:speed', 30, 1);
  return ctx.ok({});
};
actions.unbuff = function (ctx) { ctx.attr.revoke('minecraft:generic.movement_speed'); return ctx.ok({}); };
actions.points = function (ctx) { ctx.score.add('points', 1); return ctx.ok({ points: ctx.score.get('points') }); };
actions.vip = function (ctx) { return ctx.ok({ vip: ctx.predicate.test('myserver:is_vip') }); };
actions.steal = function (ctx) { return ctx.ok({}); };
```

`app.vue`：照 `docs/script-s17-stage2-runbook.md` §1 的那份（作者分支 + `button @click="call('daily')"`），
第 2 行剧本会把它整个改写。签名：

```powershell
cd platforms/1.21.1-neoforge
.\gradlew.bat signApp -PappDir=C:\temp\s18app -PgameDir=run -Pauthor=tester -PoutZip=C:\temp\s18test.zip
```

## 2. 服主数据包（§18.2/§18.3/§18.5）

世界目录下的 `datapacks\s18test\`：

- **战利品表**（1.21.1 路径 `data/myserver/loot_table/daily_gift.json`；1.20.1 是 `loot_tables/`）：

```json
{ "type": "minecraft:chest",
  "pools": [ { "rolls": 1,
    "entries": [ { "type": "minecraft:item", "name": "minecraft:diamond",
      "functions": [ { "function": "minecraft:set_count", "count": 2 } ] } ] } ] }
```

- **谓词**（1.21.1 路径 `data/myserver/predicate/is_vip.json`；1.20.1 是 `predicates/`）：

```json
{ "condition": "minecraft:entity_properties", "entity": "this",
  "predicate": { "type": "minecraft:player" } }
```

- **一个"永远假"的谓词** `data/myserver/predicate/never.json`（同上格式 + `"condition": "minecraft:inverted"` 包一层，
  或直接用 `minecraft:random_chance` 概率 0 → 不稳定，建议 inverted）。

- **礼包白名单**：默认 `#mcphone:giftable`（`data/mcphone/tags/item/default_giftable.json`）**不含硬通货**
  （钻石/下界合金等），所以默认状态 `ctx.give('minecraft:diamond', 3)` 会被拒。第 5 节要把钻石加进去：
  `data/mcphone/tags/item/giftable.json` 写成
  `{ "replace": false, "values": ["#mcphone:default_giftable", "minecraft:diamond"] }`。

`/reload` 之后：`/mcphone script approve <完整digest> daily,loot,buff,unbuff,points,vip -`
（动作列表逗号分隔；能力列表传 `-` ＝ **一个能力都不批**，第 6 行剧本要用它；后面再单独批能力）。
⚠ **省略能力列表现在会失败**：候选声明了 granted（本包有 `item.give`/`loot.roll`/`attr.grant`/`effect.give`），
`/mcphone script approve <digest> daily,...` 会被拒并提示"必须显式写能力列表"——这是对抗 S18-A1 的修复，
命令面自证第一行就跑它：`/mcphone script approve <digest> daily,loot,buff,unbuff,points,vip` → 期望报错；
`... -` → 成功且能力一个没批。

## 3. §13.6 第 1/2 行（改前端）

| # | 操作 | 期望 |
|---|---|---|
| 1 | 把客户端 `run/mcphone/apps/s18test.zip` 里的 `app.vue` 按钮文字改一个字，重压、重进世界让 `ScriptAppFolder` 重读 | 详情页出现"此 App 的界面已被本地修改"灰字；**特权不变**：动作照常 OK，服务端授权列表不变 |
| 2 | 把 `app.vue` 整个重写成"十个给我钻石的按钮"（十个按钮分别 `call('daily')`、`call('loot')`、`call('steal')`、`call('nope')`…） | 每个按钮的 RPC 都被服务端按**授权与能力**判：未授权动作 `NOT_AUTHORIZED`、没声明的动作 `NOT_DEPLOYED`（`no_such_action`）；**一个都不多给**。`backend.actions` 仍只列已批准的动作（它只是 UX，无视它直接发也一样） |

> 剧本 2 的包要留着：第 7 行复用它。

## 4. §13.6 第 6/7 行（改声明 / 改客户端）

| # | 操作 | 期望 |
|---|---|---|
| 6a | 把 `manifest.json` 的能力清单改大（加 `"block.set"`、`"item.take.other"`），重签、放进 `incoming/` | manifest 在摘要里 → **摘要变**，服务端认的是新候选（要重新批准）；旧部署对不上，请求 `VERSION_MISMATCH` |
| 6b | 不动包，直接 `/mcphoneclient rpc s18test:demo daily <真rev> <真摘要>`（此时 `item.give` **没批**） | `→ NOT_AUTHORIZED` + `mcphone.script.capability.not_approved`（服务端用自己的 Deployment，不用包里的声明） |
| 6b′ | `/mcphone script approve <digest> daily,loot,buff,unbuff,points,vip item.give`（**动作要重列**：`-` 会清空动作轴）后重试 | `→ OK`，背包 +3 钻石；再过一遍 §5 标签用例 |
| 7 | 改 MCphone 的 jar 去掉客户端所有检查（本步最省事的等价做法：把 `ScriptPage` 里 `backend.actions` 灰按钮逻辑删掉，或直接用 `/mcphoneclient rpc` 手工构造请求） | 与改前完全一致：服务端照判 —— 未批 `NOT_AUTHORIZED`、没部署 `NOT_DEPLOYED`、白名单外 `INVALID_ARGUMENT`；**拿不到任何额外特权** |

## 5. 五层实测（逐条记录原始输出）

| 层 | 操作 | 期望 |
|---|---|---|
| 战利品表 | 批准 `loot.roll` 后 `call('loot')` | 背包 +2 钻石（**一条命令都没用**）；服务端日志有表 id。`ctx.loot.roll('myserver:nope')`（临时改 server.js）→ `INVALID_ARGUMENT` + `no_such_table` |
| 谓词 | 批准所有动作后 `call('vip')` | `vip: true`（**没有自造条件语言**）；`ctx.predicate.test('myserver:nope')` → `UNAVAILABLE` + `predicate.unavailable`；把 `is_vip.json` 换成"永远假"再跑 → `vip: false`，**不用重批** |
| 属性修饰符 | `call('buff')` 后在创造模式用命令再给同一属性一条修饰符：1.21.1 `/attribute @s minecraft:generic.movement_speed modifier add 11111111-1111-1111-1111-111111111111 test 0.5 add_multiplied_base`（1.20.1 操作用 `multiply_base`），然后 `call('unbuff')` | 撤销**只掉自己那一条**：命令那条还在（`/attribute @s minecraft:generic.movement_speed get` 对照）；app 的那条是 Transient，重登即消失 |
| 标签白名单 | 默认直接 `call('daily')`；然后把 `data/mcphone/tags/item/giftable.json` 改成 `{"replace": false, "values": ["#mcphone:default_giftable", "minecraft:diamond"]}` 再 `/reload`、再 `call('daily')`；最后改成 `{"replace": true, "values": ["minecraft:apple"]}` 再跑 | 默认 `INVALID_ARGUMENT` + `mcphone.script.give.not_giftable`（**默认白名单不含硬通货**）→ 加钻石后 OK（**App digest 不变、不用重新审批**）→ 换成只许苹果后钻石又被拒；全程不重批 |
| `IItemHandler` | **不跑（PM 裁定）**：`ctx.container.read` 属 §32.7"排期靠后（设计保留，P1 不做）"，§18.9 那一行是整份 §18 的清单、载体不在本步。真服清单已加 **#23**（AE2 + 原版箱子同一段代码能读），owner = 开 `container.read` 的卡，最迟验证点 = 该卡合并后第一个真客户端窗口（勘误 E40） | **记录里写"没跑 + 原因"**（见 §8 的未跑条目表）；别留空、别写通过 |

## 6. `[capabilities] disabled` 与预设（§18.8/§31）

配置文件 `<世界>/serverconfig/mcphone-capabilities.json`（首次开服生成），**改完 `reload` 生效、不用重开服**：

| 操作 | 期望 |
|---|---|
| `"disabled": ["loot.roll"]` + `/mcphone script capabilities reload`；已装 App `call('loot')` | `UNAVAILABLE` + `mcphone.script.capability.disabled`；`daily`（item.give）照常 |
| `"disabled": ["storage.self"]`（**plain 档**）+ reload；App 里加一个 `ctx.store.getString('k')` 的按钮再点 | `UNAVAILABLE` + `capability.disabled` —— **免审批 ≠ 服主管不了**；`"disabled": []` reload 后恢复 |
| `"disabled": ["predicate.test"]` + reload；`call('vip')` | `UNAVAILABLE` + `capability.disabled`（对抗 S18-A3 之后谓词也进了目录、可关） |
| `"disabled": ["read.self.gamemode"]` + reload；App 里加一个读 `ctx.player.gameMode` 的按钮 | 读它 → `UNAVAILABLE` + `capability.disabled`；**不读它的动作一点不受影响**（getter 只在读时判门） |
| `/mcphone script capabilities` | 每条后面标 `[可关]` 或 `[本步无调用点]`：只有前者关掉才有运行期效果 |
| 关掉 `score.rw` 后 `call('points')` | 同上（读点也过门） |
| 把 `"preset"` 从 `standard` 换成 `open` + reload | `/mcphone script capabilities` 里 preset 变；显式 `disabled` 仍压过预设 |
| `"disabled": ["nope.unknown"]` + reload | 服务端日志/命令回显一条"不认识的能力 id，跳过"warning，**不崩服** |

## 7. 顺带收尾清单（那一次窗口里一起跑）

- **Stage 2 剧本 3/4/5**：`docs/script-s17-stage2-runbook.md` §3，照表跑。
- **Stage 3 剧本 8/9/10/11**（§13.6 表）：
  | # | 操作 | 期望 |
  |---|---|---|
  | 8 | 把 A 服已批准的特权 App 整包复制到 B 服客户端 | 前端能打开；所有特权动作 `NOT_DEPLOYED`（授权按 `serverId` 隔离） |
  | 9 | 再把 `server.js` 拷进 B 服 `mcphone/store/incoming/` 想自行安装 | **玩家无法自行安装后端**：必须服主 `/mcphone script approve` 走审批 |
  | 10 | 在 B 服开一个自己的单人世界当服主批准自己 | 只影响他自己的世界；A 服的授权完全不受影响 |
  | 11 | 让 A 服服主签一个"通用"令牌给 B 服 | 默认没有离线令牌这条路（要走 `trusted_issuers` 的话是另一张卡） |
- **§13.4 五层核对**：逐条对着改（前端摘要只显示、服务端用自己部署表、动作交集、能力目录查表、`serverId` 隔离）。

## 8. 记录模板

```
平台：1.21.1-neoforge / MC 版本 x.y.z / 客户端与服务端各一条日志
第 6b 行：
  操作：/mcphoneclient rpc s18test:demo daily <rev> <digest>
  期望：NOT_AUTHORIZED + capability.not_approved
  实际：<聊天栏原文>
  原始输出：<日志/截图>
```

**未跑条目表**（一条都不许留空、不许写"通过"；"哪条为什么没跑"本身就是证据）：

| 条目 | 状态 | 原因 / 去向 |
|---|---|---|
| §5 `IItemHandler`（AE2 + 原版箱子） | 未跑 | PM 裁定（E40）：`container.read` 属 §32.7 排期靠后；真服清单 #23 记账，owner = 开 `container.read` 的卡 |
| （窗口里还有哪条没跑、条件不满足、或撞上环境问题，逐条写在这里） |  |  |

跑完把每行的"实际 + 原始输出"贴进 PR #46 的评论或描述；`[本步无调用点]` 的能力只记"登记未接线"，
**既不是缺陷、也不能当"已实现"的证据**（PM 裁定）。
