package com.november.mcphone.feature.terminal.net;

import com.november.mcphone.MCphone;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：手机上按了个东西。按的是哪个写在 {@link Action} 里。
 *
 * 为什么是一个包带枚举，不是一种动作一个包
 *
 * 原来是「开终端」「开卡槽」各一个空包。真要加到四五种动作，那就是四五个几乎一模一样的文件——
 * 每个都要一份 TYPE、一份 STREAM_CODEC、一条注册、一个 handler，而它们之间唯一的差别
 * 是名字。真正该表达的是"哪一个动作"，那就把它写成字段。现在只剩两个动作，这个形状照样
 * 留着——加一个动作是加一个枚举值加一条 switch 分支，不用再开一个文件。
 *
 * 代价是加一个动作会改动这个包的取值范围。这不影响协议版本号：新客户端发的新值旧服务端
 * 读不出来，而下面那个兜底把读不出来的一律当成 {@link Action#OPEN_SLOT_MENU}——最无害的
 * 那一个（开自己手机上的卡槽界面），不会替玩家做任何他没要求的事。
 *
 * 包体里没有玩家、没有槽位号
 *
 * 服务端从连接上下文取玩家，物品从他自己的背包里找。带一个玩家 ID 或者一个槽位号进来，
 * 等于给伪造客户端开后门——"替别人开终端""开一个我没有的槽位"这两件事就都成立了。
 * 本体的末影箱那个包也是空的，同一个理由。
 *
 * 为什么非要有包
 *
 * {@code IPhoneApp.onPress()} 与页面的点击都跑在客户端，而容器菜单必须由服务端 openMenu
 * 建立，界面才由原版流程自动弹出。客户端自己 setScreen 一个终端界面，那界面背后没有菜单，
 * 点什么都没反应。合成同理：改背包只能在服务端做。
 */
public record TerminalActionPacket(Action action) implements CustomPacketPayload {

    /** 手机这边能发起的全部动作。顺序就是线上的编号，<b>只往后加，别插队</b> */
    public enum Action {
        /** 开终端：卡槽里那台优先，没有就用背包里第一台 */
        OPEN_TERMINAL,
        /** 开终端卡槽界面 */
        OPEN_SLOT_MENU,
    }

    private static final Action[] BY_ID = Action.values();

    public static final CustomPacketPayload.Type<TerminalActionPacket> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "phone_action"));

    /**
     * 编号读不出来时退回 {@link Action#OPEN_SLOT_MENU}，而不是抛异常。
     *
     * 抛出去的后果是整条连接被判定为协议错误、玩家直接掉线，而起因可能只是对面装了个新版本。
     * 退回一个无害动作，玩家看到的是"点了没反应／开了卡槽"，自己就会去查版本。
     */
    public static final StreamCodec<ByteBuf, TerminalActionPacket> STREAM_CODEC =
            ByteBufCodecs.VAR_INT.map(
                    id -> new TerminalActionPacket(id >= 0 && id < BY_ID.length
                            ? BY_ID[id] : Action.OPEN_SLOT_MENU),
                    packet -> packet.action().ordinal());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
