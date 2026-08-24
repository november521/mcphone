package com.november.mcphone.api.client.store;

import com.november.mcphone.api.client.app.IPhoneApp;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * App 元数据 —— 描述一个"可以安装的 App"，但不包含它的实现。
 *
 * 为什么要把元数据和实现分开
 *
 * {@link IPhoneApp} 是一个活的 Java 对象，只能来自本地已加载的类。而应用商店
 * 需要在"还没有实例"的时候就把 App 列出来给玩家看——典型场景是远程来源：先拉
 * 一份 JSON 列表渲染出来，玩家点了下载才去取实体。
 *
 * AppInfo 就是那份 JSON 能反序列化出来的东西：全是可序列化的字段，不引用任何
 * 实现类。这样同一套商店界面既能列本地 App，也能列远程 App。
 *
 * 为什么是 builder，不是 record
 *
 * 1.2.11 之前它是个 record，七个字段。看着更省事，但那是个定时炸弹：record 的
 * 规范构造函数是公开 API 的一部分，哪天要加第八个字段（分类、角标、评分，随便
 * 什么），所有写了 {@code new AppInfo(...)} 的附属当场编译不过。
 *
 * 而 API 的承诺是"加功能不打断附属"（见
 * {@link com.november.mcphone.api.MCphoneApi} 的兼容策略）。record 与这条承诺
 * 在结构上是冲突的——不是我们会不会小心，是这种写法根本不给你小心的余地。
 *
 * 改成 builder 之后，将来加字段只是多一个 builder 方法，谁都不用改。
 *
 * 趁现在改是因为第三方附属数量约等于零。晚一步这就是永久债务。
 *
 * 怎么用
 *
 * {@snippet :
 * AppInfo info = AppInfo.builder(myId, Component.translatable("mymod.app.foo"), sourceId)
 *         .icon(myIconTexture)
 *         .version("1.2.0")
 *         .author("someone")
 *         .description("这个 App 是干什么的")
 *         .build();
 * }
 *
 * 三个必填的走 builder 的参数，其余可选项各有默认值——少写一个不会得到 null，
 * 只会得到空串或 null 图标，而界面对这两种都有兜底。
 */
public final class AppInfo {

    private final ResourceLocation id;
    private final Component displayName;
    private final ResourceLocation iconTexture;
    private final String version;
    private final String author;
    private final String description;
    private final ResourceLocation sourceId;

    private AppInfo(Builder b) {
        this.id = b.id;
        this.displayName = b.displayName;
        this.iconTexture = b.iconTexture;
        this.version = b.version;
        this.author = b.author;
        this.description = b.description;
        this.sourceId = b.sourceId;
    }

    /** App 唯一标识，与 {@link IPhoneApp#getId()} 对应 */
    public ResourceLocation id() { return id; }

    /** 显示名称 */
    public Component displayName() { return displayName; }

    /** 图标纹理。可能为 null——远程来源列出的 App 本地还没有实现，自然也没有贴图 */
    public ResourceLocation iconTexture() { return iconTexture; }

    /** 版本号。没写时是空串，不是 null */
    public String version() { return version; }

    /** 作者。没写时是空串，不是 null */
    public String author() { return author; }

    /** 简介，显示在商店详情页。没写时是空串，不是 null */
    public String description() { return description; }

    /** 提供此 App 的来源 id，见 {@link IAppSource#getId()} */
    public ResourceLocation sourceId() { return sourceId; }

    /**
     * 开一个 builder。三个必填项从这里给，其余可选。
     *
     * @param id          App 唯一标识
     * @param displayName 显示名称
     * @param sourceId    哪个来源提供的
     */
    public static Builder builder(ResourceLocation id, Component displayName,
                                  ResourceLocation sourceId) {
        return new Builder(id, displayName, sourceId);
    }

    /**
     * 从一个已存在的 App 实例生成元数据 —— 本地来源用。
     *
     * 读 App 的那几个方法都是第三方代码，但这里【不】兜异常：调用方
     * （LocalAppSource）本来就在 SPI 的兜底范围内，在这儿再兜一层只会把
     * "哪个 App 有问题"这个信息吞掉。
     */
    public static AppInfo of(IPhoneApp app, ResourceLocation sourceId) {
        return builder(app.getId(), app.getDisplayName(), sourceId)
                .icon(app.getIconTexture())
                .version(app.getVersion())
                .author(app.getAuthor())
                .description(app.getDescription())
                .build();
    }

    /** {@link AppInfo} 的构造器。加新字段就在这儿多一个方法，不动已有签名 */
    public static final class Builder {

        private final ResourceLocation id;
        private final Component displayName;
        private final ResourceLocation sourceId;

        // 可选项的默认值。给空串而不是 null，省得每个调用方各判一次
        private ResourceLocation iconTexture = null;
        private String version = "";
        private String author = "";
        private String description = "";

        private Builder(ResourceLocation id, Component displayName, ResourceLocation sourceId) {
            this.id = id;
            this.displayName = displayName;
            this.sourceId = sourceId;
        }

        /** 图标纹理。不给的话商店画一个占位方块，不会变紫黑格 */
        public Builder icon(ResourceLocation iconTexture) {
            this.iconTexture = iconTexture;
            return this;
        }

        public Builder version(String version) {
            this.version = version == null ? "" : version;
            return this;
        }

        public Builder author(String author) {
            this.author = author == null ? "" : author;
            return this;
        }

        public Builder description(String description) {
            this.description = description == null ? "" : description;
            return this;
        }

        public AppInfo build() {
            return new AppInfo(this);
        }
    }
}
