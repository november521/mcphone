package com.november.mcphone.feature.store;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一名玩家买过哪些 App。玩家附件、按存档记：换服要重新买。整体不可变：
 * 每次购买产出新实例；Codec 解出的集合本身也不可变，别当可变集合用。
 */
public record PurchasedApps(Set<ResourceLocation> ids) {

    public static final PurchasedApps EMPTY = new PurchasedApps(Set.of());

    /** 只为给网络包一个上限，伪造客户端塞不进无限长的列表 */
    public static final int MAX_COUNT = 256;

    public PurchasedApps(Set<ResourceLocation> ids) {
        this.ids = Set.copyOf(ids);
    }

    public static final Codec<PurchasedApps> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    ResourceLocation.CODEC.listOf().fieldOf("purchased")
                            .forGetter(p -> List.copyOf(p.ids()))
            ).apply(instance, list -> new PurchasedApps(Set.copyOf(list)))
    );

    /**
     * 条数上限在编解码器层面封死。
     *
     * 1.21.1 那边写成 ByteBufCodecs.list(MAX_COUNT)，上限由那个组合子带着。
     * 1.20.1 上 FriendlyByteBuf.readList 【没有上限参数】，所以这道闸必须
     * 自己补——照搬 readList 会让伪造客户端塞进任意长的列表。
     */
    public static void encode(PurchasedApps value, FriendlyByteBuf buf) {
        buf.writeCollection(value.ids(), FriendlyByteBuf::writeResourceLocation);
    }

    public static PurchasedApps decode(FriendlyByteBuf buf) {
        List<ResourceLocation> list = buf.readCollection(n -> {
            if (n > MAX_COUNT) {
                throw new DecoderException("已购 App 列表超过上限 " + MAX_COUNT + ": " + n);
            }
            return new java.util.ArrayList<>(n);
        }, FriendlyByteBuf::readResourceLocation);
        return new PurchasedApps(Set.copyOf(list));
    }

    public boolean has(ResourceLocation id) {
        return id != null && ids.contains(id);
    }

    public boolean isFull() {
        return ids.size() >= MAX_COUNT;
    }

    /** 加一条产出新的一份；已经有了就原样返回 this */
    public PurchasedApps with(ResourceLocation id) {
        if (id == null || ids.contains(id)) return this;
        Set<ResourceLocation> next = new HashSet<>(ids);
        next.add(id);
        return new PurchasedApps(next);
    }
}
