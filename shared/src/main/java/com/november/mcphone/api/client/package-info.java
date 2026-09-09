/**
 * 客户端专用 API——本包及其子包的接口签名里带 GuiGraphics 这类客户端类型，实现类只能在客户端加载。
 *
 * 不要从物品、方块、菜单、网络包、服务端事件处理里引用本包的类型，也别让实现类被服务端的类顺带加载到：
 * 后果是【专用服务器启动即崩】，且崩溃信息不会指向你的 App。原因见 {@link com.november.mcphone.api} 的包注释。
 *
 * 服务端要触发客户端行为时，把客户端逻辑单独放一个类、用静态方法调过去，且方法签名里不许出现客户端类型
 * ——invokestatic 的属主类是第一次执行到时才解析的，校验期不碰它。可照抄
 * {@code com.november.mcphone.core.client.PhoneScreenOpener}。
 */
package com.november.mcphone.api.client;
