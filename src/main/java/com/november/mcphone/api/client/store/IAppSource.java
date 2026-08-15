package com.november.mcphone.api.client.store;

import com.november.mcphone.api.client.app.IPhoneApp;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.function.Consumer;

/**
 * 应用来源 —— 应用商店的 App 从哪来。
 *
 * ================================================================
 * 这个接口存在的意义
 * ================================================================
 *
 * 商店界面只跟 AppSource 打交道，不关心 App 究竟是本地已加载的类、
 * 从服务器下载的一份界面描述、还是别的什么。要接入新的来源，
 * 实现这个接口并通过 SPI 注册即可，商店界面一行都不用改。
 *
 * 目前只有一个内建实现 LocalAppSource（列出本地目录中尚未安装的 App）。
 *
 * ================================================================
 * 附属模组如何注册自己的来源
 * ================================================================
 *
 * 与 {@link IPhoneApp} 完全相同的 SPI 机制：
 *
 *   文件: src/main/resources/META-INF/services/
 *         com.november.mcphone.api.client.store.IAppSource
 *   内容: com.yourmod.YourAppSource
 *
 * ================================================================
 * 关于异步与线程
 * ================================================================
 *
 * listAvailable / install 都是回调式的，远程来源可以在后台线程发起
 * 网络请求而不卡住渲染线程。
 *
 * 但回调本身必须在客户端主线程执行——回调里会触碰注册表和 GUI 状态，
 * 在网络线程上直接调用会导致难以排查的并发问题。异步来源请这样切回主线程：
 *
 *   Minecraft.getInstance().execute(() -> callback.accept(result));
 *
 * ================================================================
 * 关于"从互联网下载 App"
 * ================================================================
 *
 * 这个接口刻意没有规定实例从哪来，install() 只要求最终交出一个
 * 可用的 IPhoneApp。因此以下几种路径都能套进来：
 *
 *   本地来源     直接返回目录中现成的实例
 *   数据驱动     下载一份 JSON，据此构造一个通用的可配置 App
 *   远程 jar     自行 classload（注意：运行时加载的类拿不到注册表，
 *                Mixin 也不会生效，且等同于任意代码执行，风险自负）
 */
public interface IAppSource {

    /**
     * 来源唯一标识，形如 {@code mymod:official_repo}。
     *
     * 与 {@link IPhoneApp#getId()} 同理：来源表也是全服共用的一张表，
     * 带命名空间才不会跟别的模组撞名。命名空间请用你自己的 modid。
     */
    ResourceLocation getId();

    /** 来源显示名称，展示在商店的分组标题上 */
    Component getDisplayName();

    /**
     * 列出此来源当前可安装的 App。
     *
     * 实现方应只返回尚未安装的 App。结果通过回调返回，
     * 以便远程来源异步获取；本地来源可以同步地立即回调。
     *
     * @param callback 收到列表时调用，必须在客户端主线程执行
     */
    void listAvailable(Consumer<List<AppInfo>> callback);

    /**
     * 安装指定 App。
     *
     * 实现方负责让这个 App 真正可用（本地来源即调用注册表的 install，
     * 远程来源需先下载再构造实例），成功后通过 onSuccess 交出实例。
     *
     * @param info      要安装的 App，来自本来源的 listAvailable
     * @param onSuccess 安装成功，参数为可用的 App 实例
     * @param onError   安装失败，参数为可展示给玩家的错误信息
     */
    void install(AppInfo info, Consumer<IPhoneApp> onSuccess, Consumer<Component> onError);

    /**
     * 此来源当前是否可用。
     * 远程来源可在离线、未登录、正在加载时返回 false，商店会将其标记为不可用。
     * 默认 true。
     */
    default boolean isReady() { return true; }
}
