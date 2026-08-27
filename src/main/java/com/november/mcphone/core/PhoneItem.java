package com.november.mcphone.core;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 手机物品。
 *
 * 现在它什么都不做 —— 右键开机那条路要等界面层移植过来。这【不是】占位实现里
 * 的疏忽，是刻意留白：与其现在写一个半截的开机逻辑，不如让它老实地什么都不做，
 * 移植时一眼看得出这里还没接。
 *
 * 移植这个类时要当心的两件事，都在 docs/PORTING.md 里有详细条目
 *
 * 1. 数据存储。NeoForge 那一支的手机把设备名、装了哪些 App、唱片仓里那张碟
 *    存在【数据组件】里（DataComponents，1.20.5 才有）。1.20.1 没有这套东西，
 *    只能退回 NBT（ItemStack.getOrCreateTag）。这不是换个 API 的事——组件是
 *    带类型与 codec 的，NBT 是自由格式的，读写两侧都要重写。
 *
 * 2. 玩家数据。好友、聊天记录、购买记录那些挂在玩家身上的东西，那边用的是
 *    NeoForge 的 Data Attachment；这边对应的是 Forge 的 Capability，连
 *    序列化与跨维度保留的写法都不一样。
 */
public class PhoneItem extends Item {

    public PhoneItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // 开机在这里接。别在这儿直接 new 一个 Screen —— 那会把客户端类拖进
        // 两端共用的代码里，专用服务器会启动即崩。NeoForge 那一支是靠一个
        // 单独的客户端入口类隔开的，移植时照搬那个形状
        return InteractionResultHolder.pass(stack);
    }
}
