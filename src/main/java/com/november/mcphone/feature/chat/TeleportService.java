package com.november.mcphone.feature.chat;

import com.november.mcphone.core.PhoneItem;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

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
 * 客户端发来的只有一个 UUID。手机在不在身上、是不是好友、对方在不在线，
 * 三样全在服务端查——否则改过的客户端能瞬移到服务器上任何一个人身边，
 * 而这是所有玩法里最经不起伪造的一种能力。
 *
 * 本类会被专用服务器加载，一个客户端类都不许出现。
 */
public final class TeleportService {

    private TeleportService() {}

    /**
     * 落在对方身前多远。
     *
     * 1.5 格：比玩家碰撞箱（0.6 宽）宽出一截，两人不会一落地就互相挤开；
     * 又近到一眼就知道是冲着他来的。再远就成了"在他附近"，不是"面前"。
     */
    private static final double FRONT_DISTANCE = 1.5D;

    /**
     * 传送到某个好友面前。
     *
     * @return 结果，由网络层决定要不要说话
     */
    public static TeleportOutcome teleportToFriend(ServerPlayer self, UUID targetId) {
        // 与发消息、加好友同一条线：写操作必须手机真在身上。
        // 主手、副手、背包、饰品槽都算，见 PhoneItem.isCarriedBy
        if (!PhoneItem.isCarriedBy(self)) return TeleportOutcome.NOTHING;

        // 必须是好友。这一条同时堵掉了"对着随便编造的 UUID 传送"——
        // 否则只要猜到一个人的 UUID 就能落到他家里
        if (!FriendData.get(self.server).areFriends(self.getUUID(), targetId)) {
            return TeleportOutcome.NOTHING;
        }

        ServerPlayer target = self.server.getPlayerList().getPlayer(targetId);
        if (target == null) return TeleportOutcome.PEER_OFFLINE;

        ServerLevel level = target.serverLevel();
        Vec3 spot = landingSpot(self, target, level);

        // 落地时正对着对方：他的朝向掉个头就是我该朝的方向。
        // 不这么做的话，人到了却背对着他，得自己转一圈才知道人在哪
        float yaw = target.getYRot() + 180.0F;

        // 出发点先响一声：旁边的人得知道这人是传走了，不是凭空消失。
        // 必须在传送【之前】响，之后 self 已经在另一头了
        self.level().playSound(null, self.getX(), self.getY(), self.getZ(),
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);

        // 这一个调用同时覆盖同维度与跨维度，不必自己判断在哪个世界。
        //
        // 也不必自己先下马：它进门第二件事就是 stopRiding()（1.21.1 的
        // ServerPlayer.teleportTo，字节码核过）。自己再判一次 isPassenger
        // 是死代码，而且会让人以为不写就会留下一匹被拉长到几百格外的马
        self.teleportTo(level, spot.x, spot.y, spot.z, yaw, 0.0F);

        level.playSound(null, spot.x, spot.y, spot.z,
                SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);

        // 告知，不是请求许可。走动作栏而不是聊天框：这是一次即时事件，
        // 不该在公屏历史里留一行，与好友申请的失败提示同一套做法
        target.displayClientMessage(
                Component.translatable("mcphone.chat.teleport_arrived",
                        self.getName().getString()), true);

        return TeleportOutcome.OK;
    }

    /**
     * 算落点：对方面朝方向的前方 1.5 格，站不住就退回对方的准确坐标。
     *
     * 两道检查，缺一不可：
     *
     *   放得下  —— 那 1.5 格外可能是一堵墙、一棵树、一个只有半格高的洞。
     *              按【本人】的碰撞箱量，不是按一个固定尺寸：潜行、游泳、
     *              爬行时人是矮的，用站姿去量会把本来能进的地方判成不行
     *   踩得着  —— 对方站在悬崖边朝外看时，他"面前"是几十格的空气。
     *              传过去等于把人推下崖，而他连自己怎么死的都不知道
     *
     * 任一不过就退回 target.position()。那个点一定安全——对方正站在那儿。
     * 代价只是两人重叠一瞬，原版自己会把他们推开。
     */
    private static Vec3 landingSpot(ServerPlayer self, ServerPlayer target, ServerLevel level) {
        Vec3 front = target.position().add(
                Vec3.directionFromRotation(0.0F, target.getYRot()).scale(FRONT_DISTANCE));

        AABB box = self.getDimensions(self.getPose()).makeBoundingBox(front);
        if (!level.noCollision(self, box)) return target.position();

        BlockPos below = BlockPos.containing(front.x, front.y - 0.5D, front.z);
        if (level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
            return target.position();
        }
        return front;
    }
}
