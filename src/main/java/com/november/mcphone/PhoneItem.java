package com.november.mcphone;

import com.november.mcphone.gui.PhoneScreen;
import net.minecraft.client.Minecraft;
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
 */
public class PhoneItem extends Item {

    public PhoneItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // 只在客户端打开 GUI
        if (level.isClientSide()) {
            openPhoneScreen();
        }

        return InteractionResultHolder.success(stack);
    }

    /**
     * 在客户端打开手机主屏幕。
     * 提取为独立方法，方便后续通过其他途径（如快捷键）打开手机。
     */
    public static void openPhoneScreen() {
        Minecraft.getInstance().setScreen(new PhoneScreen());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("§7右键打开手机"));
        tooltip.add(Component.literal("§8MCphone v1.0.0"));
    }
}
