package com.november.mcphone.platform.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxSong;

/**
 * 一张唱片的曲名。
 *
 * <h2>为什么要有这个门面</h2>
 *
 * 1.21 把唱片曲目抽成了注册表里的 {@code JukeboxSong}，要过
 * {@code level.registryAccess()} 才查得到；1.20.1 上没有这个东西，曲名就挂在
 * {@code RecordItem} 自己身上（{@code getDisplayName()}），不需要世界也不需要注册表。
 *
 * <h2>这个门面只救 MusicPage，别拿它去救 DiscService</h2>
 *
 * {@code MusicPage} 要的只是一个 {@link String}，包一层就够。
 * 而 {@code DiscService} 里「唱片曲目」已经渗进了局部变量类型
 * （{@code Optional&lt;Holder&lt;JukeboxSong&gt;&gt;} 对 {@code Optional&lt;RecordItem&gt;}），
 * 要脱钩得先抽一个中立的曲目句柄（曲长 ticks + 描述 + 在不在），那是另一件事。
 */
public final class DiscSongs {

    private DiscSongs() {}

    /** 优先曲子的名称（"C418 - cat"），取不到才退回物品名。 */
    public static String title(ItemStack disc) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            var song = JukeboxSong.fromStack(mc.level.registryAccess(), disc);
            if (song.isPresent()) return song.get().value().description().getString();
        }
        return disc.getHoverName().getString();
    }
}
