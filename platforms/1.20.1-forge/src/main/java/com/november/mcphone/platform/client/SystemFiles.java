package com.november.mcphone.platform.client;

import net.minecraft.Util;

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
 * <h2>包名里的 client 是硬要求</h2>
 *
 * {@code Util} 本身不是客户端类型，但这一层归客户端用；更要紧的是
 * {@code platform.client} 这个包已经因为 {@code Draw} 而立下规矩：
 * 碰客户端的东西一律放这儿，dist 隔离那道闸认的就是路径里的 {@code /client/}。
 */
public final class SystemFiles {

    private SystemFiles() {}

    /** 用系统的文件管理器打开这个目录。目录不存在时各平台自己的行为，这里不兜。 */
    public static void openInFileManager(Path dir) {
        // 1.20.1 的 Util.OS 上没有 openPath（那是 1.20.2 才加的），只有收 File 的 openFile
        Util.getPlatform().openFile(dir.toFile());
    }
}
