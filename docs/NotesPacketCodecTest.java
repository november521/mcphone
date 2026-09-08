package com.november.mcphone.feature.notes.net;

import com.november.mcphone.feature.notes.Note;
import com.november.mcphone.feature.notes.NoteList;
import com.november.mcphone.feature.notes.NoteSummary;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 记事本那一组包的线格式断言 —— 守的是「换编解码写法不会换线格式」。
 *
 * <h2>这份测试是为什么写的</h2>
 *
 * 这一组包的编解码正要从组合子（{@code StreamCodec.composite}）换成手写的
 * {@code encode} / {@code decode}，为的是让 1.20.1 那一支能用同一份代码 ——
 * 那边没有 {@code StreamCodec} 与 {@code ByteBufCodecs}。
 *
 * <b>换写法不许换字节。</b>而组合子与手写代码之间最容易出的岔子恰恰是无声的：
 * 字段顺序调个个儿、VarInt 写成 Int、字符串上限从字符数写成字节数 —— 编得过、
 * 发得出、收得到，只是字段值对不上。收发两侧一起改的话，自己跟自己还能对上，
 * <b>跟旧版本的客户端就对不上了</b>。
 *
 * 所以这里断言的是<b>字节序列本身</b>，不是「自己和自己往返」。这份测试先对着
 * 组合子那一版跑绿，再对着手写那一版跑，两次都绿才说明格式没动。
 *
 * <h2>各包的线格式</h2>
 *
 * <pre>
 * RequestNoteList   （空包，零字节）
 * RequestNote       VarInt id
 * DeleteNote        VarInt id
 * PrintNote         VarInt id
 * SaveNote          VarInt id, Utf8(2000) body
 * Note              VarInt id, Utf8(2000) body, VarLong modified
 * NoteSummary       VarInt id, Utf8(40) title, Utf8(60) preview, VarLong modified
 * SyncNote          Note
 * SyncNoteList      VarInt 条数(≤50), 然后逐个 NoteSummary
 * </pre>
 */
public class NotesPacketCodecTest {

    static int checks = 0;
    static final List<String> failures = new ArrayList<>();

    static void eq(Object actual, Object expected, String what) {
        checks++;
        if (!java.util.Objects.equals(actual, expected)) {
            failures.add(what + "  期望 " + expected + "，实际 " + actual);
        }
    }

