package com.november.mcphone.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** 独立临时目录验证迁移、不覆盖、冲突与失败；从不读写真实世界。 */
public final class PhoneSavedDataFilesTest {
    private static int checks;
    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) throws IOException {
        Path root = Files.createTempDirectory("mcphone-saveddata-migration-");
        try {
            Path fresh = root.resolve("fresh");
            Path target = PhoneSavedDataFiles.prepare(fresh, "mcphone_chat");
            check(target.equals(fresh.resolve("mcphone/mcphone_chat.dat")), "新路径按命名空间布局");
            check(!Files.exists(target), "新世界不提前写空文件");
            Path legacy = root.resolve("legacy"); Files.createDirectories(legacy);
            Path original = legacy.resolve("mcphone_chat.dat"); Files.writeString(original, "旧记录包括个人删除标记");
            Path migrated = PhoneSavedDataFiles.prepare(legacy, "mcphone_chat");
            check(Files.mismatch(original, migrated) == -1, "迁移逐字节保留压缩存档内容");
            check(Files.exists(original), "原件保留，不移动、不删除");
            Files.writeString(migrated, "更新后的记录");
            check(PhoneSavedDataFiles.prepare(legacy, "mcphone_chat").equals(migrated), "重复打开返回同一位置");
            check(Files.readString(migrated).equals("更新后的记录"), "旧副本不能覆盖新状态");
            Path renamed = root.resolve("renamed"); Files.createDirectories(renamed.resolve("minecraft"));
            Path vanillaMoved = renamed.resolve("minecraft/mcphone_chat.dat"); Files.writeString(vanillaMoved, "已被原版移动");
            check(Files.mismatch(vanillaMoved, PhoneSavedDataFiles.prepare(renamed, "mcphone_chat")) == -1,
                    "兼容原版 minecraft 命名空间位置");
            Path conflict = root.resolve("conflict"); Files.createDirectories(conflict.resolve("minecraft"));
            Files.writeString(conflict.resolve("mcphone_chat.dat"), "甲");
            Files.writeString(conflict.resolve("minecraft/mcphone_chat.dat"), "乙");
            try { PhoneSavedDataFiles.prepare(conflict, "mcphone_chat"); throw new AssertionError("冲突必须失败"); }
            catch (IOException expected) { checks++; }
            check(!Files.exists(PhoneSavedDataFiles.current(conflict, "mcphone_chat")), "冲突不生成猜测的记录");
            check(Files.readString(conflict.resolve("mcphone_chat.dat")).equals("甲"), "冲突保留原件甲");
            check(Files.readString(conflict.resolve("minecraft/mcphone_chat.dat")).equals("乙"), "冲突保留原件乙");
            Files.writeString(conflict.resolve("minecraft/mcphone_chat.dat"), "甲");
            check(Files.readString(PhoneSavedDataFiles.prepare(conflict, "mcphone_chat")).equals("甲"), "相同副本可以迁移");
            Path blocked = root.resolve("blocked"); Files.createDirectories(blocked);
            Files.writeString(blocked.resolve("mcphone_chat.dat"), "重要记录");
            Files.writeString(blocked.resolve("mcphone"), "目录位置被占用");
            try { PhoneSavedDataFiles.prepare(blocked, "mcphone_chat"); throw new AssertionError("写入失败必须抛出"); }
            catch (IOException expected) { checks++; }
            check(Files.readString(blocked.resolve("mcphone_chat.dat")).equals("重要记录"), "写入失败不损坏原件");
            try { PhoneSavedDataFiles.current(root, "../escape"); throw new AssertionError("不能越出数据目录"); }
            catch (IllegalArgumentException expected) { checks++; }
            try (var paths = Files.walk(root)) {
                check(paths.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")), "迁移不遗留临时半成品");
            }
            System.out.println("全部通过：" + checks + " 条断言");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
