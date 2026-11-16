package com.november.mcphone.mixin;

import com.november.mcphone.core.PhonePlayerData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Player.class)
abstract class PlayerDataMixin implements PhonePlayerData.Access {
    @Unique private final PhonePlayerData mcphone$data = new PhonePlayerData();
    @Unique private static final String mcphone$nbtKey = "mcphone:player_data";

    @Override
    public PhonePlayerData mcphone$playerData() {
        return mcphone$data;
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void mcphone$save(CompoundTag tag, CallbackInfo ci) {
        tag.put(mcphone$nbtKey, mcphone$data.serializeNBT());
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void mcphone$load(CompoundTag tag, CallbackInfo ci) {
        if (tag.contains(mcphone$nbtKey, Tag.TAG_COMPOUND)) {
            mcphone$data.deserializeNBT(tag.getCompound(mcphone$nbtKey));
        }
    }
}
