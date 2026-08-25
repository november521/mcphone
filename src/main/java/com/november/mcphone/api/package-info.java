/**
 * MCphone 对外开放的 API。分包按运行端，不按功能：
 *
 *   {@code api.client.*}   客户端专用——签名里有 GuiGraphics 这类客户端类型，实现类只能在客户端加载；
 *                          再按主题分 {@code api.client.app}（写 App）与 {@code api.client.store}（接商店）。
 *   其余（如 {@code api.cost.*}）  两端安全，随便用。
 *
 * 专用服务器上没有客户端类：一个类只要【签名或方法体里写着】客户端类型，被服务端加载校验就当场崩，
 * 哪怕那段代码被 isClientSide 挡住永远执行不到。构建期的 verifyDistIsolation 会扫产物字节码，
 * 路径里没有 {@code /client/} 的类出现客户端类型就构建失败。
 *
 * 兼容承诺见 {@link com.november.mcphone.api.MCphoneApi}，改 API 之前先读它。
 */
package com.november.mcphone.api;
