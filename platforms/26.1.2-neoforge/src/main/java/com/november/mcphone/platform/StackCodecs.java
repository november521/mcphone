package com.november.mcphone.platform;

import com.mojang.serialization.MapCodec;
import net.minecraft.world.item.ItemStack;

/**
 * 物品堆的编解码里跨版本换过写法的那几处 —— 全仓唯一碰它们的地方。
 *
 * <h2>为什么值得一层</h2>
 *
 * 「一个可以为空的物品堆字段」这件事，两个版本给的工具不一样：
 * 1.20.5 起有 {@code ItemStack.OPTIONAL_CODEC}，之前只有不接受空栈的
 * {@code ItemStack.CODEC}。而空栈（仓里没唱片）恰恰是常态。
 *
 * <b>这不只是签名不同，两边落盘的形状也不同</b>：这一支总会写出那个字段，另一支在空的时候整个不写。
 * 所以门面必须各自保住自己那一份格式 —— 这一层收的是「怎么表达」，
 * 不是「统一成一种」。<b>存档格式一个字节都不能变。</b>
 *
 * <h2>这个包放什么</h2>
 *
 * {@code platform} 下装的是「各目标做同一件事、但写法不同」的东西。
 * <b>不要往这里放业务逻辑</b> —— 「唱片仓里放着什么」是业务，属于 {@code DiscState}；
 * 「这个版本怎么写一个可空的物品堆字段」才是这里的事。
 */
public final class StackCodecs {

    private StackCodecs() {}

    /**
     * 一个<b>可以为空</b>的物品堆字段。空栈按这一支的惯例表达，读回来仍是空栈。
     *
     * @param name 字段名，两支必须一致 —— 它是存档里的键
     */
    public static MapCodec<ItemStack> optionalStackField(String name) {
        // 1.20.5 起原版自带：空栈也编得出，字段照写
        return ItemStack.OPTIONAL_CODEC.fieldOf(name);
    }
}
