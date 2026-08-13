package com.november.mcphone.network.chat;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

/**
 * "加联系人"界面里的一行 —— 一个当前在线的玩家。
 *
 * @param id        玩家 UUID
 * @param name      玩家名
 * @param isContact 是否已经是本人的联系人。由服务端算好带下来，
 *                  界面据此显示"加为联系人"还是"已添加"——让客户端
 *                  自己拿在线列表去比对联系人列表也能算，但那要求
 *                  客户端手里有完整联系人表，白白多同步一份数据
 */
public record OnlinePlayer(UUID id, String name, boolean isContact) {

    public static final StreamCodec<ByteBuf, OnlinePlayer> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, OnlinePlayer::id,
                    ByteBufCodecs.stringUtf8(ConversationSummary.MAX_NAME_LENGTH), OnlinePlayer::name,
                    ByteBufCodecs.BOOL, OnlinePlayer::isContact,
                    OnlinePlayer::new
            );
}
