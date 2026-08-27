package com.november.mcphone.feature.store.net;

import com.november.mcphone.MCphone;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 网络包：客户端 → 服务端，"我要买这个 App"。
 *
 * 只带 App id，不带价格。价格由服务端自己查 AppPriceRegistry——客户端报
 * 过来的价格一个字都不能信，否则改个客户端就能把末影箱 App 标成 0 元。
 *
 * id 本身也不可信，服务端会核对它确实被报过价（见 StoreNetworking）。
 *
 * @param appId 要买哪个 App
 */
public record PurchaseAppPacket(ResourceLocation appId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<PurchaseAppPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "purchase_app"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PurchaseAppPacket> STREAM_CODEC =
            StreamCodec.composite(
                    ResourceLocation.STREAM_CODEC, PurchaseAppPacket::appId,
                    PurchaseAppPacket::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
