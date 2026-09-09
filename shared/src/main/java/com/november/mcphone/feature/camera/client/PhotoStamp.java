package com.november.mcphone.feature.camera.client;

import net.minecraft.world.entity.player.Player;
import org.jetbrains.annotations.Nullable;

/**
 * 照片右下角那一叠字里的<b>一项</b>。一项就是一行。
 *
 * 返回 {@code null} 表示这一次不印 —— 那一项自己的开关关着、或者此刻没有值可写
 * （例如"手上拿的是什么"而手是空的）。所以"印不印"由每一项自己回答，相机不必知道
 * 有哪些项、更不必为每一项加一个 if。
 *
 * <h2>为什么给的是一行字，而不是让它自己画</h2>
 *
 * 这一叠字要在<b>照片里</b>当作内容存在，不是浮在画面上的辅助线。于是描边、按画面高度
 * 取整放大、右对齐、行距——每一样都得几项之间一致，否则一张照片上会出现两种字号。
 * 让每一项自己画，等于让每一项把这些各想一遍，而它们迟早会想得不一样。
 * 排版只写一遍，在 {@link CameraStamp#render}。
 *
 * <h2>加一项要做什么</h2>
 *
 * 写一个实现，{@link CameraStamp#register} 登记进去。相机、两支平台的 CameraHandler
 * 一行都不用改 —— 那正是这个接口存在的理由：印什么是功能的事，而"哪一帧画得进照片"
 * 是加载器的事，两件事从此不必一起改。
 */
public interface PhotoStamp {

    /**
     * 这一次要印的那行字，{@code null} 或空串表示这次不印。
     *
     * 每帧调用（取景时每帧、拍照那一帧也是同一句），所以别在这里做读盘、查表一类的事。
     */
    @Nullable
    String line(Player player);
}
