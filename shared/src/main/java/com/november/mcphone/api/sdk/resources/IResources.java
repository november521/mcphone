package com.november.mcphone.api.sdk.resources;

/**
 * 只读资源 SDK（施工方案 §29），调用实例绑定当前玩家。新增方法用 default 保留原有附属兼容性。
 *
 * <p><b>初稿把它归进 C 档"交给适配器、不强求统一"，那是错的</b>：模组服上能源和流体比原版机制
 * 常见得多，而且 {@code IEnergyStorage} 的读数是 {@code int}（§29.4 实测），求和会溢出 ——
 * 不定一层抽象，每个 App 各踩一遍这个坑。
 */
public interface IResources {
    default java.util.List<ResourceType> list(){return java.util.List.of();}
    default ResourceType defaultType(ResourceType.Kind kind){return null;}
    /** 调用者绑定玩家，槽位只能是本人背包 0–35 或副手 40。 */
    default ResourceReading readItem(net.minecraft.resources.ResourceLocation type,int slot){return null;}
    /** 调用者绑定当前维度、已加载区块、距离及服主配置的目标谓词。 */
    default ResourceReading readBlock(net.minecraft.resources.ResourceLocation type,net.minecraft.core.BlockPos pos,net.minecraft.core.Direction side){return null;}
}
