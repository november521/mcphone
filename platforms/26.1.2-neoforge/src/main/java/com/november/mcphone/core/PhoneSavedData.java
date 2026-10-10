package com.november.mcphone.core;

import com.mojang.serialization.Codec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/** 26.1.2 的 Codec 存档接缝；业务仍只实现 write 与 load，不依赖新版存储入口。 */
public abstract class PhoneSavedData extends SavedData {
    // SavedDataStorage 以完整的 SavedDataType 为键；每次创建新的 Codec 会形成不同键，
    // 导致连续读取同一文件得到不同实例、丢失尚未保存的数据。因此类型按文件名只创建一次。
    private static final ConcurrentHashMap<String, SavedDataType<?>> TYPES = new ConcurrentHashMap<>();

    protected abstract CompoundTag write(CompoundTag tag);

    protected static <T extends PhoneSavedData> T getOrCreate(
            MinecraftServer server, String name, Supplier<T> create, Function<CompoundTag, T> load) {
        Path file;
        try {
            file = PhoneSavedDataFiles.prepare(server.getWorldPath(LevelResource.ROOT).resolve("data"), name);
        } catch (IOException failure) {
            throw new IllegalStateException("MCphone 存档迁移失败，保留原件并停止读取：" + name, failure);
        }
        var storage = server.overworld().getDataStorage();
        var dataType = type(name, create, load);
        T existing = storage.get(dataType);
        if (existing != null) return existing;
        // 原版在读取失败时返回 null，随后 computeIfAbsent 会新建空记录。
        // 文件明明存在时拒绝这条回退，避免丢失聊天、删除标记、服务器身份或资金。
        if (Files.exists(file)) throw new IllegalStateException("MCphone 存档无法解码，拒绝重建空记录：" + file);
        return storage.computeIfAbsent(dataType);
    }

    /** 同一名称只能对应一种业务数据；本方法包内可见，供真实 Codec 的断言测试验证。 */
    @SuppressWarnings("unchecked")
    static <T extends PhoneSavedData> SavedDataType<T> type(
            String name, Supplier<T> create, Function<CompoundTag, T> load) {
        return (SavedDataType<T>) TYPES.computeIfAbsent(name, key -> {
            Codec<T> codec = CompoundTag.CODEC.xmap(load, data -> data.write(new CompoundTag()));
            // 新原版按命名空间保存到 data/mcphone/<name>.dat，保留业务已有的文件名与 NBT 格式。
            return new SavedDataType<>(Identifier.fromNamespaceAndPath("mcphone", key), create, codec);
        });
    }
}
