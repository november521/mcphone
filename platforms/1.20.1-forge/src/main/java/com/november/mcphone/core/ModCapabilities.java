package com.november.mcphone.core;

import com.november.mcphone.MCphone;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.capabilities.RegisterCapabilitiesEvent;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 玩家数据的 Capability 注册 —— 对应 NeoForge 那一支的 ModAttachments。
 *
 * 两套模型的差别（docs/PORTING.md 第 3 条）
 *
 *   声明    AttachmentType.builder(...)  →  CapabilityToken + RegisterCapabilitiesEvent
 *   附加    自动                          →  自己监听 AttachCapabilitiesEvent
 *   序列化  builder 里给 codec            →  自己实现 INBTSerializable
 *   重生    .copyOnDeath()                →  自己监听 PlayerEvent.Clone 手动拷
 *
 * 最后一行是重点，而且比清单原先记的更绕，见下面 onPlayerClone 的注释。
 */
public final class ModCapabilities {

    private ModCapabilities() {}

    /** 附加时用的 id，也是存档里这一格的键 */
    private static final ResourceLocation PHONE_DATA_ID =
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "player_data");

    public static final Capability<PhonePlayerData> PHONE_DATA =
            CapabilityManager.get(new CapabilityToken<>() {});

    // ---- 挂在 mod 总线上 ----
    public static void register(RegisterCapabilitiesEvent event) {
        event.register(PhonePlayerData.class);
    }

    // ---- 挂在 Forge 总线上 ----

    /** 给每个玩家挂上那一格数据。只认 Player，其它实体不挂 */
    public static void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event) {
        if (event.getObject() instanceof Player) {
            event.addCapability(PHONE_DATA_ID, new Provider());
        }
    }

    /**
     * 玩家重生或从末地回来时，把数据从旧实体拷到新实体。
     *
     * 【这一处不能凭直觉写。】清单原先只记了"copyOnDeath() 对应手动拷"，
     * 容易读成"没写 copyOnDeath 的就不用管"——那是错的。NeoForge 的语义是：
     *
     *   可序列化的实体附着数据【默认不在死亡时保留】，
     *   但【从末地返回时一律保留】。
     *
     * 也就是说 Clone 这个事件两种情况都会触发，而"不保留"只针对死亡那一种。
     * 完全不监听 Clone，玩家打完末影龙走传送门回主世界就会发现壁纸没了——
     * 那边不会，两支就此分叉。
     *
     * 所以下面按 isWasDeath() 分流：
     *
     *   末地返回（!isWasDeath）  一律拷贝
     *   死亡重生（isWasDeath）   只拷那边标了 copyOnDeath 的字段
     *
     * 壁纸那边【没有】标 copyOnDeath，所以死亡时就该丢——这是原样复现，
     * 不是偷懒。另外四样（聊天已读、唱片仓、笔记、已购 App）那边都标了，
     * 搬过来时要在这里补上死亡分支。
     *
     * 还有一个 Forge 特有的坑：旧实体的 capability 在这时已经被 invalidate 了，
     * 不先 reviveCaps() 就读，拿到的是空的 LazyOptional，症状是"拷贝静默失败"。
     */
    public static void onPlayerClone(PlayerEvent.Clone event) {
        boolean wasDeath = event.isWasDeath();

        Player original = event.getOriginal();
        original.reviveCaps();
        try {
            original.getCapability(PHONE_DATA).ifPresent(from ->
                    event.getEntity().getCapability(PHONE_DATA).ifPresent(to -> {
                        if (wasDeath) {
                            // 死亡：只保留那边标了 copyOnDeath 的字段
                            to.copyDeathPersistentFrom(from);
                        } else {
                            // 末地返回：那边一律保留，不分标没标
                            to.copyFrom(from);
                        }
                    }));
        } finally {
            original.invalidateCaps();
        }
    }

    /**
     * 取玩家的手机数据。
     *
     * capability 理论上可能取不到（别的 mod 用事件把附加拦了，或者在附加之前
     * 就被调用）。那种情况下返回一个临时的空数据而不是抛异常：读到默认壁纸
     * 只是不好看，抛异常会把整条网络包处理链打断。
     */
    public static PhonePlayerData of(Player player) {
        return player.getCapability(PHONE_DATA).orElseGet(PhonePlayerData::new);
    }

    /** 把 PhonePlayerData 包成 Forge 要的 provider；顺带负责它的存档读写 */
    private static final class Provider implements ICapabilitySerializable<CompoundTag> {

        private final PhonePlayerData data = new PhonePlayerData();
        private final LazyOptional<PhonePlayerData> handle = LazyOptional.of(() -> data);

        @Override
        public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap,
                                                          @Nullable Direction side) {
            return cap == PHONE_DATA ? handle.cast() : LazyOptional.empty();
        }

        @Override
        public CompoundTag serializeNBT() {
            return data.serializeNBT();
        }

        @Override
        public void deserializeNBT(CompoundTag tag) {
            data.deserializeNBT(tag);
        }
    }
}
