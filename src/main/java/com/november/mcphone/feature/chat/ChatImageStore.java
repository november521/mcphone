package com.november.mcphone.feature.chat;

import com.november.mcphone.MCphone;
import net.minecraft.Util;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Set;
import java.util.UUID;

/**
 * 图片消息的像素仓：一张图一个文件，放在存档目录下的 {@code mcphone/chat-images/}。
 *
 * 为什么不塞进 SavedData
 *
 * 聊天记录（{@link ChatData}）是 SavedData，整份常驻内存、每次保存整份写盘。图片跟着进去
 * 意味着：一台服务器上所有人发过的所有图，全都常驻服务端内存，而且每 5 分钟连同聊天记录
 * 整个重写一遍。几十 MB 的东西不该这么对待——它们只在有人正好翻到那条消息时才被读一次。
 *
 * 所以像素单独成文件，消息里只留一个 id（见 {@link ImageBody}）。要读哪张才去读哪张。
 *
 * 谁来删
 *
 * 三条路，都在 {@link ChatService} 里收口：
 *   一条消息被 100 条的上限挤出去时，它那张图跟着删；
 *   一对会话里的图超过 {@link ChatImage#MAX_IMAGES_PER_CONVERSATION} 张时，最旧的那些只删像素
 *   （消息留着，显示成「图片已过期」）；
 *   服务器启动时扫一遍孤儿文件——写完文件、消息还没落盘就崩了的话，那个文件再没人认领。
 *
 * 线程
 *
 * 读、写、删、扫描全都落在后台线程上（磁盘 IO 不该占着主线程），因此本类不持有任何状态：
 * 每次调用自己算路径、自己开关文件。唯一要想一想的是"正读着的图恰好被删"——
 * 那时读到的是 null，而调用方本来就要处理这一种。
 */
public final class ChatImageStore {

    private ChatImageStore() {}

    private static final String DIR_NAME = "mcphone/chat-images";

    private static final String SUFFIX = ".png";

