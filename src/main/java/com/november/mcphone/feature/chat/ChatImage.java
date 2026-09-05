package com.november.mcphone.feature.chat;

/**
 * 图片消息的几个硬上限。客户端压图、服务端收图、存档清理三边共用这一份，
 * 改一个数不必去别处找配套的另一个。
 *
 * 为什么图要压这么小
 *
 * 手机屏幕是 120×200 个 GUI 像素，一个气泡最宽 80 出头；GUI 缩放最高 4 倍，
 * 也就是屏幕上顶多 320 个真实像素。存 256 的长边已经比看得见的还清楚一点，
 * 再大只是白占硬盘与带宽——而这两样都是服主替所有人付的。
 *
 * 为什么要分片传
 *
 * 原版对【客户端发给服务端】的自定义包有 32767 字节的硬上限
 * （ServerboundCustomPayloadPacket.MAX_PAYLOAD_SIZE），超了在编码阶段就断线。
 * 一张 256 长边的 PNG 常在 30～80 KB，装不进一个包，所以上传按 {@link #CHUNK_BYTES}
 * 切片。反过来【服务端发给客户端】那条路上限是 1 MB，一个包发得完，不必切。
 */
public final class ChatImage {

    private ChatImage() {}

    /** 存下来的图片长边上限（像素），客户端在发送前就压到这个尺寸 */
    public static final int MAX_SIDE = 256;

    /** 一张图的字节上限。压过之后仍超出的，客户端会再降一档尺寸重压 */
    public static final int MAX_BYTES = 96 * 1024;

    /** 上传切片大小。32767 的硬上限之外还要留出包头与 UUID 等字段的余量，取 16 KB 稳妥 */
    public static final int CHUNK_BYTES = 16 * 1024;

    /** 一次上传最多几片。按上限算是 6 片，多给一片的余量；超过即判定为伪造包 */
    public static final int MAX_CHUNKS = MAX_BYTES / CHUNK_BYTES + 1;

    /**
     * 每对会话最多留几张图的像素。
     *
     * 消息本身留 100 条（{@link ChatData#MAX_MESSAGES_PER_CONVERSATION}），但图不能照这个数留：
     * 100 张 × 96 KB × 每人上百个好友，硬盘是服主的。超出之后【只删像素、不删消息】——
     * 那条消息还在，显示成「图片已过期」。删掉整条的话，聊天记录会凭空少几行，
     * 而玩家不会知道少的是什么。
     */
    public static final int MAX_IMAGES_PER_CONVERSATION = 20;

    /** 一次上传从第一片到最后一片的时限。超时即丢弃，防止半截上传永远占着内存 */
    public static final long UPLOAD_TIMEOUT_MS = 15_000L;
}
