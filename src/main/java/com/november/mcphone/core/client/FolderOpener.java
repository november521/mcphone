package com.november.mcphone.core.client;

import com.november.mcphone.MCphone;
import net.minecraft.Util;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 用系统自带的文件管理器打开一个目录 —— 壁纸、相册、表情三处共用的那一句。
 *
 * <h2>为什么不能直接用 {@code Util.getPlatform().openPath}</h2>
 *
 * 原版那个方法在 Windows 上走 {@code rundll32 url.dll,FileProtocolHandler <uri>}。
 * 这条命令是给<b>文件与 URL</b> 设计的，对目录【静默失败】：进程正常退出、返回码 0、
 * 什么都不弹。实测（Windows 11 + 资源管理器窗口计数）：
 *
 * <pre>
 *   rundll32 url.dll,FileProtocolHandler file:///…/wallpapers/   → 新窗口 0 个
 *   explorer.exe "…\wallpapers"                                   → 新窗口 1 个
 * </pre>
 *
 * 所以玩家点「打开文件夹」会觉得"这个键是坏的"——上游同样如此，不是移植引入的。
 *
 * <h2>分平台的做法</h2>
 *
 * <ul>
 *   <li><b>Windows</b>：{@code explorer.exe <绝对路径>}。这是资源管理器自己的入口，
 *       目录与文件都认。
 *   <li><b>其他平台</b>：仍然用原版 {@code openPath}（Linux 的 xdg-open、macOS 的 open
 *       对目录都没问题），不自己拼命令。
 *   <li><b>兜底</b>：Windows 上 explorer 起不来时（极少见）退回 AWT
 *       {@link Desktop#open}，再不行才记日志。
 * </ul>
 *
 * <h2>路径必须是绝对的</h2>
 *
 * 三个调用方给的目录都是相对游戏目录的（如 {@code config/mcphone/wallpapers}）。
 * {@code explorer.exe} 的当前工作目录未必是游戏目录，所以这里统一转成绝对路径再交出去。
 *
 * <h2>为什么不阻塞</h2>
 *
 * 起一个外部进程很快，但绝不在渲染线程上等它——{@code start} 之后就返回，
 * 进程活多久与我们无关。
 */
public final class FolderOpener {

    private FolderOpener() {}

    /**
     * 打开一个目录。目录不存在时会先建出来——玩家点它的目的往往正是
     * "我还没有图，想放几张进去"，那时弹一句"路径不存在"最没用。
     */
    public static void open(Path dir) {
        if (dir == null) return;

        Path absolute;
        try {
            Files.createDirectories(dir);
            absolute = dir.toAbsolutePath().normalize();
        } catch (Exception e) {
            MCphone.LOGGER.warn("[MCphone] 打开目录前准备失败 {}: {}", dir, e.getMessage());
            return;
        }

        if (isWindows()) {
            if (openWithExplorer(absolute)) return;
            if (openWithAwt(absolute.toFile())) return;
            MCphone.LOGGER.warn("[MCphone] 无法打开目录: {}", absolute);
            return;
        }

        // Linux / macOS：原版这条路对目录是好的
        Util.getPlatform().openPath(absolute);
    }

    private static boolean isWindows() {
        return Util.getPlatform() == Util.OS.WINDOWS;
    }

    /**
     * {@code explorer.exe <path>}。
     *
     * 路径整体作为一个参数传（{@code ProcessBuilder} 不做 shell 拆分），
     * 因此带空格、中文的目录名都不会被拆断。
     */
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
