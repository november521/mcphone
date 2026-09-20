# S17 Stage 2 验收 runbook（剧本 3/4/5 + 客户端三处）

> 这份是**操作清单**，不是设计说明。模型与时序见 `docs/script-s17-stage2-model.md`。
> 本阶段的三平台实现一致，下面以 `1.21.1-neoforge` 为例；换 forge/fabric 只改 `platforms/<目标>/` 的路径。

## 0. 准备

- 一个已签名的测试包（下面第 1 节现做一个），客户端与服务端**用同一个 zip**。
- 客户端至少进过一次 `runClient`，`config/mcphone/keys/author.key` 已生成（"设置 → 开发者 → 我的签名密钥"）。

## 1. 造一个带后端的测试包

目录（任意位置，比如 `C:\temp\s17app`）：

```
manifest.json
app.vue
server.js
```

`manifest.json`：

```json
{
  "format": 1,
  "id": "s17test:demo",
  "version": "1.0.0",
  "name": "S17 测试 App",
  "author": "tester",
  "description": "握手/请求闭环验收用",
  "icon": "icon.png",
  "engine": "declarative-1",
  "deploy": "server",
  "actions": ["claim_daily", "steal"],
  "capabilities": []
}
```

`app.vue`（作者分支 + 点一下发 RPC，两个都要能看）：

```vue
<template>
  <column>
    <column v-if="!backend.available">
      <text>这个 App 需要「{{ backend.serverName }}」才能使用</text>
      <text>当前服务器没有安装它的后端</text>
    </column>
    <column v-else>
      <text>可用动作：{{ backend.actions.length }}</text>
      <button @click="call('claim_daily')">领取</button>
    </column>
  </column>
</template>

<script>
  state = { }
</script>

<style>
  column { padding: 6; gap: 4; }
  button { width: fill; }
</style>
```

`server.js`：

```javascript
actions.claim_daily = function (ctx) { return ctx.ok({}); };
actions.steal = function (ctx) { return ctx.ok({}); };
```

`icon.png` 随便放一张 16×16 PNG。签名（在目标平台目录下跑）：

```powershell
cd platforms/1.21.1-neoforge
.\gradlew.bat signApp -PappDir=C:\temp\s17app -PgameDir=run -Pauthor=tester -PoutZip=C:\temp\s17test.zip
```

## 2. 服务端

1. 把 `s17test.zip` 放进 `<世界目录>/mcphone/store/incoming/`（世界目录 = 开了世界的 `run/world`，
   或专用服的 `world/`）。
2. 起服（`runServer` 或单人开世界），OP 执行 `/mcphone script list` 找到候选的完整 digest。

**空表 → `NOT_DEPLOYED`（先测这一条）**：批准前，在客户端执行

```
/mcphoneclient handshake                            # 应显示"本服没有已批准部署（或握手还没收齐）"
/mcphoneclient rpc s17test:demo claim_daily
# 期望：→ NOT_DEPLOYED + mcphone.script.code.not_deployed
```

3. 批准（只批 `claim_daily`，**不批 `steal`** —— 剧本 5 要用它），并授权：

```
/mcphone script approve <完整digest> claim_daily
/mcphone script authorize s17test:demo all
```

4. 客户端重进世界（重新握手，`/mcphoneclient handshake` 应能看到该部署、`actions=[claim_daily]`）。

## 3. 剧本 3/4/5（逐行记录：操作 / 期望 / 实际 / 原始输出）

> 复制 zip 到客户端 `run/mcphone/apps/s17test.zip` 并在商店"本机脚本 App"里安装一次。
> 记录时把聊天栏原始输出整行贴出来。

| # | 操作 | 期望 |
|---|---|---|
| 3 | `/mcphoneclient rpc s17test:demo claim_daily <真rev> deadbeef00000000` | **无事发生**：`→ OK`（`frontendDigest` 只回显，不参与判定） |
| 4a | `/mcphoneclient rpc s17test:demo claim_daily deadbeef deadbeef00000000` | `→ VERSION_MISMATCH` |
| 4b | `/mcphoneclient rpc s17test:nope claim_daily` | `→ NOT_DEPLOYED` |
| 5a | `/mcphoneclient rpc s17test:demo steal <真rev> <真摘要>` | `→ NOT_AUTHORIZED`（动作声明了但 OP 没批） |
| 5b | `/mcphoneclient rpc s17test:demo loot <真rev> <真摘要>` | `→ NOT_DEPLOYED` + `mcphone.script.no_such_action`（连声明都没有） |

已部署 + 已授权那条正路：手机里进 App，点「领取」→ 屏幕底部弹出 `成功`（`ScriptPage` 的 toast）。
同一条也可以 `/mcphoneclient rpc s17test:demo claim_daily` 复现 `→ OK`。

`<真rev>` / `<真摘要>` 从 `/mcphoneclient handshake` 的输出里抄（显示的是短摘要，命令里要用**完整**值；
完整值可从 `/mcphone script list` 或服务端日志里拿）。

## 4. 客户端三处（截图或日志）

| 看什么 | 怎么造 | 期望 |
|---|---|---|
| 详情页三行 + 「申请」 | 商店 → 本机脚本 App → 选这个 App 的详情 | 部署：本服已部署 · 版本 1；授权：你可以使用：claim_daily；来源：服务器商店 · 服主 <日期> 批准；未授权时右边有「申请」 |
| 空壳横幅 + 作者分支 | 服务端 `/mcphone script remove s17test:demo`，客户端重进世界；先看详情页再看 App 内页 | 详情页顶部一条"需要服务器端"横幅；App 内走作者的 `v-if="!backend.available"` 分支 |
| `frontendModified` 灰字 | 把客户端 `run/mcphone/apps/s17test.zip` 里的 `app.vue` 改一个字重压（appId 不变），重进世界让 `ScriptAppFolder` 重读 | 详情页出现灰色"此 App 的界面已被本地修改"；RPC 照常（灰字只是提示） |
| `call()` 的 toast | 重新批准部署后进 App 点「领取」 | 底部出现结果提示；连点 5 下时第 5 下是"正在处理，请稍候"（同 App 最多 4 个在飞） |

## 5. 记录模板

```
平台：1.21.1-neoforge / MC 版本 x.y.z / 客户端与服务端各一条日志
剧本 3：
  操作：/mcphoneclient rpc ...
  期望：OK（无事发生）
  实际：<聊天栏原文>
  原始输出：<日志/截图>
```

跑完把三处截图与五条命令的原文贴进 PR 描述或本文件的副本里。
