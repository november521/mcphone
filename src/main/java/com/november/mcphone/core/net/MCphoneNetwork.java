package com.november.mcphone.core.net;

import com.november.mcphone.MCphone;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 网络通道 —— 这一支特有的加载器管道，NeoForge 那边没有对应文件。
 *
 * 为什么需要这个类
 *
 * NeoForge 21.x 用的是原版 payload 体系：每个包自带一个 ResourceLocation 类型
 * 和一个 StreamCodec，注册时把 TYPE、STREAM_CODEC、处理函数三样交给
 * PayloadRegistrar 就完事了。1.20.1 上这套【完全不存在】，对应物是 Forge 自己的
 * SimpleChannel：一条通道 + 手工分配的整数序号。
 *
 * 直接照着 SimpleChannel 的原生 API 写 34 个包，每个包都要挑对 consumer 的
 * 变体、还要记得 getSender() 在客户端方向必然是 null —— 漏一样就是一个只在
 * 特定方向上才发作的 bug。所以这里把它们收进两个注册函数，
 * 上层只写"收到包之后做什么"。
 *
 * 序号的规矩：只在末尾追加
 *
 * SimpleChannel 认的是整数序号，不是 ResourceLocation。序号由下面的计数器按
 * 【注册顺序】发放，所以两端必须按同一顺序注册——同一份代码自然如此，但
 * 【在中间插入或删除一个包会让它后面所有包的序号平移】。真发生了，旧客户端
 * 会把 A 包当成 B 包解码，症状是乱七八糟的字段值而不是干脆的报错。
 *
 * 唯一的护栏是下面的 PROTOCOL_VERSION：两端对不上就直接拒绝连接。所以
 * 【动了包的顺序就必须把它 +1】。往末尾追加不用动。
 */
public final class MCphoneNetwork {

    private MCphoneNetwork() {}

    /**
     * 通道协议版本。改动包的【顺序】时必须递增；仅在末尾追加新包时不用动。
     *
     * 与 NeoForge 那一支 event.registrar("1") 里的 "1" 是同一个意思，
     * 但两支的编号各走各的：包的线格式本来就不一样，没有互通的可能。
     *
     * "2"：图片消息那三个包（SendChatImage / RequestChatImage / ChatImageData）
     * 插在了 NewMessage 与 RequestOnlinePlayers 之间，而不是追加在末尾——为的是让
     * 这个文件与 main 的注册顺序逐行对得上，往后再照着搬时不必两边数序号。
     * 代价就是它后面所有包的序号平移了三位，所以这里必须 +1。
     *
     * "3"：「终端」那两个包（TerminalAction / SyncTerminalSlot）同样插在了
     * MusicNetworking 之后而不是追加在末尾，理由与上一条相同。而且这一次
     * 【就算追加在末尾也得升】：SyncTerminalSlot 是 S2C 的，0.11.1 的客户端
     * 收到一个它不认识的序号是解码抛异常、netty 当场断线，而不是安静地忽略。
     * 升上来之后两端版本对不上直接拒绝连接，玩家看到的是一句人话。
     */
    //
    // "4"：手机屏幕亮不亮那个 C2S 包（PhoneScreenOnPacket）。它插在设备名之后而不是
    // 追加在末尾，后面所有包的序号跟着平移了，两端版本对不上必须拒绝连接。
    private static final String PROTOCOL_VERSION = "4";

    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            ResourceLocation.fromNamespaceAndPath(MCphone.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals
    );

    /** 下一个包的序号。见类注释里"只在末尾追加"那一段 */
    private static int nextId = 0;

    /**
     * 注册一个客户端 → 服务端的包。
     *
     * handler 拿到的是【已经确认非空的 ServerPlayer】，而且已经在主线程上。
     *
     * 主线程那一半是 {@code consumerMainThread} 给的：它把处理函数包进
     * {@code ctx.enqueueWork(...)}，并且自己调掉 {@code setPacketHandled(true)}
     * ——两件事都不必在这里再写一遍（对着 Forge 47.4.23 的字节码核过：那个
     * 方法体就是 enqueueWork 一句加 setPacketHandled 一句）。
     * 【但只有这一个变体是这样】：{@code consumerNetworkThread(BiConsumer)}
     * 两件都不做，照它写就是在网络线程上碰世界，症状偶发且无法复现。
     *
     * 非空那一半才是这里真正加的：{@code NetworkEvent.Context.getSender()}
     * 只在服务端方向有值，客户端方向必然是 null。NeoForge 的
     * {@code IPayloadContext.player()} 两个方向都给得出玩家，照那边的写法
     * 直译过来会在客户端方向空指针。
     */
    public static <T> void registerToServer(Class<T> type,
                                            BiConsumer<T, FriendlyByteBuf> encoder,
                                            Function<FriendlyByteBuf, T> decoder,
                                            BiConsumer<T, ServerPlayer> handler) {
        CHANNEL.messageBuilder(type, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(encoder)
                .decoder(decoder)
                .consumerMainThread((msg, ctxSupplier) -> {
                    ServerPlayer sender = ctxSupplier.get().getSender();
                    // 连接在包排队期间断掉就会是 null。方向已由 NetworkDirection
                    // 限死，所以这里只可能是"人走了"，静默丢弃即可
                    if (sender != null) handler.accept(msg, sender);
                })
                .add();
    }

    /**
     * 注册一个服务端 → 客户端的包。
     *
     * handler 只拿得到包本身，【没有玩家参数】—— 客户端方向 getSender() 必然
     * 是 null，给一个永远为 null 的参数只会诱人去用它。客户端玩家要自己从
     * Minecraft.getInstance() 取。线程与 setPacketHandled 同样由
     * {@code consumerMainThread} 负责，见上面那个方法的注释。
     *
     * 注意 handler 的实现【不能】直接出现在专用服务端会加载的类里：碰
     * Minecraft、碰 Screen 这些客户端类型，专用服务端一加载就崩。这条由
     * build.gradle 的 verifyDistIsolation 任务把关。
     */
    public static <T> void registerToClient(Class<T> type,
                                            BiConsumer<T, FriendlyByteBuf> encoder,
                                            Function<FriendlyByteBuf, T> decoder,
                                            Consumer<T> handler) {
        CHANNEL.messageBuilder(type, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(encoder)
                .decoder(decoder)
                .consumerMainThread((msg, ctxSupplier) -> handler.accept(msg))
                .add();
    }

    /** 客户端调用：把包发给服务端。对应 NeoForge 的 PacketDistributor.sendToServer */
    public static void sendToServer(Object packet) {
        CHANNEL.sendToServer(packet);
    }

    /**
     * 服务端调用：把包发给某一个玩家。
     *
     * NeoForge 那边在处理函数里回包用的是 ctx.reply(...)，这边等价的写法是
     * 冲着发件人本人来一发。用 PacketDistributor 而不是 CHANNEL.reply，是因为
     * 上面的注册函数没有把 Context 透给 handler —— 那本来就是它要挡住的东西。
     */
    public static void sendToPlayer(ServerPlayer player, Object packet) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }
}
