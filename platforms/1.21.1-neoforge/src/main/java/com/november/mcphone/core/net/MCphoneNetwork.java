package com.november.mcphone.core.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 网络层的门口 —— 注册一个包、发一个包，都从这儿走。
 *
 * <h2>为什么处理函数不该拿到 IPayloadContext</h2>
 *
 * 原先每个处理函数都收一个 {@code IPayloadContext}，于是每个函数体的第一件事都是同样
 * 的两句：{@code ctx.enqueueWork(...)} 包一层，再 {@code ctx.player() instanceof
 * ServerPlayer} 取一次玩家。四十个处理函数抄了四十遍，而这两件事和"收到之后做什么"
 * 毫无关系。
 *
 * 收进这里之后，上层只写业务：<b>客户端 → 服务端的处理函数拿到的是已经确认非空、
 * 且已经在主线程上的 {@link ServerPlayer}；服务端 → 客户端的处理函数只拿到包本身。</b>
 *
 * <h2>为什么 S2C 那个方向不给玩家参数</h2>
 *
 * 因为 1.20.1 那一支上<b>给不出来</b>。那边取发送者用 {@code NetworkEvent.Context
 * .getSender()}，它只在服务端方向有值，客户端方向必然是 null。NeoForge 的
 * {@code IPayloadContext.player()} 两个方向都给得出玩家（客户端方向给的是本地玩家），
 * 照这边的写法直译过去就是空指针。
 *
 * 所以这里主动把它去掉：<b>两支的处理函数签名因此一致</b>，而客户端要用本地玩家时
 * 自己从 {@code Minecraft.getInstance()} 取 —— 那本来就是它该有的来源。
 *
 * <h2>两个发包方法的方向是不对称的，别被"门口"这个说法骗了</h2>
 *
 * {@link #sendToServer} <b>只能在客户端调</b>，{@link #sendToPlayer} <b>只能在服务端调</b>。
 * 这不是约定，是硬的：{@code PacketDistributor.sendToServer} 第一句就是
 * {@code Preconditions.checkState(FMLEnvironment.dist.isClient(), "Cannot send
 * serverbound payloads on the server")}，在专用服务端上调会抛 IllegalStateException。
 *
 * 好消息是它抛得干净、信息清楚，不是 NoClassDefFoundError —— 这个类只引用
 * {@code PacketDistributor} 这个公共类，类加载本身不会出事。
 *
 * ⚠ 但 {@code verifyDistIsolation} <b>看不穿</b> {@code PacketDistributor} 这一跳
 * （它扫的是常量池里的客户端类型名，而这里出现的是一个两端都有的类），也不该看穿。
 * 也就是说<b>这条边界只有注释在守</b>。1.20.1 那一支要照 {@code NetworkEvent} 那套
 * 手写这两个方法体，那正是最容易把方向搞反的时刻。
 *
 * <h2>这一层不改线格式</h2>
 *
 * 它动的只是"谁在什么线程上、拿着什么参数被调到"，包的编解码与注册顺序一个字节没动。
 */
public final class MCphoneNetwork {

    private MCphoneNetwork() {}

    /**
     * 注册一个客户端 → 服务端的包。
     *
     * 处理函数拿到的 {@link ServerPlayer} 已经非空，且已经在主线程上。
     *
     * 玩家为空只可能是"包排队期间连接断了"—— 方向已由 {@code playToServer} 限死，
     * 不会收到客户端方向的调用。静默丢弃即可，那个玩家已经不在了。
     */
    public static <T extends CustomPacketPayload> void registerToServer(
            PayloadRegistrar registrar,
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            BiConsumer<T, ServerPlayer> handler) {

        registrar.playToServer(type, codec, (packet, ctx) -> ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer player) {
                handler.accept(packet, player);
            }
        }));
    }

    /**
     * 注册一个服务端 → 客户端的包。处理函数只拿到包本身，理由见类注释。
     *
     * ⚠ <b>处理函数体与它直接调到的东西，都不能碰客户端类型。</b>碰 Minecraft、碰
     * Screen 这些，专用服务端在<b>校验这个类</b>的那一刻就崩，不需要执行到那一行。
     *
     * 这些处理函数写在 {@code *Networking} 里，而那些类在 {@code net} 包下 ——
     * {@code verifyDistIsolation} 的规则是"路径里有 {@code /client/} 的类才准引用
     * 客户端类型"，所以它们一个客户端类型都不许出现。
     *
     * <b>别把这条理解成"丢给一个叫 XxxClientCache 的类就安全了"。</b>眼下十二个 S2C
     * 处理函数里，只有音乐那三个真的落在 {@code client} 包下；聊天、笔记、商店那几个
     * {@code *ClientCache} 名字带 Client，人却在 {@code net} 包里。它们合规靠的<b>不是
     * 位置</b>，而是那几个类本身就只是几个静态字段的持有者，压根不碰客户端类型。
     *
     * 所以判据只有一条：<b>真要碰 Minecraft / Screen / 渲染，那段代码必须住在路径带
     * {@code /client/} 的类里</b>，处理函数只留一个方法引用指过去（音乐那三个就是这么做的）。
     */
    public static <T extends CustomPacketPayload> void registerToClient(
            PayloadRegistrar registrar,
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            Consumer<T> handler) {

        registrar.playToClient(type, codec, (packet, ctx) -> ctx.enqueueWork(() -> handler.accept(packet)));
    }

    /** 客户端调用：把包发给服务端 */
    public static void sendToServer(CustomPacketPayload packet) {
        PacketDistributor.sendToServer(packet);
    }

    /**
     * 服务端调用：把包发给某一个玩家。
     *
     * "回给发消息的那个人"只是其中一种用法（它取代了原先的 {@code ctx.reply(...)}
     * —— 那个方法 1.20.1 上没有对应物，而"发给这个玩家"两支都写得出来）。同样走这里的
     * 还有：发给聊天的<b>对方</b>、以及在一个循环里发给一圈听众
     * （{@code DiscService.sendTo}）。
     *
     * <b>这个门面刻意只有两个原语</b>：发给服务端、发给某一个玩家。没有 sendToAll、
     * 没有 sendToPlayersTrackingEntity —— 那些在 1.20.1 上形状不一样，摆在这儿就等于
     * 承诺了两支都有。要广播就自己写循环（上面那个 {@code sendTo} 就是），
     * <b>不要绕过这里去 import {@code PacketDistributor}</b>：全仓只有这个文件该出现
     * 那个类名，这是条 grep 得出来的不变量。
     */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload packet) {
        PacketDistributor.sendToPlayer(player, packet);
    }
}
