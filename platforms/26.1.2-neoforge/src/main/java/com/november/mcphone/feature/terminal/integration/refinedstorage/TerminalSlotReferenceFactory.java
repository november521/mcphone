package com.november.mcphone.feature.terminal.integration.refinedstorage;
import com.refinedmods.refinedstorage.common.api.RefinedStorageApi;
import net.minecraft.resources.Identifier;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
/** RS 3 注册位置 codec，替代旧版 SlotReferenceFactory 对象。 */
public final class TerminalSlotReferenceFactory {
    private TerminalSlotReferenceFactory() {}
    public static final StreamCodec<RegistryFriendlyByteBuf,TerminalSlotReference> CODEC=
            ByteBufCodecs.VAR_INT.map(TerminalSlotReference::new,TerminalSlotReference::inventorySlot).cast();
    static void register() { RefinedStorageApi.INSTANCE.getPlayerSlotReferenceFactories().register(Identifier.fromNamespaceAndPath("mcphone","terminal_slot"),CODEC); }
}
