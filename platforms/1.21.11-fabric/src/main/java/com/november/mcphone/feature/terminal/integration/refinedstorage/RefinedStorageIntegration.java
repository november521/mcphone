package com.november.mcphone.feature.terminal.integration.refinedstorage;

import com.november.mcphone.feature.terminal.integration.TerminalIntegration;
import com.november.mcphone.feature.terminal.integration.TerminalSource;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReferenceHandlerItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Refined Storage 2 的接入 —— 无线网格，以及它那一系的其它随身物件。
 *
 * 一个 instanceof 覆盖多少东西
 *
 * RS 把"能被远程唤起的随身物件"抽象成了 {@link SlotReferenceHandlerItem}：
 *
 *   无线网格、无线自动合成监视器  经由 {@code AbstractNetworkEnergyItem} 实现它
 *   便携网格                      {@code PortableGridBlockItem} 直接实现它
 *
 * 所以认领同样只要一句 instanceof，而且 RS 以后加的新物件、别人给 RS 写的附属物件，只要
 * 挂在这个接口上就自动认得。这和 AE2 那边靠 {@code WirelessTerminalItem} 一网打尽是同一种
 * 好运，区别是 RS 这个是<b>接口</b>，比继承更靠得住。
 *
 * 便携网格也能装进手机，这是顺带的
 *
 * 它是个方块物品，自己带一格存储。RS 让它实现了同一个接口，那它就该和无线网格一样能从
 * 手机上点开——这是 RS 的决定，不是我们的。我们不额外判断"这东西像不像终端"。
 *
 * 电量、绑没绑网络
 *
 * 一条都不在这里。{@code use} 里 RS 自己查，查不过它自己给玩家发消息。
 */
public final class RefinedStorageIntegration implements TerminalIntegration {

    public static final String MODID = "refinedstorage";

    /** 显示名。与 modid 一样是编译期常量，理由见 {@code Ae2Integration.NAME} */
    public static final String NAME = "Refined Storage";

    @Override
    public String modId() {
        return MODID;
    }

    @Override
    public String displayName() {
        return NAME;
    }

    @Override
    public boolean claims(ItemStack stack) {
        return stack.getItem() instanceof SlotReferenceHandlerItem;
    }

    @Override
    public boolean open(ServerPlayer player, ItemStack stack, TerminalSource source) {
        if (!(stack.getItem() instanceof SlotReferenceHandlerItem handler)) return false;
        handler.use(player, stack, referenceFor(source));
        return true;
    }

    /**
     * 把"在哪儿"翻译成 RS 认的位置。
     *
     * 两种情况都用我们自己那一个实现，理由（RS 自带的那个构造函数是包内可见的）见
     * {@link TerminalSlotReference} 的类注释。
     */
    private static SlotReference referenceFor(TerminalSource source) {
        return source.map(
                ignored -> TerminalSlotReference.phoneSlot(),
                inventorySlot -> new TerminalSlotReference(inventorySlot.index()));
    }

    @Override
    public void setup() {
        TerminalSlotReferenceFactory.register();
    }
}
