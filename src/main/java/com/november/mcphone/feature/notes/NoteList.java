package com.november.mcphone.feature.notes;

import io.netty.handler.codec.DecoderException;
import net.minecraft.network.FriendlyByteBuf;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 记事本里的一整组笔记，跟着玩家走。整体不可变：每次改动产出新实例；
 * Codec 解出来的集合本身也是不可变的，按可变集合用会抛 UnsupportedOperationException。
 */
public record NoteList(List<Note> notes) {

    public static final NoteList EMPTY = new NoteList(List.of());

    public NoteList(List<Note> notes) {
        this.notes = List.copyOf(notes);
    }

    public static final Codec<NoteList> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Note.CODEC.listOf().fieldOf("notes").forGetter(NoteList::notes)
            ).apply(instance, NoteList::new)
    );

    public static final int MAX_COUNT = 50;

    /** 条数上限在编解码器层面封死，伪造客户端塞不进更多 */
    public static void encode(NoteList msg, FriendlyByteBuf buf) {
        buf.writeCollection(msg.notes(), (b, v) -> Note.encode(v, b));
    }

    public static NoteList decode(FriendlyByteBuf buf) {
        return new NoteList(
                buf.readCollection(n -> {
            if (n > MAX_COUNT) throw new DecoderException("列表超过上限 MAX_COUNT: " + n);
            return new java.util.ArrayList<>(n);
        }, Note::decode));
    }

    public boolean isEmpty() {
        return notes.isEmpty();
    }

    public Optional<Note> find(int id) {
        return notes.stream().filter(note -> note.id() == id).findFirst();
    }

    /** id 已存在就是改，否则新增；正文超长截断；新增时已满则原样返回 this，由界面提示 */
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

    /** id 不存在时原样返回 this */
    public NoteList delete(int id) {
        if (find(id).isEmpty()) return this;
        return new NoteList(notes.stream().filter(note -> note.id() != id).toList());
    }

    /** 取最大值加一而不是条数：删掉中间几条后按条数生成会撞号 */
    public int nextId() {
        return notes.stream().mapToInt(Note::id).max().orElse(0) + 1;
    }

    public List<Note> sortedByRecent() {
        return notes.stream()
                .sorted(Comparator.comparingLong(Note::modified).reversed())
                .toList();
    }
}
