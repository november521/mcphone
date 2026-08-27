package com.november.mcphone.core;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 服主的开关 —— 这台服务器允许手机做什么。
 *
 * 为什么必须是服务端配置，不能是客户端配置
 *
 * 客户端配置在每个玩家自己电脑上，他想改就改。用它来管"能不能传送"，
 * 等于把规则交给被管的人——改一行配置就绕过去了。
 *
 * 服务端配置由服主一份说了算，而且 NeoForge 会在玩家连上来时把它同步给
 * 客户端。所以界面能据此提前把按钮藏起来，而真正的拦截仍在服务端：
 * 界面只是不给入口，伪造客户端照样发得出包，那一层不能省。
 *
 * 读它必须容忍"还没加载"
 *
 * 主菜单里、以及连上服务器之前，这份配置根本没有值，直接 get() 会抛
 * IllegalStateException。所以一律走下面那几个包装方法，拿不到值时返回
 * 默认——默认是开着的，与"不配置就保持原样"一致。
 *
 * 配置文件位置：serverconfig/mcphone-server.toml（单人游戏在存档目录下，
 * 每个存档一份；专用服务器在 serverconfig/ 下）。
 */
public final class ServerConfig {

    private ServerConfig() {}

    public static final ForgeConfigSpec SPEC;

    /** 允不允许好友之间互相传送 */
    public static final ForgeConfigSpec.BooleanValue ALLOW_FRIEND_TELEPORT;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("MCphone 的服务端开关。改完重进世界（或重启服务器）生效。",
                        "Server-side switches for MCphone.")
               .push("gameplay");

        ALLOW_FRIEND_TELEPORT = builder
                .comment("允不允许玩家用美西螈里的传送键传到好友身边。",
                        "关掉之后：那个图标不再显示，服务端也会拒绝传送请求。",
                        "已经建立的好友关系与聊天不受影响。",
                        "Allow teleporting to a friend from the Axolotl app.",
                        "When off, the icon is hidden and the server rejects the request.")
                .translation("mcphone.config.allow_friend_teleport")
                .define("allowFriendTeleport", true);

        builder.pop();
        SPEC = builder.build();
    }

    /**
     * 允不允许好友传送。
     *
     * 配置没加载时返回 true：那只发生在主菜单或连上服务器之前，而那时
     * 谁也传送不了。返回 false 反而会让界面在刚进世界的一瞬间闪一下
     * ——图标先没有、配置到了又冒出来。
     */
    public static boolean allowFriendTeleport() {
        return !SPEC.isLoaded() || ALLOW_FRIEND_TELEPORT.get();
    }
}
