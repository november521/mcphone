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
import com.november.mcphone.feature.chat.ChatData;
import com.november.mcphone.feature.chat.FriendData;
import com.november.mcphone.feature.notes.NoteList;

/**
 * 一名玩家买过哪些 App。
 *
 * ============================================================
 * 为什么存在服务端，而不是跟着 installed.json 走
 * ============================================================
 *
 * 因为购买要扣真物品。安装状态（主屏上摆哪几个图标）是客户端偏好，玩家
 * 自己改那个 json 不伤害任何人；但"买过了"一旦能被客户端改写，价格就形同
 * 虚设——那文件就在玩家自己的电脑上。
 *
 * 所以它是玩家附件，随存档走。由此产生一个必须说清的语义：**购买是按存档
 * 记的**。在 A 服买过，去 B 服要重新买。扣真物品的前提下这是唯一说得通的
 * 模型——你不能拿 A 服的末影箱换 B 服的 App。
 *
 * ============================================================
 * 为什么整个是不可变的
 * ============================================================
 *
 * 与 NoteList 同一个理由：附件持有的那一份不该被界面或网络层就地改掉，
 * 否则谁改了它、什么时候改的全无从追查。每次购买都产出一份新的。
 *
 * 顺带躲开老坑：Codec 解出来的集合本身不可变，按可变集合去用的话，读过档
 * 的世界里第一次增删就抛 UnsupportedOperationException。FriendData 与
 * ChatData 都在这上面栽过。
 */
public record PurchasedApps(Set<ResourceLocation> ids) {

    public static final PurchasedApps EMPTY = new PurchasedApps(Set.of());

    /**
     * 一名玩家最多能买这么多。
     *
     * 不是为了限制玩家，是给网络包一个上限：编解码器层面封死之后，伪造
     * 客户端塞不进一份无限长的列表。实际能买的数量本来就被价格表限死
     * （只有被报过价的 App 才能购买），这个数只是兜底。
     */
    public static final int MAX_COUNT = 256;

    /** 构造时就定死不可变，外面传进来什么集合都不影响 */
    public PurchasedApps(Set<ResourceLocation> ids) {
        this.ids = Set.copyOf(ids);
    }

    public static final Codec<PurchasedApps> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    ResourceLocation.CODEC.listOf().fieldOf("purchased")
                            .forGetter(p -> List.copyOf(p.ids()))
            ).apply(instance, list -> new PurchasedApps(Set.copyOf(list)))
    );

    /** 网络传输用。条数上限在编解码器层面封死 */
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

    /** 加一条，产出新的一份。已经有了就原样返回 */
    public PurchasedApps with(ResourceLocation id) {
        if (id == null || ids.contains(id)) return this;
        Set<ResourceLocation> next = new HashSet<>(ids);
        next.add(id);
        return new PurchasedApps(next);
    }
}
