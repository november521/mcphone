package com.november.mcphone.core.net;

import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * 线格式上带上限的东西 —— 眼下只有一样：一串同类的值。
 *
 * <h2>为什么这一支需要它 —— 这里明明有现成的</h2>
 *
 * 这一支（1.21.1）是有 {@code ByteBufCodecs.list(上限)} 的：上限跟着组合子走，而且
 * <b>收发两侧都拦</b>。<b>本仓刻意不用它</b>，因为
 * {@code net.minecraft.network.codec} 这个包是 1.20.5 才有的东西 ——
 * 1.20.1 那一支上 {@code StreamCodec} 与 {@code ByteBufCodecs} 都不存在。包的编解码
 * 改成手写，为的是那一支能用同一份代码；代价就是上限得自己带，这个类就是那个代价。
 *
 * 手写这条路上只有 {@link FriendlyByteBuf} 的
 * {@code writeCollection(集合, 写一个)} 与 {@code readCollection(分配器, 读一个)}，
 * 两个方法都不认上限，而且<b>写的那一侧根本没有地方写</b>。
 *
 * <b>所以不要把这个类"简化"成 ByteBufCodecs.list。</b>那样这一支会更短，
 * 而那一支会编不过。
 *
 * <h2>编码那一侧为什么也要拦</h2>
 *
 * 编码超量与解码超量是同一个 bug 的两头，但发作的地方差得远：
 *
 * <table border="1">
 *   <caption>拦与不拦的差别</caption>
 *   <tr><th></th><th>抛在哪儿</th><th>玩家看到什么</th></tr>
 *   <tr><td>拦了编码</td><td>发的那一端，指着攒出这串东西的那一行</td>
 *       <td>发不出去，日志里有话说</td></tr>
 *   <tr><td>不拦编码</td><td><b>收的那一端</b>解码时抛</td>
 *       <td><b>对方莫名其妙掉线</b>，两边日志都指不到人</td></tr>
 * </table>
 *
 * netty 的解码异常等于断开这条连接。所以漏掉编码那一侧不是"少一道保险"，是把一次本可以
 * 就地定位的错误挪到了另一台机器上；如果那个包是客户端发的，还等于把发包的玩家自己踢下线。
 *
 * <h2>上限本身不在这里</h2>
 *
 * 它是业务上的数（笔记留多少条、好友多少人），属于那个功能，由调用方给。
 */
public final class Wire {

    private Wire() {}

    /**
     * 分配容量的天花板，与原版 {@code ByteBufCodecs.MAX_INITIAL_COLLECTION_SIZE} 同值。
     *
     * 不直接引用那个常量：它在 {@code net.minecraft.network.codec} 包下，那个包 1.20.5
     * 才有，1.20.1 那一支引不到。写死一个同值的数，两支才能用同一份代码。
     */
    private static final int MAX_INITIAL_CAPACITY = 65536;

    /**
     * 写一串值，超过上限直接抛。
     *
     * encoder 的方向是 {@code (值, buf)}，与手写的那批 {@code static void encode(值, buf)}
     * 一致，所以 {@code NoteSummary::encode} 这样的方法引用可以直接传进来。
     * （{@code FriendlyByteBuf.writeCollection} 自己那个 Writer 是反过来的
     * {@code (buf, 值)}，照它的方向写，每个包的编码函数都得多包一层 lambda。）
     *
     * <b>本仓眼下有三种 encode 形状</b>，别以为只有一种：这批手写的静态
     * {@code (值, buf)}；还在用组合子的五处匿名类重写 {@code (buf, 值)}
     * （PhoneLocation、DiscActionPacket、SendChatImagePacket、ConversationSummary、
     * Relation）；以及 {@code PhoneLocation} 接口上的实例方法 {@code writeTo(buf)}。
     *
     * 好在<b>后面两种里的实例形式不必二选一</b>：实例方法 {@code void encode(buf)} 的
     * 方法引用同样绑得上 {@code BiConsumer<T, FriendlyByteBuf>}（接收者变成第一个参数），
     * 所以将来统一时，静态与实例两种写法都能直接传进这里。
     */
    public static <T> void writeList(FriendlyByteBuf buf, Collection<T> values, int max,
                                     BiConsumer<T, FriendlyByteBuf> encoder) {
        if (values.size() > max) {
            throw new EncoderException("要发的列表超过上限 " + max + ": " + values.size());
        }
        buf.writeCollection(values, (b, v) -> encoder.accept(v, b));
    }

    /**
     * 读一串值，超过上限直接抛。
     *
     * <h2>为什么自己读条数，不走 {@code buf.readCollection}</h2>
     *
     * 因为那个方法会<b>先把条数截断再交给分配器</b>
     * （{@code FriendlyByteBuf.readCollection}：
     * {@code collectionFactory.apply(Math.min(i, MAX_INITIAL_COLLECTION_SIZE))}，
     * 那个常量是 65536）。把上限检查写进分配器里，检查到的就是截断后的值：
     * 上限小于 65536 时碰巧还拦得住，一旦某个上限设到 65536 以上，<b>这道闸就永远不会
     * 触发</b>，而且报错信息里的条数也是假的。
     *
     * 原版自己的 {@code ByteBufCodecs.collection} 是<b>先按原始值查上限、再截断</b>
     * （{@code readCount(buf, maxSize)} 然后 {@code factory.apply(Math.min(i, 65536))}）。
     * 这里照它的顺序写，行为才与被替换掉的那套一致。
     *
     * 顺带把负数也挡了：{@code -1 > max} 为假，落到 {@code new ArrayList<>(-1)} 会抛
     * {@code IllegalArgumentException} 而不是 {@code DecoderException}。
     *
     * <h2>先检查再分配，这一条守的是 1.20.1 那一支</h2>
     *
     * 一个伪造的包报二十亿条，照着这个数直接 {@code new ArrayList<>(n)} 就能把收方的内存
     * 吃光，而那时候一个元素都还没读。<b>在这一支上这条穿不过去</b> —— 上面那个 65536
     * 的截断是 vanilla 兜着的，跟这里检不检查无关。
     *
     * 但那个截断出自 {@code net.minecraft.network.codec.ByteBufCodecs}，而那个包
     * 1.20.5 才有：<b>1.20.1 的 {@code readCollection} 拿到的是原始条数</b>，
     * 这里的检查是那一支唯一的防线。所以别因为"在这边试了试没事"就把它简化掉。
     */
    public static <T> List<T> readList(FriendlyByteBuf buf, int max,
                                       Function<FriendlyByteBuf, T> decoder) {
        int n = buf.readVarInt();
        if (n < 0 || n > max) {
            throw new DecoderException("收到的列表条数不合法（上限 " + max + "）: " + n);
        }
        // 分配容量仍然封在 65536：上限本身可能设得很大，而条数是对方说了算的
        List<T> out = new ArrayList<>(Math.min(n, MAX_INITIAL_CAPACITY));
        for (int i = 0; i < n; i++) {
            out.add(decoder.apply(buf));
        }
        return out;
    }
}
