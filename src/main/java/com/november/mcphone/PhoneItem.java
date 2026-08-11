package com.november.mcphone;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * MCphone 核心物品 —— 右键打开手机主界面
 * 目前为空壳物品，后续将接入 GUI 系统展示 App 图标列表
 */
public class PhoneItem extends Item {

    public PhoneItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        if (!level.isClientSide()) {
            // TODO: 打开手机 GUI（下一阶段实现）
            player.sendSystemMessage(
                    Component.literal("§e[MCphone] §r手机已打开 —— 功能开发中...")
            );
        }

        return InteractionResultHolder.success(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("§7右键打开手机"));
        tooltip.add(Component.literal("§8MCphone v1.0.0"));
    }
}
