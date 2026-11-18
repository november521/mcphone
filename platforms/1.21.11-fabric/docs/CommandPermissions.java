package com.november.mcphone.test;
/** 保留同一权限等级的命令测试夹具，断言和命令本身不随平台变化。 */
public final class CommandPermissions {
    private CommandPermissions() {}
    public static net.minecraft.server.permissions.PermissionSet level(int value) {
        return net.minecraft.server.permissions.LevelBasedPermissionSet.forLevel(net.minecraft.server.permissions.PermissionLevel.byId(value));
    }
}
