package com.november.mcphone.feature.terminal.integration.refinedstorage;

import com.november.mcphone.MCphone;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReference;
import com.refinedmods.refinedstorage.common.api.support.slotreference.SlotReferenceFactory;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * 把 {@link TerminalSlotReference} 登记进 RS 的注册表，并说明它怎么写进网络包。
 *
 * 不注册会怎样
 *
 * 和 AE2 那边一模一样：菜单一打开，位置就要随包发给客户端，而写的那一步要先在注册表里查
 * 出这个工厂的 id。查不到就抛异常，玩家看到的是点一下掉线。
 *
 * 线上格式是 ResourceLocation，不是类名
 *
 * 这一点比 AE2 那边稳：AE2 写的是类的全名字符串，我们哪天挪个包就变了；RS 写的是注册时
 * 给的 id，包名怎么改都不影响存档与联机。所以这个 id <b>一旦发出去就不能再改</b>。
 *
 * 什么时候注册
 *
 * {@code RefinedStorageApi.INSTANCE} 是个代理对象，真正的实现由 RS 自己的初始化挂上去。
 * 太早调（比如在我们的模组构造里）拿到的是还没挂 delegate 的空壳。所以这一步放在
 * FMLCommonSetup 里——那时所有模组的构造都跑完了，不需要靠依赖顺序推理。
 */
public final class TerminalSlotReferenceFactory implements SlotReferenceFactory {

    public static final TerminalSlotReferenceFactory INSTANCE = new TerminalSlotReferenceFactory();

    /** 注册 id。发出去之后不能改，改了等于换了一种位置，老存档里开着的界面读不回来 */
    private static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "terminal_slot");

    /**
     * 一个 VarInt 就够：{@code -1} 是卡槽，其余是背包槽位号。
     *
     * {@code cast()} 那一下是类型转换不是逻辑：{@code ByteBufCodecs.VAR_INT} 声明在
     * {@code ByteBuf} 上，而 RS 要的是 {@code RegistryFriendlyByteBuf}（它是前者的子类型）。
     * 泛型不协变，所以要显式转一次。
     */
    private static final StreamCodec<RegistryFriendlyByteBuf, SlotReference> STREAM_CODEC =
            ByteBufCodecs.VAR_INT
                    .<SlotReference>map(TerminalSlotReference::new, TerminalSlotReferenceFactory::slotOf)
                    .cast();

    private TerminalSlotReferenceFactory() {}

    /**
     * 读到不是我们这一种时按"卡槽"写。
     *
     * 这种情况不该发生（RS 按工厂分派，拿到我们的工厂就一定是我们的位置），写成抛异常也
     * 说得通。选一个无害的默认值是因为这条路在网络编码里，异常的代价是掉线，而写错的代价
     * 只是界面开不出来。
     */
    private static Integer slotOf(SlotReference reference) {
        return reference instanceof TerminalSlotReference phoneSlot
                ? phoneSlot.inventorySlot()
                : TerminalSlotReference.PHONE_SLOT;
    }

    @Override
    public StreamCodec<RegistryFriendlyByteBuf, SlotReference> getStreamCodec() {
        return STREAM_CODEC;
    }

    /** 由 {@link RefinedStorageIntegration#setup()} 调，两端都调 */
    static void register() {
        RefinedStorageApi.INSTANCE.getSlotReferenceFactoryRegistry().register(ID, INSTANCE);
    }
}
