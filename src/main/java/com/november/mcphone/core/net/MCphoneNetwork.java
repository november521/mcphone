package com.november.mcphone.core.net;

import com.november.mcphone.MCphone;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
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
 * 直接照着 SimpleChannel 的原生 API 写 34 个包，每个包都要记得
 * setPacketHandled、记得 enqueueWork、记得 getSender() 可能是 null —— 漏一样
 * 就是一个只在特定方向上才发作的 bug。所以这里把那三样收进注册函数里，
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
     */
    private static final String PROTOCOL_VERSION = "1";

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
     * 这两件事都不是白来的：
     *
     *   - NetworkEvent.Context.getSender() 只在服务端方向有值，客户端方向是
     *     null。NeoForge 的 IPayloadContext.player() 两个方向都给得出玩家，
     *     照那边的写法直译过来会在客户端方向空指针
     *   - 网络包默认在【网络线程】上处理。碰世界、碰玩家、碰物品都必须先
     *     enqueueWork 切回主线程，否则是并发访问，症状是偶发且无法复现
     */
    public static <T> void registerToServer(Class<T> type,
                                            BiConsumer<T, FriendlyByteBuf> encoder,
                                            Function<FriendlyByteBuf, T> decoder,
                                            BiConsumer<T, ServerPlayer> handler) {
        CHANNEL.messageBuilder(type, nextId++, NetworkDirection.PLAY_TO_SERVER)
                .encoder(encoder)
                .decoder(decoder)
                .consumerMainThread((msg, ctxSupplier) -> {
                    NetworkEvent.Context ctx = ctxSupplier.get();
                    ServerPlayer sender = ctx.getSender();
                    // 连接在包排队期间断掉就会是 null。方向已由 NetworkDirection
                    // 限死，所以这里只可能是"人走了"，静默丢弃即可
                    if (sender != null) handler.accept(msg, sender);
                    ctx.setPacketHandled(true);
                })
                .add();
    }

    /**
     * 注册一个服务端 → 客户端的包。
     *
     * handler 只拿得到包本身，【没有玩家参数】—— 客户端方向 getSender() 必然
     * 是 null，给一个永远为 null 的参数只会诱人去用它。客户端玩家要自己从
     * Minecraft.getInstance() 取。
     *
     * 注意 handler 的实现【不能】直接出现在专用服务端会加载的类里：碰
     * Minecraft、碰 Screen 这些客户端类型，专用服务端一加载就崩。这条以后由
     * verifyDistIsolation 任务来把关（还没加，见 docs/PORTING.md）。
     */
    public static <T> void registerToClient(Class<T> type,
                                            BiConsumer<T, FriendlyByteBuf> encoder,
                                            Function<FriendlyByteBuf, T> decoder,
                                            Consumer<T> handler) {
        CHANNEL.messageBuilder(type, nextId++, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(encoder)
                .decoder(decoder)
                .consumerMainThread((msg, ctxSupplier) -> {
                    NetworkEvent.Context ctx = ctxSupplier.get();
                    handler.accept(msg);
                    ctx.setPacketHandled(true);
                })
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
