package com.november.mcphone.network.chat;

import com.november.mcphone.chat.ChatMessage;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

/**
 * 会话列表里的一行 —— 打开聊天 App 时看到的摘要。
 *
 * ============================================================
 * 为什么只发摘要
 * ============================================================
 *
 * 打开 App 时【不】发历史消息，只发这个。一个有 50 个联系人、每人
 * 100 条记录的玩家，全量同步要收 5000 条消息，每次开手机都来一遍。
 * 历史消息等玩家真的点进某个会话再单独拉。
 *
 * ============================================================
 * 为什么手写 StreamCodec 而不用 composite
 * ============================================================
 *
 * StreamCodec.composite 最多支持 6 个字段（已核对原版源码），而本记录
 * 正好 6 个——用它就顶到了天花板，以后想加"最后一条是谁发的""已读回执"
 * 之类就得整个推倒重写。手写只多十来行，字段数不受限。
 *
 * @param id       对端玩家 UUID
 * @param name     对端显示名。离线联系人也有名字，见 Contact 的说明
 * @param online   对端此刻是否在线。瞬时状态，服务端现算现发、不落盘
 * @param lastText 最后一条消息的正文，空串表示还没聊过
 * @param lastTime 最后一条消息的时刻，0 表示还没聊过
 * @param unread   未读条数
 */
public record ConversationSummary(UUID id, String name, boolean online,
                                  String lastText, long lastTime, int unread) {

    /** 玩家名长度上限，原版用户名最长 16 */
    public static final int MAX_NAME_LENGTH = 16;

    public static final StreamCodec<ByteBuf, ConversationSummary> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ConversationSummary decode(ByteBuf buf) {
                    UUID id = UUIDUtil.STREAM_CODEC.decode(buf);
                    String name = ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH).decode(buf);
                    boolean online = ByteBufCodecs.BOOL.decode(buf);
                    String lastText = ByteBufCodecs.stringUtf8(ChatMessage.MAX_TEXT_LENGTH).decode(buf);
                    long lastTime = ByteBufCodecs.VAR_LONG.decode(buf);
                    int unread = ByteBufCodecs.VAR_INT.decode(buf);
                    return new ConversationSummary(id, name, online, lastText, lastTime, unread);
                }

                @Override
                public void encode(ByteBuf buf, ConversationSummary value) {
                    UUIDUtil.STREAM_CODEC.encode(buf, value.id());
                    ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH).encode(buf, value.name());
                    ByteBufCodecs.BOOL.encode(buf, value.online());
                    ByteBufCodecs.stringUtf8(ChatMessage.MAX_TEXT_LENGTH).encode(buf, value.lastText());
                    ByteBufCodecs.VAR_LONG.encode(buf, value.lastTime());
                    ByteBufCodecs.VAR_INT.encode(buf, value.unread());
                }
            };

    /** 还没聊过的联系人 —— 会话列表里显示为空会话 */
    public static ConversationSummary empty(UUID id, String name, boolean online) {
        return new ConversationSummary(id, name, online, "", 0L, 0);
    }
}
