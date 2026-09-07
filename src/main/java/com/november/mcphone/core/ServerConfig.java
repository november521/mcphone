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
 * 服务端配置由服主一份说了算，而且 Forge 会在握手时把它同步给客户端
 * （走 ConfigSync / HandshakeMessages.S2CConfigData）。所以界面能据此提前把按钮藏起来，而真正的拦截仍在服务端：
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

    /** 允不允许在美西螈里发图片 */
    public static final ForgeConfigSpec.BooleanValue ALLOW_CHAT_IMAGES;

    /** 一张图（动图是所有帧拼成的那一张）最多多少 KB */
    public static final ForgeConfigSpec.IntValue CHAT_IMAGE_MAX_KB;

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

        ALLOW_CHAT_IMAGES = builder
                .comment("允不允许玩家在美西螈里把相册里的照片发给好友。",
                        "关掉之后：输入栏左边那个图片键不再显示，服务端也会拒收上传的图。",
                        "已经发过的图不会被删，仍然看得见——关掉的是「再发新的」。",
                        "为什么会想关：图片是存进存档目录的（mcphone/chat-images/），",
                        "一张的上限见下面的 chatImageMaxKb，每对会话最多留 20 张。",
                        "人多的服务器请自行算一下硬盘。",
                        "Allow sending photos from the Gallery to friends in the Axolotl app.",
                        "When off, the image button is hidden and the server rejects uploads.",
                        "Images already sent stay readable; they are stored under the world folder.")
                .translation("mcphone.config.allow_chat_images")
                .define("allowChatImages", true);

        CHAT_IMAGE_MAX_KB = builder
                .comment("一张图最多多少 KB。客户端按这个数压缩，服务端按这个数收。",
                        "这个数直接决定硬盘：每对会话最多留 20 张不同的图，",
                        "所以每对好友的上限是 这个数 × 20。默认 512 KB 就是每对 10 MB。",
                        "实际远小于上限：Minecraft 的截图大片是天空与地形，压出来常常只有几 KB；",
                        "这个数卡的是最坏情况——满屏噪点的截图、以及动图（所有帧拼成一张）。",
                        "调小会怎样：客户端自动降一档尺寸重压，图变糊；动图会掉帧、再不行退成一张静态图。",
                        "调大会怎样：图更清楚、动图更流畅，硬盘与带宽跟着涨。",
                        "上限 768 KB 是硬的：再往上就顶到原版「服务端发给客户端」那 1 MB 的包上限了。",
                        "Max size of one chat image in KB. Clients compress to this; the server enforces it.",
                        "Disk per friend pair is this times 20 (the per-conversation image cap).",
                        "Capped at 768 KB by vanilla's 1 MB clientbound payload limit.")
                .translation("mcphone.config.chat_image_max_kb")
                .defineInRange("chatImageMaxKb", 512, 64, 768);

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

    /** 允不允许发图片。没加载时返回 true，理由同 {@link #allowFriendTeleport()} */
    public static boolean allowChatImages() {
        return !SPEC.isLoaded() || ALLOW_CHAT_IMAGES.get();
    }

    /**
     * 一张图的字节上限。客户端压到这个数以内，服务端也按它收。
     *
     * 没加载时返回默认值：那只发生在主菜单，那时也没人在发图。真正进了世界之后，
     * 客户端拿到的是【服主那一份】——服务端配置会在连上来时同步过来（见类注释）。
     */
    public static int chatImageMaxBytes() {
        return (SPEC.isLoaded() ? CHAT_IMAGE_MAX_KB.get() : DEFAULT_IMAGE_MAX_KB) * 1024;
    }

    /** 与上面 defineInRange 里那个默认值必须一致 */
    private static final int DEFAULT_IMAGE_MAX_KB = 512;
}
