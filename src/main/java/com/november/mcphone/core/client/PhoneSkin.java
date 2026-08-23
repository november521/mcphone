package com.november.mcphone.core.client;

import com.november.mcphone.MCphone;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * =============================================================
 * 手机界面换肤 —— 贴图优先，纯色兜底
 * =============================================================
 *
 * 界面上的每个视觉元素都可以用贴图替换。放了贴图就用贴图，没放就用
 * {@link PhoneTheme} 里的纯色画，功能完全不受影响。
 *
 * 贴图放置目录：
 *   src/main/resources/assets/mcphone/textures/   （模组内置，按功能分子目录）
 *   资源包中的同一路径                                   （玩家覆盖）
 *
 * 尺寸【不必】精确匹配：贴图会被拉伸到目标区域。想要不变形，按注释里
 * 标注的建议尺寸或其等比放大画即可。
 *
 * =============================================================
 * 为什么要主动探测贴图存不存在
 * =============================================================
 *
 * Minecraft 找不到贴图时不会报错，而是画一张紫黑格子的占位图。若不主动
 * 探测，"没放贴图"与"贴图放错了"都会变成满屏紫黑格子，而不是优雅地
 * 退回纯色。所以每个元素在首次绘制时查一次 ResourceManager。
 *
 * 查询结果必须缓存——ResourceManager 的查找不是免费的，而绘制是每帧的。
 * 缓存又必须能被清空，否则玩家按 F3+T 重载资源包后，界面还是旧的。
 * 清空由 {@link #clearCache()} 负责，挂在客户端资源重载事件上。
 */
public final class PhoneSkin {

    private PhoneSkin() {}

    /**
     * 可换肤的界面元素。
     *
     * 每一项对应 textures/ 下的一个 PNG，括号里第一个值是路径（不含
     * textures/ 前缀与 .png 后缀），第二个是 1.2.7 之前那条平铺在
     * textures/gui/ 下的老路径——新的找不到时回退到它，好让老资源包继续能用
     * 加 .png。注释中的尺寸是建议值，不是硬性要求。
     */
    public enum Element {
        /** 手机外壳边框。建议 128×208（含边框的整机尺寸） */
        FRAME("phone/frame", "phone_frame"),

        /** 顶部状态栏背景。建议 120×10 */
        STATUS_BAR("phone/status_bar", "status_bar"),

        /** 底部导航栏背景。建议 120×14 */
        NAV_BAR("phone/nav_bar", "nav_bar"),

        /** 导航栏"返回"键图标。建议 40×14；没有贴图时画 ◁ 字符 */
        NAV_BACK("phone/nav_back", "nav_back"),

        /** 导航栏"主页"键图标。建议 40×14；没有贴图时画 ○ 字符 */
        NAV_HOME("phone/nav_home", "nav_home"),

        /** 导航栏"多任务"键图标。建议 40×14；没有贴图时画 □ 字符 */
        NAV_TASKS("phone/nav_tasks", "nav_tasks"),

        /**
         * 主屏拖动排序时，"松手就落这儿"的空槽提示。建议 20×20，与 App 图标同尺寸。
         *
         * 没有贴图时用 {@link PhoneTheme#COLOR_APP_DROP_SLOT} 填。
         *
         * 这一项是 1.3.9 才有的，1.2.7 那套老路径下从来不存在
         * home_drop_slot.png——那个参数只是构造器的形式要求，查一次查不到就
         * 落回兜底色，不影响任何老资源包。
         */
        HOME_DROP_SLOT("phone/drop_slot", "home_drop_slot"),

        /**
         * 主屏底部的页码点 —— 不是当前这一页的那些。建议 3×3。
         *
         * 没有贴图时用 {@link PhoneTheme#COLOR_PAGE_DOT} 填。
         */
        HOME_PAGE_DOT("phone/page_dot", "home_page_dot"),

        /**
         * 页码点 —— 当前这一页。建议 3×3。
         *
         * 单独一张而不是把上面那张调亮，理由与 {@link #STORE_BUTTON_DISABLED} 一样：
         * 调亮是我们替美术做的决定，"你在这一页"该长什么样应该由画贴图的人说了算。
         * 没有贴图时用 {@link PhoneTheme#COLOR_PAGE_DOT_ACTIVE} 填。
         */
        HOME_PAGE_DOT_ACTIVE("phone/page_dot_active", "home_page_dot_active"),

        /**
         * 拖着图标停在屏幕边上时，那条"再等一下就翻页"的提示条。建议 10×176（竖条）。
         *
         * 会随停留时长由浅到深淡入——贴图整张按透明度调制，所以画成实心竖条即可，
         * 不必自己做渐变。没有贴图时用 {@link PhoneTheme#COLOR_PAGE_EDGE} 填。
         */
        HOME_PAGE_EDGE("phone/page_edge", "home_page_edge"),

        /**
         * 自己发出的聊天气泡底。
         *
         * 气泡大小随文字长短变化，贴图会被整张拉伸过去，所以纯色或纵向
         * 渐变最稳妥；带圆角的图会被拉扁，本类没有九宫格拉伸。
         */
        CHAT_BUBBLE_SELF("chat/bubble_self", "chat_bubble_self"),

        /** 对方发来的聊天气泡底。拉伸方式同 {@link #CHAT_BUBBLE_SELF} */
        CHAT_BUBBLE_PEER("chat/bubble_peer", "chat_bubble_peer"),

        /** 会话界面底部输入栏的底。建议 90×14 */
        CHAT_INPUT_BAR("chat/input_bar", "chat_input_bar"),

        /**
         * 会话列表里每个在线好友那一行右下角的"传送到他身边"小图标。建议 7×7。
         *
         * 7 是与旁边的字目测等高的尺寸：原版字体行高 9、字形格 8，而大写字母
         * 的实际笔画高度就是 7。贴图请按【实际绘制尺寸】画：这里不做平滑缩放，
         * 16×16 画进 7×7 的框会被抽掉一多半像素。
         *
         * 没有贴图时画一个 → 字符，与导航栏三个键缺图时画 ◁ ○ □ 同一套做法：
         * 兜底也要能看懂是什么，不能只剩一个色块。
         *
         * 这一项 1.4.10 才有，老路径下从来不存在 chat_teleport.png——那个参数
         * 只是构造器的形式要求，与 HOME_DROP_SLOT 同理。
         */
        CHAT_TELEPORT("chat/teleport", "chat_teleport"),

        /**
         * 收到消息时右上角弹出的通知底。建议 160×32。
         *
         * 160×32 是原版通知的槽位尺寸，照这个画才不会与其他模组的通知
         * 挤在一起错位。
         */
        TOAST_BG("phone/toast", "toast_bg"),

        /**
         * 未读条数的角标底。建议 12×9。
         *
         * 会话列表与消息通知共用同一张：两处都是"这里有几条没看"，
         * 分成两张贴图的话，换肤时容易只换一处，看着像两个模组。
         */
        UNREAD_BADGE("phone/unread_badge", "unread_badge"),

        /**
         * 应用详情页上那个可点的按钮底（购买 / 下载）。建议 100×16。
         *
         * 与聊天气泡同理，整张拉伸，没有九宫格，所以纯色或纵向渐变最稳妥。
         * 没有贴图时用 {@link PhoneTheme#COLOR_BUTTON} 填。
         */
        STORE_BUTTON("store/button", "store_button"),

        /**
         * 点不动时的按钮底（已安装、买不起）。建议 100×16。
         *
         * 单独一张而不是把可点的那张调暗：调暗是我们替美术做的决定，而
         * "不可点"该长什么样应该由画贴图的人说了算。没有贴图时用
         * {@link PhoneTheme#COLOR_BUTTON_DISABLED} 填。
         */
        STORE_BUTTON_DISABLED("store/button_disabled", "store_button_disabled"),

        /**
         * 应用商店里「联动 App」那个入口格子的图标。建议 20×20，与 App 图标同尺寸。
         *
         * 没有贴图时画一个纯色底加三个小方块，不画字符。理由与"已装/未装"用文字
         * 而不用 ✓✗ 一样：好看的符号（❖ ⚭ 之类）在部分字体下会掉成方框，而这是
         * 玩家进商店第一眼看到的格子。没有贴图时用
         * {@link PhoneTheme#COLOR_STATUS_BAR} 填。
         */
        STORE_COMPANION("store/companion", "store_companion"),

        /**
         * 音乐播放器底部那一条上的「上一首」。建议 9×9，与行内文字等高。
         *
         * 缺图时画 ⏮ 字符，与导航栏三个键、传送图标同一套做法：兜底也要
         * 能看懂是什么，不能只剩一个色块。
         */
        MUSIC_PREV("music/prev", "music_prev"),

        /**
         * 「播放」键。建议 9×9。缺图时画 ▶ 字符。
         *
         * 与暂停分成两张而不是一张调色：那个键在两种状态下画什么，该由
         * 画贴图的人决定，与商店的"点不动"按钮同一个理由。
         */
        MUSIC_PLAY("music/play", "music_play"),

        /** 「暂停」键。建议 9×9。缺图时画 ⏸ 字符。与 {@link #MUSIC_PLAY} 成对 */
        MUSIC_PAUSE("music/pause", "music_pause"),

        /** 「下一首」。建议 9×9。缺图时画 ⏭ 字符 */
        MUSIC_NEXT("music/next", "music_next"),

        /**
         * 循环模式键 —— 列表循环。建议 9×9，缺图时画 ↻ 字符。
         *
         * 三种模式各一张，而不是一张图配三个角标：它们在任何播放器里都是
         * 完全不同的图形（↻ ① ⇄），拼不出来。
         */
        MUSIC_MODE_LIST_LOOP("music/mode_list_loop", "music_mode_list_loop"),

        /** 循环模式键 —— 单曲循环。建议 9×9，缺图时画 ① 字符 */
        MUSIC_MODE_SINGLE_LOOP("music/mode_single_loop", "music_mode_single_loop"),

        /** 循环模式键 —— 随机播放。建议 9×9，缺图时画 ⇄ 字符 */
        MUSIC_MODE_SHUFFLE("music/mode_shuffle", "music_mode_shuffle"),

        /**
         * 浏览器那块大面板的底。建议 320×200，会被整张拉伸到面板大小。
         *
         * 绝大部分会被网页盖住，真正看得见的只有地址栏那一条和加载中的空白期，
         * 所以纯色即可。没有贴图时用 {@link PhoneTheme#COLOR_SCREEN_BG} 填。
         */
        BROWSER_PANEL("browser/panel", "browser_panel"),

        /**
         * 浏览器工具条那一条的底（后退/前进/刷新 + 地址栏）。建议 320×22。
         *
         * 它在面板【外面】，浮在面板上方的留白里，不占网页的高度。
         */
        BROWSER_BAR("browser/bar", "browser_bar");

        /** 现在的路径，按功能分目录 */
        private final ResourceLocation texture;

        /**
         * 1.2.7 之前那条平铺在 textures/gui/ 下的老路径。
         *
         * 留着不是为了好看，是为了不弄坏玩家已经做好的资源包：这些路径在
         * README 里作为换肤契约公开过两个版本，说改就改等于把别人的资源包
         * 单方面作废。新路径优先，找不到才回退到这条，并在日志里提一句。
         */
        private final ResourceLocation legacyTexture;

        Element(String path, String legacyFileName) {
            this.texture = ResourceLocation.fromNamespaceAndPath(
                    MCphone.MODID, "textures/" + path + ".png");
            this.legacyTexture = ResourceLocation.fromNamespaceAndPath(
                    MCphone.MODID, "textures/gui/" + legacyFileName + ".png");
        }

        public ResourceLocation texture() {
            return texture;
        }

        public ResourceLocation legacyTexture() {
            return legacyTexture;
        }
    }

    /** 一张已确认存在的贴图及其真实尺寸 */
    private record SkinTexture(ResourceLocation location, int width, int height) {}

    /**
     * 探测结果缓存。值为 empty 表示"查过了，没有这张贴图"——
     * 必须把"没有"也缓存下来，否则缺贴图的元素每帧都要查一次资源管理器。
     */
    private static final Map<Element, Optional<SkinTexture>> CACHE = new HashMap<>();

    /** resolveWithLegacy 的结果缓存。键是"想要的路径"，值是"实际用的路径" */
    private static final Map<ResourceLocation, ResourceLocation> PATH_CACHE = new HashMap<>();

    /** 资源重载时调用，挂在客户端重载事件上 */
    public static void clearCache() {
        CACHE.clear();
        PATH_CACHE.clear();
    }

    // ============================================================
    //  绘制
    // ============================================================

    /**
     * 画贴图，拉伸到目标区域。
     *
     * @return 真的画了贴图才返回 true；没有贴图返回 false，
     *         调用方据此自行兜底（比如画文字符号）
     */
    public static boolean draw(GuiGraphics g, Element element, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) return false;

        SkinTexture tex = resolve(element).orElse(null);
        if (tex == null) return false;

        // 11 参重载：(贴图, x, y, 目标宽, 目标高, u, v, 源区宽, 源区高, 纹理宽, 纹理高)
        // 取整张贴图拉伸到目标区域，故 u/v 为 0、源区尺寸＝纹理尺寸
        g.blit(tex.location(), x, y, w, h, 0, 0,
                tex.width(), tex.height(), tex.width(), tex.height());
        return true;
    }

    /** 画贴图；没有贴图则用兜底色填满同一区域 */
    public static void drawOrFill(GuiGraphics g, Element element,
                                  int x, int y, int w, int h, int fallbackColor) {
        if (!draw(g, element, x, y, w, h)) {
            g.fill(x, y, x + w, y + h, fallbackColor);
        }
    }

    // ============================================================
    //  探测
    // ============================================================

    private static Optional<SkinTexture> resolve(Element element) {
        return CACHE.computeIfAbsent(element, PhoneSkin::probe);
    }

    /** 查资源管理器：贴图在不在，多大 */
    private static Optional<SkinTexture> probe(Element element) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return Optional.empty();

        // 新路径优先。找不到再试老路径——1.2.7 之前的资源包按那套路径做的，
        // 直接作废别人的劳动成果不合适
        Optional<SkinTexture> found = read(mc, element.texture());
        if (found.isPresent()) return found;

        found = read(mc, element.legacyTexture());
        if (found.isPresent()) {
            MCphone.LOGGER.info(
                    "[MCphone] 贴图 {} 走的是老路径。新路径是 {}，建议资源包跟进——"
                    + "老路径还能用，但将来会去掉",
                    element.legacyTexture(), element.texture());
        }
        return found;
    }

    /** 读一张贴图并量出尺寸。不存在或不是合法 PNG 时返回 empty */
    private static Optional<SkinTexture> read(Minecraft mc, ResourceLocation loc) {
        Optional<Resource> res = mc.getResourceManager().getResource(loc);
        if (res.isEmpty()) return Optional.empty();

        try (InputStream in = res.get().open()) {
            int[] size = readPngSize(in);
            if (size == null) {
                MCphone.LOGGER.warn("[MCphone] {} 不是有效的 PNG，忽略", loc);
                return Optional.empty();
            }
            return Optional.of(new SkinTexture(loc, size[0], size[1]));
        } catch (Exception e) {
            MCphone.LOGGER.warn("[MCphone] 读取贴图 {} 失败: {}", loc, e.toString());
            return Optional.empty();
        }
    }

    /**
     * 在"新路径"和"老路径"之间挑一个真实存在的，给不走 Element 那套的调用方用
     * （目前是内建 App 的图标）。
     *
     * 两个都不存在时返回新路径：让原版画出紫黑格，比悄悄什么都不画好——
     * 至少美术一眼能看出是哪张图没放对。
     */
    public static ResourceLocation resolveWithLegacy(ResourceLocation preferred,
                                                     ResourceLocation legacy) {
        return PATH_CACHE.computeIfAbsent(preferred, p -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null) return p;
            if (mc.getResourceManager().getResource(p).isPresent()) return p;
            if (mc.getResourceManager().getResource(legacy).isPresent()) {
                MCphone.LOGGER.info(
                        "[MCphone] 贴图 {} 走的是老路径。新路径是 {}，建议资源包跟进",
                        legacy, p);
                return legacy;
            }
            return p;
        });
    }

    /**
     * 从 PNG 头部读出宽高。
     *
     * 只读前 24 字节而不是解码整张图：这里只要尺寸，把图整个解码进内存
     * 纯属浪费——尤其是玩家可能放进来一张很大的贴图。
     *
     * PNG 的固定结构：8 字节签名 + 4 字节块长度 + 4 字节 "IHDR"
     * + 4 字节宽 + 4 字节高，全部大端序。
     *
     * @return {宽, 高}；不是合法 PNG 时返回 null
     */
    private static int[] readPngSize(InputStream in) throws Exception {
        byte[] head = in.readNBytes(24);
        if (head.length < 24) return null;

        // 校验 IHDR 标识，避免把别的格式当成 PNG 读出天文数字般的尺寸
        if (head[12] != 'I' || head[13] != 'H' || head[14] != 'D' || head[15] != 'R') {
            return null;
        }

        int width = readInt(head, 16);
        int height = readInt(head, 20);
        if (width <= 0 || height <= 0) return null;

        return new int[]{width, height};
    }

    private static int readInt(byte[] b, int offset) {
        return ((b[offset] & 0xFF) << 24)
             | ((b[offset + 1] & 0xFF) << 16)
             | ((b[offset + 2] & 0xFF) << 8)
             | (b[offset + 3] & 0xFF);
    }
}
