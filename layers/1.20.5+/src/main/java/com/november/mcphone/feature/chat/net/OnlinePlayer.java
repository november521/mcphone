package com.november.mcphone.feature.chat.net;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.UUID;

/** "加联系人"界面里的一行（一个在线玩家）；relation 决定这一行显示什么按钮。 */
public record OnlinePlayer(UUID id, String name, Relation relation) {

    public static final StreamCodec<ByteBuf, OnlinePlayer> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, OnlinePlayer::id,
                    ByteBufCodecs.stringUtf8(ConversationSummary.MAX_NAME_LENGTH), OnlinePlayer::name,
                    Relation.STREAM_CODEC, OnlinePlayer::relation,
                    OnlinePlayer::new
            );
}
