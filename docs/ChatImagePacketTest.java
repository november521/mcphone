package com.november.mcphone.feature.chat.net;

import com.november.mcphone.feature.chat.ChatImage;
import com.november.mcphone.feature.chat.ImageBody;
import com.november.mcphone.feature.chat.MessageBody;
import com.november.mcphone.feature.chat.TextBody;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 图片那三个包与会话摘要的线格式测试 —— 这一支【自己写的】那一层。
 *
 * 为什么这一支特别需要它
 *
 * 1.21.1 那边这几个包的编解码是 {@code StreamCodec.composite(...)} 拼出来的：
 * 字段顺序、上限、Optional 的写法全由组合子保证，读写两侧【是同一份声明】，
 * 想写反都难。1.20.1 没有那套东西，这几个包的 encode / decode 是【两段各写一遍的
 * 手写代码】——两段之间没有任何东西保证它们对得上。
 *
 * 写反的症状不是报错，是字段值乱七八糟：把宽写成高、把 frames 与 frameMs 调个个儿，
 * 编译过、发得出、收得到，只是对方看到的图比例不对或者动画速度不对。所以这一层
 * 必须有往返测试，而那一支不必。
 *
 * 顺带守着 Wire 的两侧上限：编码那一侧漏了的话，超量的包会照发，由【收方】解码时抛，
 * 而 netty 的解码异常等于断开连接——症状是对方莫名其妙掉线，错却在发的那一端。
 *
 * 跑法（要挂 Forge 的编译类路径）：见 docs/PORTING.md 的"复现这套筛法"一节，
 * 把 out 目录和本文件一起编了即可。
 */
