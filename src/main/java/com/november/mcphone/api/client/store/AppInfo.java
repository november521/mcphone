package com.november.mcphone.api.client.store;

import com.november.mcphone.api.client.IPhoneApp;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * App 元数据 —— 描述一个"可以安装的 App"，但不包含它的实现。
 *
 * ================================================================
 * 为什么要把元数据和实现分开
 * ================================================================
 *
 * {@link IPhoneApp} 是一个活的 Java 对象，只能来自本地已加载的类。
 * 而应用商店需要在"还没有实例"的时候就把 App 列出来给玩家看——
 * 典型场景是远程来源：先拉一份 JSON 列表渲染出来，玩家点了下载才去取实体。
 *
 * AppInfo 就是那份 JSON 能反序列化出来的东西：全是可序列化的字段，
 * 不引用任何实现类。这样同一套商店界面既能列本地 App，也能列远程 App。
 *
 * @param id           App 唯一标识，与 {@link IPhoneApp#getId()} 对应
 * @param displayName  显示名称
 * @param iconTexture  图标纹理，可为 null（商店会画占位图标）
 * @param version      版本号
 * @param author       作者
 * @param description  简介，显示在商店列表中
 * @param sourceId     提供此 App 的来源 id，见 {@link IAppSource#getId()}
 */
public record AppInfo(
        ResourceLocation id,
        Component displayName,
        ResourceLocation iconTexture,
        String version,
        String author,
        String description,
        ResourceLocation sourceId
) {

    /** 从一个已存在的 App 实例生成元数据 —— 本地来源用 */
    public static AppInfo of(IPhoneApp app, ResourceLocation sourceId) {
        return new AppInfo(
                app.getId(),
                app.getDisplayName(),
                app.getIconTexture(),
                app.getVersion(),
                app.getAuthor(),
                app.getDescription(),
                sourceId
        );
    }
}
