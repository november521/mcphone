// 每个动作的真实结果必须结合背包、钱包、收件箱和审计核对；UNKNOWN 不自动重试。
actions.read = function(ctx) {
    return ctx.ok({uuid:ctx.player.uuid, position:ctx.player.position, time:ctx.world.time,
        players:ctx.players.onlineCount, cycle:ctx.cycle.label('daily')});
};
actions.wallet = function(ctx) {
    var id=ctx.currency.default();
    return ctx.ok({currency:id, balance:id===null?null:String(ctx.currency.balance(id))});
};
actions.claim = function(ctx) { ctx.give('minecraft:diamond',1); return ctx.ok({claimed:true}); };
actions.loot = function(ctx) { var refs=ctx.loot.roll('myserver:daily_gift');ctx.give(refs);return ctx.ok({rolled:true}); };
actions.mail = function(ctx) {
    var page=ctx.player.inventory(0);
    return ctx.ok({result:ctx.mailbox.deposit(ctx.player.uuid,[page.items[0]],'completion-mail')});
};
actions.kv = function(ctx) { ctx.store.setString('note',ctx.params.note);return ctx.ok({note:ctx.store.getString('note','')}); };
actions.notify = function(ctx) {
    ctx.notify.self('completion:notice',{titleKey:'completion.notice.title',bodyKey:'completion.notice.body',priority:'HIGH',dedupeKey:'completion',expiresAfterMs:60000});
    return ctx.ok({queued:true});
};
actions.hold = function(ctx) {
    var id=ctx.currency.default();return ctx.ok({currency:id,held:ctx.currency.hold(id,ctx.player.uuid,1n,'completion-hold')});
};
actions.refund = function(ctx) { return ctx.ok({result:ctx.currency.refund(ctx.currency.default(),ctx.params.held,'completion-refund')}); };
actions.release = function(ctx) { return ctx.ok({result:ctx.currency.release(ctx.currency.default(),ctx.params.held,'completion-release')}); };
actions.mint = function(ctx) { return ctx.ok({result:ctx.currency.mint(ctx.currency.default(),10n,'completion-mint')}); };
actions.burn = function(ctx) { return ctx.ok({result:ctx.currency.burn(ctx.currency.default(),1n,'completion-burn')}); };
actions.offer = function(ctx) { ctx.escrow.offer(0,1,ctx.params.buyer);return ctx.ok({proposal:true}); };
actions.resource = function(ctx) { return ctx.ok({available:ctx.resource.list()}); };
actions.fetch = function(ctx) { return ctx.ok(ctx.fetch(ctx.params.url,0)); };
actions.background = function(ctx) {
    ctx.notify.self('completion:background',{titleKey:'completion.notice.title',priority:'LOW',dedupeKey:'heartbeat',expiresAfterMs:300000});
    return ctx.ok({time:ctx.time.epochMillis()});
};
