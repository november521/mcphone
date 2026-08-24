/**
 * MCphone 对外开放的 API。
 *
 * 包的划分方式：按运行端，不按功能
 *
 * 这里的分包规则只有一条，但它比任何功能分类都重要：
 *
 *   {@code api.client.*}   客户端专用。签名里出现 GuiGraphics、Screen
 *                          这类客户端类型，实现类只能在客户端加载。
 *
 *   其余（如 {@code api.cost.*}）  两端安全。可以被服务端代码引用，
 *                          也可以被客户端代码引用。
 *
 * 客户端那一半再按主题分：
 *
 *   {@code api.client.app}     写一个 App（IPhoneApp、RequiredMod）
 *   {@code api.client.store}   接入应用商店（IAppSource、AppInfo）
 *
 * 这条规则不靠自觉：构建期的 verifyDistIsolation 会扫产物字节码，路径里没有
 * {@code /client/} 的类只要常量池里出现客户端类型就直接构建失败。1.2.12 那次
 * 想把 IPhoneApp 挪去 {@code api.app}，当场就被它拦下来了——路径里的 client
 * 一丢，这条编码也就没了。
 *
 * 兼容承诺
 *
 * 见 {@link com.november.mcphone.api.MCphoneApi}：那里写着 API 代号，以及
 * "以后加功能不打断附属"具体是靠哪几条规矩做到的。改 API 之前先读它。
 *
 * 为什么这条规则值得单独用包名来表达
 *
 * Minecraft 的专用服务器上根本没有客户端类。NeoForge 的
 * RuntimeDistCleaner 会在加载 {@code net.minecraft.client.*} 时直接抛
 * 异常——而触发它的不是"执行到了那句代码"，是"JVM 校验那个类"。
 *
 * 也就是说：一个类只要【签名或方法体里写着】客户端类型，哪怕那段代码
 * 被 {@code level.isClientSide()} 挡得死死的、服务端永远执行不到，
 * 只要这个类在服务端被加载，就会当场崩服。
 *
 * MCphone 1.0.44 就是这么把一台 453 个模组的服务器打崩的：物品类
 * PhoneItem 里写了一句 {@code Minecraft.setScreen(new PhoneScreen(...))}，
 * 注册物品时服务端加载并校验这个类，校验器为了确认 PhoneScreen 是不是
 * Screen 的子类而去加载 Screen，然后就崩了。
 *
 * 用包名把两类 API 分开，是为了让附属模组作者一眼看见这条界线，
 * 不必先崩一次服务器才知道。
 *
 * 附属模组该怎么用
 *
 * 实现 {@code api.client} 下的接口时，把实现类放进你自己的客户端专用
 * 包里，不要从公共代码（物品、方块、网络包、事件处理）去引用它们。
 *
 * {@code api.cost} 下的东西没有这个限制，随便用。
 */
package com.november.mcphone.api;

