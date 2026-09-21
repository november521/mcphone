// S15/S18 真服窗口夹具：每个动作只做一件可核对的事，返回 ctx.ok({...}) 让调用端能读结果。
// 只登记意图 / 只读，不在这里碰世界；落地在主线程（见 docs/P1-window-fixtures.md）。

actions.daily = function (ctx) {
    ctx.give('minecraft:diamond', 3);
    return ctx.ok({});
};

actions.loot = function (ctx) {
    ctx.loot.roll('myserver:daily_gift');
    return ctx.ok({});
};

actions.buff = function (ctx) {
    ctx.attr.grant('minecraft:generic.movement_speed', 0.1);
    ctx.effect.give('minecraft:speed', 30, 1);
    return ctx.ok({});
};

actions.unbuff = function (ctx) {
    ctx.attr.revoke('minecraft:generic.movement_speed');
    return ctx.ok({});
};

actions.points = function (ctx) {
    ctx.score.add('points', 1);
    return ctx.ok({ points: ctx.score.get('points') });
};

actions.vip = function (ctx) {
    return ctx.ok({ vip: ctx.predicate.test('myserver:is_vip') });
};

// 只读钱包：#6 的两台服务器用不同货币 id，这段代码不改，靠 default() 适配。
// list() 只调用、不运算返回值（宿主交出来的是 Java 对象，别在脚本里按 JS 数组用它）。
actions.wallet = function (ctx) {
    var id = ctx.currency.default();
    ctx.currency.list();
    var balance = id === null ? null : ctx.currency.balance(id);
    return ctx.ok({ defaultId: id, balance: balance });
};
