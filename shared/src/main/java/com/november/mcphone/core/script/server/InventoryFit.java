package com.november.mcphone.core.script.server;

import java.util.List;

/**
 * "这批物品放不放得进背包"的纯算术（S18 的 {@code reject} 策略，§20.9）。
 *
 * <p><b>只数空格子，不做堆叠合并</b>：合并要判"同物品且组件一致"，而组件比较在 1.20.1 与
 * 1.21.1 上是两套 API。宁可保守（少算容量 → 偶尔多报一次"背包满"），也不在不一致时
 * 误判"放得下"——那会走到"加到一半失败"，把一次发放变成结果不明。
 */
public final class InventoryFit {

    private InventoryFit() {
    }

    /**
     * @param emptySlots 背包里空槽位数
     * @param counts     每件待放入物品的数量
     * @param maxStacks  同下标的原版堆叠上限
     * @return 只用空格子够不够
     */
    public static boolean fits(int emptySlots, List<Integer> counts, List<Integer> maxStacks) {
        if (counts.size() != maxStacks.size()) {
            throw new IllegalArgumentException("counts 与 maxStacks 长度不一致");
        }
        int need = 0;
        for (int i = 0; i < counts.size(); i++) {
            int count = counts.get(i);
            int max = maxStacks.get(i);
            if (count <= 0) throw new IllegalArgumentException("待放入数量必须为正：" + count);
            if (max <= 0) throw new IllegalArgumentException("堆叠上限必须为正：" + max);
            need += (count + max - 1) / max;
        }
        return need <= emptySlots;
    }
}
