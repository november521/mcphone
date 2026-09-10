package com.november.mcphone.core.client;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.PhoneLocation;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
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
 * 目前是美西螈与阅读：一个一天要开几十次，一个是"翻开书之后手机必然被顶掉"——
 * 那一下不是玩家想关手机，是他正在看书。要给别的 App 开这个待遇，两处一起改——
 * id 加进 {@link #RESUMABLE}，并在 {@link #appOf} 里把它的页面映射到这个 id。
 *
 * 一部设备一份
 *
 * 按 {@link PhoneLocation} 分开存。<b>别退回单份静态字段</b>：那样平板从主屏收起时
 * （{@code PhoneHud.dismiss()} 会替它调 {@code shutdown()} → {@code save(MAIN)}，
 * 而 MAIN 不在白名单里）会把手机刚存的续开状态一起清掉，不崩不报错。
 *
 * 位置换了就续不上（手机从主手挪进背包，那次开机回主屏）。这是有意的降级：
 * 位置是本仓「哪一部」的既有表达，换一套设备身份要先给物品一个稳定 id。
 *
 * 只活在这一局
 *
 * 记在内存里，不落盘，进世界时由 MCphoneClient 清掉：换个服务器还停在上一个服务器的会话上是错的，
 * 那边的好友这边未必有。清在【进】世界而不是退出世界，理由见那边的注释。
 */
public final class PhoneSession {

    private static final ResourceLocation CHAT_APP =
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "chat");

    private static final ResourceLocation READER_APP =
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "reader");

    /** 允许续开的 App。加成员见类注释 */
    private static final Set<ResourceLocation> RESUMABLE = Set.of(CHAT_APP, READER_APP);

    /** 一部设备记一笔。peer 只有 mode 是 CHAT_CONVERSATION 时有意义 */
    private record Saved(PhoneScreen.Mode mode, UUID peer) {}

    /**
     * 哪一部停在哪一页。
     *
     * 键是位置，条数因此被玩家身上的槽位数封住；再加上进世界时整张表会清空，
     * 不会长期堆积。
     */
    private static final Map<PhoneLocation, Saved> SAVED = new HashMap<>();

    private PhoneSession() {}

    /**
     * 关机时记一笔。白名单外的页面记成"没有"，下次这一部照常从主屏开。
     *
     * <b>只动 {@code where} 这一部的记录</b>：别的设备正记着什么与这次关机无关。
     */
    public static void save(PhoneLocation where, PhoneScreen.Mode mode, UUID conversationPeer) {
        // 拿不到位置就什么都不记 —— 尤其不能顺手清掉别人的
        if (where == null) return;

        ResourceLocation app = appOf(mode);
        if (app == null || !RESUMABLE.contains(app)) {
            SAVED.remove(where);
            return;
        }

        // 加好友是个临时页，它的搜索框每次 open 都清空，续开会停在一个空搜索页上，
        // 看着像坏了。记成它的上一级——会话列表。
        //
        // 【子页记成它的上一级，这一步在调用方做】：不是每个目标都有同一批 Mode
        // （正在读的那本 txt 就只有一部分目标有那一页），而"哪些 Mode 存在"是那一侧的知识。
        // 这里点名一个别的目标没有的常量，那个目标连 shared/ 都编不过。所以调用方传进来的
        // 已经是"能续开的那一页"，见各目标 PhoneScreen 里调 save 的那一句。
        PhoneScreen.Mode saved = switch (mode) {
            case CHAT_ADD_CONTACT -> PhoneScreen.Mode.CHAT;
            default -> mode;
        };
        SAVED.put(where, new Saved(saved,
                saved == PhoneScreen.Mode.CHAT_CONVERSATION ? conversationPeer : null));
    }

    /**
     * 这次开机停在哪一页，null 表示回主屏。
     * 白名单与安装状态在这里再查一次：记下之后玩家可能把这个 App 卸了。
     */
    public static PhoneScreen.Mode resumeMode(PhoneLocation where) {
        Saved s = where == null ? null : SAVED.get(where);
        if (s == null) return null;

        ResourceLocation app = appOf(s.mode());
        if (app == null || !RESUMABLE.contains(app) || !PhoneScreenRegistry.isInstalled(app)) {
            SAVED.remove(where);
            return null;
        }

        // 对端丢了就退回会话列表：进一个不知道是谁的会话没有意义
        if (s.mode() == PhoneScreen.Mode.CHAT_CONVERSATION && s.peer() == null) {
            s = new Saved(PhoneScreen.Mode.CHAT, null);
            SAVED.put(where, s);
        }
        return s.mode();
    }

    /** 这一部续开会话时的对端，没有则 null */
    public static UUID resumePeer(PhoneLocation where) {
        Saved s = where == null ? null : SAVED.get(where);
        return s == null ? null : s.peer();
    }

    /** 进世界时把【所有】设备的记录清掉，理由见类注释 */
    public static void clearAll() {
        SAVED.clear();
    }

    /** 这一页属于哪个 App；null 表示不属于任何可续开的 App（主屏、设置、商店……） */
    private static ResourceLocation appOf(PhoneScreen.Mode mode) {
        return switch (mode) {
            case CHAT, CHAT_ADD_CONTACT, CHAT_CONVERSATION -> CHAT_APP;
            case READER -> READER_APP;
            default -> null;
        };
    }
}
