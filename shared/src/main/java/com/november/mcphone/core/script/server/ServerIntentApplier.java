package com.november.mcphone.core.script.server;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.ItemRefs;
import com.november.mcphone.core.script.net.ScriptErrorCode;
import com.november.mcphone.platform.LootAccess;
import com.november.mcphone.platform.PlayerAbilities;
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

    /** 礼包白名单的标签 id：默认只含原版，服主在数据包里维护（改标签不用重新审批）。 */
    private static final TagKey<Item> GIFTABLE =
            TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath("mcphone", "giftable"));

    /** UUID → 在线玩家。离线返回 null（结果回 UNAVAILABLE，不静默吞）。 */
    private final Function<UUID, ServerPlayer> players;

    public ServerIntentApplier(Function<UUID, ServerPlayer> players) {
        this.players = players;
    }

    @Override
    public Landed apply(UUID playerId, List<ActionIntent> intents) {
        if (intents == null || intents.isEmpty()) return Landed.ok();
        ServerPlayer player = players == null ? null : players.apply(playerId);
        if (player == null) {
            // 玩家已经离线：东西没地方放，明确回"做不了"，不写账本成功
            return new Landed(ScriptErrorCode.UNAVAILABLE, "mcphone.script.intent_unavailable");
        }
        ServerLevel level = (ServerLevel) player.level();

        // ---- 第一步：校验 + 物化（只读；掷表不把东西给谁）
        List<ItemStack> stacks = new ArrayList<>();
        List<ActionIntent> attrs = new ArrayList<>();
        for (ActionIntent intent : intents) {
            switch (intent.kind()) {
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
                default -> {
                    MCphone.LOGGER.warn("[MCphone] 不认识的意图种类：{}", intent.kind());
                    return fail(ScriptErrorCode.INVALID_ARGUMENT, "");
                }
            }
        }

        // ---- 第二步：容量预检（只数空格子，保守）。放不下就一个都不放。
        var inventory = player.getInventory();
        int empty = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) empty++;
        }
        List<Integer> counts = new ArrayList<>(stacks.size());
        List<Integer> maxes = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            counts.add(stack.getCount());
            maxes.add(stack.getMaxStackSize());
        }
        if (!InventoryFit.fits(empty, counts, maxes)) {
            return new Landed(ScriptErrorCode.INVENTORY_FULL,
                    ScriptErrorCode.INVENTORY_FULL.defaultMessageKey());
        }

        // ---- 第三步：落地。先属性后物品；走到这里再失败就是结果不明。
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
        for (ItemStack stack : stacks) {
            if (!inventory.add(stack)) {
                MCphone.LOGGER.error("[MCphone] item.give 在容量预检之后仍然放不进去，结果不明：{}", stack);
                return fail(ScriptErrorCode.UNKNOWN, "");
            }
        }
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

    /** 修饰符 id：{@code mcphone:script/<intent 里那个 key>}。key 由产出侧拼好（含 appId 路径）。 */
    private static ResourceLocation modifierId(ActionIntent intent) {
        String key = intent.kind().equals(ActionIntent.ATTR_GRANT)
                ? intent.asAttr().modifierKey() : intent.asRevoke().modifierKey();
        return ResourceLocation.tryParse(MCphone.MODID + ":script/" + key);
    }
}
