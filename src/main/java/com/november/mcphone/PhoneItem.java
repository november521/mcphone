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
            openPhoneScreen(hand);
        }

        return InteractionResultHolder.success(stack);
    }

    /**
     * 在客户端打开手机主屏幕。
     * 提取为独立方法，方便后续通过其他途径（如快捷键）打开手机。
     *
     * @param hand 手机在哪只手上。设备名要写回这只手上的物品堆，
     *             玩家两只手各拿一只手机时不能改错。
     */
    public static void openPhoneScreen(InteractionHand hand) {
        Minecraft.getInstance().setScreen(new PhoneScreen(hand));
    }

    /** 不指定手时按主手处理 */
    public static void openPhoneScreen() {
        openPhoneScreen(InteractionHand.MAIN_HAND);
    }

    /**
     * 起过名的手机在物品栏里显示设备名。
     *
     * 覆盖 getName 而不是去写原版的 custom_name 组件：
     * custom_name 是铁砧改名用的，占了它玩家就没法再用铁砧改名，
     * 而且那条路径显示出来是斜体。这里返回的名字与普通物品名一样正常显示。
     * 玩家若真用铁砧改了名，custom_name 优先级更高，会盖过设备名——
     * 这是原版既有行为，符合直觉。
     */
    @Override
    public Component getName(ItemStack stack) {
        String deviceName = stack.get(ModDataComponents.DEVICE_NAME.get());
        if (deviceName != null && !deviceName.isBlank()) {
            return Component.literal(deviceName);
        }
        return super.getName(stack);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("mcphone.item.tooltip.open"));
        // 版本号取运行时真值填进 %s，语言文件里不写死，免得升版本时漏改某一份
        tooltip.add(Component.translatable("mcphone.item.tooltip.version", MCphone.getVersion()));
    }
}
