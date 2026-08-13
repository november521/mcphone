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
 * 记事本里的一整组笔记。
 *
 * 只有这一种笔记，跟着玩家走。曾经还设想过一种"跟着手机走"的笔记，
 * 存在物品数据组件里——那条路被性能否掉了：物品组件要参与容器每 tick
 * 的快照比对，还会随任何一次容器同步整份下发，等于让玩家把并不在看的
 * 笔记一直背在背包同步链路上。
 *
 * ============================================================
 * 为什么整个是不可变的
 * ============================================================
 *
 * 每次改动都产出一份新的，而不是原地改：附件持有的那一份不该被界面或
 * 网络层就地改掉，否则谁改了它、什么时候改的，全无从追查。
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

    /** 最多存几条。再多也不是记事本该干的事了 */
    public static final int MAX_COUNT = 50;

    /** 网络传输用。条数上限在编解码器层面就封死，伪造客户端塞不进更多 */
    public static final StreamCodec<ByteBuf, NoteList> STREAM_CODEC = StreamCodec.composite(
            Note.STREAM_CODEC.apply(ByteBufCodecs.list(MAX_COUNT)),
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
     * 正文超长按上限截断而不是拒绝：玩家打了一长串字，点保存却什么都没
     * 发生是最气人的。截断至少留下了绝大部分内容。
     *
     * 新增时若已经装满，原样返回——这时该由界面告诉玩家"满了"，
     * 数据层悄悄丢掉才是真的坏。
     */
    public NoteList save(Note note) {
        String body = note.body().length() > Note.MAX_BODY_LENGTH
                ? note.body().substring(0, Note.MAX_BODY_LENGTH)
                : note.body();
        Note trimmed = new Note(note.id(), body, note.modified());

        List<Note> next = new ArrayList<>(notes);
        for (int i = 0; i < next.size(); i++) {
            if (next.get(i).id() == trimmed.id()) {
                next.set(i, trimmed);
                return new NoteList(next);
            }
        }

        if (next.size() >= MAX_COUNT) return this;

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
