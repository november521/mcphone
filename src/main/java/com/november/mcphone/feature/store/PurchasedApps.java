package com.november.mcphone.feature.store;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
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

    /** 条数上限在编解码器层面封死 */
    public static final StreamCodec<ByteBuf, PurchasedApps> STREAM_CODEC =
            ResourceLocation.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_COUNT))
                    .map(list -> new PurchasedApps(Set.copyOf(list)),
                            p -> List.copyOf(p.ids()));

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
