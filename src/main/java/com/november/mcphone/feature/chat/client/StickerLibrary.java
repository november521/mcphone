package com.november.mcphone.feature.chat.client;

import com.november.mcphone.core.client.ImageCodec;
import com.november.mcphone.core.client.ImageFolder;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 表情库 —— 扫描 {@code config/mcphone/stickers/}，那就是玩家自己的表情包。
 *
 * 为什么是一个目录，而不是游戏里的"导入"按钮
 *
 * 表情多半是玩家从别处一整包存下来的。让他在目录里一次丢十张，比在手机屏幕上一张张点
 * 快得多；而"游戏里也能加"这件事由拖放兜住——开着表情页把文件拖进游戏窗口就收进来了
 * （见 PhoneScreen.onFilesDrop）。两条路都不需要弹系统文件选择器，那东西在 macOS 上
 * 与游戏抢主线程，是有名的崩溃源。
 *
 * 与相册的关系
 *
 * 扫描、缩略图、缓存、导入、删除全在 {@link ImageFolder} 里，与相册用的是同一份实现；
 * 这里只是把它钉在表情目录上，并放宽认得的后缀——截图目录里只该有 PNG，而表情包从哪儿
 * 来的都有。
 *
 * 存在客户端而不是存档里：表情是"我这个人"的东西，换服务器、换存档都该跟着走，
 * 与书架收藏（config/mcphone/reader/shelf.json）同一个道理。
 */
public final class StickerLibrary {

    /** 与音乐目录、壁纸目录同一个爹：config/mcphone/<功能>/ */
    private static final Path DIR = Path.of("config/mcphone/stickers");

    /**
     * 缩略图长边上限。表情格子与相册的一样大（33×24），96 足够；
     * 真正发出去时是拿原文件重新压的，与这份缩略图无关。
     */
    private static final int THUMB_MAX_SIDE = 96;

    private static final int MIN_CACHED = 24;

    /** ImageIO 自带解码器认得的那几种。gif 只取第一帧——手机上不做动图 */
    private static final Set<String> EXTENSIONS =
            Set.of(".png", ".jpg", ".jpeg", ".gif", ".bmp");

    private static final ImageFolder FOLDER = new ImageFolder(
            () -> DIR, EXTENSIONS, THUMB_MAX_SIDE, MIN_CACHED, "sticker_thumb_");

    private StickerLibrary() {}

    public static ImageFolder folder() { return FOLDER; }

    public static List<ImageFolder.Entry> stickers() { return FOLDER.entries(); }

    public static int count() { return FOLDER.count(); }

    public static ImageFolder.Entry get(int index) { return FOLDER.get(index); }

    public static void refresh() { FOLDER.refresh(); }

    public static void ensureCacheFor(int perPage) { FOLDER.ensureCacheFor(perPage); }

    public static ImageCodec.Texture thumbnail(ImageFolder.Entry sticker) {
        return FOLDER.thumbnail(sticker);
    }

    /** 把一个外部图片文件收进表情目录，成功返回文件名 */
    public static String importFrom(Path source) { return FOLDER.importFrom(source); }

    public static boolean delete(ImageFolder.Entry sticker) { return FOLDER.delete(sticker); }

    public static void releaseAll() { FOLDER.releaseAll(); }

    /** 表情目录本身，「打开文件夹」那个键用得上 */
    public static Path directory() { return FOLDER.directory(); }
}
