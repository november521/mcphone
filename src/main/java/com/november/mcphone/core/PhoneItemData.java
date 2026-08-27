package com.november.mcphone.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 跟着物品走的手机数据 —— 对应 NeoForge 那一支的 ModDataComponents。
 *
 * 为什么设备名要存在物品堆上
 *
 * 壁纸存在玩家身上（见 {@link PhonePlayerData}），因为壁纸是"这个玩家的偏好"。
 * 设备名不同，它是"这一只手机的名字"：玩家可以有好几只手机，名字得跟着物品走，
 * 丢在地上、放进箱子、交易给别人都还在。
 *
 * 与那一支的差别：组件 → 裸 NBT
 *
 * 1.20.5 起原版用数据组件取代了物品 NBT，那一支把设备名注册成一个
 * DataComponentType&lt;String&gt;，带 persistent 与 networkSynchronized 两个编解码器。
 * 1.20.1 上这套不存在，退回 stack.getOrCreateTag()。
 *
 * 【读的那一侧因此必须自己校验】，这是两支之间最实质的差别：
 * 组件读出来要么是对的类型要么是 null，由类型系统兜着；NBT 读出来可能是任何
 * 东西 —— 玩家用 /give 塞进来的、老存档留下的、别的 mod 写的。所以下面
 * getDeviceName 要显式判 TAG_STRING，不能直接 getString。
 *
 * 同步不必操心：物品 NBT 本来就跟着 ItemStack 走网络，不像组件那样要单独
 * 声明 networkSynchronized。
 */
public final class PhoneItemData {

    private PhoneItemData() {}

    /**
     * 设备名在 NBT 里的键。
     *
     * 未命名的手机【不带这个键】，而不是带一个空串——这样"没起过名"与
     * "起了个空名"不会混淆，物品比较（合并堆叠、配方匹配）也不会被空标签干扰。
     * 与那一支"未命名就不带组件"的语义一致。
     */
    private static final String KEY_DEVICE_NAME = "DeviceName";

    /** NBT 的字符串类型 id，判类型用 */
    private static final byte TAG_STRING = 8;

    /** 没起过名返回 null，与那边组件读不到时的返回值一致 */
    public static @Nullable String getDeviceName(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        // contains(key, TAG_STRING) 而不是 contains(key)：别人往这个键写了别的
        // 类型时，getString 会静默返回空串，那就分不清"没起过名"和"被写坏了"
        if (tag == null || !tag.contains(KEY_DEVICE_NAME, TAG_STRING)) return null;
        return tag.getString(KEY_DEVICE_NAME);
    }

    public static void setDeviceName(ItemStack stack, String name) {
        stack.getOrCreateTag().putString(KEY_DEVICE_NAME, name);
    }

    /** 清除设备名，恢复默认物品名。移除键而不是写空串，理由见 KEY_DEVICE_NAME */
    public static void clearDeviceName(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) return;
        tag.remove(KEY_DEVICE_NAME);
        // 标签空了就整个摘掉：留着一个空 CompoundTag 会让这只手机与
        // 没起过名的手机 ItemStack.matches 判不相等，堆叠和配方都会受影响
        if (tag.isEmpty()) stack.setTag(null);
    }
}
