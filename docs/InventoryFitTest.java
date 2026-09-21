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
        eq(new ActionIntent("wat", new byte[0]).capability(), null, "没登记的种类没有能力");

        boolean threw = false;
        try {
            ActionIntent.itemGive("minecraft:diamond", 0, "");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "数量 0 在产出点就拒");

        threw = false;
        try {
            ActionIntent.itemGive("UPPER:Bad", 1, "");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        check(threw, "坏物品 id（大写命名空间）在产出点就拒");
    }

    static void resolve() {
        // 物品解析要碰 BuiltInRegistries（docs 里没有 Bootstrap），真物品的解析归 PR③ 的真服用例；
        // 这里只钉不碰注册表的两条：门面未接通的能力与不认识的种类。
        ServerIntentApplier.Resolved loot = ServerIntentApplier.resolve(
                List.of(ActionIntent.lootRoll("myserver:daily")));
        eq(loot.error().code(), ScriptErrorCode.UNAVAILABLE,
                "loot.roll 的门面未接通 → UNAVAILABLE（不谎报）");
        eq(loot.error().messageKey(), "mcphone.script.intent_unavailable", "带专门的文案键");
        eq(loot.stacks().size(), 0, "不做不了的事就不产出物品");

        ServerIntentApplier.Resolved unknown = ServerIntentApplier.resolve(
                List.of(new ActionIntent("wat", new byte[0])));
        eq(unknown.error().code(), ScriptErrorCode.INVALID_ARGUMENT, "不认识的种类 → INVALID_ARGUMENT");
    }

    public static void main(String[] args) {
        capacity();
        intentCodec();
        resolve();

        System.out.println("断言 " + checks + " 条");
        if (!failures.isEmpty()) {
            System.out.println("失败 " + failures.size() + " 条：");
            for (String f : failures) System.out.println("  - " + f);
            System.exit(1);
        }
        System.out.println("全部通过");
    }
}
