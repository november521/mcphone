package com.november.mcphone;

import com.mojang.serialization.Codec;
import com.november.mcphone.notes.NoteList;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * MCphone 的数据组件注册。
 *
 * ================================================================
 * 为什么设备名要存在物品堆上
 * ================================================================
 *
 * 壁纸存在玩家身上（WallpaperData 附件），因为壁纸是"这个玩家的偏好"。
 * 设备名不同，它是"这一只手机的名字"：玩家可以有好几只手机，
 * 名字得跟着物品走，丢在地上、放进箱子、交易给别人都还在。
 * 所以用数据组件挂在 ItemStack 上，而不是玩家附件。
 *
 * 1.21 起 NBT 标签已被数据组件取代，自定义组件必须注册到
 * Registries.DATA_COMPONENT_TYPE。
 *
 * 两个编解码器缺一不可：
 *   - persistent    存档用，决定写进存档与物品 NBT 的样子
 *   - networkSynchronized  同步用，服务端改完要发给客户端才能显示出来
 * 只给 persistent 的话，多人游戏里改了名客户端看不见。
 */
public final class ModDataComponents {

    private ModDataComponents() {}

    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, MCphone.MODID);

    /**
     * 设备名称。未命名的手机不带这个组件，而不是带一个空串——
     * 这样"没起过名"与"起了个空名"不会混淆，
     * 物品比较（如合并堆叠、配方匹配）也不会被空组件干扰。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> DEVICE_NAME =
            COMPONENTS.register("device_name",
                    () -> DataComponentType.<String>builder()
                            .persistent(Codec.STRING)
                            .networkSynchronized(ByteBufCodecs.STRING_UTF8)
                            .build());

    /**
     * 本机笔记 —— 跟着手机走的那一半记事本。
     *
     * 存在物品上而不是玩家身上，是为了让它跟着手机转手：手机送人、丢在
     * 地上被捡走，这些笔记也跟着过去，像贴在机身背面的便签。另一半
     * （私人笔记）跟着玩家走，见 {@link ModAttachments#NOTES}。
     *
     * 有了 networkSynchronized，客户端不必发任何网络包就能读到本机笔记
     * ——物品组件本来就会随背包同步下来。这也是本机笔记的条数与长度收得
     * 比私人笔记紧得多的原因：它一直挂在物品上跟着背包走，见 NoteScope。
     *
     * 没有笔记的手机不带这个组件，而不是带一个空列表：这样"从没写过"
     * 与"写过又删光了"不会在物品比较时产生差别，堆叠与配方匹配也不受
     * 干扰。设备名那边是同一个考虑。
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<NoteList>> NOTES =
            COMPONENTS.register("notes",
                    () -> DataComponentType.<NoteList>builder()
                            .persistent(NoteList.CODEC)
                            .networkSynchronized(NoteList.STREAM_CODEC)
                            .build());
}
