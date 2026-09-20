# S14 核实记录：三档存储与保险箱

> 这是一份核实记录，不是设计。**施工方案本身不在本仓库里**，文中的 §编号指它。

对应 §17.1–§17.7、§10.4.1、§16.5。日期 2026-09-17，分支 `feature/script-store`。

## 一、勘误收口（E15 / E16 / E17）

三条 grep 判据全部为空，**包括注释里也没有那些标识符**：

```
grep -rn "PlayerStore"        --include='*.java' .   → 0 处
grep -rn "sealedPut\|sealedGet" --include='*.java' . → 0 处
grep -rn "currentWorldKey" shared/.../core/script/   → 0 处
```

⚠ 注释里本来写了"不另立 PlayerStore 门面"这种解释性的句子 —— 那是**反着说**的，
不是在用它。但判据要的是字面为空，所以那几句改成了"不另立第二个门面"，
把 §17.3 / §20.2 / §22.7 引的那个旧门面的来龙去脉收到这份文档里：

> §10.4.1 早已删掉那个门面。脚本存储走扩展后的 `PhonePlayerData`：
> `scriptKv()` / `setScriptKv()` 与 `scriptGuards()` / `setScriptGuards()`。

**E17 的口径落地为**：`local` 档的脚本面沿用 `ctx.store.*` 的同名族
（`getString/setString/getLong/setLong/getBool/setBool/remove/keys`），
**没有 `ctx.localStore` 这类第三套命名**。档位是"这段脚本跑在哪一侧"决定的，
不是方法名里带的 —— 作者不该为了选档去记两套名字。
这条写在 `KvBackend` 与 `LocalStore` 的类注释里。

## 二、三档的落点

| 档 | 类 | 落在哪 |
|---|---|---|
| local | `LocalStore` | `config/mcphone/appdata/<serverId>/<appId>/local.json`，**按 serverId 分桶** |
| shared | `ScriptKv` + `PhonePlayerData.scriptKv()` | 服务端存档，明文 |
| 守卫 | `ScriptGuards` + `PhonePlayerData.scriptGuards()` | 服务端存档，**脚本写不到** |
| sealed | `SealedRecord` + `SealedBackend` | 服务端存档，密文；客户端 `VaultClient` 解 |

**守卫与 KV 是两块**，不是一块里的两个前缀。放一起脚本就能把"只能领一次"清零。
1.20.1 的 `copyDeathPersistentFrom` 两块都带上了 —— 不带的话死一次就能重领，
而死亡在 Minecraft 里是随时可以自己安排的事。

## 三、密码学：能纯逻辑判的与要跑服务器的

§17.7 有五条判据，其中三条要一台跑着的服务器。**它们判的事在断言测试里都有对应的纯逻辑版本**：

| §17.7 | 纯逻辑对应物 | 结果 |
|---|---|---|
| 存档里 grep 明文 token 搜不到 | 密文里不含明文的任何四字符片段 | ✅ |
| 把某条记录换成另一个玩家的密文 → 报解密失败 | AAD 换 playerUUID → `AEADBadTagException` | ✅ |
| 把 recordVersion 改小 → 报版本倒退 | `VaultClient.accept(3, 5)` 为假，且 `get` 抛 `KEY_ROLLBACK` | ✅ |
| 服务端日志 / 审计日志里 grep 搜不到 | ❌ 要真日志 | 未做 |
| `docs/VaultTest.java` 全绿 | ✅ **10089 条断言** | ✅ |

§17.6 列的断言一条不少，另外加了六条（标在测试里）：AAD 用长度前缀拼不许相邻字段借位、
改 nonce 必失败、填充能无歧义还原（含以零字节结尾的明文）、同一明文两次加密密文不同、
密文里不含明文片段、版本表存进 local 再读回来照样挡回滚。

**构造表逐项对上**：PBKDF2-HMAC-SHA256 600,000 轮 / 256 bit、AES-256-GCM、
nonce 12 字节随机、salt 16 字节、AAD 六个字段、明文补齐到 64 字节倍数。

## 四、配额

三个档的超限写入都**显式抛** `StoreQuota.QuotaExceeded`，带可读的本地化键，
不静默截断、不静默丢 key。§17.2 的 3 项与 §17.3 的 6 项在断言测试里逐个对过数。

## 五、本步做不到的

| §17.7 + 任务书 | 状态 |
|---|---|
| `docs/VaultTest.java` 全绿（三目标） | ✅ |
| 存档 / 日志里 grep 不到明文 | ◐ 密文侧已判；真存档与真日志要跑服务器 |
| 手动换记录 / 改版本号的复现 | ◐ 逻辑已判；手动复现要跑服务器 |
| `ctx` 上没有能读 sealed 明文的方法 | ✅ 当前 `ctx.sealed` 只挂真实可用的 get；无消费方的 put 已移除 |
| local 档不进网络包 | ✅ `grep LocalStore` 在 net 包下为空 |
| 配额超限显式拒绝 | ✅ |
| **死亡保留：死后 scriptGuards 仍在** | ❌ **要跑服务器**。代码侧：`copyDeathPersistentFrom` 带了它，1.21.1 两支的 attachment 都 `copyOnDeath()` |
| 两条 grep 收口 | ✅ |
| 作者指南两条 | ✅ `docs/script-app-author-guide.md` |

**保险箱口令界面**（「设置 → 保险箱」）写了，`VaultPage` + `PhoneScreen` 九处接点，
三条必须出现的文案都在。**但它一次都没渲染过** —— 我跑不了 runClient。
编得过、逻辑（强度档、二次输入一致、口令只在 `char[]` 里且关页即抹）有断言，
像素与手感没人看过。

## 怎么复现

```bash
cd platforms/1.20.1-forge && ./gradlew assertTests    # 或 1.21.1-neoforge / 1.21.1-fabric
```
