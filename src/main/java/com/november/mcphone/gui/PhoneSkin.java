package com.november.mcphone.gui;

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
 *   src/main/resources/assets/mcphone/textures/gui/   （模组内置）
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
     * 每一项对应 textures/gui/ 下的一个 PNG 文件，文件名即括号里的值
     * 加 .png。注释中的尺寸是建议值，不是硬性要求。
     */
    public enum Element {
        /** 手机外壳边框。建议 128×208（含边框的整机尺寸） */
        FRAME("phone_frame"),

        /** 顶部状态栏背景。建议 120×10 */
        STATUS_BAR("status_bar"),

        /** 底部导航栏背景。建议 120×14 */
        NAV_BAR("nav_bar"),

        /** 导航栏"返回"键图标。建议 40×14；没有贴图时画 ◁ 字符 */
        NAV_BACK("nav_back"),

        /** 导航栏"主页"键图标。建议 40×14；没有贴图时画 ○ 字符 */
        NAV_HOME("nav_home"),

        /** 导航栏"多任务"键图标。建议 40×14；没有贴图时画 □ 字符 */
        NAV_TASKS("nav_tasks"),

        /**
         * 自己发出的聊天气泡底。
         *
         * 气泡大小随文字长短变化，贴图会被整张拉伸过去，所以纯色或纵向
         * 渐变最稳妥；带圆角的图会被拉扁，本类没有九宫格拉伸。
         */
        CHAT_BUBBLE_SELF("chat_bubble_self"),

        /** 对方发来的聊天气泡底。拉伸方式同 {@link #CHAT_BUBBLE_SELF} */
        CHAT_BUBBLE_PEER("chat_bubble_peer"),

        /** 会话界面底部输入栏的底。建议 90×14 */
        CHAT_INPUT_BAR("chat_input_bar");

        private final ResourceLocation texture;

        Element(String fileName) {
            this.texture = ResourceLocation.fromNamespaceAndPath(
                    MCphone.MODID, "textures/gui/" + fileName + ".png");
        }

        public ResourceLocation texture() {
            return texture;
        }
    }

    /** 一张已确认存在的贴图及其真实尺寸 */
    private record SkinTexture(ResourceLocation location, int width, int height) {}

    /**
     * 探测结果缓存。值为 empty 表示"查过了，没有这张贴图"——
     * 必须把"没有"也缓存下来，否则缺贴图的元素每帧都要查一次资源管理器。
     */
    private static final Map<Element, Optional<SkinTexture>> CACHE = new HashMap<>();

    /** 资源重载时调用，挂在客户端重载事件上 */
    public static void clearCache() {
        CACHE.clear();
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

        Optional<Resource> res = mc.getResourceManager().getResource(element.texture());
        if (res.isEmpty()) return Optional.empty();

        try (InputStream in = res.get().open()) {
            int[] size = readPngSize(in);
            if (size == null) {
                MCphone.LOGGER.warn("[MCphone] {} 不是有效的 PNG，忽略", element.texture());
                return Optional.empty();
            }
            return Optional.of(new SkinTexture(element.texture(), size[0], size[1]));
        } catch (Exception e) {
            MCphone.LOGGER.warn("[MCphone] 读取贴图 {} 失败: {}", element.texture(), e.toString());
            return Optional.empty();
        }
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
