package com.november.mcphone.platform.client;

import com.november.mcphone.MCphone;
import net.minecraft.Util;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 交给系统自己的文件管理器打开一个目录 —— 全仓唯一碰这一句的地方。
 *
 * <h2>为什么值得一层</h2>
 *
 * 同一件事（最终都走 xdg-open / explorer / open），两个版本的入口不同：
 * {@code Util.OS.openPath(Path)} 是 <b>1.20.2 才加的</b>，1.20.1 上只有收
 * {@link java.io.File} 的 {@code openFile}。方法名与参数类型都不一样。
 *
 * 三处「打开文件夹」（相册、表情、壁纸）都从这里走，两支因此只差这个文件。
 *
 * <h2>Windows 那一支为什么不用 openPath</h2>
 *
 * 原版在 Windows 上走 {@code rundll32 url.dll,FileProtocolHandler}，而它对
 * <b>目录</b>是静默失败的：退出码 0、不报错、不弹窗口（实测同一路径 explorer.exe
 * 能开出窗口、rundll32 不能）。「打开文件夹」这一格点下去毫无动静就是这么来的。
 * 所以 Windows 上直接起 explorer.exe，失败再退到 AWT Desktop。
 *
 * <h2>包名里的 client 是硬要求</h2>
 *
 * {@code Util} 本身不是客户端类型，但这一层归客户端用；更要紧的是
 * {@code platform.client} 这个包已经因为 {@code Draw} 而立下规矩：碰客户端的东西
 * 一律放这儿，dist 隔离那道闸认的就是路径里的 {@code /client/}。
 */
public final class SystemFiles {

    private SystemFiles() {}

    /** 用系统的文件管理器打开这个目录。目录不存在时先建出来。 */
    public static void openInFileManager(Path dir) {
        if (dir == null) return;

        Path absolute;
        try {
            Files.createDirectories(dir);
            absolute = dir.toAbsolutePath().normalize();
        } catch (Exception e) {
            MCphone.LOGGER.warn("[MCphone] 打开目录前准备失败 {}: {}", dir, e.getMessage());
            return;
        }

        if (Util.getPlatform() == Util.OS.WINDOWS) {
            if (openWithExplorer(absolute)) return;
            if (openWithAwt(absolute.toFile())) return;
            MCphone.LOGGER.warn("[MCphone] 无法打开目录: {}", absolute);
            return;
        }

        // Linux / macOS：原版这条路对目录是好的
        Util.getPlatform().openPath(absolute);
    }

    private static boolean openWithExplorer(Path absolute) {
        try {
            new ProcessBuilder("explorer.exe", absolute.toString()).start();
            return true;
        } catch (Exception e) {
            MCphone.LOGGER.warn("[MCphone] explorer.exe 打不开 {}: {}", absolute, e.getMessage());
            return false;
        }
    }

    /** AWT 兜底。headless 或没有桌面环境时返回 false */
    private static boolean openWithAwt(java.io.File file) {
        try {
            if (!Desktop.isDesktopSupported()) return false;
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.OPEN)) return false;
            desktop.open(file);
            return true;
        } catch (Exception e) {
            MCphone.LOGGER.warn("[MCphone] AWT Desktop 打不开 {}: {}", file, e.getMessage());
            return false;
        }
    }
}
