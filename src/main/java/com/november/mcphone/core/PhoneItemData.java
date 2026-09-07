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

    /**
     * 屏幕正亮着在 NBT 里的键。
     *
     * 【这一位与那一支的差别最大，读之前先看这里】。那边它是一个只声明了
     * {@code networkSynchronized}、<b>没声明 persistent</b> 的组件——同步得出去、但不落盘，
     * 因为它说的是"此刻有人正开着它"，不是手机自身的属性。
     *
     * 1.20.1 的 NBT <b>同步与落盘是同一份</b>，做不到只同步不落盘。于是多出一件那边不用做的
     * 事：开着手机崩一次，这一位会跟着存进存档，那部手机就永远亮着。补擦放在
     * {@link PhoneScreenOnCleanup}（上线、下线各一道），理由与擦不到的那一种都写在那个类里。
     *
     * 灭着的手机<b>不带这个键</b>，而不是带一个 false —— 与设备名同一条规矩：空标签会让物品
     * 不再与原版的那只相等，影响堆叠与配方匹配。
     */
    private static final String KEY_SCREEN_ON = "ScreenOn";

    /** NBT 的字节类型 id（布尔在 NBT 里就是 byte），判类型用 */
    private static final byte TAG_BYTE = 1;

    /**
     * 这一部手机的屏幕正亮着吗 —— 物品模型据此在黑屏与白屏之间切。
     *
     * 写它的只有服务端收到 {@code PhoneScreenOnPacket} 那一处，读它的只有渲染那一处
     * （{@code PhoneItemProperties}）。
     */
    public static boolean isScreenOn(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        // 判类型而不是直接 getBoolean，理由与 getDeviceName 那句相同：这个键谁都写得进来
        return tag != null && tag.contains(KEY_SCREEN_ON, TAG_BYTE) && tag.getBoolean(KEY_SCREEN_ON);
    }

    /** 点亮这一部的屏幕 */
    public static void setScreenOn(ItemStack stack) {
        if (stack.isEmpty()) return;
        stack.getOrCreateTag().putBoolean(KEY_SCREEN_ON, true);
    }

    /**
     * 灭掉这一部的屏幕。移除键而不是写 false，理由见 {@link #KEY_SCREEN_ON}。
     *
     * 擦完之后如果整个标签空了就把标签本身也去掉：留一个 {@code {}} 在那儿，这只手机就与
     * 原版新给的那只不相等了。
     */
    public static void clearScreenOn(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(KEY_SCREEN_ON)) return;
        tag.remove(KEY_SCREEN_ON);
        if (tag.isEmpty()) stack.setTag(null);
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
