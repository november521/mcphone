package com.november.mcphone.feature.reader.client;

import com.november.mcphone.api.client.app.RequiredMod;
import com.november.mcphone.core.client.PhoneApp;
import com.november.mcphone.core.client.PhoneScreen;
import com.november.mcphone.feature.reader.client.source.BookSources;
import com.november.mcphone.feature.reader.client.source.PatchouliSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 阅读 App —— 整合包里所有教程书收进一个书架。
 *
 * 它解决的是什么
 *
 * 几百个模组的整合包里，教程书有几十本，每一本都是一个物品。想查点什么就得先
 * 在仓库里翻出那本书，翻完还得放回去；出门在外想起要看，书多半不在身上。
 * 手机里这一页把它们全列出来，点一下就翻开——书本身还在原处，我们只是不再要求
 * 玩家随身带着它们。
 *
 * 翻书这件事仍然是 Patchouli 的
 *
 * 点开一本书，接管屏幕的是它自己的界面，进度、已读标记、条目锁定全对得上。
 * 我们只提供目录，理由见 {@link PatchouliSource} 的类注释。
 *
 * 预装且免费
 *
 * 它不替代任何一件实物——教程书本来就是白送的，卖它没有对应物。而且这个 App
 * 存在的意义就是"少走几步"，把它埋进商店等玩家自己发现，等于第一步就多走了。
 *
 * 贴图: assets/mcphone/textures/app/reader.png (20×20)
 */
public final class ReaderApp extends PhoneApp {

    public ReaderApp() {
        super("reader");
    }

    /**
     * 声明的前置仍然只有 Patchouli —— 那是这个 App 的主力书源，也是「设置 → 关于」
     * 和商店「联动 App」页要告诉玩家的那一个。
     *
     * 但可用性【不】按它算，见 {@link #isAvailable()}。
     */
    @Override
    public List<RequiredMod> requiredMods() {
        return List.of(new RequiredMod(
                PatchouliSource.PATCHOULI_MODID,
                Component.translatable("mcphone.compat.patchouli").getString()));
    }

    /**
     * 有任何一个书源能出书，这个 App 就该在。
     *
     * 默认实现是"前置全装了才可用"，那在只有一个书源时是对的。现在书源不止一个：
     * 一个只装了沉浸工程、没装 Patchouli 的整合包里，书城照样有一本工程师手册可看，
     * 这时候把 App 藏起来是错的。
     *
     * 代价是这种包里「关于」页会显示"Patchouli 未装"——那句话本身没说错（它确实
     * 没装），只是不再等于"这个 App 用不了"。要把商店那句「需要 %s」改成"任一"
     * 的说法，得动商店那一支的代码，那是另一个 App 的事，不在这次的范围里。
     */
    @Override
    public boolean isAvailable() {
        return BookSources.anyAvailable();
    }

    /** 与时钟、记事本一致：书架是手机内的一个模式，不另开 Screen */
    @Override
    public void onPress() {
        if (Minecraft.getInstance().screen instanceof PhoneScreen ps) {
            ps.navigateTo(PhoneScreen.Mode.READER);
        }
    }
}
