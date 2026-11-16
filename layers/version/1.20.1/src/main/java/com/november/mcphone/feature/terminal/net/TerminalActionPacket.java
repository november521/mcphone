package com.november.mcphone.feature.terminal.net;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 客户端 → 服务端：手机上按了个东西。按的是哪个写在 {@link Action} 里。
 *
 * <h2>为什么是一个包带枚举，不是一种动作一个包</h2>
 *
 * 两个动作各一个空包的话，就是两个几乎一模一样的文件——每个都要一份编解码、一条注册、
 * 一个 handler，而它们之间唯一的差别是名字。真正该表达的是"哪一个动作"，那就把它写成
 * 字段。加一个动作是加一个枚举值加一条 switch 分支，不用再开一个文件。唱片仓那个
 * {@code DiscActionPacket} 是同一个形状。
 *
 * 代价是加一个动作会改动这个包的取值范围。这不影响协议版本号：新客户端发的新值旧服务端
 * 读不出来，而下面那个兜底把读不出来的一律当成 {@link Action#OPEN_SLOT_MENU}——最无害的
 * 那一个（开自己手机上的卡槽界面），不会替玩家做任何他没要求的事。
 *
 * <h2>包体里没有玩家、没有槽位号</h2>
 *
 * 服务端从连接上下文取玩家，物品从他自己的背包里找。带一个玩家 ID 或者一个槽位号进来，
 * 等于给伪造客户端开后门——"替别人开终端""开一个我没有的槽位"这两件事就都成立了。
 * 末影箱那个包也是空的，同一个理由。
 *
 * <h2>为什么非要有包</h2>
 *
 * {@code IPhoneApp.onPress()} 与页面的点击都跑在客户端，而容器菜单必须由服务端 openMenu
 * 建立，界面才由原版流程自动弹出。客户端自己 setScreen 一个终端界面，那界面背后没有菜单，
 * 点什么都没反应。
 */
public record TerminalActionPacket(Action action) {

    /** 手机这边能发起的全部动作。顺序就是线上的编号，<b>只往后加，别插队</b> */
    public enum Action {
        /** 开终端：卡槽里那台优先，没有就用背包里第一台 */
        OPEN_TERMINAL,
        /** 开终端卡槽界面 */
        OPEN_SLOT_MENU;

        private static final Action[] VALUES = values();

        /**
         * 编号读不出来时退回 {@link #OPEN_SLOT_MENU}，而不是抛异常。
         *
         * 抛出去的后果是整条连接被判定为协议错误、玩家直接掉线，而起因可能只是对面装了个
         * 新版本。退回一个无害动作，玩家看到的是"点了开出卡槽"，自己就会去查版本。
         */
        static Action decode(int ordinal) {
            return (ordinal >= 0 && ordinal < VALUES.length) ? VALUES[ordinal] : OPEN_SLOT_MENU;
        }
    }

    public static void encode(TerminalActionPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.action().ordinal());
    }

    public static TerminalActionPacket decode(FriendlyByteBuf buf) {
        return new TerminalActionPacket(Action.decode(buf.readVarInt()));
    }
}