public class ChatImagePacketTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void check(boolean cond, String what) {
        checks++;
        if (!cond) failures.add(what);
    }

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) {
            failures.add(what + "  期望 " + expected + "，实际 " + actual);
        }
    }

    /** 编码 → 解码 → 缓冲区必须正好读空。多一个字节都说明两侧对不上 */
    static <T> T roundTrip(T value, java.util.function.BiConsumer<T, FriendlyByteBuf> encode,
                           java.util.function.Function<FriendlyByteBuf, T> decode, String what) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        encode.accept(value, buf);
        T back = decode.apply(buf);
        eq(buf.readableBytes(), 0, what + " 解完应当正好读空");
        return back;
    }

    /** 期望这一段抛出某种异常。抛不出来才是问题 —— 那说明上限根本没生效 */
    static void expectThrow(Class<? extends Throwable> type, Runnable body, String what) {
        checks++;
        try {
            body.run();
            failures.add(what + "  期望抛 " + type.getSimpleName() + "，实际什么都没抛");
        } catch (Throwable t) {
            if (!type.isInstance(t)) {
                failures.add(what + "  期望抛 " + type.getSimpleName()
                        + "，实际抛的是 " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        UUID peer = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID image = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa");

        sendChatImage(peer);
        requestChatImage(peer, image);
        chatImageData(image);
        conversationSummary(peer, image);

        if (failures.isEmpty()) {
            System.out.println("全部通过（" + checks + " 项）");
        } else {
            System.out.println("失败 " + failures.size() + " / " + checks + " 项：");
            failures.forEach(f -> System.out.println("  " + f));
            System.exit(1);
        }
    }

    //  SendChatImagePacket —— 八个字段，最容易把顺序写岔的一个

    private static void sendChatImage(UUID peer) {
        // 八个字段刻意各取不同的值：写反了任意两个，下面的逐字段比对就会抓到。
        // 全填一样的数（比如都填 1）的话，顺序错了照样"往返成功"
        byte[] chunk = bytes(1234, 7);
        SendChatImagePacket sent = new SendChatImagePacket(peer, 320, 180, 12, 60, 3, 5, chunk);

        SendChatImagePacket back = roundTrip(sent, SendChatImagePacket::encode,
                SendChatImagePacket::decode, "SendChatImagePacket");

        eq(back.target(), peer, "发图包的收件人");
        eq(back.width(), 320, "发图包的宽");
        eq(back.height(), 180, "发图包的高");
        eq(back.frames(), 12, "发图包的帧数");
        eq(back.frameMs(), 60, "发图包的每帧延迟");
        eq(back.chunkIndex(), 3, "发图包的片号");
        eq(back.chunkCount(), 5, "发图包的总片数");
        check(Arrays.equals(back.chunk(), chunk), "发图包的那一片字节");

        // 空片：最后一片正好切在边界上时会出现，不能当成坏包
        SendChatImagePacket empty = new SendChatImagePacket(peer, 1, 1, 1, 0, 0, 1, new byte[0]);
        check(Arrays.equals(roundTrip(empty, SendChatImagePacket::encode,
                SendChatImagePacket::decode, "空片").chunk(), new byte[0]), "空片应当原样回来");

        // 正好顶到上限的一片必须过 —— 卡在上限【之内】的东西被拒收，是最难查的一类 off-by-one
        byte[] full = bytes(ChatImage.CHUNK_BYTES, 3);
        SendChatImagePacket max = new SendChatImagePacket(peer, 1, 1, 1, 0, 0, 1, full);
        check(Arrays.equals(roundTrip(max, SendChatImagePacket::encode,
                        SendChatImagePacket::decode, "满片").chunk(), full),
                "正好 CHUNK_BYTES 的一片应当收得下");

        // 超一个字节：两侧都要拦。只拦解码那一侧的话，挨罚的是收件人
        byte[] tooBig = bytes(ChatImage.CHUNK_BYTES + 1, 3);
        SendChatImagePacket over = new SendChatImagePacket(peer, 1, 1, 1, 0, 0, 1, tooBig);
        expectThrow(EncoderException.class,
                () -> SendChatImagePacket.encode(over, new FriendlyByteBuf(Unpooled.buffer())),
                "超长的一片在编码时就该被拦下");
        expectThrow(DecoderException.class, () -> {
            FriendlyByteBuf raw = new FriendlyByteBuf(Unpooled.buffer());
            raw.writeUUID(peer);
            for (int i = 0; i < 6; i++) raw.writeVarInt(1);
            raw.writeByteArray(tooBig);          // 绕过我们的编码，模拟伪造客户端
            SendChatImagePacket.decode(raw);
        }, "超长的一片在解码时也该被拒收");
    }

    //  RequestChatImagePacket —— 一串 UUID，上限 MAX_IDS

    private static void requestChatImage(UUID peer, UUID image) {
        List<UUID> ids = List.of(image, UUID.nameUUIDFromBytes("b".getBytes()));
        RequestChatImagePacket back = roundTrip(new RequestChatImagePacket(peer, ids),
                RequestChatImagePacket::encode, RequestChatImagePacket::decode,
                "RequestChatImagePacket");
        eq(back.peer(), peer, "取图包的对端");
        eq(back.images(), ids, "取图包要的那几张");

        // 空列表：界面上一张都不缺时不该发包，但真发了也得读得回来
        eq(roundTrip(new RequestChatImagePacket(peer, List.of()),
                RequestChatImagePacket::encode, RequestChatImagePacket::decode,
                "空的取图包").images(), List.of(), "空列表应当原样回来");

        // 正好 MAX_IDS 张必须过
        List<UUID> full = new ArrayList<>();
        for (int i = 0; i < RequestChatImagePacket.MAX_IDS; i++) {
            full.add(UUID.nameUUIDFromBytes(("id" + i).getBytes()));
        }
        eq(roundTrip(new RequestChatImagePacket(peer, full),
                        RequestChatImagePacket::encode, RequestChatImagePacket::decode,
                        "满额的取图包").images(),
                full, "正好 MAX_IDS 张应当收得下");

        // 多一张：编码那一侧就要拦。这是个 C2S 包 —— 漏了的话，服务端解码时抛，
        // 而那等于把发包的玩家自己踢下线，还查不到是哪一步的错
        List<UUID> over = new ArrayList<>(full);
        over.add(UUID.nameUUIDFromBytes("one-too-many".getBytes()));
        expectThrow(EncoderException.class,
                () -> RequestChatImagePacket.encode(new RequestChatImagePacket(peer, over),
                        new FriendlyByteBuf(Unpooled.buffer())),
                "超过 MAX_IDS 张在编码时就该被拦下");
        expectThrow(DecoderException.class, () -> {
            FriendlyByteBuf raw = new FriendlyByteBuf(Unpooled.buffer());
            raw.writeUUID(peer);
            raw.writeCollection(over, FriendlyByteBuf::writeUUID);
            RequestChatImagePacket.decode(raw);
        }, "超过 MAX_IDS 张在解码时也该被拒收");
    }

    //  ChatImageDataPacket —— 像素本体，空数组另有含义

    private static void chatImageData(UUID image) {
        byte[] png = bytes(40_000, 11);
        ChatImageDataPacket back = roundTrip(new ChatImageDataPacket(image, png),
                ChatImageDataPacket::encode, ChatImageDataPacket::decode, "ChatImageDataPacket");
        eq(back.image(), image, "回图包的图片 id");
        check(Arrays.equals(back.data(), png), "回图包的像素");

        // 【空数组是有含义的】：这张图没了。客户端靠它把气泡改成「已过期」并且不再问第二次，
        // 所以它必须原样活过一次往返，不能被当成"没数据"给吞掉
        ChatImageDataPacket gone = roundTrip(ChatImageDataPacket.gone(image),
                ChatImageDataPacket::encode, ChatImageDataPacket::decode, "图没了");
        eq(gone.image(), image, "「图没了」也要带着 id");
        eq(gone.data().length, 0, "「图没了」的数据必须是空数组");

        // 上限那一头
        byte[] over = bytes(ChatImage.MAX_BYTES_CEILING + 1, 5);
        expectThrow(EncoderException.class,
                () -> ChatImageDataPacket.encode(new ChatImageDataPacket(image, over),
                        new FriendlyByteBuf(Unpooled.buffer())),
                "超过上限的一张图在编码时就该被拦下");
    }

    //  ConversationSummary —— 唯一带 Optional 的那个，而且正文是分派出去的

    private static void conversationSummary(UUID id, UUID image) {
        // 还没聊过：last 是空。writeOptional / readOptional 写反了它就会变成"有一条空正文"
        ConversationSummary empty = roundTrip(ConversationSummary.empty(id, "阿狸", true),
                ConversationSummary::encode, ConversationSummary::decode, "没聊过的会话");
        eq(empty.id(), id, "会话摘要的 id");
        eq(empty.name(), "阿狸", "会话摘要的名字");
        eq(empty.online(), true, "会话摘要的在线状态");
        eq(empty.last(), Optional.empty(), "没聊过的会话，last 必须是空");
        eq(empty.lastTime(), 0L, "没聊过的会话，时间必须是 0");
        eq(empty.unread(), 0, "没聊过的会话，未读必须是 0");

        // 最后一条是文本
        ConversationSummary text = roundTrip(
                new ConversationSummary(id, "阿狸", false,
                        Optional.of(new TextBody("在吗")), 1_700_000_000_000L, 7),
                ConversationSummary::encode, ConversationSummary::decode, "文本收尾的会话");
        eq(text.last(), Optional.of(new TextBody("在吗")), "文本正文应当原样回来");
        eq(text.lastTime(), 1_700_000_000_000L, "会话摘要的时间");
        eq(text.unread(), 7, "会话摘要的未读数");

        // 最后一条是图 —— 这一条同时验到 MessageBody 的种类分派：正文换了个实现类，
        // 而摘要这一层一个字都没改
        MessageBody photo = new ImageBody(image, 256, 144, 24, 60);
        ConversationSummary pic = roundTrip(
                new ConversationSummary(id, "阿狸", true, Optional.of(photo), 1L, 0),
                ConversationSummary::encode, ConversationSummary::decode, "图片收尾的会话");
        eq(pic.last(), Optional.of(photo), "图片正文应当原样回来");

        // 名字上限：clampName 之后必须发得出去（32 字符最多 96 字节，在 writeUtf 的上限内）
        String longName = "名".repeat(ConversationSummary.MAX_NAME_LENGTH);
        eq(roundTrip(ConversationSummary.empty(id, longName, false),
                        ConversationSummary::encode, ConversationSummary::decode, "长名字")
                        .name(),
                longName, "顶到上限的名字应当发得出去");
        eq(ConversationSummary.clampName("名".repeat(100)).length(),
                ConversationSummary.MAX_NAME_LENGTH, "过长的名字应当被截到上限");
    }

    /** 造一段可复现的假数据。全零的字节数组测不出"少写了几个字节" */
    private static byte[] bytes(int length, int seed) {
        byte[] out = new byte[length];
        for (int i = 0; i < length; i++) out[i] = (byte) (i * seed + 1);
        return out;
    }
}
