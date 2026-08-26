package com.november.mcphone.feature.reader.client;

import com.november.mcphone.api.client.app.RequiredMod;
import com.november.mcphone.core.client.PhoneApp;
import com.november.mcphone.core.client.PhoneScreen;
import com.november.mcphone.feature.reader.client.source.BookSources;
import com.november.mcphone.feature.reader.client.source.ImmersiveEngineeringManual;
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
     * 两个书源模组都是【联动】，不是前置。
     *
     * 1.8.5 之前这里声明的是 requiredMods（硬前置），那在只有 Patchouli 一个
     * 书源时还算贴切；接了沉浸工程之后就成了假话——两者互不依赖，缺一个另一个
     * 照样有书可看，把 Patchouli 说成"缺了这个 App 就不可用"既不准确，也会让
     * 商店的联动页对一个根本没被它卡住的 App 写上"需要 Patchouli"。
     *
     * 声明成联动之后：「设置 → 关于」照常列出这两个模组、告诉玩家各自装没装
     * （那一页存在的理由就是回答"我怎么没有这个"），而商店的「联动 App」页
     * 不再收这个 App——它没有被任何一个模组卡住。
     */
    @Override
    public List<RequiredMod> companionMods() {
        return List.of(
                new RequiredMod(PatchouliSource.PATCHOULI_MODID,
                        Component.translatable("mcphone.compat.patchouli").getString()),
                new RequiredMod(ImmersiveEngineeringManual.MODID,
                        Component.translatable("mcphone.compat.immersiveengineering").getString()));
    }

    /**
     * 有任何一个书源能出书，这个 App 就该在。
     *
     * 默认实现是"前置全装了才可用"，而这个 App 现在一条前置都不声明——照默认走
     * 就成了"永远可用"，于是两个书源模组都没装的整合包里，主屏上会多一个点开
     * 只有空书城的 App。所以自己判：书源都出不了书，就别出现。
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
