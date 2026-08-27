package com.november.mcphone.feature.chat;

import com.november.mcphone.core.ServerConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.UUID;

/**
 * 好友传送：不需要对方同意（好友本身已双向自愿），但两头都响音效、对方动作栏收到通知。
 * 校验全在服务端，客户端只发一个 UUID。落点与朝向同原版 /tp，不自己算。会被专用服务器加载，不许出现客户端类。
 */
public final class TeleportService {

    private TeleportService() {}

    public static TeleportOutcome teleportToFriend(ServerPlayer self, UUID targetId) {
        // 服主开关必须在服务端拦，界面藏掉图标挡不住伪造客户端
        if (!ServerConfig.allowFriendTeleport()) return TeleportOutcome.DISABLED;

        if (!FriendGuard.mayActOn(self, targetId)) return TeleportOutcome.NOTHING;

        ServerPlayer target = self.server.getPlayerList().getPlayer(targetId);
        if (target == null) return TeleportOutcome.PEER_OFFLINE;

        // 出发点的音效必须在传送之前响，之后 self 已经在另一头了
        self.level().playSound(null, self.getX(), self.getY(), self.getZ(),
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);

        // 这一个调用同时覆盖同维度与跨维度，且自带 stopRiding()，不必自己先下马
        self.teleportTo(target.serverLevel(),
                target.getX(), target.getY(), target.getZ(),
                target.getYRot(), target.getXRot());

        target.serverLevel().playSound(null, target.getX(), target.getY(), target.getZ(),
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);

        // 走动作栏不走聊天框：即时事件不该在公屏历史里留一行
        target.displayClientMessage(
                Component.translatable("mcphone.chat.teleport_arrived",
                        self.getName().getString()), true);

        return TeleportOutcome.OK;
    }

}
