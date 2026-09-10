package com.november.mcphone.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 存档级数据的基类，把 {@link SavedData} 跨版本变了形状的两处关在这里。
 *
 * 1.20.5 起 {@code save} 多收一个 {@code HolderLookup.Provider}，而它是覆写，
 * 签名必须与父类一致，静态门面插不进去；{@code computeIfAbsent} 那边同理，
 * 那一支收的是 {@code SavedData.Factory}，这一支收 (loader, factory, name) 三个参数。
 *
 * 子类只写 {@link #write} 与一个 {@code load(CompoundTag)}，两支同形，可以进共用层。
 *
 * 这一支的 {@code computeIfAbsent} <b>参数顺序是 loader 在前</b>（那一支的 Factory 是 ctor 在前），
 * 写反了编译不过但很容易看花眼。
 */
public abstract class PhoneSavedData extends SavedData {

    /** 把自己写进 tag。返回值就是传进来的那个 tag。 */
    protected abstract CompoundTag write(CompoundTag tag);

    @Override
    public CompoundTag save(CompoundTag tag) {
        return write(tag);
    }

    /** 取或建。 */
    protected static <T extends PhoneSavedData> T getOrCreate(
            MinecraftServer server, String name, Supplier<T> create, Function<CompoundTag, T> load) {
        return server.overworld().getDataStorage().computeIfAbsent(load, create, name);
    }
}
