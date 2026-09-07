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
 * 线格式上带上限的两样东西：一串同类的值，和一段字节。
 *
 * 为什么这一支需要这个类
 *
 * 1.21.1 那边写的是 {@code ByteBufCodecs.list(上限)} 与 {@code ByteBufCodecs.byteArray(上限)},
 * 上限由组合子自己带着，而且【收发两侧都拦】：解码时超量拒收，编码时超量也直接抛。
 * 1.20.1 的 {@link FriendlyByteBuf} 没有这套组合子，只有
 * {@code readCollection(分配器, 读一个)} 与 {@code writeCollection(集合, 写一个)}——
 * 上限得自己写，而且【写的那一侧根本没有地方写】。
 *
 * 于是移植过来的六处各自手写了一份"读之前检查"的分配器，长得都一样、缩进各不相同，
 * 而且六处【无一】拦编码那一侧。收进这里之后，每一处都缩成一行，两侧的上限也回来了。
 *
 * 为什么编码那一侧也要拦
 *
 * 编码超量与解码超量是同一个 bug 的两头，但发作的地方差得远：
 *
 *   拦了编码   发的那一端当场抛，日志里指着发包的那一行——是谁攒出这么大一串，一眼看得见
 *   不拦编码   包照发，由【收的那一端】解码时抛。netty 的解码异常等于断开这条连接，
 *              于是症状是"对方莫名其妙掉线"，而错误出在发的那一端，两边日志都指不到人
 *
 * 也就是说，漏掉编码那一侧的检查不是"少一道保险"，而是把一次本可以就地定位的错误
 * 挪到了另一台机器上，还顺手踢掉一个无辜的玩家。
 *
 * 上限本身仍然由调用方给：它是业务上的数（好友多少人、每对会话留多少条），
 * 属于那个功能，不属于这里。
 */
public final class Wire {

    private Wire() {}

    /**
     * 写一串值，超过上限直接抛。
     *
     * encoder 的方向是 {@code (值, buf)}——与本仓所有 encode 方法一致，所以
     * {@code ChatMessage::encode} 这样的方法引用可以直接传进来。
     * （{@code FriendlyByteBuf.writeCollection} 自己那个 Writer 是反过来的
     * {@code (buf, 值)}，照它的方向写，本仓每个包的编码函数都得多包一层 lambda。）
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
     * 【检查发生在分配之前】，这是这个方法唯一要紧的地方：先照着报上来的条数
     * new 一个 ArrayList 再检查的话，一个伪造的包写个 20 亿就能把收方的内存吃光，
     * 而那时候还一个字节都没读。
     */
    public static <T> List<T> readList(FriendlyByteBuf buf, int max,
                                       Function<FriendlyByteBuf, T> decoder) {
        return buf.readCollection(n -> {
            if (n > max) throw new DecoderException("收到的列表超过上限 " + max + ": " + n);
            return new ArrayList<>(n);
        }, decoder::apply);
    }

    /** 写一段字节，超过上限直接抛。理由同 {@link #writeList} */
    public static void writeBytes(FriendlyByteBuf buf, byte[] data, int max) {
        if (data.length > max) {
            throw new EncoderException("要发的字节数超过上限 " + max + ": " + data.length);
        }
        buf.writeByteArray(data);
    }

    /** 读一段字节。上限由 {@code readByteArray} 自己拦，同样在分配之前 */
    public static byte[] readBytes(FriendlyByteBuf buf, int max) {
        return buf.readByteArray(max);
    }
}
