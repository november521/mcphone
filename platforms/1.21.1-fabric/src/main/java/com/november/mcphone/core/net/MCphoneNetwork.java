package com.november.mcphone.core.net;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * 网络层的门口 —— 注册一个包、发一个包，都从这儿走。
 *
 * <h2>为什么处理函数不该拿到网络上下文</h2>
 *
 * 原先每个处理函数都收一个上下文对象，于是每个函数体的第一件事都是同样
 * 的两句：包一层"回到主线程"，再判一次"发包的人是谁"。四十个处理函数抄了
 * 四十遍，而这两件事和"收到之后做什么"毫无关系。
 *
 * 收进这里之后，上层只写业务：<b>客户端 → 服务端的处理函数拿到的是已经确认非空、
 * 且已经在主线程上的 {@link ServerPlayer}；服务端 → 客户端的处理函数只拿到包本身。</b>
 * Fabric 的 play 阶段回调本来就跑在主线程上，这一层没有额外的调度。
 *
 * <h2>为什么 S2C 那个方向不给玩家参数</h2>
 *
 * 因为那个方向根本没有"发送者"可给。客户端要用本地玩家，
 * 自己从 {@code Minecraft.getInstance()} 取 —— 那本来就是它该有的来源。
 *
 * <h2>两个发包方法的方向是不对称的，别被"门口"这个说法骗了</h2>
 *
 * {@link #sendToServer} <b>只能在客户端调</b>，{@link #sendToPlayer} <b>只能在服务端调</b>。
 * Fabric 上 {@code ClientPlayNetworking} 是客户端专用类，所以"发往服务端"与
 * "登记 S2C 接收器"这两件事不能写死在这里 —— 本类只留一个桥：
 * {@code core/client/ClientNetworking} 在客户端启动时把真正的实现装进来
 * （{@link #installClient}）。在专用服务端上那个桥永远是空的，误用会得到
 * 一个带说明的 {@link IllegalStateException}，而不是 {@code NoClassDefFoundError}。
 *
 * ⚠ 这也是 {@code verifyDistIsolation} 只能靠注释说清的一条边界：本类的字节码里
 * 没有任何客户端类型（装桥用的是函数参数，类名不出现在常量池里），
 * 但方向反了照样是运行时错误。
 *
 * <h2>这一层不改线格式</h2>
 *
 * 它动的只是"谁在什么线程上、拿着什么参数被调到"，包的编解码与注册顺序一个字节没动。
 *
 * <h2>与 NeoForge 版的一处结构差别</h2>
 *
 * NeoForge 的 {@code registerToClient} 在注册时就立即生效；Fabric 的
 * {@code ClientPlayNetworking} 是客户端专用类，共享代码碰不得。所以这里把
 * S2C 的接收器先收进 {@link #pendingS2C}，等 {@code ClientNetworking.register()}
 * （只在客户端跑）来领走。专用服务端上这个列表永远空着 —— 服务端不需要
 * 接收器，只需要 {@code registerToClient} 里那行 {@code PayloadTypeRegistry.playS2C()}
 * 的编解码登记，服务端发包时要用它来编码。
 */
public final class MCphoneNetwork {

    private MCphoneNetwork() {}

    /** 客户端装进来的"发往服务端"实现；专用服务端上恒为 null */
    private static Consumer<CustomPacketPayload> serverSender;

    /** S2C 接收器的候车室：共享代码登记，客户端启动时领走 */
    private static final List<PendingS2C<?>> pendingS2C = new ArrayList<>();

    private record PendingS2C<T extends CustomPacketPayload>(
            CustomPacketPayload.Type<T> type,
            Consumer<T> handler) {}

    /**
     * 注册一个客户端 → 服务端的包。
     *
     * 处理函数拿到的 {@link ServerPlayer} 已经非空，且已经在主线程上。
     *
     * 玩家为空只可能是"包排队期间连接断了"。静默丢弃即可，那个玩家已经不在了。
     */
    public static <T extends CustomPacketPayload> void registerToServer(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            BiConsumer<T, ServerPlayer> handler) {

        PayloadTypeRegistry.playC2S().register(type, codec);
        ServerPlayNetworking.registerGlobalReceiver(type,
                (packet, ctx) -> handler.accept(packet, ctx.player()));
    }

    /**
     * 注册一个服务端 → 客户端的包。处理函数只拿到包本身，理由见类注释。
     *
     * ⚠ <b>处理函数体与它直接调到的东西，都不能碰客户端类型。</b>碰 Minecraft、碰
     * Screen 这些，专用服务端在校验这个类的那一刻就崩，不需要执行到那一行。
     *
     * 这些处理函数写在 {@code *Networking} 里，而那些类在 {@code net} 包下 ——
     * {@code verifyDistIsolation} 的规则是"路径里有 {@code /client/} 的类才准引用
     * 客户端类型"，所以它们一个客户端类型都不许出现。真要碰 Minecraft / Screen /
     * 渲染，那段代码必须住在路径带 {@code /client/} 的类里，处理函数只留一个
     * 方法引用指过去（音乐那三个就是这么做的）。
     */
    public static <T extends CustomPacketPayload> void registerToClient(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            Consumer<T> handler) {

        // 编解码登记必须在共享阶段完成：服务端发包（sendToPlayer）要用它编码
        PayloadTypeRegistry.playS2C().register(type, codec);
        pendingS2C.add(new PendingS2C<>(type, handler));
    }

    /** 客户端调用：把包发给服务端 */
    public static void sendToServer(CustomPacketPayload packet) {
        Consumer<CustomPacketPayload> sender = serverSender;
        if (sender == null) {
            throw new IllegalStateException(
                    "sendToServer 只能在客户端调：客户端网络桥尚未安装（专用服务端上它永远是空的）");
        }
        sender.accept(packet);
    }

    /**
     * 服务端调用：把包发给某一个玩家。
     *
     * "回给发消息的那个人"只是其中一种用法（它取代了原先的 {@code ctx.reply(...)}
     * —— 那个方法在 Fabric 上没有对应物，而"发给这个玩家"两支都写得出来）。同样走这里的
     * 还有：发给聊天的<b>对方</b>、以及在一个循环里发给一圈听众
     * （{@code DiscService.sendTo}）。
     *
     * <b>这个门面刻意只有两个原语</b>：发给服务端、发给某一个玩家。要广播就自己写循环，
     * <b>不要绕过这里去 import 客户端网络类</b>：全仓只有 ClientNetworking（客户端包）
     * 和本类该碰网络原语，这是条 grep 得出来的不变量。
     */
    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload packet) {
        ServerPlayNetworking.send(player, packet);
    }

    // ---- 只由 core/client/ClientNetworking 调用 ----

    /**
     * 客户端启动时装桥：真正的"发往服务端"由 {@code ClientPlayNetworking::send}
     * 提供，候车室里的 S2C 接收器由 installer 领走。装桥的时机在客户端初始化里，
     * 早于任何界面，因此不会漏。
     */
    public static void installClient(Consumer<CustomPacketPayload> sender,
                                     S2CInstaller installer) {
        serverSender = sender;
        for (PendingS2C<?> p : pendingS2C) {
            installOne(p, installer);
        }
        pendingS2C.clear();
    }

    /** 通配符捕获助手：type 与 handler 必须在同一个 T 上被认领 */
    private static <T extends CustomPacketPayload> void installOne(
            PendingS2C<T> p, S2CInstaller installer) {
        installer.register(p.type(), p.handler());
    }

    /** 领走 S2C 接收器的那只手 —— 实现在客户端包里，本类不引用它 */
    public interface S2CInstaller {
        <T extends CustomPacketPayload> void register(
                CustomPacketPayload.Type<T> type, Consumer<T> handler);
    }
}
