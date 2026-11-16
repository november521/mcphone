package com.november.mcphone.platform;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.MinecraftServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import java.util.Optional;
import java.util.UUID;
/** 原版玩家名称缓存、提示和管理权限的版本接缝。 */
public final class PlayerAccess {
    private PlayerAccess() {}
    public static String name(Player p) { return p.getGameProfile().getName(); }
    public static Optional<String> cachedName(MinecraftServer server,UUID id) {
        var cache=server.getProfileCache();
        return cache==null?Optional.empty():cache.get(id).map(p -> p.getName());
    }
    public static void message(Player p,Component text,boolean overlay) {
        p.displayClientMessage(text,overlay);
    }
    public static boolean admin(CommandSourceStack source) {
        return source.hasPermission(3);
    }

    public static Component itemName(net.minecraft.world.level.ItemLike item) { return item.asItem().getDescription(); }
    public static void teleport(net.minecraft.server.level.ServerPlayer player,net.minecraft.server.level.ServerLevel level,
                                double x,double y,double z,float yaw,float pitch) {
        player.teleportTo(level,x,y,z,yaw,pitch);
    }
}
