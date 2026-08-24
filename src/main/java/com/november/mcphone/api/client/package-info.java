/**
 * 客户端专用 API —— 本包及其子包中的一切都只能在客户端使用。
 *
 * 这条约束是硬的
 *
 * 本包的接口签名里带有 GuiGraphics 这类客户端类型（手机界面本来就是
 * 客户端的东西）。因此：
 *
 *   ✗ 不要从物品、方块、菜单、网络包、服务端事件处理里引用本包的类型
 *   ✗ 不要让实现类被服务端的类"顺带"加载到
 *   ✓ 把实现类放在你自己的客户端专用包里，只由客户端代码引用
 *
 * 违反的后果不是功能失效，是【专用服务器启动即崩】，而且崩溃信息指向
 * 的是加载失败的那个原版类，不会告诉你是哪个 App 写错了——排查成本很高。
 * 原因见 {@link com.november.mcphone.api} 的包注释。
 *
 * 一个安全的跨端调用写法
 *
 * 服务端代码确实需要触发客户端行为时，把客户端逻辑单独放一个类，
 * 用静态方法调过去，并且【方法签名里不许出现客户端类型】：
 *
 *   // 公共代码里
 *   if (level.isClientSide()) {
 *       MyModClientHooks.openMyScreen(someDistSafeArgument);
 *   }
 *
 * invokestatic 的属主类是第一次执行到时才解析的，校验期不碰它；
 * 而校验期会检查的参数类型，这里全是两端安全的。于是
 * MyModClientHooks 在服务端从头到尾不会被加载。
 *
 * MCphone 自己的 com.november.mcphone.core.client.PhoneScreenOpener 就是
 * 这个写法的实例，可以照抄。
 */
package com.november.mcphone.api.client;
