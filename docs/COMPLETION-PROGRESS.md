# 开发收尾记录（2026-10-06）

工作目录 `mcphone-completion`，分支 `feature/complete-script-platform`，基线 `2c8565c`。本地实现覆盖方案中本轮允许的执行面，逐章落点与保留边界见 [完成矩阵](COMPLETION-MATRIX.md)。代码保存在本地开发分支，尚未推送或合并；真实游戏测试由用户自行执行，不能以编译结果代替。

本轮增加真实前端回调、长文本输入、商店与独立文件通道、签名及版本见证、作者更新和吊销、守卫、礼包、存储配额与保险箱、收件箱、物品引用与托管、货币归属及结算日志、资源读取、HTTPS 与私人 PNG、通知与后台、原生管理事务、审计及本人 KV 清理。

## 自动化与交付

最终三个目标的完整 build 均通过，失败任务数为零。构建包含所有断言测试与目标适用的架构闸；跨目标共执行 338 个断言测试任务，同一共用测试在不同目标重复运行，不代表 338 种独立功能。

| 目标 | Java | 断言任务 | 架构闸 | 最终日志 |
| --- | --- | --- | --- | --- |
| Forge 1.20.1 | 17 | 112 | 10 | completion-forge-final-build2.log |
| NeoForge 1.21.1 | 21 | 113 | 16 | completion-neoforge-final-build4.log |
| Fabric 1.21.1 | 21 | 113 | 16 | completion-fabric-final-build2.log |

签名样例检查通过 77 项，涵盖真实包读取、SFC 编译、静态后端预检、前端封套、更新双签名、权限增量、人工降级及签名冲突。交付的生成工具已实际运行，生成的七个 ZIP、三份 feed 和索引来自同一批测试作者。清理检查验证持久容量释放、其他玩家/App 隔离、密文保留、重启不从旧附件复活数据。

修正旧 1.20.1 数据包的 tags/items 路径，夹具检查共 81 项；临时恢复旧 tags/item 错误时确实失败两项，恢复正确路径后全部通过。三份完整 JAR 已检查加载器元数据、必需的 Rhino/JavaMP3 及新增客户端类型；Forge class 版本 61，NeoForge/Fabric 65。

完整游戏任务见 [自行验收任务](COMPLETION-TEST-TASKS.md)，共 T01–T43，另外有原生管理 A1–A11 与故障 R1–R7。全部游戏状态目前为未测。JAR、SHA-256、签名样例、源码、数据包、配置、记录模板和最终日志统一收集到工作区 `MCPhone-验收包-20261006`，测试构建沿用当前 1.10.2，不是正式发布。

## 已明确的范围

`cost` 按方案 §20.8 策略 A 拒绝。B 类 groups/waypoints/escrow/stats Java 接口按 §23.5 保留；resources 2 为真实只读接口。container、资源移动和远程机器操作等不开放面不伪装成功。外部资源与经济提供者、输入法、真实 GL 生命周期、跨版本物品附加数据、崩溃窗口、KubeJS 共存及加载顺序须用户实测。

开发说明：[前端回调](FRONTEND-CALLBACKS.md)、[物品与读取](SCRIPT-ITEMS-READS.md)、[更新](SERVER-FRONTEND-UPDATES.md)、[SDK 与运行期](SDK-RUNTIME.md)、[私人 PNG](REMOTE-IMAGES-TESTS.md)、[收件箱/货币/通知](MAILBOX-CURRENCY-NOTIFICATIONS.md)、[原生管理](NATIVE-ADMIN-TESTS.md)。
