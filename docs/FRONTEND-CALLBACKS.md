# 前端函数与异步回调

`app.vue` 和 `pages/*.vue` 的 `<script>` 可以声明初始 `state` 和具名函数。源码在装包时编译一次，页面打开后创建独立运行作用域。模板的插值仍使用有界表达式；函数从 `@click` 调用。

```vue
<script>
state = { ready: true, nextAt: '0', count: 0 }

function claim() {
    phone.call('claim_daily', {}, function (r) {
        if (r.ok) {
            state.ready = false;
            state.nextAt = r.data.nextAt;
            state.count++;
            toast('领取成功');
        } else if (r.code === 'COOLDOWN') {
            toast('还没到时间');
        } else {
            toast(r.message);
        }
    });
}
</script>
<template>
<column>
  <text :text="count" />
  <button text="领取" :enabled="ready" @click="claim()" />
</column>
</template>
```

示例省略单文件形式必需的 `<manifest>`；打包形式的清单放在 `manifest.json`。`nextAt` 按 RPC 的整数约定使用十进制字符串，避免毫秒时间和金额经过浮点数损失精度。

## 宿主接口

| 接口 | 回调或效果 |
| --- | --- |
| `phone.call(actionId, params, callback)` | `r.ok`、`r.code`、`r.data`、已本地化的 `r.message`，以及字符串形式的 `requestId`、`retryAfterMs`、`stateRevision` |
| `phone.fetch(url, {authorization:'', offset:0}, callback)` | 私人网络请求的 `status`、`text` 等结果；复用包的主机声明、玩家许可和服务器网络开关 |
| `phone.image(url, {authorization:''}, callback)` | 成功返回仅本 App 可用的 src、width、height；远程 PNG 最多 64 KiB、512×512 |
| `phone.imageBytes(base64, callback)` | 把已取得的 PNG 字节登记成同样的私人图片，不会再联网 |
| `phone.sealed.get(key, callback)` | `r.ok`、`r.message`、成功时的 `r.data.text`；须先由玩家在原生设置中解锁保险箱 |
| `phone.sealed.put(key, plaintext, callback)` | `r.ok`、`r.message`；明文最多 2 KiB，服务器只收到密文 |
| `toast(text)` | 页面内提示，最多 256 字符 |
| `nav(page)` / `back()` / `close()` | 同一 App 导航、返回或关闭 |

`backend` 是当前握手与调用结果的只读快照，每次函数或回调执行时更新。RPC 回包及其嵌套数据只读，作者需要改值时应复制到自己的状态。

同一 App 的 `phone.call` 总在飞上限是四个；前端单页的异步桥也最多保留四个回调。第五次调用在本地回 `IN_PROGRESS`，不发送、不排队。普通参数和结果最多 4 KiB，只接受 JSON 数据值；大数和小数须使用字符串。`UNKNOWN` 不会触发宿主重试。

## 状态和执行边界

- 顶层只允许字面量 `state` 和具名函数，不执行顶层业务调用。最多 32 个函数，每个最多八个普通参数；不能覆盖宿主和标准全局名字。
- 保留初始状态的既有规则：最多 16 个键，类型和对象形状固定。普通字符串仍最多 64 字符；绑定 text-input 的字符串允许按该节点声明的 max-length（硬上限 4096）提交，因此异步读取的较长保险箱文本可回填。RPC、KV 和保险箱的字节限额分别继续生效。
- 页面之间保持各自状态，返回后继续使用；关闭 App 清空状态和闭包。换服或撤销策略时丢弃旧回调，迟到结果不会恢复旧页面。
- 每次执行最多 200 万 Rhino 指令单位、50 ms。同步返回形成的回调链还受总墙钟和八次事件限制，避免 Java 栈递归。
- 同一 App 的所有页面共享 1 MiB 驻留预算。计量包括回调的活动作用域、嵌套对象和大字符串，拒绝 getter、不可计量的 Map/Set/生成器等驻留值；临时计算后请转为普通数据再保留。
- 使用与后端同一套沙箱和原生尺寸闸，禁止 Java 访问、`eval` 和动态函数构造。失败进入错误页并显示原文件行号，不继续处理旧布局的点击或键盘输入。

状态提交和本次尚未发送的宿主操作在求值及预算检查成功后才生效。先前已发送的服务端请求可能已经完成，前端后来发生异常不能撤销它，应核对服务端结果和审计记录。

网络 PENDING 不会消费终态回调；同 URL 已在飞时新请求返回 BUSY。临时原生吊销检查期间最多暂存四个终态结果，检查通过后再交付；关闭、换服或最终撤销清空它们。图片完整步骤见 `REMOTE-IMAGES-TESTS.md`。

## 人工测试任务

自动化覆盖见 `FrontendRuntimeTest`、`SfcCompilerTest` 和 `ScriptCallTest`。以下需要由测试者在真实游戏内执行：

前端语义断言使用同包可控单调钟，避免 CI 的并发调度和 JVM 首次初始化触发偶发超时；另行强制到达截止时间，验证状态回滚、闭包释放和未发送操作取消，并验证 Rhino 观察器硬中断。生产入口始终使用 System.nanoTime，50 ms 和两百万指令上限保持不变。

1. 安装含 `claim()` 的签名测试 App。批准并授权 `claim_daily` 后点击，确认只收到一次物品、状态和提示更新；未部署和未授权时检查对应结果文案。
2. 在返回结果前关闭手机、切换服务器或卸载 App。确认迟到结果不弹回旧页面、不修改新服务器的状态。
3. 连续发起五个未完成请求，确认只有四个请求进入服务端，第五个显示 `IN_PROGRESS`；服务端回包后再次点击可以调用。
4. 在详情页发请求后返回入口页。确认回调更新的是发起页面自己的状态，返回详情页后可以看到结果。
5. 构造抛异常、死循环、坏状态类型、含 getter 的参数、超过 4 KiB 的参数和超大闭包。确认出现错误页，错误前尚未发送的操作没有进入服务端，旧按钮和输入框不能继续操作。
6. 在执行队列中撤销 App 或禁用能力，确认服务端最终拒绝落地。用服务端审计与物品/余额核对，不能仅凭页面 toast 判断成功。
7. 关闭私人网络许可后测试 `phone.fetch`；开启许可和服务器允许项后测试正常文字响应。更换凭证后结果缓存不能串用。
8. 手动解锁保险箱后测试 get/put，检查客户端可解密、服务端文件只有密文；关闭手机后再操作应要求重新解锁。

本文件描述已实现的前端阶段，不能代替整个开发方案的最终完成矩阵或真实游戏验收记录。