    static <T> byte[] bytes(StreamCodec<? super FriendlyByteBuf, T> codec, T value) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        codec.encode(buf, value);
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }

    static <T> void eqBytes(StreamCodec<? super FriendlyByteBuf, T> codec, T value, byte[] expected, String what) {
        checks++;
        byte[] actual = bytes(codec, value);
        if (!Arrays.equals(actual, expected)) {
            failures.add(what + "  期望 " + Arrays.toString(expected)
                    + "，实际 " + Arrays.toString(actual));
        }
    }

    static <T> void roundTrip(StreamCodec<? super FriendlyByteBuf, T> codec, T value, String what) {
        checks++;
        T back = codec.decode(new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes(codec, value))));
        if (!value.equals(back)) {
            failures.add(what + "  往返之后变了：" + value + " -> " + back);
        }
    }

    /** 喂一段伪造的字节，断言解码侧拒收（而且抛的是 DecoderException，不是别的） */
    static <T> void decodeRejects(StreamCodec<? super FriendlyByteBuf, T> codec,
                                  byte[] raw, String what) {
        checks++;
        try {
            T got = codec.decode(new FriendlyByteBuf(Unpooled.wrappedBuffer(raw)));
            failures.add(what + "  应当拒收，实际解出了 " + got);
        } catch (DecoderException expected) {
            // 正是要的：netty 会把它当成解码失败处理
        } catch (RuntimeException other) {
            failures.add(what + "  抛的是 " + other.getClass().getSimpleName()
                    + " 而不是 DecoderException：" + other.getMessage());
        }
    }

    /**
     * 编完之后缓冲区必须正好读空，多写或少写都算错。
     *
     * ⚠ 它只报得出"读少了"：读多了会在 {@code decode} 里直接抛 IndexOutOfBounds，
     * 测试是崩而不是记一条具名失败。构建照样红，只是错误信息没那么好看。
     */
    static <T> void exact(StreamCodec<? super FriendlyByteBuf, T> codec, T value, String what) {
        checks++;
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        codec.encode(buf, value);
        codec.decode(buf);
        if (buf.readableBytes() != 0) {
            failures.add(what + "  解完还剩 " + buf.readableBytes() + " 字节没读");
        }
    }

    public static void main(String[] args) {

        //  一、上限常量：它们进线格式（字符串的长度前缀、列表的条数上限），改一个就是协议变更
        eq(Note.MAX_BODY_LENGTH, 2000, "Note.MAX_BODY_LENGTH");
        eq(NoteSummary.MAX_TITLE_LENGTH, 40, "NoteSummary.MAX_TITLE_LENGTH");
        eq(NoteSummary.MAX_PREVIEW_LENGTH, 60, "NoteSummary.MAX_PREVIEW_LENGTH");
        eq(NoteList.MAX_COUNT, 50, "NoteList.MAX_COUNT");

        //  二、逐字节钉死
        // 空包：一个字节都不该写。写了的话旧客户端会把多出来的字节当成下一个包的开头
        eqBytes(RequestNoteListPacket.STREAM_CODEC, new RequestNoteListPacket(),
                new byte[]{}, "RequestNoteList 是零字节");

        // 单个 VarInt 的三个包，字节应当完全一样
        eqBytes(RequestNotePacket.STREAM_CODEC, new RequestNotePacket(7),
                new byte[]{7}, "RequestNote(7)");
        eqBytes(DeleteNotePacket.STREAM_CODEC, new DeleteNotePacket(7),
                new byte[]{7}, "DeleteNote(7)");
        eqBytes(PrintNotePacket.STREAM_CODEC, new PrintNotePacket(7),
                new byte[]{7}, "PrintNote(7)");
        // 300 = 0b1_0010_1100，VarInt 两字节：低七位置续位 -> 0xAC，高位 -> 0x02
        eqBytes(RequestNotePacket.STREAM_CODEC, new RequestNotePacket(300),
                new byte[]{(byte) 0xAC, 0x02}, "RequestNote(300) 跨字节 VarInt");

        // 字符串是「VarInt 长度 + UTF-8 字节」
        eqBytes(SaveNotePacket.STREAM_CODEC, new SaveNotePacket(1, "hi"),
                new byte[]{1, 2, 'h', 'i'}, "SaveNote(1, \"hi\")");
        // 字段顺序：id 在前、body 在后。调过来这一条会红
        eqBytes(SaveNotePacket.STREAM_CODEC, new SaveNotePacket(2, "a"),
                new byte[]{2, 1, 'a'}, "SaveNote 的字段顺序是 id 在前");

        // Note：VarInt, 字符串, VarLong
        eqBytes(Note.STREAM_CODEC, new Note(7, "hi", 1L),
                new byte[]{7, 2, 'h', 'i', 1}, "Note(7, \"hi\", 1)");
        // modified 是 VarLong 不是 VarInt —— 写成 VarInt 时小数值看不出区别，
        // 这一条用一个超过 int 范围的真实时间戳把它钉住
        // 1_700_000_000_000 的 VarLong：见下面逐字节
        eqBytes(Note.STREAM_CODEC, new Note(0, "", 1_700_000_000_000L),
                new byte[]{0, 0, (byte) 0x80, (byte) 0xD0, (byte) 0x95, (byte) 0xFF,
                           (byte) 0xBC, 0x31},
                "Note 的 modified 是 VarLong");

        // NoteSummary：四个字段，两个字符串挨着，顺序不能换
        eqBytes(NoteSummary.STREAM_CODEC, new NoteSummary(3, "t", "p", 300L),
                new byte[]{3, 1, 't', 1, 'p', (byte) 0xAC, 0x02}, "NoteSummary(3,t,p,300)");

        // 列表：VarInt 条数在前，然后逐个
        eqBytes(SyncNoteListPacket.STREAM_CODEC,
                new SyncNoteListPacket(List.of()),
                new byte[]{0}, "SyncNoteList 空列表只有一个 0");
        eqBytes(SyncNoteListPacket.STREAM_CODEC,
                new SyncNoteListPacket(List.of(new NoteSummary(1, "a", "b", 0L))),
                new byte[]{1, 1, 1, 'a', 1, 'b', 0}, "SyncNoteList 一条");
        eqBytes(SyncNoteListPacket.STREAM_CODEC,
                new SyncNoteListPacket(List.of(
                        new NoteSummary(1, "a", "b", 0L),
                        new NoteSummary(2, "c", "d", 0L))),
                new byte[]{2, 1, 1, 'a', 1, 'b', 0, 2, 1, 'c', 1, 'd', 0}, "SyncNoteList 两条");

        eqBytes(SyncNotePacket.STREAM_CODEC, new SyncNotePacket(new Note(9, "x", 2L)),
                new byte[]{9, 1, 'x', 2}, "SyncNote 就是一个 Note");

        //  三、往返 + 「不多写不少写」
        Note note = new Note(42, "第一行\n第二行", 1_700_000_000_000L);
        roundTrip(Note.STREAM_CODEC, note, "Note 带换行与中文");
        exact(Note.STREAM_CODEC, note, "Note");

        NoteSummary summary = new NoteSummary(42, "标题", "预览", 1_700_000_000_000L);
        roundTrip(NoteSummary.STREAM_CODEC, summary, "NoteSummary 中文");
        exact(NoteSummary.STREAM_CODEC, summary, "NoteSummary");

        roundTrip(RequestNoteListPacket.STREAM_CODEC, new RequestNoteListPacket(), "RequestNoteList");
        exact(RequestNoteListPacket.STREAM_CODEC, new RequestNoteListPacket(), "RequestNoteList");

        for (int id : new int[]{0, 1, 127, 128, 300, 65535, Integer.MAX_VALUE}) {
            roundTrip(RequestNotePacket.STREAM_CODEC, new RequestNotePacket(id), "RequestNote " + id);
            roundTrip(DeleteNotePacket.STREAM_CODEC, new DeleteNotePacket(id), "DeleteNote " + id);
            roundTrip(PrintNotePacket.STREAM_CODEC, new PrintNotePacket(id), "PrintNote " + id);
        }

        roundTrip(SaveNotePacket.STREAM_CODEC, new SaveNotePacket(1, ""), "SaveNote 空正文");
        roundTrip(SaveNotePacket.STREAM_CODEC, new SaveNotePacket(1, "带\n换行的\n正文"), "SaveNote 多行");
        exact(SaveNotePacket.STREAM_CODEC, new SaveNotePacket(1, "abc"), "SaveNote");

        roundTrip(SyncNotePacket.STREAM_CODEC, new SyncNotePacket(note), "SyncNote");
        exact(SyncNotePacket.STREAM_CODEC, new SyncNotePacket(note), "SyncNote");

        List<NoteSummary> full = new ArrayList<>();
        for (int i = 0; i < NoteList.MAX_COUNT; i++) {
            full.add(new NoteSummary(i, "标题" + i, "预览" + i, i));
        }
        roundTrip(SyncNoteListPacket.STREAM_CODEC, new SyncNoteListPacket(full), "SyncNoteList 满 50 条");
        exact(SyncNoteListPacket.STREAM_CODEC, new SyncNoteListPacket(full), "SyncNoteList 满 50 条");

        //  四、上限：编码侧
        // 列表超量：编码这一侧就该抛，别让它到对面才炸 —— netty 的解码异常等于断开连接，
        // 那时错误出在发的那一端，两边日志都指不到人
        List<NoteSummary> over = new ArrayList<>(full);
        over.add(new NoteSummary(99, "多出来的", "第 51 条", 0L));
        checks++;
        try {
            bytes(SyncNoteListPacket.STREAM_CODEC, new SyncNoteListPacket(over));
            failures.add("列表 51 条应当在编码时就被拦下，实际没抛");
        } catch (RuntimeException expected) {
            // 意料之中
        }

        // 字符串超量同理。上限按【字符数】算，另有一道 3 倍的字节天花板
        checks++;
        try {
            bytes(SaveNotePacket.STREAM_CODEC,
                    new SaveNotePacket(1, "x".repeat(Note.MAX_BODY_LENGTH + 1)));
            failures.add("正文超过 2000 字符应当在编码时被拦下，实际没抛");
        } catch (RuntimeException expected) {
            // 意料之中
        }
        roundTrip(SaveNotePacket.STREAM_CODEC,
                new SaveNotePacket(1, "x".repeat(Note.MAX_BODY_LENGTH)), "正文正好 2000 字符");

        //  五、上限：解码侧 —— 往返测不出这一半
        //
        // 收发两侧一起写错时，往返照样绿；而伪造的包只走解码这一侧。这几条喂的是手拼的
        // 字节，不经过我们的编码器。
        decodeRejects(SyncNoteListPacket.STREAM_CODEC, new byte[]{51},
                "条数 51 超过上限 50，解码侧应拒收");
        decodeRejects(SyncNoteListPacket.STREAM_CODEC,
                new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x0F},
                "条数 -1（VarInt 全 F）应当是 DecoderException，不是 IllegalArgumentException");
        // 十亿条：这一条是那句"先检查再分配"的实证 —— 拦不住的话，
        // 收方会照着这个数去分配，而此时一个元素都还没读
        decodeRejects(SyncNoteListPacket.STREAM_CODEC,
                new byte[]{(byte) 0x80, (byte) 0x94, (byte) 0xEB, (byte) 0xDC, 0x03},
                "条数 10 亿应当在分配之前就被拦下");
        // 上限刚好那一条要收得下：50 条之后没有内容，所以会因为读不到元素而抛，
        // 但【不能】是"超过上限"那种拒收 —— 这一条把 51 与 50 的边界钉住
        checks++;
        try {
            SyncNoteListPacket.STREAM_CODEC.decode(
                    new FriendlyByteBuf(Unpooled.wrappedBuffer(new byte[]{50})));
            failures.add("条数 50 后面没内容，应当因读不到元素而抛");
        } catch (DecoderException | IndexOutOfBoundsException expected) {
            // 意料之中：50 没超上限，于是开始读元素，缓冲区空了
        }

        // 字符串上限同理：长度前缀报 2001，解码侧该拒收
        byte[] tooLongUtf = new byte[]{1, (byte) 0xD1, 0x0F};   // id=1, 长度前缀 2001
        decodeRejects(SaveNotePacket.STREAM_CODEC, tooLongUtf,
                "正文长度前缀 2001 超过上限，解码侧应拒收");

        //  收尾
        if (failures.isEmpty()) {
            System.out.println("全部通过（" + checks + " 项）");
        } else {
            System.out.println("失败 " + failures.size() + " / " + checks + " 项：");
            failures.forEach(f -> System.out.println("  " + f));
            System.exit(1);
        }
    }
}
