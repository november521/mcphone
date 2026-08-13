package com.november.mcphone.notes;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 一组笔记 —— 私人与本机两处共用同一个容器。
 *
 * 两者的区别只在"存在哪儿"和"能存多少"，装的东西完全一样，没必要写
 * 两份增删改。上限由调用方按 {@link NoteScope} 传进来，容器自己不预设
 * 立场。
 *
 * ============================================================
 * 为什么整个是不可变的
 * ============================================================
 *
 * 每次改动都产出一份新的，而不是原地改。这样附件与数据组件都能安心持有
 * 它——数据组件尤其要求值类型不可变，否则两个物品堆共享同一份列表时，
 * 改一个会连另一个一起改掉。
 *
 * 顺带躲开了另一个坑：Codec 解出来的集合本身就是不可变的，若按可变集合
 * 去用，读过档的世界里第一次增删就抛 UnsupportedOperationException。
 * FriendData 与 ChatData 都在这上面栽过。
 */
public record NoteList(List<Note> notes) {

    public static final NoteList EMPTY = new NoteList(List.of());

    /** 构造时就定死不可变，外面传进来什么集合都不影响 */
    public NoteList(List<Note> notes) {
        this.notes = List.copyOf(notes);
    }

    public static final Codec<NoteList> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Note.CODEC.listOf().fieldOf("notes").forGetter(NoteList::notes)
            ).apply(instance, NoteList::new)
    );

    /**
     * 网络传输用。
     *
     * 条数上限取两类里更宽的那个：编解码器是最外层的闸门，比实际上限小
     * 会截断真实数据，各传各的又要维护两个 StreamCodec。真正的条数限制
     * 在 {@link #save} 里按 scope 施加。
     */
    public static final StreamCodec<ByteBuf, NoteList> STREAM_CODEC = StreamCodec.composite(
            Note.STREAM_CODEC.apply(ByteBufCodecs.list(NoteScope.PERSONAL.maxCount)),
            NoteList::notes,
            NoteList::new
    );

    public boolean isEmpty() {
        return notes.isEmpty();
    }

    public Optional<Note> find(int id) {
        return notes.stream().filter(note -> note.id() == id).findFirst();
    }

    /**
     * 存一条笔记：id 已存在就是改，不存在就是新增。
     *
     * 正文按 scope 的上限截断而不是拒绝：玩家打了一长串字，点保存却什么
     * 都没发生是最气人的。截断至少留下了绝大部分内容。
     *
     * 新增时若已经装满，原样返回——这时该由界面告诉玩家"满了"，
     * 数据层悄悄丢掉才是真的坏。
     */
    public NoteList save(Note note, NoteScope scope) {
        String body = note.body().length() > scope.maxLength
                ? note.body().substring(0, scope.maxLength)
                : note.body();
        Note trimmed = new Note(note.id(), body, note.modified());

        List<Note> next = new ArrayList<>(notes);
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).id() == trimmed.id()) {
                next.set(i, trimmed);
                return new NoteList(next);
            }
        }

        if (next.size() >= scope.maxCount) return this;

        next.add(trimmed);
        return new NoteList(next);
    }

    /** 删一条。id 不存在时原样返回，不报错——重复点删除不该炸 */
    public NoteList delete(int id) {
        if (find(id).isEmpty()) return this;
        return new NoteList(notes.stream().filter(note -> note.id() != id).toList());
    }

    /**
     * 下一个可用的 id。
     *
     * 取当前最大值加一，而不是用条数：删掉中间几条之后，按条数生成的 id
     * 会和现存的撞上，撞上就变成"新建笔记覆盖了旧笔记"。
     */
    public int nextId() {
        return notes.stream().mapToInt(Note::id).max().orElse(0) + 1;
    }

    /** 按最后修改时间倒序 —— 刚写的排在最前，和所有备忘录一个样 */
    public List<Note> sortedByRecent() {
        return notes.stream()
                .sorted(Comparator.comparingLong(Note::modified).reversed())
                .toList();
    }
}
