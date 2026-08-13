package com.november.mcphone.chat;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 玩家的联系人列表与已读进度 —— 跟着玩家走的数据，存为附件。
 *
 * ============================================================
 * 为什么是不可变的
 * ============================================================
 *
 * 附件的默认值由 {@code AttachmentType.builder(() -> DEFAULT)} 提供，
 * 而 DEFAULT 是全局共享的同一个实例。如果本类可变，任何一个玩家改了
 * 自己的联系人，就会改到那个共享默认值上，所有还没有过联系人的玩家
 * 会突然全都"有了好友"——而且这种串数据的 bug 极难复现。
 *
 * 故所有修改都返回新实例，调用方用 setData 写回。
 *
 * ============================================================
 * 未读数怎么算
 * ============================================================
 *
 * 不在消息上打"已读"标记（那要改写别人会话里的消息），而是每个对端
 * 记一个"上次已读时刻"。未读数 = 该会话中晚于这个时刻的消息条数，
 * 由 {@link ChatData#countAfter} 现算。
 *
 * 好处是已读状态纯属本人私事，读消息不会去动共享的会话记录。
 *
 * 在线状态不在这里：见 {@link Contact} 的说明，那是瞬时的。
 */
public record ContactsData(List<Contact> contacts, Map<UUID, Long> lastRead) {

    /** 联系人数量上限，防止伪造客户端无限加好友把存档撑大 */
    public static final int MAX_CONTACTS = 100;

    public static final ContactsData DEFAULT = new ContactsData(List.of(), Map.of());

    public static final Codec<ContactsData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    Contact.CODEC.listOf().fieldOf("contacts").forGetter(ContactsData::contacts),
                    // 键必须编码成字符串：NBT 的复合标签只接受字符串键，
                    // 用默认的 UUIDUtil.CODEC（编成 int 数组）会写不进去
                    Codec.unboundedMap(UUIDUtil.STRING_CODEC, Codec.LONG)
                            .fieldOf("last_read").forGetter(ContactsData::lastRead)
            ).apply(instance, ContactsData::new)
    );

    public boolean hasContact(UUID who) {
        return findContact(who).isPresent();
    }

    public Optional<Contact> findContact(UUID who) {
        for (Contact c : contacts) {
            if (c.id().equals(who)) return Optional.of(c);
        }
        return Optional.empty();
    }

    /**
     * 加一个联系人。已存在或已达上限时返回自身（不产生新实例），
     * 调用方可用 {@code result == this} 判断有没有真的加上。
     */
    public ContactsData withContact(Contact who) {
        if (hasContact(who.id()) || contacts.size() >= MAX_CONTACTS) return this;

        List<Contact> next = new ArrayList<>(contacts);
        next.add(who);
        return new ContactsData(List.copyOf(next), lastRead);
    }

    /** 删一个联系人。已读进度一并清掉，免得删了又加回来时未读数不对 */
    public ContactsData withoutContact(UUID who) {
        if (!hasContact(who)) return this;

        List<Contact> nextContacts = new ArrayList<>();
        for (Contact c : contacts) {
            if (!c.id().equals(who)) nextContacts.add(c);
        }

        Map<UUID, Long> nextRead = new HashMap<>(lastRead);
        nextRead.remove(who);

        return new ContactsData(List.copyOf(nextContacts), Map.copyOf(nextRead));
    }

    /**
     * 对方在线时刷新其显示名。名字没变则返回自身，避免每次同步都
     * 产生新实例、把附件标脏引发无谓的存档写入。
     */
    public ContactsData withRefreshedName(UUID who, String currentName) {
        Optional<Contact> existing = findContact(who);
        if (existing.isEmpty() || existing.get().name().equals(currentName)) return this;

        List<Contact> next = new ArrayList<>();
        for (Contact c : contacts) {
            next.add(c.id().equals(who) ? new Contact(who, currentName) : c);
        }
        return new ContactsData(List.copyOf(next), lastRead);
    }

    /** 与某人的会话读到了哪个时刻。从未读过则返回 0，即全部算未读 */
    public long getLastRead(UUID peer) {
        return lastRead.getOrDefault(peer, 0L);
    }

    /**
     * 把与某人的会话标为读到 time 为止。
     *
     * 只前进不后退：客户端可能因为网络乱序发来一个更早的时刻，
     * 直接覆盖的话已经读过的消息会重新变成未读。
     */
    public ContactsData withLastRead(UUID peer, long time) {
        if (getLastRead(peer) >= time) return this;

        Map<UUID, Long> next = new HashMap<>(lastRead);
        next.put(peer, time);
        return new ContactsData(contacts, Map.copyOf(next));
    }
}
