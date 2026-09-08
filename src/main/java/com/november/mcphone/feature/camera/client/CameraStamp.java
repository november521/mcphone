package com.november.mcphone.feature.camera.client;

import com.november.mcphone.core.client.AppOptions;
import com.november.mcphone.core.client.ClientConfig;
import com.november.mcphone.core.client.PhoneTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * 照片右下角那一行坐标 —— 在哪儿拍的，印在照片里。
 *
 * 默认开着。「设置 → App 管理器 → 相机 → 坐标水印」里关，存在客户端配置里（跟着这台电脑走）。
 *
 * 为什么它是相机里【唯一】允许入镜的一层
 *
 * 相机的其余每一样东西都在躲镜头：取景框在拍照那一帧被整层跳过（见
 * {@link CameraHandler#onRenderGui}），白闪与模糊从抓取【之后】才起算（见
 * {@link CameraFlash} 的时序那一段）——照片里只该有世界。坐标戳反过来，它的
 * 全部意义就是留在照片上，所以那个 return 之前先画它：取景框被跳过的那一帧，
 * 画面上只剩世界和这一行字，而那一帧正是要被抓下来的一帧。
 *
 * 这一句话的位置顺带定下了另外两件事：
 *
 * 一、**取景时看到的就是照片上的样子**。预览和照片是同一句代码画的，不会出现
 * "按下快门才发现位置/字号和刚才看到的不一样"。
 *
 * 二、**坐标是烧进像素的**，不是照片的一份附带数据。相册、截图文件夹里那张 png、
 * 发给别人的那一份，看到的是同一行字，不依赖任何人装着本模组。代价是拍完不能反悔：
 * 这一版之前拍的照片不会凭空长出坐标，关掉开关也擦不掉已经拍下的那些。
 */
public final class CameraStamp {

    private CameraStamp() {}

    /**
     * 字与取景框那条边线之间留几像素。四角卡尺占着边线上那两像素，留够就不会压上去
     * ——而贴着同一条线排，看着才像是取景框框住了它，不是随便浮在角上的一行字。
     */
    private static final int INSET = 4;

    /**
     * 不透明的白，与取景框那档半透明的白（{@link PhoneTheme#COLOR_VIEWFINDER}）不是一回事：
     * 取景框只是浮在画面上的辅助线，这一行却要在照片里当作内容存在。半透明的话底下
     * 那块世界的颜色会渗上来，拍雪地、拍天空时几乎读不出来。
     */
    private static final int COLOR = PhoneTheme.COLOR_CAMERA_STAMP;

    /** true = 印坐标。值的真身在配置里，这里是渲染每帧要读的那一份 */
    private static boolean enabled = true;

    //  开关

    public static boolean isEnabled() {
        return enabled;
    }

    /** 配置读进来时推给这里。渲染只读这个静态字段，一帧都不碰配置 */
    public static void setEnabled(boolean value) {
        enabled = value;
    }

    /** App 管理器里那一行的定义。由 {@link CameraApp} 在构造时登记 */
    public static AppOptions.Toggle appOption() {
        return new AppOptions.Toggle(
                "mcphone.camera.coords",
                // 这一个开关的"开"就是字面意义上的开，所以用通用的那两个字；
                // 闪光那一行不能这么写，它开着是"模糊"、关着是"白闪"
                "mcphone.gui.on",
                "mcphone.gui.off",
                CameraStamp::isEnabled,
                value -> {
                    // 先让下一帧就用上，再落盘。存盘会绕回 ClientConfig.apply 再设一次
                    // 同样的值——重复但无害，与快门闪光同一套路数
                    enabled = value;
                    ClientConfig.saveCameraCoordStamp(value);
                });
    }

    //  渲染

    /**
     * 画在右下角，贴着取景框的边线往里让 {@link #INSET} 像素。
     *
     * 位置按屏幕算而不是写死：{@link CameraOverlay#margin} 是取景框自己那条留白，
     * 两边共用同一个数，玩家换分辨率、改 GUI 缩放时这一行才会跟着卡尺一起动。
     */
    public static void render(GuiGraphics g, Font font, int w, int h) {
        if (!enabled) return;

        Player player = Minecraft.getInstance().player;
        if (player == null) return;   // 相机模式下不该发生，真发生了也只是这一帧没有戳

        String text = text(player);
        int margin = CameraOverlay.margin(w, h);

        int x = w - margin - INSET - font.width(text);
        int y = h - margin - INSET - font.lineHeight;

        // 带阴影：照片的底是任意一块世界，纯白的字落在雪地、沙漠、夕阳上都会糊掉，
        // 而一圈暗边在什么底上都读得出来。这也是原版给世界上的字一律带阴影的理由
        g.drawString(font, text, x, y, COLOR, true);
    }

    /**
     * 印上去的那一行。取整到方块坐标，与 F3 的 Block 那一行、与玩家嘴里说的"坐标"是同一个数
     * ——小数位对"我在哪儿拍的"没有意义，只会把这一行撑长。
     *
     * 走翻译键而不是自己拼字符串，资源包与整合包因此能改格式（例如只留三个数、或者换成
     * 中文的"东 %s"）。每帧查一次翻译表：这点开销在一个满屏都在渲染世界的地方可以忽略，
     * 而缓存下来就要自己盯着"玩家换了语言"这件事，那才是会坏的地方。
     */
    private static String text(Player player) {
        return Component.translatable("mcphone.camera.stamp",
                Mth.floor(player.getX()),
                Mth.floor(player.getY()),
                Mth.floor(player.getZ())).getString();
    }
}
