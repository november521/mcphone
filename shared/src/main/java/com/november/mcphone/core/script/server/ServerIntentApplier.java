package com.november.mcphone.core.script.server;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.ItemRefs;
import com.november.mcphone.core.script.net.ScriptErrorCode;
import com.november.mcphone.platform.LootAccess;
import com.november.mcphone.platform.PlayerAbilities;
import com.november.mcphone.platform.PlayerEffects;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * 生产落地端（S18）：把意图变成真实效果，<b>只在主线程调</b>。
 *
 * <h2>整批原子（尽力而为）</h2>
 *
 * 三步：① 校验 + 物化（掷战利品表、解析物品、检查属性 id —— 都是只读的，掷表不动世界）；
 * ② 背包容量预检（只数空格子，保守）；③ 落地（先属性后物品）。①② 任一失败 = <b>一个都不落地</b>；
 * ③ 走到一半失败 = 结果不明（{@code UNKNOWN}），绝不谎报成功。
 *
 * <h2>各层的坑</h2>
 *
 * <ul>
 *   <li>背包满：§20.9 的 {@code reject} 策略 → {@link ScriptErrorCode#INVENTORY_FULL}，
 *       <b>不消耗配额、不写 cooldown</b>（那两样是守卫的事，这里根本没碰）。</li>
 *   <li>表不存在：{@link ScriptErrorCode#INVALID_ARGUMENT} + {@link #NO_SUCH_TABLE}，
 *       与"背包满"分得清。</li>
 *   <li>属性：{@code Transient} 修饰符，id 是 {@code mcphone:script/<appId path>/<属性>}，
 *       <b>撤销只删自己那条</b>；认不得的属性回 {@link #ATTR_UNAVAILABLE}。</li>
 * </ul>
 */
public final class ServerIntentApplier implements IntentApplier {

    /** 战利品表不存在时的本地化键。 */
    public static final String NO_SUCH_TABLE = "mcphone.script.loot.no_such_table";

    /** 属性 id 这一支认不得时的本地化键。 */
    public static final String ATTR_UNAVAILABLE = "mcphone.script.attr.unavailable";

    /** 物品不在礼包白名单时的本地化键（§18.5）。 */
    public static final String NOT_GIFTABLE = "mcphone.script.give.not_giftable";

    /** 效果 id 这一支认不得时的本地化键。 */
    public static final String EFFECT_UNAVAILABLE = "mcphone.script.effect.unavailable";

    /** 礼包白名单的标签 id：默认只含原版，服主在数据包里维护（改标签不用重新审批）。 */
    private static final TagKey<Item> GIFTABLE =
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("mcphone", "giftable"));

    /** UUID → 在线玩家。离线返回 null（结果回 UNAVAILABLE，不静默吞）。 */
    private final Function<UUID, ServerPlayer> players;
    private final ServerMailbox mailbox;
    private ServerNotifications notifications;
    private ServerItemEscrow escrow;
    private ServerScriptItems itemHandles;
    public void itemHandles(ServerScriptItems value){itemHandles=value;}
    private final SelfMessageRate messageRate=new SelfMessageRate(System::currentTimeMillis);
    public void escrow(ServerItemEscrow value){escrow=value;}
    public void notifications(ServerNotifications value){notifications=value;}
    private java.util.function.BooleanSupplier mailboxEnabled = () -> true;
    public void mailboxEnabled(java.util.function.BooleanSupplier enabled) { mailboxEnabled = enabled; }

    public ServerIntentApplier(Function<UUID, ServerPlayer> players) {
        this(players, null);
    }
    public ServerIntentApplier(Function<UUID, ServerPlayer> players, ServerMailbox mailbox) {
        this.players = players;
        this.mailbox = mailbox;
    }

    @Override
    public Landed apply(UUID playerId,List<ActionIntent> intents,com.november.mcphone.core.script.net.ScriptRpc rpc){
        if(intents!=null&&intents.stream().anyMatch(i->i.kind().equals(ActionIntent.ITEM_GIVE_OTHER))){
            // 他人物品单独成批，防止 A 已收到而 B 背包满时被误报为整批未执行。
            if(intents.size()!=1||rpc==null||ScriptHost.current()==null)return fail(ScriptErrorCode.INVALID_ARGUMENT,"");
            var give=intents.get(0).asGiveOther();var sender=players.apply(playerId);var recipient=players.apply(give.recipient());
            ScriptHost host=ScriptHost.current();Deployment d=host.deployments().deployment(rpc.appId());
            if(sender==null||recipient==null||d==null)return fail(ScriptErrorCode.UNAVAILABLE,"");
            for(String cap:List.of("item.give","item.give.other"))if(host.capabilityPolicy().check(cap,java.util.Set.copyOf(d.approvedCapabilities()))!=CapabilityPolicy.Verdict.OK)return fail(ScriptErrorCode.NOT_AUTHORIZED,"");
            if(!host.capabilities().boundary().crossDimensionTransfer()&&!sender.serverLevel().dimension().equals(recipient.serverLevel().dimension()))return fail(ScriptErrorCode.UNAVAILABLE,"");
            return applyRecorded(give.recipient(),List.of(ActionIntent.itemGive(give.itemId(),give.count(),"")),rpc,playerId);
        }
        if(intents!=null&&intents.stream().anyMatch(i->i.kind().equals(ActionIntent.ESCROW_OFFER)||i.kind().equals(ActionIntent.ITEM_TAKE))){if(intents.size()!=1||escrow==null)return fail(ScriptErrorCode.INVALID_ARGUMENT,"");ActionIntent intent=intents.get(0);var e=intent.asEscrow();boolean destroy=intent.kind().equals(ActionIntent.ITEM_TAKE);if(!e.app().equals(rpc.appId()))return fail(ScriptErrorCode.INVALID_ARGUMENT,"");try{return escrow.propose(playerId,e.app(),rpc.actionId(),rpc.deployRev(),e.slot(),e.count(),destroy?playerId:UUID.fromString(e.recipient()),destroy)?Landed.ok():fail(ScriptErrorCode.EXHAUSTED,"");}catch(IllegalArgumentException bad){return fail(ScriptErrorCode.INVALID_ARGUMENT,"");}}
        return applyRecorded(playerId,intents,rpc);
    }
    @Override
    public Landed apply(UUID playerId, List<ActionIntent> intents) {
        return applyRecorded(playerId,intents,null);
    }
    private Landed applyRecorded(UUID playerId,List<ActionIntent> intents,com.november.mcphone.core.script.net.ScriptRpc rpc){
        return applyRecorded(playerId,intents,rpc,playerId);
    }
    private Landed applyRecorded(UUID playerId,List<ActionIntent> intents,com.november.mcphone.core.script.net.ScriptRpc rpc,UUID actor){
        if (intents == null || intents.isEmpty()) return Landed.ok();
        ServerPlayer player = players == null ? null : players.apply(playerId);
        if (player == null) {
            // 玩家已经离线：东西没地方放，明确回"做不了"，不写账本成功
            return new Landed(ScriptErrorCode.UNAVAILABLE, "mcphone.script.intent_unavailable");
        }
        ServerLevel level = (ServerLevel) player.level();
        // 托管与扣除先交给原生确认页面；不允许与发奖意图混在同一次请求里。
        if(intents.stream().anyMatch(i->i.kind().equals(ActionIntent.ESCROW_OFFER)||i.kind().equals(ActionIntent.ITEM_TAKE)))return fail(ScriptErrorCode.UNAVAILABLE,"");

        // ---- 第一步：校验 + 物化（只读；掷表不把东西给谁）
        List<ItemStack> stacks = new ArrayList<>();
        List<ActionIntent> attrs = new ArrayList<>();
        List<ActionIntent> effects = new ArrayList<>();
        List<ActionIntent> notices = new ArrayList<>();
        List<String> messages=new ArrayList<>();
        java.util.Set<String> consumedHandles=new java.util.HashSet<>();
        for (ActionIntent intent : intents) {
            switch (intent.kind()) {
                case ActionIntent.ITEM_REFS -> {
                    if(itemHandles==null||rpc==null)return fail(ScriptErrorCode.UNAVAILABLE,"");List<String> handles=intent.asRefs();
                    for(String handle:handles)if(!consumedHandles.add(handle))return fail(ScriptErrorCode.INVALID_ARGUMENT,"");
                    try{for(ItemStack stack:itemHandles.materialize(actor,rpc,handles)){if(!stack.is(GIFTABLE))return new Landed(ScriptErrorCode.INVALID_ARGUMENT,NOT_GIFTABLE);stacks.add(stack);}}
                    catch(com.november.mcphone.core.script.engine.HostError bad){return fail(bad.resultCode(),bad.messageKey());}
                }
                case ActionIntent.MESSAGE_SELF -> {String text=intent.asMessage();if(text.length()>1536||text.codePoints().anyMatch(Character::isISOControl))return fail(ScriptErrorCode.INVALID_ARGUMENT,"");messages.add(text);}
                case ActionIntent.NOTIFY_SELF,ActionIntent.NOTIFY_SUBSCRIBERS -> {
                    if(notifications==null)return fail(ScriptErrorCode.UNAVAILABLE,"");
                    var notice=intent.asNotify();notifications.validate(notice.app(),notice.message());notices.add(intent);
                }
                case ActionIntent.ITEM_GIVE -> {
                    ActionIntent.Give give = intent.asGive();
                    ItemStack stack = ItemRefs.resolve(give.itemId(), give.count());
                    if (stack.isEmpty()) {
                        MCphone.LOGGER.warn("[MCphone] item.give 的物品 id 解析不出：{}", give.itemId());
                        return fail(ScriptErrorCode.INVALID_ARGUMENT, "");
                    }
                    // §18.5：白名单是标签，服主在数据包里维护 —— 改标签不改 App 的 digest
                    if (!stack.is(GIFTABLE)) {
                        MCphone.LOGGER.warn("[MCphone] item.give 的物品不在礼包白名单里：{}", give.itemId());
                        return new Landed(ScriptErrorCode.INVALID_ARGUMENT, NOT_GIFTABLE);
                    }
                    stacks.add(stack);
                }
                case ActionIntent.LOOT_ROLL -> {
                    ResourceLocation id = tableId(intent);
                    if (id == null) return fail(ScriptErrorCode.INVALID_ARGUMENT, "");
                    if (!LootAccess.exists(level, id)) {
                        MCphone.LOGGER.warn("[MCphone] loot.roll 的表不存在：{}", id);
                        return new Landed(ScriptErrorCode.INVALID_ARGUMENT, NO_SUCH_TABLE);
                    }
                    stacks.addAll(LootAccess.roll(level, id, player));
                }
                case ActionIntent.ATTR_GRANT, ActionIntent.ATTR_REVOKE -> {
                    ResourceLocation id = attrId(intent);
                    if (id == null || !PlayerAbilities.available(player, id)) {
                        return new Landed(ScriptErrorCode.INVALID_ARGUMENT, ATTR_UNAVAILABLE);
                    }
                    attrs.add(intent);
                }
                case ActionIntent.EFFECT_GIVE -> {
                    ResourceLocation id = effectId(intent);
                    if (id == null || !PlayerEffects.available(id)) {
                        return new Landed(ScriptErrorCode.INVALID_ARGUMENT, EFFECT_UNAVAILABLE);
                    }
                    effects.add(intent);
                }
                default -> {
                    MCphone.LOGGER.warn("[MCphone] 不认识的意图种类：{}", intent.kind());
                    return fail(ScriptErrorCode.INVALID_ARGUMENT, "");
                }
            }
        }

        // ---- 第二步：容量预检（只数空格子，保守）。放不下就一个都不放。
        if(rpc!=null&&ScriptHost.current()!=null){ScriptHost host=ScriptHost.current();com.google.gson.JsonObject data=new com.google.gson.JsonObject();data.addProperty("version",Long.toString(host.version(host.deployments().deployment(rpc.appId()).packageDigest())));data.addProperty("request",Long.toString(rpc.requestId()));data.addProperty("action",rpc.actionId());data.addProperty("recipient",playerId.toString());com.google.gson.JsonArray output=new com.google.gson.JsonArray();for(ItemStack stack:stacks)output.add(com.november.mcphone.platform.StackCodecs.encode(stack,player.serverLevel().getServer().registryAccess()));data.add("items",output);data.add("intents",new com.google.gson.Gson().toJsonTree(intents));try{host.quotas().audit().append(actor,"script.land",rpc.appId(),null,data);}catch(java.io.IOException failure){return fail(ScriptErrorCode.UNAVAILABLE,"");}}
        var inventory = player.getInventory();
        int empty = 0;
        for (int i = 0; i < 36; i++) {
            if (inventory.getItem(i).isEmpty()) empty++;
        }
        List<Integer> counts = new ArrayList<>(stacks.size());
        List<Integer> maxes = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            counts.add(stack.getCount());
            maxes.add(stack.getMaxStackSize());
        }
        boolean mailed = !InventoryFit.fits(empty, counts, maxes);
        if(!messageRate.allow(playerId,messages.size()))return fail(ScriptErrorCode.RATE_LIMITED,"");
        if (mailed && (mailbox == null || !mailboxEnabled.getAsBoolean() || !mailbox.deposit(playerId, stacks, "script reward"))) {
            return new Landed(ScriptErrorCode.INVENTORY_FULL,
                    ScriptErrorCode.INVENTORY_FULL.defaultMessageKey());
        }

        // ---- 第三步：落地。先属性、再效果、后物品；走到这里再失败就是结果不明。
        for (ActionIntent intent : attrs) {
            ResourceLocation attrId = attrId(intent);
            ResourceLocation modifierId = modifierId(intent);
            boolean ok = intent.kind().equals(ActionIntent.ATTR_GRANT)
                    ? PlayerAbilities.grant(player, attrId, modifierId, intent.asAttr().amount(), intent.asAttr().operation())
                    : PlayerAbilities.revoke(player, attrId, modifierId);
            if (!ok) {
                MCphone.LOGGER.error("[MCphone] 属性落地在预检之后仍然失败，结果不明：{}", intent);
                return fail(ScriptErrorCode.UNKNOWN, "");
            }
        }
        for (ActionIntent intent : effects) {
            ActionIntent.Effect e = intent.asEffect();
            ResourceLocation effectId = ResourceLocation.tryParse(e.effectId());
            if (effectId == null || !PlayerEffects.give(player, effectId, e.durationTicks(), e.amplifier())) {
                MCphone.LOGGER.error("[MCphone] 效果落地在预检之后仍然失败，结果不明：{}", intent);
                return fail(ScriptErrorCode.UNKNOWN, "");
            }
        }
        for (ItemStack stack : mailed ? List.<ItemStack>of() : stacks) {
            if (!inventory.add(stack)) {
                MCphone.LOGGER.error("[MCphone] item.give 在容量预检之后仍然放不进去，结果不明：{}", stack);
                return fail(ScriptErrorCode.UNKNOWN, "");
            }
        }
        for(ActionIntent intent:notices){var n=intent.asNotify();notifications.post(playerId,n.app(),n.message(),intent.kind().equals(ActionIntent.NOTIFY_SUBSCRIBERS));}
        for(String text:messages)player.sendSystemMessage(net.minecraft.network.chat.Component.literal(text));
        return Landed.ok();
    }

    private static Landed fail(ScriptErrorCode code, String messageKey) {
        return new Landed(code, messageKey == null || messageKey.isEmpty() ? code.defaultMessageKey() : messageKey);
    }

    private static ResourceLocation tableId(ActionIntent intent) {
        return ResourceLocation.tryParse(intent.asRoll().tableId());
    }

    private static ResourceLocation attrId(ActionIntent intent) {
        String raw = intent.kind().equals(ActionIntent.ATTR_GRANT)
                ? intent.asAttr().attributeId() : intent.asRevoke().attributeId();
        return ResourceLocation.tryParse(raw);
    }

    private static ResourceLocation effectId(ActionIntent intent) {
        return ResourceLocation.tryParse(intent.asEffect().effectId());
    }

    /** 修饰符 id：{@code mcphone:script/<intent 里那个 key>}。key 由产出侧拼好（含 appId 路径）。 */
    private static ResourceLocation modifierId(ActionIntent intent) {
        String key = intent.kind().equals(ActionIntent.ATTR_GRANT)
                ? intent.asAttr().modifierKey() : intent.asRevoke().modifierKey();
        return ResourceLocation.tryParse(MCphone.MODID + ":script/" + key);
    }
}
