package com.november.mcphone.api.client.app;

/**
 * 一个 App 依赖的外部模组——「联动 App」那一页显示的就是它。刻意没有下载链接。
 *
 * @param modId       模组 id，与 {@code ModList.isLoaded} 用的是同一个值
 * @param displayName 给玩家看的名字，例如 "Waystones（传送石碑）"。自己写死，别在运行时查——要显示它的时候那个模组多半没装
 */
public record RequiredMod(String modId, String displayName) {}
