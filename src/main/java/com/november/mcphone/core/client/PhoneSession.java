package com.november.mcphone.core.client;

import com.november.mcphone.MCphone;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;
import java.util.UUID;

/**
 * 关机时停在哪一页，下次开机还停在哪一页——真手机就是这样，不必每次从主屏再点一遍。
 *
 * 为什么要白名单
 *
 * 续开对高频 App 是省事，对低频的反而碍事：上次去「设置 → 壁纸」换了张壁纸，
 * 下次开机多半是想看看有没有新消息，停在壁纸页上还得先按一次返回，比回主屏多一步。
 * 所以默认一律回主屏，值得续开的一个一个列进 {@link #RESUMABLE}。
 *
 * 目前只有美西螈：它是唯一一个一天要开几十次的 App。要给别的 App 开这个待遇，
 * 两处一起改——id 加进 {@link #RESUMABLE}，并在 {@link #appOf} 里把它的页面映射到这个 id。
 *
 * 只活在这一局
 *
 * 记在内存里，不落盘，退出世界时由 MCphoneClient 清掉，与 ChatClientCache 那几个缓存同一个待遇：
 * 换个服务器还停在上一个服务器的会话上是错的，那边的好友这边未必有。
 */
public final class PhoneSession {

    private static final ResourceLocation CHAT_APP =
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "chat");

    /** 允许续开的 App。加成员见类注释 */
    private static final Set<ResourceLocation> RESUMABLE = Set.of(CHAT_APP);

    private static PhoneScreen.Mode savedMode;

    /** 上次停在与谁的会话上；只有 savedMode 是 CHAT_CONVERSATION 时有意义 */
    private static UUID savedPeer;

    private PhoneSession() {}

    /** 关机时记一笔。白名单外的页面记成"没有"，下次照常从主屏开 */
    public static void save(PhoneScreen.Mode mode, UUID conversationPeer) {
        ResourceLocation app = appOf(mode);
        if (app == null || !RESUMABLE.contains(app)) {
            clear();
            return;
        }

        // 加好友是个临时页，它的搜索框每次 open 都清空，续开会停在一个空搜索页上，
        // 看着像坏了。记成它的上一级——会话列表
        savedMode = mode == PhoneScreen.Mode.CHAT_ADD_CONTACT ? PhoneScreen.Mode.CHAT : mode;
        savedPeer = savedMode == PhoneScreen.Mode.CHAT_CONVERSATION ? conversationPeer : null;
    }

    /**
     * 这次开机停在哪一页，null 表示回主屏。
     * 白名单与安装状态在这里再查一次：记下之后玩家可能把这个 App 卸了。
     */
    public static PhoneScreen.Mode resumeMode() {
        if (savedMode == null) return null;

        ResourceLocation app = appOf(savedMode);
        if (app == null || !RESUMABLE.contains(app) || !PhoneScreenRegistry.isInstalled(app)) {
            clear();
            return null;
        }

        // 对端丢了就退回会话列表：进一个不知道是谁的会话没有意义
        if (savedMode == PhoneScreen.Mode.CHAT_CONVERSATION && savedPeer == null) {
            savedMode = PhoneScreen.Mode.CHAT;
        }
        return savedMode;
    }

    /** 续开会话时的对端，没有则 null */
    public static UUID resumePeer() {
        return savedPeer;
    }

    /** 退出世界时清掉，理由见类注释 */
    public static void clear() {
        savedMode = null;
        savedPeer = null;
    }

    /** 这一页属于哪个 App；null 表示不属于任何可续开的 App（主屏、设置、商店……） */
    private static ResourceLocation appOf(PhoneScreen.Mode mode) {
        return switch (mode) {
            case CHAT, CHAT_ADD_CONTACT, CHAT_CONVERSATION -> CHAT_APP;
            default -> null;
        };
    }
}
