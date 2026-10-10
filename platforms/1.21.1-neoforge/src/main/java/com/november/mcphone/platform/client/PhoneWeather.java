package com.november.mcphone.platform.client;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.core.BlockPos;
/** 原版降水查询新版需要当前维度的海平面参数。 */
public final class PhoneWeather {
    private PhoneWeather() {}
    public static Biome.Precipitation precipitation(Level level,BlockPos pos) {
        return level.getBiome(pos).value().getPrecipitationAt(pos);
    }
}
