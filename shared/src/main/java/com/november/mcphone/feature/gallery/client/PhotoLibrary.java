package com.november.mcphone.feature.gallery.client;

import com.november.mcphone.core.client.ImageCodec;
import com.november.mcphone.core.client.ImageFolder;
import net.minecraft.client.Minecraft;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 相册照片库 —— 扫描 <游戏目录>/screenshots/，按需提供缩略图。
 *
 * 目录里既有相机 App 拍的照片，也有玩家按 F2 随手截的图，两者都是原版 Screenshot.grab
 * 写出来的，格式与命名完全一致，相册不作区分。
 *
 * 扫描、缩略图缓存、写入、删除都在 {@link ImageFolder} 里——美西螈的表情目录用的是同一套，
 * 只有目录与后缀不同。本类只是把那份能力钉在截图目录上，并补一个相册独有的大图预览。
 */
public final class PhotoLibrary {

    /** 截图目录名，与原版 Screenshot.grab 写入的位置一致 */
    private static final String SCREENSHOT_DIR = "screenshots";

    /** 缩略图长边上限（像素）。手机屏幕只有 120 宽，96 足够清晰。 */
    private static final int THUMB_MAX_SIDE = 96;

    /** 缩略图贴图缓存下限。够装下一页还有余量。 */
    private static final int MIN_CACHED = 24;

    /** 只认 .png：截图目录里的东西都是原版写出来的 PNG，别的后缀多半是玩家自己放的别的东西 */
    private static final ImageFolder FOLDER = new ImageFolder(
            () -> Minecraft.getInstance().gameDirectory.toPath().resolve(SCREENSHOT_DIR),
            Set.of(".png"), THUMB_MAX_SIDE, MIN_CACHED, "photo_thumb_");

    private PhotoLibrary() {}

    /** 相册这一页的照片就是目录里的文件，类型直接沿用 {@link ImageFolder.Entry} */
    public static ImageFolder folder() { return FOLDER; }

    public static List<ImageFolder.Entry> getPhotos() { return FOLDER.entries(); }

    public static int count() { return FOLDER.count(); }

    public static ImageFolder.Entry get(int index) { return FOLDER.get(index); }

    public static void refresh() { FOLDER.refresh(); }

    public static void ensureCacheFor(int perPage) { FOLDER.ensureCacheFor(perPage); }

    public static ImageCodec.Texture thumbnail(ImageFolder.Entry photo) {
        return FOLDER.thumbnail(photo);
    }

    /** 把一段 PNG 存进截图目录，成功返回文件名。会话里「保存」收到的图走这条 */
    public static String save(byte[] png, String namePrefix) {
        return FOLDER.save(png, namePrefix);
    }

    public static boolean delete(ImageFolder.Entry photo) {
        if (photo != null && photo.cacheKey().equals(previewKey)) releasePreview();
        return FOLDER.delete(photo);
    }

    /**
     * 释放全部缩略图贴图。退出相册界面时调用——照片贴图对手机的其他界面毫无用处，
     * 留着白占显存。
     */
    public static void releaseAll() {
        releasePreview();
        FOLDER.releaseAll();
    }

    //  大图预览（单张查看用）

    /**
     * 预览图长边上限。
     *
     * 缩略图只有 96px，放大到满屏会糊。手机屏幕 120×200 GUI 像素，GUI 缩放最高 4 倍即
     * 480×800 实际像素，512 足够清晰又不至于把一张 4K 截图整个搬进显存。
     */
    private static final int PREVIEW_MAX_SIDE = 512;

    /** 当前预览的照片缓存键，null 表示没有预览 */
    private static String previewKey = null;

    /** 当前预览贴图，加载完成前为 null */
    private static ImageCodec.Texture preview = null;

    /**
     * 取一张照片的大图预览。同一时刻只保留一张——大图比缩略图重得多，缓存多张没有意义。
     *
     * 未就绪时返回 null，调用方可以先拿缩略图放大顶着，这样翻看时不会出现空白。
     */
    public static ImageCodec.Texture preview(ImageFolder.Entry photo) {
        if (photo == null) return null;

        String key = photo.cacheKey();
        if (key.equals(previewKey)) return preview;   // 命中；加载中时 preview 仍为 null

        // 换了一张：立刻释放上一张，别让两张大图同时占着显存
        releasePreview();
        previewKey = key;

        final int gen = FOLDER.generation();
        FOLDER.submit(photo.path(), PREVIEW_MAX_SIDE, image -> {
            if (image == null) return;
            // 期间玩家已翻到别的照片或退出了相册，这张白读了，丢弃
            if (gen != FOLDER.generation() || !key.equals(previewKey)) {
                image.close();
                return;
            }
            preview = ImageCodec.upload(image, "photo_view_");
        });
        return null;
    }

    /** 释放预览贴图。退出单张查看、或切换到另一张时调用。 */
    public static void releasePreview() {
        if (preview != null) {
            ImageCodec.release(preview);
            preview = null;
        }
        // 键一并清掉：在飞的那次加载回来时会发现键对不上，自行丢弃
        previewKey = null;
    }

    /** 截图目录本身，「打开文件夹」那种按钮用得上 */
    public static Path directory() { return FOLDER.directory(); }
}
