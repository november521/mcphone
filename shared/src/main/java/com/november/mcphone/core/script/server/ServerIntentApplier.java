package com.november.mcphone.core.script.server;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.ItemRefs;
import com.november.mcphone.core.script.net.ScriptErrorCode;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 生产落地端（S18）：把意图变成真实效果，<b>只在主线程调</b>。
 *
 * <h2>本批支持</h2>
 *
 * <ul>
 *   <li>{@code item.give} → 放发起者背包；放不下按 {@code reject} 策略回
 *       {@link ScriptErrorCode#INVENTORY_FULL}（§20.9），<b>一个都不放</b>。</li>
 * </ul>
 *
 * <p>{@code loot.roll} / {@code attr.*} 的落地要 <code>platform/LootAccess</code> 与
 * <code>platform/PlayerAbilities</code> 两个门面，在 PR② 的后一提交接上；在那之前一律回
 * {@code UNAVAILABLE}（不静默吞、不谎报成功）。{@code ctx.loot} / {@code ctx.attr} 节点由宿主
 * 决定挂不挂 —— 接上门面之前它们不该出现在生产 {@code ctx} 上（E12）。
 */
public final class ServerIntentApplier implements IntentApplier {

    /** UUID → 在线玩家。离线返回 null（结果回 UNAVAILABLE，不静默吞）。 */
    private final java.util.function.Function<java.util.UUID, ServerPlayer> players;

    public ServerIntentApplier(java.util.function.Function<java.util.UUID, ServerPlayer> players) {
        this.players = players;
    }

    /** 解析阶段的结果：要么一条错误（整批不落地），要么一串待放入的物品。 */
    record Resolved(Landed error, List<ItemStack> stacks) {
        static Resolved fail(ScriptErrorCode code, String messageKey) {
            return new Resolved(new Landed(code, messageKey), List.of());
        }

        static Resolved ok(List<ItemStack> stacks) {
            return new Resolved(Landed.ok(), List.copyOf(stacks));
        }
    }

    /**
     * 第一步（纯解析，不碰玩家）：把意图变成具体物品。不支持的种类 / 坏数据 → 整批失败。
     * <b>给断言直接调</b>；生产从 {@link #apply} 进来。
     */
    static Resolved resolve(List<ActionIntent> intents) {
        List<ItemStack> incoming = new ArrayList<>(intents.size());
        for (ActionIntent intent : intents) {
            switch (intent.kind()) {
                case ActionIntent.ITEM_GIVE -> {
                    ActionIntent.Give give = intent.asGive();
                    ItemStack stack = ItemRefs.resolve(give.itemId(), give.count());
                    if (stack.isEmpty()) {
                        MCphone.LOGGER.warn("[MCphone] item.give 的物品 id 解析不出：{}", give.itemId());
                        return Resolved.fail(ScriptErrorCode.INVALID_ARGUMENT,
                                ScriptErrorCode.INVALID_ARGUMENT.defaultMessageKey());
                    }
                    incoming.add(stack);
                }
                case ActionIntent.LOOT_ROLL, ActionIntent.ATTR_GRANT, ActionIntent.ATTR_REVOKE -> {
                    // 门面未接通：明确回"做不了"，别让脚本以为已经发了
                    return Resolved.fail(ScriptErrorCode.UNAVAILABLE, "mcphone.script.intent_unavailable");
                }
                default -> {
                    MCphone.LOGGER.warn("[MCphone] 不认识的意图种类：{}", intent.kind());
                    return Resolved.fail(ScriptErrorCode.INVALID_ARGUMENT,
                            ScriptErrorCode.INVALID_ARGUMENT.defaultMessageKey());
                }
            }
        }
        return Resolved.ok(incoming);
    }

    @Override
    public Landed apply(java.util.UUID playerId, List<ActionIntent> intents) {
        if (intents == null || intents.isEmpty()) return Landed.ok();
        ServerPlayer player = players == null ? null : players.apply(playerId);
        if (player == null) {
            // 玩家已经离线：东西没地方放，明确回"做不了"，不写账本成功
            return new Landed(ScriptErrorCode.UNAVAILABLE, "mcphone.script.intent_unavailable");
        }

        // 第一步：全部解析成具体物品；任何一条不支持/坏数据 → 整批不落地
        Resolved resolved = resolve(intents);
        if (!resolved.error().succeeded()) return resolved.error();
        List<ItemStack> incoming = resolved.stacks();

        // 第二步：容量预检（只数空格子，保守）。放不下就一个都不放。
        var inventory = player.getInventory();
        int empty = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            if (inventory.getItem(i).isEmpty()) empty++;
        }
        List<Integer> counts = new ArrayList<>(incoming.size());
        List<Integer> maxes = new ArrayList<>(incoming.size());
        for (ItemStack stack : incoming) {
            counts.add(stack.getCount());
            maxes.add(stack.getMaxStackSize());
        }
        if (!InventoryFit.fits(empty, counts, maxes)) {
            return new Landed(ScriptErrorCode.INVENTORY_FULL,
                    ScriptErrorCode.INVENTORY_FULL.defaultMessageKey());
        }

        // 第三步：真的放。预检之后仍然失败 = 已经放进去的收不回来 → 结果不明，不许谎报成功。
        for (ItemStack stack : incoming) {
            if (!inventory.add(stack)) {
                MCphone.LOGGER.error("[MCphone] item.give 在容量预检之后仍然放不进去，结果不明：{}", stack);
                return new Landed(ScriptErrorCode.UNKNOWN, ScriptErrorCode.UNKNOWN.defaultMessageKey());
            }
        }
        return Landed.ok();
    }
}
