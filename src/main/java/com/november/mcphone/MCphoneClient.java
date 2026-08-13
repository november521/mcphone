package com.november.mcphone;

import com.november.mcphone.client.CameraHandler;
import com.november.mcphone.client.ChatNotifier;
import com.november.mcphone.client.MCphoneKeyBindings;
import com.november.mcphone.client.PhoneKeyHandler;
import com.november.mcphone.gui.PhoneContainerScreen;
import com.november.mcphone.gui.PhoneScreenRegistry;
import com.november.mcphone.gui.PhoneSkin;
import com.november.mcphone.gui.WallpaperStore;
import com.november.mcphone.menu.ModMenus;
import com.november.mcphone.network.chat.ChatClientCache;
import com.november.mcphone.network.notes.NotesClientCache;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = MCphone.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = MCphone.MODID, value = Dist.CLIENT)
public class MCphoneClient {

    public MCphoneClient(ModContainer container, IEventBus modEventBus) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        // 按键注册是【模组总线】事件，显式挂载而不依赖注解自动路由
        modEventBus.addListener(MCphoneKeyBindings::register);

        // 相机模式的监听都在【游戏总线】，同样显式挂载
        NeoForge.EVENT_BUS.addListener(CameraHandler::onClientTick);

        // 快捷键开机，同样在游戏总线上读按键
        NeoForge.EVENT_BUS.addListener(PhoneKeyHandler::onClientTick);
        NeoForge.EVENT_BUS.addListener(CameraHandler::onRenderGui);
        NeoForge.EVENT_BUS.addListener(CameraHandler::onScreenOpening);

        // 退出世界时清空聊天缓存。不清的话，换到另一个服务器时会先闪出
        // 上一个服务器的会话列表——那是别处的数据，既尴尬又可能泄露信息
        NeoForge.EVENT_BUS.addListener(
                (ClientPlayerNetworkEvent.LoggingOut event) -> {
                    ChatClientCache.clear();
                    NotesClientCache.clear();
                });

        // 收到消息时弹通知。装在这里而不是让网络层直接调 ChatNotifier：
        // 网络层在专用服务器上也会加载，碰不得客户端的类
        ChatClientCache.setMessageListener(ChatNotifier::onMessage);
    }

    /**
     * 资源重载时清空换肤贴图的探测缓存。
     *
     * 不清的话，玩家按 F3+T 重载资源包、或换了资源包之后，界面画的还是
     * 上一套贴图——而且不报错，只是"改了没反应"，很难想到是缓存。
     */
    @SubscribeEvent
    static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(
                (ResourceManagerReloadListener) manager -> PhoneSkin.clearCache());
    }

    /**
     * 把菜单类型与界面类绑定。
     *
     * RegisterMenuScreensEvent 是【模组总线】事件。不注册的话，服务端
     * openMenu 后客户端不知道该开哪个界面，表现为菜单开了但什么都没显示。
     */
    @SubscribeEvent
    static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.ENDER_CHEST.get(), PhoneContainerScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // 扫描壁纸目录
        WallpaperStore.scan();

        // 触发 PhoneScreenRegistry 延迟加载（内建 + SPI）
        PhoneScreenRegistry.getAppCount();

        MCphone.LOGGER.info("MCphone 客户端加载完成");
        MCphone.LOGGER.info("玩家: {}", Minecraft.getInstance().getUser().getName());
    }
}
