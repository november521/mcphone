package com.november.mcphone.platform;

import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.file.*;

/** 原版 saveAll 会吞 IO 异常；确认奖励出箱前必须读回玩家存档核对完整背包。 */
public final class PlayerSaves {
    private PlayerSaves() {}
    public static boolean inventoryConfirmed(ServerPlayer player) {
        Path file=player.level().getServer().getWorldPath(LevelResource.ROOT).resolve("playerdata").resolve(player.getUUID()+".dat");
        try {
            if(!Files.isRegularFile(file) || Files.size(file)>16*1024*1024) return false;
            try(var savedFile=java.nio.channels.FileChannel.open(file,StandardOpenOption.WRITE)) { savedFile.force(true); }
            CompoundTag expected=player.saveWithoutId(new CompoundTag());
            try(var input=Files.newInputStream(file)) {
                CompoundTag saved=NbtIo.readCompressed(input, NbtAccounter.create(16L*1024*1024));
                return expected.getList("Inventory",Tag.TAG_COMPOUND).equals(saved.getList("Inventory",Tag.TAG_COMPOUND));
            }
        } catch(java.io.IOException | RuntimeException failure) { return false; }
    }
}
