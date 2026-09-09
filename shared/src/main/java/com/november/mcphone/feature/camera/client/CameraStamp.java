package com.november.mcphone.feature.camera.client;

import com.november.mcphone.core.client.AppOptions;
import com.november.mcphone.core.client.ClientConfig;
import com.november.mcphone.core.client.PhoneTheme;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 *
 * 同一组坐标还写进【文件名】
 *
 * 烧进像素那一行是给"发出去给别人看"用的，而在游戏里它读不出来：手机相册把整张照片塞进
 * 一百来像素宽，那行字只剩一两个像素高。所以拍照时顺手把坐标写进文件名
 * （{@link #fileName}），相册看大图时把它解回来、用<b>界面文字</b>显示（{@link #coordsIn}）——
 * 界面文字多小的照片都清清楚楚。
 *
 * 为什么是文件名，不是 png 元数据或另存一份索引：文件名<b>不花任何额外代价</b>。原版
 * {@code Screenshot.grab} 本来就收一个文件名，写元数据要把几 MB 的文件读回来再写一遍，
 * 而另存一份索引意味着又多一份会与磁盘对不上的状态。文件名还有个附赠的好处：在文件管理器
 * 里排一眼就看得见"这张是在哪儿拍的"。
 *
 * 代价是改名就没了。这与阅读进度按文件名记是同一个取舍（见 {@code TxtProgress}）。
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

    /** 描边色。纯黑不透明——它要在雪地和夜空上都把白字圈出来 */
    private static final int OUTLINE = 0xFF000000;

    /**
     * 字高占画面高度的目标比例。
     *
     * 2% 是照着手机相机的日期水印定的：小到不抢画面，大到缩掉一半仍读得出。
     * 只是<b>目标</b>——真正用的倍数取整（见 {@link #scaleOf}），位图字体放大到非整数倍会糊，
     * 而这一行的全部意义就是"看得清"。
     */
    private static final double TARGET_HEIGHT = 0.02;

    /** 最多放大到几倍。再大就从"水印"变成"标语"了 */
    private static final int MAX_SCALE = 4;

    /** true = 印坐标。值的真身在配置里，这里是渲染每帧要读的那一份 */
    private static boolean enabled = true;

    /** 内建的那一项：坐标。它跟着 {@link #enabled} 走，关掉就这一项不印，别的项照旧 */
    private static final PhotoStamp COORDS_STAMP = player -> enabled ? text(player) : null;

    /**
     * 要印的那几项，按登记顺序自下而上排 —— 第一项贴着取景框的底边，后来的往上叠。
     *
     * 坐标是内建的第一项；别的项由各自的功能 {@link #register} 进来。这里只管
     * "有哪些项、怎么排"，不管每一项印什么。
     */
    private static final List<PhotoStamp> STAMPS = new ArrayList<>(List.of(COORDS_STAMP));

    /**
     * 登记一项。在客户端初始化时调一次即可 —— 与 {@code AppOptions.register} 同一套路数：
     * 登记的是"能印什么"，印不印由那一项自己每帧回答。
     */
    public static void register(PhotoStamp stamp) {
        STAMPS.add(stamp);
    }

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
        Player player = Minecraft.getInstance().player;
        if (player == null) return;   // 相机模式下不该发生，真发生了也只是这一帧没有戳

        List<String> lines = new ArrayList<>(STAMPS.size());
        for (PhotoStamp stamp : STAMPS) {
            String line = stamp.line(player);
            if (line != null && !line.isEmpty()) lines.add(line);
        }
        if (lines.isEmpty()) return;

        int scale = scaleOf(h);
        int margin = CameraOverlay.margin(w, h);

        // 自下而上：第 0 项贴着底边（只有一项时，位置与从前逐像素相同），后面的往上叠。
        // 每一行各自右对齐 —— 右边那条线是取景框的边，字都从它往左长
        for (int i = 0; i < lines.size(); i++) {
            String text = lines.get(i);
            int x = w - margin - INSET - font.width(text) * scale;
            int y = h - margin - INSET - (i + 1) * font.lineHeight * scale;
            drawOutlined(g, font, text, x, y, scale);
        }
    }

    /**
     * 一行字，四面描边。
     *
     * 描边而不是阴影：阴影只往右下偏一像素，白字压在雪地、沙漠、夕阳上时，左上那两条边
     * 仍然与底色糊在一起；照片的底是任意一块世界，只能四面都圈住。八次 drawString 的
     * 代价在一个满屏渲染世界的地方可以忽略。
     */
    private static void drawOutlined(GuiGraphics g, Font font, String text, int x, int y, int scale) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx != 0 || dy != 0) g.drawString(font, text, dx, dy, OUTLINE, false);
            }
        }
        g.drawString(font, text, 0, 0, COLOR, false);

        g.pose().popPose();
    }

    /**
     * 这一行画多大 —— 按<b>画面高度</b>算，不跟着 GUI 缩放走。
     *
     * 为什么不能就用字体本来的大小：照片是按<b>物理像素</b>抓的（{@code Screenshot.grab} 抓的是
     * 主渲染目标），而界面上一个字有多少物理像素，取决于玩家的 GUI 缩放。同一台 1080p：
     *
     * <pre>
     *   GUI 缩放 3   字高 27 物理像素 ＝ 画面高的 2.5%   看得清
     *   GUI 缩放 1   字高  9 物理像素 ＝ 画面高的 0.8%   照片放到 100% 才勉强认得出
     * </pre>
     *
     * 也就是说，同样一张照片，戳有多大取决于一个与拍照毫无关系的设置。这里把它掰回来：
     * 按比例算出倍数，<b>取整</b>之后再放大。取整是因为原版字体是位图字体，1.7 倍这种
     * 放大会把每个笔画糊成两像素灰边——而这一行要的恰恰是清楚。
     *
     * 结果是常见配置（GUI 缩放 2、3）下与从前一模一样，只有把 GUI 缩到很小的那些人
     * 会看到戳被放大——那正是从前看不清的那一档。
     */
    static int scaleOf(int guiScaledHeight) {
        int scale = (int) Math.round(guiScaledHeight * TARGET_HEIGHT / 9.0);
        return Math.max(1, Math.min(MAX_SCALE, scale));
    }

    //  文件名

    /**
     * 从文件名里认坐标的模式。
     *
     * 不锚在结尾：重名时后面还会缀 {@code _1}（与原版截图同一套规矩），锚死就认不出来了。
     */
    private static final Pattern COORDS = Pattern.compile("_X(-?\\d+)_Y(-?\\d+)_Z(-?\\d+)");

    /**
     * 这张照片该叫什么 —— 原版那个时间戳文件名后面缀上坐标。
     *
     * 返回 {@code null} 表示"按原版的规矩起名"：水印关着、或者玩家不在时就是这样，
     * 而 {@code Screenshot.grab} 收到 null 正是这个意思，调用方不必判。
     *
     * 重名时缀 {@code _1}、{@code _2}，与原版 {@code Screenshot.getFile} 一模一样——
     * 指定了文件名之后原版不再自己让路，同一秒里在同一格上连拍两张就会互相覆盖。
     */
    @Nullable
    public static String fileName(File gameDirectory, @Nullable Player player) {
        if (!enabled || player == null) return null;

        String base = Util.getFilenameFormattedDateTime()
                + "_X" + Mth.floor(player.getX())
                + "_Y" + Mth.floor(player.getY())
                + "_Z" + Mth.floor(player.getZ());

        File dir = new File(gameDirectory, "screenshots");
        String name = base + ".png";
        for (int n = 1; new File(dir, name).exists(); n++) {
            name = base + "_" + n + ".png";
        }
        return name;
    }

    /**
     * 文件名里记着的坐标，认不出来就 {@code null}（F2 截的图、这一版之前拍的照片都是）。
     *
     * 格式与烧在照片上那一行走<b>同一个翻译键</b>：同一张照片，界面上显示的和印在像素里的
     * 是同一行字，不会让人以为是两个数。
     *
     * 调用方记得<b>按文件名缓存</b>结果：这一句会被每帧问一次，而答案只在换一张照片时才变。
     */
    @Nullable
    public static String coordsIn(String fileName) {
        if (fileName == null) return null;

        Matcher m = COORDS.matcher(fileName);
        if (!m.find()) return null;

        return Component.translatable("mcphone.camera.stamp",
                m.group(1), m.group(2), m.group(3)).getString();
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
