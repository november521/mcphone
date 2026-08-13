package com.november.mcphone.chat;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.UUIDUtil;

import java.util.UUID;

/**
 * 一个联系人。
 *
 * ============================================================
 * 为什么名字要存下来
 * ============================================================
 *
 * 联系人离线时，服务端手上只有一个 UUID。要显示"是谁"有两条路：
 *
 *   1. 靠服务端资料缓存（getProfileCache）反查——缓存可能被清空，
 *      某些配置下压根拿不到，届时界面上就是一串 UUID
 *   2. 加好友时把当时的名字一并存下来
 *
 * 选 2：离线也认得出是谁，不依赖任何缓存。代价是对方改名后、在下次
 * 上线之前会显示旧名——对方一上线就会被刷新，可以接受。
 *
 * 在线状态【不存】在这里：那是瞬时的，由同步包现算现发。存下来的话
 * 一旦服务端崩溃重启，存档里就会留下一堆永远"在线"的幽灵。
 *
 * @param id   玩家 UUID，唯一标识。玩家可以改名，UUID 不会变
 * @param name 加为联系人时的玩家名，对方在线时会被刷新
 */
public record Contact(UUID id, String name) {

    public static final Codec<Contact> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    UUIDUtil.CODEC.fieldOf("id").forGetter(Contact::id),
                    Codec.STRING.fieldOf("name").forGetter(Contact::name)
            ).apply(instance, Contact::new)
    );
}
