package com.november.mcphone.core;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 存档级数据的基类，把 {@link SavedData} 跨版本变了形状的两处关在这里。
 *
 * 两处都在这一支上多一个 {@code HolderLookup.Provider}：{@code save} 是覆写，
 * 签名必须与父类一致，静态门面插不进去；{@code computeIfAbsent} 收的是
 * {@code SavedData.Factory}，1.20.1 收的是 (loader, factory, name) 三个参数。
 *
 * 子类只写 {@link #write} 与一个 {@code load(CompoundTag)}，两支同形，可以进共用层。
 * 别把 {@code HolderLookup.Provider} 漏进子类的签名 —— 那一支没有这个类型。
 */
public abstract class PhoneSavedData extends SavedData {

    /** 把自己写进 tag。返回值就是传进来的那个 tag。 */
    protected abstract CompoundTag write(CompoundTag tag);

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        return write(tag);
    }

    /** 取或建。{@code load} 收的是裸 tag，注册表那个参数在这里被吃掉。 */
    protected static <T extends PhoneSavedData> T getOrCreate(
            MinecraftServer server, String name, Supplier<T> create, Function<CompoundTag, T> load) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(create, (tag, registries) -> load.apply(tag), null), name);
    }
}
