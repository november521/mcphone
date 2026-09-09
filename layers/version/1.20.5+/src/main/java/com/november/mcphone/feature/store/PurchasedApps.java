package com.november.mcphone.feature.store;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.november.mcphone.core.net.Wire;
import net.minecraft.network.FriendlyByteBuf;
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
    public static final StreamCodec<FriendlyByteBuf, PurchasedApps> STREAM_CODEC =
            StreamCodec.of((buf, msg) -> encode(msg, buf), PurchasedApps::decode);

    /**
     * 上线的顺序<b>不保证</b>：里头是个 Set，{@code Set.copyOf} 的迭代顺序每次 JVM
     * 启动都可能不同。这不影响正确性（它本来就是集合），但意味着同一份数据两次编码
     * 出来的字节可以不一样 —— 别拿它做缓存键或者哈希比对。
     */
    public static void encode(PurchasedApps value, FriendlyByteBuf buf) {
        Wire.writeList(buf, value.ids(), MAX_COUNT, (v, b) -> b.writeResourceLocation(v));
    }

    public static PurchasedApps decode(FriendlyByteBuf buf) {
        List<ResourceLocation> list =
                Wire.readList(buf, MAX_COUNT, FriendlyByteBuf::readResourceLocation);
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