    /** PNG 的文件头：8 字节签名 + 4 字节段长 + "IHDR" + 宽 + 高，宽在第 16 字节 */
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'
    };

    private static final int PNG_HEADER_MIN = 24;

    /** 存档目录下的图片仓；不存在时【不】创建，由 {@link #write} 在真要写的时候建 */
    private static Path dir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(DIR_NAME);
    }

    private static Path file(MinecraftServer server, UUID id) {
        return dir(server).resolve(id + SUFFIX);
    }

    /**
     * 收下一张图，返回分配给它的 id；写不进去返回 null。
     *
     * id 由服务端生成，不采信客户端：让客户端指定 id，等于允许它覆盖别人的图。
     *
     * 按内容算 id —— 同一张图反复发只存一份
     *
     * id 是 SHA-256(会话键 + 图片字节) 的前 16 个字节。于是同一对好友之间反复发同一张表情，
     * 算出来的是同一个 id、落在同一个文件上：第二次起连写盘都省了，也只占"每对会话 20 张"
     * 里的一个名额。表情就是靠这一条才发得起——它天生要被反复发。
     *
     * 为什么把会话键也拌进去
     *
     * 不拌的话，同一张图在全服只有一份，删它就得先确认【全服】没有别的消息还认领着它，
     * 那是一次全表扫描，而删除发生在每次发图之后。拌进会话键则每对会话各存一份，删之前
     * 只要看这一段记录（至多 100 条）—— 代价是几十 KB 的重复，换来的是删除永远便宜。
     */
    public static UUID write(MinecraftServer server, ConversationKey conversation, byte[] png) {
        UUID id = idFor(conversation, png);
        Path path = file(server, id);
        try {
            // 已经有了就直接用：同一张图、同一对会话，内容一样就是同一个文件
            if (Files.isRegularFile(path)) return id;

            Files.createDirectories(path.getParent());
            Files.write(path, png);
            return id;
        } catch (IOException e) {
            MCphone.LOGGER.error("[MCphone] 图片写入失败: {}", e.getMessage());
            return null;
        }
    }

    /** SHA-256(会话键 + 字节) 的前 16 字节。SHA-256 而不是 UUID.nameUUIDFromBytes（那是 MD5） */
    private static UUID idFor(ConversationKey conversation, byte[] png) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(conversation.toStorageKey().getBytes(StandardCharsets.UTF_8));
            digest.update(png);
            ByteBuffer hash = ByteBuffer.wrap(digest.digest());
            return new UUID(hash.getLong(), hash.getLong());
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 Java 平台必须实现的算法，走不到这儿；真走到了也不能让一条消息把服务器带走
            MCphone.LOGGER.error("[MCphone] 取不到 SHA-256，退回随机 id: {}", e.getMessage());
            return UUID.randomUUID();
        }
    }

    /** 读一张图；文件不在（已过期、被删、或存档被搬动过）返回 null */
    public static byte[] read(MinecraftServer server, UUID id) {
        Path path = file(server, id);
        try {
            if (!Files.isRegularFile(path)) return null;
            return Files.readAllBytes(path);
        } catch (IOException e) {
            MCphone.LOGGER.warn("[MCphone] 图片读取失败 {}: {}", id, e.getMessage());
            return null;
        }
    }

    /**
     * 删一张图。文件本来就不在也算成功——调用方要的是"这张图没了"，不是"我删掉了它"。
     *
     * 挪到后台线程做，与写盘同一个理由：调用它的是玩家发图这条路（挤掉最旧的那几张），
     * 那是主线程。删除的结果没有人在等，因此不必回主线程收尾。
     *
     * 正被读着的图恰好被删也无妨：读那一端拿到的是 null，而它本来就要处理这一种
     * （回一个"这张图没了"）。
     */
    public static void delete(MinecraftServer server, UUID id) {
        Path path = file(server, id);
        Util.backgroundExecutor().execute(() -> {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                MCphone.LOGGER.warn("[MCphone] 图片删除失败 {}: {}", id, e.getMessage());
            }
        });
    }

    /**
     * 这堆字节像不像一张我们收得下的 PNG。
     *
     * 服务端不解码图片——解码是客户端的事，服务端只是个中转与仓库。但完全不看就存，
     * 等于把任意字节转发给每一个收件人的解码器。所以这里只做最便宜的两项检查：
     * 文件头是不是 PNG，以及它自己声明的宽高是不是在上限之内。
     *
     * 真正的解码防线仍在客户端（见 ChatImageCache）：它按解出来的真实尺寸再判一次，
     * 并且把解码整个包在 try 里——这里通过了也不代表那边就该无条件相信。
     */
    public static boolean looksLikePng(byte[] data) {
        if (data == null || data.length < PNG_HEADER_MIN || data.length > ChatImage.MAX_BYTES) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (data[i] != PNG_SIGNATURE[i]) return false;
        }

        int width = readInt(data, 16);
        int height = readInt(data, 20);
        return width >= 1 && width <= ChatImage.MAX_SIDE
                && height >= 1 && height <= ChatImage.MAX_SIDE;
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    /**
     * 删掉没有任何消息认领的图片文件。服务器启动时跑一次。
     *
     * 会有孤儿是因为"写文件"与"消息落盘"不是一个原子操作：图写完了、服务器在下一次
     * 存档之前崩了，那个文件就再没人提起。一次崩溃留一两个文件不算什么，但服务器会崩
     * 很多次，而没人会记得去清。
     *
     * 反过来的顺序（先记消息后写文件）不用考虑：那样丢的是像素，玩家会看到一条永远
     * 加载不出来的图片消息，比多占几 KB 硬盘糟得多。
     *
     * 挂载见 MCphone 的构造函数。漏挂没有任何症状——硬盘慢慢变大，一年后才看得出来。
     */
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();

        // 聊天记录是 SavedData，只能在主线程碰；扫目录与删文件是磁盘上的事，挪去后台。
        // 启动这一刻的引用集合就是全部：那时还没有人连进来，产生不了新的图
        Set<UUID> referenced = ChatData.get(server).referencedImages();
        Util.backgroundExecutor().execute(() -> sweepOrphans(server, referenced));
    }

    private static void sweepOrphans(MinecraftServer server, Set<UUID> referenced) {
        Path dir = dir(server);
        if (!Files.isDirectory(dir)) return;   // 还没人发过图

        int removed = 0;
        try (var stream = Files.list(dir)) {
            for (Path path : stream.toList()) {
                UUID id = parseId(path);
                // 认不出名字的文件不动：那多半是服主自己放进去的东西，不该替他删
                if (id == null || referenced.contains(id)) continue;

                try {
                    Files.deleteIfExists(path);
                    removed++;
                } catch (IOException e) {
                    MCphone.LOGGER.warn("[MCphone] 清理孤儿图片失败 {}: {}", path, e.getMessage());
                }
            }
        } catch (IOException e) {
            MCphone.LOGGER.warn("[MCphone] 扫描图片仓失败: {}", e.getMessage());
            return;
        }

        if (removed > 0) {
            MCphone.LOGGER.info("[MCphone] 清掉了 {} 张没有消息认领的图片", removed);
        }
    }

    private static UUID parseId(Path path) {
        String name = path.getFileName().toString();
        if (!name.endsWith(SUFFIX)) return null;
        try {
            return UUID.fromString(name.substring(0, name.length() - SUFFIX.length()));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
