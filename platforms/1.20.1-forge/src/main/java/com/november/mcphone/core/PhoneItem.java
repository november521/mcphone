package com.november.mcphone.core;

import com.november.mcphone.MCphone;
import com.november.mcphone.compat.CuriosCompat;
import com.november.mcphone.core.client.PhoneScreenOpener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * MCphone 核心物品 —— 右键打开设备界面。手机与平板都是它，只差一个 {@link DeviceKind}。
 */
public class PhoneItem extends Item {

    private final DeviceKind kind;

    public PhoneItem(Properties properties, DeviceKind kind) {
        super(properties);
        this.kind = kind;
    }

    /** 手机还是平板。界面按它决定屏幕多大，见 {@code DeviceMetrics} */
    public DeviceKind kind() {
        return kind;
    }

    /**
     * 这一堆物品是不是本模组的设备（手机或平板）。判定只写一遍，各处共用。
     *
     * 叫 isDevice 而不是 isPhone：平板也要返回 true —— 揣着平板一样能收发消息、
     * 一样能开末影箱。名字要是还叫"是不是手机"，每个调用点都得再想一遍"平板算不算"，
     * 而答案永远是算。
     */
    public static boolean isDevice(ItemStack stack) {
        return stack.getItem() instanceof PhoneItem;
    }

    /**
     * 这一堆是哪种设备，不是设备则为 {@code null}。
     *
     * 返回 null 而不是退回 {@link DeviceKind#PHONE}：位置失效（物品被丢了、格子空了）
     * 与"手里拿的确实是手机"是两回事，调用方该自己决定拿不到时怎么办。界面那一侧的
     * 决定写在 {@code DeviceMetrics.of}：没有设备就按手机那套尺寸画，绝不会没有尺寸可用。
     */
    public static @Nullable DeviceKind kindOf(ItemStack stack) {
        return stack.getItem() instanceof PhoneItem item ? item.kind() : null;
    }

    /**
     * 这个玩家身上带着手机吗 —— 拿在手上、放在背包、挂在饰品槽都算。
     *
     * 为什么不再要求"拿在手上"
     *
     * 原先服务端各处校验的是"手上有手机"，因为界面只能靠右键手机打开，
     * 手上必然拿着。有了饰品槽之后这个前提不成立了：手机挂在腰上，
     * 手里空空，照样该能用。
     *
     * 放宽到"身上带着"并不削弱这道校验的意义——它防的是伪造客户端凭空
     * 发消息、凭空开末影箱，而那种客户端同样变不出一部手机来。
     *
     * 1.21.1 那边一句 inventory.contains(Predicate) 就够了。1.20.1 的 Inventory
     * 【没有】收谓词的那个重载（只有 contains(ItemStack) 和 contains(TagKey)），
     * 所以这里自己遍历 getContainerSize()——它同样覆盖主背包、盔甲栏与副手，
     * 手上那只自然也在内，与那边扫到的范围一致。
     *
     * 没装 Curios 时 {@link CuriosCompat} 直接返回 false，不碰它的类。
     */
    public static boolean isCarriedBy(Player player) {
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (isDevice(inventory.getItem(slot))) return true;
        }
        return CuriosCompat.isEquipped(player, PhoneItem::isDevice);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);

        // 只在客户端打开 GUI
        //
        // 开界面的代码在 PhoneScreenOpener 里，不在本类——这不是为了整洁，
        // 是本类【必须】不出现任何客户端类型：注册物品时服务端要加载并校验
        // 它，校验器碰上 setScreen(Screen) 这类签名就会去加载 Screen，专用
        // 服务器上当场抛异常。光是写着就会崩，不执行也一样，1.0.44 就是这么
        // 崩的。详见 PhoneScreenOpener 的类注释，别把那两个方法搬回来。
        if (level.isClientSide()) {
            PhoneScreenOpener.open(new PhoneLocation.InHand(hand));
        }

        return InteractionResultHolder.success(stack);
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
        String deviceName = PhoneItemData.getDeviceName(stack);
        if (deviceName != null && !deviceName.isBlank()) {
            return Component.literal(deviceName);
        }
        return super.getName(stack);
    }

    /**
     * 1.21.1 那边第二个参数是 Item.TooltipContext（1.20.5 引入，带注册表访问）。
     * 1.20.1 上还是可空的 Level，签名对不上就【不会覆盖到父类方法】——
     * 而那不报错，只是提示行永远不出现。加 @Override 让编译器替我们盯着。
     */
    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(kind.openTooltipKey()));
        // 版本号取运行时真值填进 %s，语言文件里不写死，免得升版本时漏改某一份
        tooltip.add(Component.translatable("mcphone.item.tooltip.version", MCphone.getVersion()));
    }
}
