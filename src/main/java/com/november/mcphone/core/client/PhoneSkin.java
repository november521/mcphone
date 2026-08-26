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
 * 手机界面换肤 —— 贴图优先，纯色兜底（{@link PhoneTheme}）。
 * 贴图放 assets/mcphone/textures/（资源包同路径可覆盖），尺寸不必精确匹配，会被拉伸到目标区域。
 * 探测结果有缓存，资源重载时由 {@link #clearCache()} 清空。
 */
public final class PhoneSkin {

    private PhoneSkin() {}

    /**
     * 可换肤的界面元素。构造器第一个参数是 textures/ 下的路径（不含前缀与 .png），
     * 第二个是 1.2.7 之前 textures/gui/ 下的老路径，新的找不到时回退。
     * 注释里的尺寸是【画在屏幕上有多大】，不是文件必须多大：贴图会被拉伸到目标区域，
     * 整数倍放大的图一样能用，线条细、有弧度的图案这么做更清楚（自带的 frame 是 2 倍，导航栏三个键是 10 倍）。
     */
    public enum Element {
        /**
         * 手机外壳边框。建议 136×216（含边框整机）；中间 120×200 必须透明——外壳最后画、盖在所有内容之上，
         * 中间画的东西（内圆角、刘海）会显示。可见的一圈是外侧 8px，圆角半径建议不超过 11px。
         */
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

        /** 主屏拖动时"松手落这儿"的空槽。建议 20×20；兜底色 {@link PhoneTheme#COLOR_APP_DROP_SLOT} */
        HOME_DROP_SLOT("phone/drop_slot", "home_drop_slot"),

        /** 主屏底部页码点（非当前页）。建议 3×3；兜底色 {@link PhoneTheme#COLOR_PAGE_DOT} */
        HOME_PAGE_DOT("phone/page_dot", "home_page_dot"),

        /** 页码点（当前页）。建议 3×3；兜底色 {@link PhoneTheme#COLOR_PAGE_DOT_ACTIVE} */
        HOME_PAGE_DOT_ACTIVE("phone/page_dot_active", "home_page_dot_active"),

        /** 拖图标停在屏幕边上时的翻页提示条。建议 10×176（竖条），整张按透明度淡入，画实心即可；兜底色 {@link PhoneTheme#COLOR_PAGE_EDGE} */
        HOME_PAGE_EDGE("phone/page_edge", "home_page_edge"),

        /** 自己发出的聊天气泡底。整张拉伸（无九宫格），纯色或纵向渐变最稳妥 */
        CHAT_BUBBLE_SELF("chat/bubble_self", "chat_bubble_self"),

        /** 对方发来的聊天气泡底。拉伸方式同 {@link #CHAT_BUBBLE_SELF} */
        CHAT_BUBBLE_PEER("chat/bubble_peer", "chat_bubble_peer"),

        /** 会话界面底部输入栏的底。建议 90×14 */
        CHAT_INPUT_BAR("chat/input_bar", "chat_input_bar"),

        /** 会话列表在线好友行的"传送"小图标。建议 7×7，按实际绘制尺寸画（不做平滑缩放）；缺图时画 → 字符 */
        CHAT_TELEPORT("chat/teleport", "chat_teleport"),

        /** 收到消息的通知底。建议 160×32（原版通知槽位尺寸） */
        TOAST_BG("phone/toast", "toast_bg"),

        /** 未读条数角标底。建议 12×9；主屏图标角标、会话列表与通知共用 */
        UNREAD_BADGE("phone/unread_badge", "unread_badge"),

        /** 应用详情页可点按钮底（购买/下载）。建议 100×16，整张拉伸；兜底色 {@link PhoneTheme#COLOR_BUTTON} */
        STORE_BUTTON("store/button", "store_button"),

        /** 不可点的按钮底（已安装、买不起）。建议 100×16；兜底色 {@link PhoneTheme#COLOR_BUTTON_DISABLED} */
        STORE_BUTTON_DISABLED("store/button_disabled", "store_button_disabled"),

        /** 商店「联动 App」入口格图标。建议 20×20；缺图时画纯色底加三个小方块，兜底色 {@link PhoneTheme#COLOR_STATUS_BAR} */
        STORE_COMPANION("store/companion", "store_companion"),

        /** 音乐播放器「上一首」。建议 9×9，与行内文字等高；缺图时画 ⏮ 字符 */
        MUSIC_PREV("music/prev", "music_prev"),

        /** 「播放」键。建议 9×9；缺图时画 ▶ 字符 */
        MUSIC_PLAY("music/play", "music_play"),

        /** 「暂停」键。建议 9×9。缺图时画 ⏸ 字符。与 {@link #MUSIC_PLAY} 成对 */
        MUSIC_PAUSE("music/pause", "music_pause"),

        /** 「下一首」。建议 9×9。缺图时画 ⏭ 字符 */
        MUSIC_NEXT("music/next", "music_next"),

        /** 唱片仓「取出」。建议 9×9；缺图时画 ⏏ 字符 */
        MUSIC_EJECT("music/eject", "music_eject"),

        /** 唱片仓「从背包放」。建议 9×9；缺图时画 ▤ 字符。只在仓空时画，与「取出」不同时出现 */
        MUSIC_BACKPACK("music/backpack", "music_backpack"),

        /** 循环模式键 —— 列表循环。建议 9×9；缺图时画 ↻ 字符 */
        MUSIC_MODE_LIST_LOOP("music/mode_list_loop", "music_mode_list_loop"),

        /** 循环模式键 —— 单曲循环。建议 9×9，缺图时画 ① 字符 */
        MUSIC_MODE_SINGLE_LOOP("music/mode_single_loop", "music_mode_single_loop"),

        /** 循环模式键 —— 随机播放。建议 9×9，缺图时画 ⇄ 字符 */
        MUSIC_MODE_SHUFFLE("music/mode_shuffle", "music_mode_shuffle"),

        /**
         * 相册单张查看的「删除」键。建议 9×9；缺图时画「删除」两个字。
         * 只管"还没上膛"的那一态；点过一次后的「再点一次确认」一律是文字，不走贴图。
         */
        GALLERY_DELETE("gallery/delete", "gallery_delete"),

        /** 浏览器面板底。建议 320×200，整张拉伸；兜底色 {@link PhoneTheme#COLOR_SCREEN_BG} */
        BROWSER_PANEL("browser/panel", "browser_panel"),

        /** 浏览器工具条底（后退/前进/刷新 + 地址栏）。建议 320×22；在面板外面，浮在上方留白里 */
        BROWSER_BAR("browser/bar", "browser_bar"),

        /**
         * 书架列表里那本兜底的书。建议 16×16，按实际绘制尺寸画（不做平滑缩放）；
         * 兜底色 {@link PhoneTheme#COLOR_BOOK_SPINE}。
         * 只在书源画不出那本书自己的图标时才用得上——多数书画出来是它的物品。
         */
        READER_BOOK("reader/book", "reader_book");

        /** 现在的路径，按功能分目录 */
        private final ResourceLocation texture;

        /** 1.2.7 之前 textures/gui/ 下的老路径，为兼容已发布的资源包保留；新路径找不到才回退 */
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

    /** 探测结果缓存；empty（没有这张贴图）也要缓存，否则缺贴图的元素每帧都要查一次资源管理器 */
    private static final Map<Element, Optional<SkinTexture>> CACHE = new HashMap<>();

    /** resolveWithLegacy 的结果缓存。键是"想要的路径"，值是"实际用的路径" */
    private static final Map<ResourceLocation, ResourceLocation> PATH_CACHE = new HashMap<>();

    /** 资源重载时调用，挂在客户端重载事件上 */
    public static void clearCache() {
        CACHE.clear();
        PATH_CACHE.clear();
    }

    /** 画贴图，拉伸到目标区域。真的画了才 true；没有贴图返回 false，调用方自行兜底 */
    public static boolean draw(GuiGraphics g, Element element, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) return false;

        SkinTexture tex = resolve(element).orElse(null);
        if (tex == null) return false;

        // 走 GuiUtil 而不是 g.blit：原版那条 blit 不开混合，半透明贴图会被当成不透明画
        GuiUtil.drawTexture(g, tex.location(), x, y, w, h, tex.width(), tex.height());
        return true;
    }

    /** 这个元素有没有贴图。给贴图与兜底形状不一样的地方用（如相册删除键），画之前就要知道走哪一支 */
    public static boolean has(Element element) {
        return resolve(element).isPresent();
    }

    /** 画贴图；没有贴图则用兜底色填满同一区域 */
    public static void drawOrFill(GuiGraphics g, Element element,
                                  int x, int y, int w, int h, int fallbackColor) {
        if (!draw(g, element, x, y, w, h)) {
            g.fill(x, y, x + w, y + h, fallbackColor);
        }
    }

    private static Optional<SkinTexture> resolve(Element element) {
        return CACHE.computeIfAbsent(element, PhoneSkin::probe);
    }

    /** 查资源管理器：贴图在不在，多大 */
    private static Optional<SkinTexture> probe(Element element) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return Optional.empty();

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
     * 在新老路径之间挑一个真实存在的，给不走 Element 那套的调用方（内建 App 图标）用。
     * 两个都不存在时返回新路径：让原版画紫黑格，比悄悄什么都不画好排查。
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

    /** 从 PNG 头部（前 24 字节）读出 {宽, 高}，不解码整张图；不是合法 PNG 返回 null */
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
