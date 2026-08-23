package com.november.mcphone.feature.chat;

import com.november.mcphone.core.ServerConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import java.util.UUID;

/**
 * 好友传送 —— 在美西螈的好友列表里点一下，人就到对方面前。
 *
 * ============================================================
 * 为什么单独一个类，不塞进 ChatService
 * ============================================================
 *
 * ChatService 是【聊天】的业务规则：消息、好友、已读。传送不是聊天，
 * 它只是碰巧从同一个 App 的同一个列表进去。塞进去的话那个类会慢慢长成
 * "美西螈里所有功能的杂物间"，而这个 App 后面还要加东西。
 *
 * 两者共用的只有一样：{@link FriendData} 里的好友关系。那是数据，
 * 本来就该被多处读。
 *
 * ============================================================
 * 不需要对方同意，但对方必须知道
 * ============================================================
 *
 * 这是刻意的取舍：好友本身已经是双向自愿的（要对方点头才加得上），
 * 所以"能加好友"就当作"接受被传送"。没有 TPA 那一轮请求与等待。
 *
 * 但【告知】和【许可】是两回事。有人凭空出现在背后而全无动静，是这种
 * 功能最容易挨骂的地方，所以两头各响一次末影人传送音效，对方还会在
 * 动作栏上收到一句"谁来了"。这不给他拒绝的余地，只让他知道刚才发生了
 * 什么——不然他只会以为自己见了鬼，或者服务器有外挂。
 *
 * ============================================================
 * 校验全在这里，客户端一句都不算数
 * ============================================================
 *
 * 客户端发来的只有一个 UUID。手机在不在身上、是不是好友（这两道走
 * {@link FriendGuard}）、对方在不在线，三样全在服务端查——否则改过的
 * 客户端能瞬移到服务器上任何一个人身边，而这是所有玩法里最经不起伪造的
 * 一种能力。
 *
 * ============================================================
 * 落点就是原版 /tp 的落点，不自己算
 * ============================================================
 *
 * 落在对方站的那个坐标上，朝向也取他的——与 /tp &lt;玩家&gt; &lt;玩家&gt; 一模一样。
 *
 * 1.4.4 到 1.4.7 之间这里算过一个"对方身前 1.5 格"的落点，还带碰撞检测
 * 与踩空检测。那是我们自己发明的一套规则：多三十行、多两个可能算错的
 * 分支（把人塞进墙里、判成悬空而白白退回），换来的只是落地时不必转身。
 *
 * 两人重叠一瞬是这么做的全部代价，而原版自己会把他们推开——玩家对
 * /tp 的预期本来就是这样。
 *
 * 本类会被专用服务器加载，一个客户端类都不许出现。
 */
public final class TeleportService {

    private TeleportService() {}

    /**
     * 传送到某个好友面前。
     *
     * @return 结果，由网络层决定要不要说话
     */
    public static TeleportOutcome teleportToFriend(ServerPlayer self, UUID targetId) {
        // 服主的开关排在最前：关掉之后这个功能就不该存在，后面几道校验
        // 连跑都不必跑。界面那边也会藏掉图标，但拦截必须在这里——界面只是
        // 不给入口，伪造客户端照样发得出包
        if (!ServerConfig.allowFriendTeleport()) return TeleportOutcome.DISABLED;

        // 手机在身上 + 确实是好友。两道门与发消息共用同一处实现，
        // 理由（它们是安全边界，不是风格）见 FriendGuard
        if (!FriendGuard.mayActOn(self, targetId)) return TeleportOutcome.NOTHING;

        ServerPlayer target = self.server.getPlayerList().getPlayer(targetId);
        if (target == null) return TeleportOutcome.PEER_OFFLINE;

        // 出发点先响一声：旁边的人得知道这人是传走了，不是凭空消失。
        // 必须在传送【之前】响，之后 self 已经在另一头了
        self.level().playSound(null, self.getX(), self.getY(), self.getZ(),
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);

        // 落点、朝向全取对方的，与原版 /tp <玩家> <玩家> 同一个语义。
        //
        // 这一个调用同时覆盖同维度与跨维度，不必自己判断在哪个世界；也不必
        // 自己先下马，它进门第二件事就是 stopRiding()（1.21.1 的
        // ServerPlayer.teleportTo，字节码核过）
        self.teleportTo(target.serverLevel(),
                target.getX(), target.getY(), target.getZ(),
                target.getYRot(), target.getXRot());

        target.serverLevel().playSound(null, target.getX(), target.getY(), target.getZ(),
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);

        // 告知，不是请求许可。走动作栏而不是聊天框：这是一次即时事件，
        // 不该在公屏历史里留一行，与好友申请的失败提示同一套做法
        target.displayClientMessage(
                Component.translatable("mcphone.chat.teleport_arrived",
                        self.getName().getString()), true);

        return TeleportOutcome.OK;
    }

}
