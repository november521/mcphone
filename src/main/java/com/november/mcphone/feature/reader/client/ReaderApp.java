package com.november.mcphone.feature.reader.client;

import com.november.mcphone.api.client.app.RequiredMod;
import com.november.mcphone.core.client.PhoneApp;
import com.november.mcphone.core.client.PhoneScreen;
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
     * 声明前置而不是覆盖 isAvailable()："可用性"与"缺什么"必须是同一个来源，
     * 「设置 → 关于」那一页就是靠这个汇总出来的。
     *
     * 将来接第二种书源（别的手册模组、玩家自己传的书）时，这里要跟着改成
     * "有任何一个书源可用"——那时候只装了另一个手册模组的玩家不该看不见这个 App。
     * 眼下只有一个书源，写死它最诚实。
     */
    @Override
    public List<RequiredMod> requiredMods() {
        return List.of(new RequiredMod(
                PatchouliSource.PATCHOULI_MODID,
                Component.translatable("mcphone.compat.patchouli").getString()));
    }

    /** 与时钟、记事本一致：书架是手机内的一个模式，不另开 Screen */
    @Override
    public void onPress() {
        if (Minecraft.getInstance().screen instanceof PhoneScreen ps) {
            ps.navigateTo(PhoneScreen.Mode.READER);
        }
    }
}
