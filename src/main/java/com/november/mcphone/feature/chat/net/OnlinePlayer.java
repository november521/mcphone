package com.november.mcphone.feature.chat.net;

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
 * @param relation  本人与他的关系，决定这一行显示什么按钮。
 *                  原先是个 isContact 布尔量，双向好友表达不了——
 *                  "我发了申请等他"与"他发了申请等我"必须分得开
 */
public record OnlinePlayer(UUID id, String name, Relation relation) {

    public static final StreamCodec<ByteBuf, OnlinePlayer> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, OnlinePlayer::id,
                    ByteBufCodecs.stringUtf8(ConversationSummary.MAX_NAME_LENGTH), OnlinePlayer::name,
                    Relation.STREAM_CODEC, OnlinePlayer::relation,
                    OnlinePlayer::new
            );
}
