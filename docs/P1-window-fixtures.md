# 真服窗口夹具（S18 + S15d′）—— 用法与放置

> 用途：给 `tasks/P1-window-index.md` 排的那一次真服窗口备齐**测试夹具**。
> 纪律：**这些只是夹具，不许借此改任何产品行为**；勾选状态只在
> `tasks/P1-realserver-checklist.md`（PM 维护），本文件不另立状态。
> 详细操作口径见 `docs/script-s18-runbook.md`（S18 部分）与 S15d′ 的验收项。

## 一、材料清单

| # | 材料 | 位置 | 对应清单/剧本 |
|---|---|---|---|
| 1 | 带后端测试 App（7 个动作） | `demo-apps/window/s15window-server/` | S18 §3/§4/§5、S15d′ 配置生效 |
| 2 | 纯前端包（无 server.js） | `demo-apps/window/s15window-frontend/` | "剥掉后端变空壳"对照 |
| 3 | 服主数据包（giftable 加钻石 / 真谓词 / 真战利品表） | `demo-apps/window/datapack-1.21.1/`、`datapack-1.20.1/` | §5 五层实测的标签/谓词/战利品 |
| 4 | 两台服务器的货币配置 | `demo-apps/window/economy/economy-server-a.json`、`economy-server-b.json` | #6（不同货币 id）、#13、审计分档 |
| 5 | 伪造 `deployRev`/`actionId`/`frontendDigest` 用法 | 本文件 §三 | Stage 2 剧本 3/4/5 |

动作覆盖（服务器 App）：`daily`=ctx.give、`loot`=ctx.loot.roll、`buff`=attr.grant+effect.give、
`unbuff`=attr.revoke、`points`=ctx.score、`vip`=ctx.predicate.test、`wallet`=ctx.currency 只读。

## 二、放置与签名

1. **数据包**：整个目录拷进 `<世界目录>/datapacks/s15win/`（1.21.1 用
   `datapack-1.21.1`，1.20.1 用 `datapack-1.20.1`；两版的目录名差异已经在各自包里：
   1.21.1 是 `loot_table/`+`predicate/`，1.20.1 是 `loot_tables/`+`predicates/`），
   进服 `/reload`。默认 `#mcphone:giftable` **不含钻石**是本步的预期，本包把它加上
   （改标签不改 App 的 digest、不用重新审批）。
2. **货币配置**：服务器 A/B 各拷一份到 `<世界目录>/serverconfig/mcphone-economy.json`
   （A 用 `economy-server-a.json`，B 用 `economy-server-b.json`；文件名固定）。重开服后
   用 `/mcphone economy status` 核对注册与默认货币。
3. **签名**（在目标平台目录下，以 1.21.1-neoforge 为例）：
   ```powershell
   cd platforms/1.21.1-neoforge
   .\gradlew.bat signApp -PappDir=..\..\demo-apps\window\s15window-server -PgameDir=run -Pauthor=tester -PoutZip=C:\temp\s15win-server.zip
   .\gradlew.bat signApp -PappDir=..\..\demo-apps\window\s15window-frontend -PgameDir=run -Pauthor=tester -PoutZip=C:\temp\s15win-front.zip
   ```
4. **安装**：带后端的 zip 放进 `<世界目录>/mcphone/store/incoming/` 走审批；
   纯前端 zip 放客户端 `run/mcphone/apps/` 按普通 App 安装。

## 三、伪造请求用法（剧本 3/4/5）

`<真rev>` / `<真摘要>` 从 `/mcphoneclient handshake` 的输出里抄（命令里要用完整值）：

| 剧本 | 命令 | 期望 |
|---|---|---|
| 3（伪造 frontendDigest） | `/mcphoneclient rpc s15win:demo daily <真rev> deadbeef00000000` | `→ OK`（摘要只回显，不参与判定） |
| 4a（伪造 deployRev） | `/mcphoneclient rpc s15win:demo daily deadbeef <真摘要>` | `→ VERSION_MISMATCH` |
| 4b（未部署的 app） | `/mcphoneclient rpc s15win:nope daily` | `→ NOT_DEPLOYED` |
| 5a（未批的动作） | 先只批 `daily,buff,unbuff,points,vip,wallet`（不给 `loot`）；再 `/mcphoneclient rpc s15win:demo loot <真rev> <真摘要>` | `→ NOT_AUTHORIZED` |
| 5b（没声明的动作） | `/mcphoneclient rpc s15win:demo nope <真rev> <真摘要>` | `→ NOT_DEPLOYED` + `no_such_action` |

## 四、审计分档与窗口记录

- 审计分档口径见 `docs/economy-audit-tiers.md`：非 builtin 档打"**不可对账**"
  —— **不报平、不算不平、退出码不变**；别把它当成"平了"。
- 记录格式与"未跑条目逐条写原因"的要求见 `docs/script-s18-runbook.md` §8；
  `IItemHandler` 一行按 PM 裁定**不跑**（E40，转真服清单 #23）。
