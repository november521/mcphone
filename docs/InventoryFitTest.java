package com.november.mcphone.core.script.server;

import com.november.mcphone.core.script.net.ScriptErrorCode;

import java.util.ArrayList;
import java.util.List;

/**
 * S18 断言：物品发放的两块纯逻辑 —— 容量预检（{@link InventoryFit}）与意图解析
 * （{@code ServerIntentApplier.resolve}）+ {@link ActionIntent} 的编解码。
 *
 * <p>真把物品塞进玩家背包那一步要真服务器（PR③），这里保证"放不下先拒、坏数据先拒、
 * 不支持的种类明确回做不了"。
 */
public class InventoryFitTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) failures.add(what + "  期望 " + expected + "，实际 " + actual);
    }

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures.add(what);
    }

    static void capacity() {
        check(InventoryFit.fits(1, List.of(1), List.of(64)), "一个空格放一个物品：够");
        check(InventoryFit.fits(1, List.of(64), List.of(64)), "一个空格放一整堆：够");
        check(!InventoryFit.fits(1, List.of(65), List.of(64)), "65 个要两格：不够");
        check(InventoryFit.fits(2, List.of(64, 64), List.of(64, 64)), "两格放两堆：够");
        check(!InventoryFit.fits(0, List.of(1), List.of(64)), "没有空格：不够（不做堆叠合并，保守）");
        check(InventoryFit.fits(1, List.of(16), List.of(16)), "16 上限的一件占一格：够");

        boolean threw = false;
        try {
            InventoryFit.fits(1, List.of(0), List.of(64));
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "数量必须为正");
    }

    static void intentCodec() {
        ActionIntent.Give give = ActionIntent.itemGive("minecraft:diamond", 3, "闪光").asGive();
        eq(give.itemId(), "minecraft:diamond", "item.give 往返：id");
        eq(give.count(), 3, "item.give 往返：数量");
        eq(give.customName(), "闪光", "item.give 往返：自定义名");

        ActionIntent.Roll roll = ActionIntent.lootRoll("myserver:daily").asRoll();
        eq(roll.tableId(), "myserver:daily", "loot.roll 往返：表 id");

        ActionIntent.Attr attr = ActionIntent.attrGrant("minecraft:generic.movement_speed", 0.1d, 0, "speed").asAttr();
        eq(attr.attributeId(), "minecraft:generic.movement_speed", "attr.grant 往返：属性 id");
        eq(attr.amount(), 0.1d, "attr.grant 往返：数值");
        eq(attr.modifierKey(), "speed", "attr.grant 往返：修饰符 key");
        eq(ActionIntent.attrRevoke("minecraft:generic.movement_speed", "speed").asRevoke().modifierKey(),
                "speed", "attr.revoke 往返");

        eq(new ActionIntent(ActionIntent.ITEM_GIVE, new byte[0]).capability(), "item.give",
                "种类 → 能力映射（item.give）");
        eq(new ActionIntent(ActionIntent.LOOT_ROLL, new byte[0]).capability(), "loot.roll",
                "种类 → 能力映射（loot.roll）");
        eq(new ActionIntent(ActionIntent.ATTR_GRANT, new byte[0]).capability(), "attr.grant",
                "种类 → 能力映射（attr.grant）");
        eq(new ActionIntent(ActionIntent.EFFECT_GIVE, new byte[0]).capability(), "effect.give",
                "种类 → 能力映射（effect.give）");
        eq(new ActionIntent("wat", new byte[0]).capability(), null, "没登记的种类没有能力");

        // S18-B2：意图种类映射到的能力必须在 enforced 里 —— 新增一个意图接到"本步无调用点"的项
        // （比如 item.give.other）时这里当场红，逼接线者同步 CapabilityCatalog.ENFORCED。
        for (String kind : new String[]{ActionIntent.ITEM_GIVE, ActionIntent.LOOT_ROLL,
                ActionIntent.ATTR_GRANT, ActionIntent.ATTR_REVOKE, ActionIntent.EFFECT_GIVE}) {
            String cap = new ActionIntent(kind, new byte[0]).capability();
            check(CapabilityCatalog.enforced(cap), kind + " → " + cap + " 必须在 enforced 里");
        }

        ActionIntent.Effect effect = ActionIntent.effectGive("minecraft:speed", 600, 2).asEffect();
        eq(effect.effectId(), "minecraft:speed", "effect.give 往返：效果 id");
        eq(effect.durationTicks(), 600, "effect.give 往返：时长（tick）");
        eq(effect.amplifier(), 2, "effect.give 往返：等级");

        boolean threw = false;
        try {
            ActionIntent.itemGive("minecraft:diamond", 0, "");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "数量 0 在产出点就拒");

        threw = false;
        try {
            ActionIntent.effectGive("minecraft:speed", 72_001, 0);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "效果超过 1 小时在产出点就拒");

        threw = false;
        try {
            ActionIntent.itemGive("UPPER:Bad", 1, "");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "坏物品 id（大写命名空间）在产出点就拒");
    }

    /** 失败口径的文案键必须在（真跑落地要主线程 + 真玩家，归 PR③ 的真服用例）。 */
    static void failureKeys() {
        check(ServerIntentApplier.NO_SUCH_TABLE.startsWith("mcphone.script."), "表不存在有本地化键");
        check(ServerIntentApplier.ATTR_UNAVAILABLE.startsWith("mcphone.script."), "属性认不得有本地化键");
        check(ServerIntentApplier.EFFECT_UNAVAILABLE.startsWith("mcphone.script."), "效果认不得有本地化键");
        check(ServerIntentApplier.NOT_GIFTABLE.startsWith("mcphone.script."), "不在礼包白名单有本地化键");
        eq(ScriptErrorCode.INVENTORY_FULL.defaultMessageKey(), "mcphone.script.code.inventory_full",
                "背包满用追加的第 16 个码");
    }

    public static void main(String[] args) {
        capacity();
        intentCodec();
        failureKeys();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
