package com.november.mcphone.feature.chat;

import net.minecraft.server.level.ServerPlayer;
import java.util.UUID;

/** 删除只改变当前连接玩家的可见性，不删共享消息或图片，也不发送网络包。 */
public final class ChatMessageDeletionService {
    private ChatMessageDeletionService() {}
    public static ChatDeletionResult delete(ServerPlayer player, UUID peer, UUID messageId) {
        if (peer.equals(player.getUUID()) || !FriendGuard.mayActOn(player, peer)) return ChatDeletionResult.FORBIDDEN;
        return deleteRecord(player.getUUID(), peer, messageId, ChatData.get(player.server), ChatDeletionData.get(player.server));
    }
    /** 门禁通过后只在本人参与的会话中找 ID；收到/发出、文本/图片使用相同规则。 */
    static ChatDeletionResult deleteRecord(UUID owner, UUID peer, UUID messageId, ChatData chat, ChatDeletionData deletions) {
        if (owner.equals(peer)) return ChatDeletionResult.FORBIDDEN;
        if (!deletions.isAvailable()) return ChatDeletionResult.STORAGE_ERROR;
        if (chat.getMessages(owner, peer).stream().noneMatch(m -> m.id().equals(messageId))) return ChatDeletionResult.NOT_FOUND;
        deletions.hide(owner, peer, messageId);
        return ChatDeletionResult.OK;
    }
}
