# 命令模板配置与 T34 验收

固定配置文件是世界目录 `serverconfig/mcphone-command-templates.json`。首次生成时总开关和影响他人开关都为 false。模板定义由服主编辑文件，原生「服务器管理」提供查看、开关和重载预览；脚本不能创建或修改定义。

下面模板正文与 `CommandTemplateTest` 的真实验证样例相同。整体配置块尚未作为独立夹具过闸；实际命令解析路径和游戏效果按 T34 检查。

```json
{
  "enabled": false,
  "allow_affect_others": false,
  "permission_plugins": ["lp", "luckperms", "permissions"],
  "templates": [{
    "id": "gift",
    "reviewed": true,
    "commands": ["give ${self} ${item} ${count}"],
    "arguments": {
      "self": {"type": "self"},
      "item": {"type": "resource", "tag": "mcphone:giftable"},
      "count": {"type": "int", "min": 1, "max": 64}
    },
    "allowed_paths": ["/give/targets/item/count"]
  }]
}
```

在测试 App 中声明、审核并批准精确能力 `command.template:gift`，再由原生管理开启命令模板。后端调用为：

```javascript
actions.commandGift = function(ctx) {
    return ctx.ok(ctx.command.run('gift', {item:'minecraft:diamond', count:1}));
};
```

该动作片段未单独作为交付签名包过闸。修改 App 源码后必须重新签名、审核和部署。不要直接修改交付 ZIP 内的 manifest 或 server.js；摘要变化会使作者签名失效。

`self` 由宿主当前登录玩家生成，客户端不得传入。每次执行都用当前玩家、固定权限等级 2、当前 Brigadier 命令树重新解析，解析节点必须精确命中 allowed_paths。原版同步命令返回正数后仍须核对背包；未验证的插件命令即使返回正数也标 UNKNOWN，不自动重试。

## 执行 T34

1. 装载对应测试数据包，确认 giftable 标签允许钻石。记录背包数量，在关闭总开关时调用，物品不变。
2. 用原生重载预览装载定义，再开启模板总开关；只批准别的模板或撤销本模板能力，调用仍须拒绝。
3. 精确批准 gift 模板后，以 count=1 调用，核对一颗钻石及 UTC 审计。
4. 分别传入 count=0、65、非整数，额外 self 字段，含空格/引号/换行/NBT/选择器的 item，及标签外物品。应拒绝且无物品变化。
5. 改成错误 allowed_paths 并重载，调用应拒绝；若共存插件替换解析节点，核对原模板也不能复用过期解析许可。
6. 提交 execute、function、op、mcphone 管理命令或自由 string 槽的定义，重载必须拒绝并保留旧配置。
7. 模板若影响他人，需明确 affect_others、本地总许可及 App 的 command.affect_others 审批；普通 gift 模板不能把目标改为其他玩家。

定义最多 32 个模板，每个 1–8 条、每条 512 字符、最多 16 个强类型参数。只接受 self、带上下界 int、有限 enum、命中服主物品标签的 resource；权限插件的权限节点写死，仅允许 self UUID 槽。
