package com.november.mcphone.api;


/**
 * API 的版本与兼容承诺。
 *
 * 这个类存在的意义
 *
 * MCphone 想做到的一件事是：谁都可以写自己的 App，而且写完之后不用跟着我们
 * 的每一次更新改代码。
 *
 * 想做到这一点，光是"尽量别乱改"是不够的——那是态度，不是保证。得有一条能
 * 落到代码上的规矩，还得有个数字让附属能问"我在跟哪一代 API 说话"。
 *
 * 兼容策略 —— 写在代码里，不是写在 README 里
 *
 * 写在这儿是因为改 API 的人一定会打开这个类，而不一定会去翻文档。
 *
 * 【一、已发布的接口不加抽象方法】
 *
 * 新增能力一律走 {@code default} 方法，或者开一个新接口。给已发布的接口加一个
 * 抽象方法，等于让所有附属在升级的那一刻编译不过——而他们多半是在玩家报"进不去
 * 游戏"之后才知道。
 *
 * {@link com.november.mcphone.api.client.app.IPhoneApp} 现在是 4 个抽象方法 + 10 个
 * default，这个比例是刻意的：抽象的那几个是"不写就没法工作"的（id、名字、图标、
 * 点击），其余全部给默认值。
 *
 * 【二、已发布的记录不改构造函数】
 *
 * record 的规范构造函数是公开 API 的一部分。给 AppInfo 加一个字段，所有写了
 * {@code new AppInfo(...)} 的附属当场编译不过。
 *
 * 所以对外只给 builder（见 {@link com.november.mcphone.api.client.store.AppInfo}），
 * 将来加字段只是多一个 builder 方法，谁都不用改。
 * {@link com.november.mcphone.api.client.app.RequiredMod} 只有两个字段且不会再长，
 * 留着直接构造。
 *
 * 【三、不改已发布的方法签名，包括参数顺序与类型】
 *
 * 要改就新增一个重载，老的标 {@code @Deprecated} 并写明替代品，至少留一个大版本
 * 再删。
 *
 * 【四、包名也是 API】
 *
 * 挪一个类的包，效果等同于删了它再新建一个。1.2.11 那次全量重排包结构，前提是
 * 当时第三方附属数量约等于零——那是最后一个能这么干的窗口，以后不再有第二次。
 *
 * 【五、这些规矩只管 api 包】
 *
 * {@code api} 之外的一切（core、feature、compat、util）都是内部实现，随时可能
 * 改，附属不该引用。要用什么却发现只有内部有，提出来，我们把它挪进 API——这比
 * 你去引用内部类、然后在某次更新里无声地坏掉要好。
 */
public final class MCphoneApi {

    private MCphoneApi() {}

    /**
     * API 代号。每次往 API 里加东西就 +1，只增不减。
     *
     * 附属拿它干什么
     *
     * 你想用一个较新的能力，又不想把用旧版 MCphone 的玩家挡在门外时：
     *
     * {@snippet :
     * if (MCphoneApi.VERSION >= 2) {
     *     // 用新能力
     * } else {
     *     // 退化成旧写法
     * }
     * }
     *
     * 注意这只能用来判断【方法存不存在的语义】，不能替代类加载：如果你的代码
     * 里直接写了新版才有的类型，那个类在旧版上根本不存在，JVM 校验你的类时就
     * 会抛 NoClassDefFoundError——那句 if 还没轮到执行。真要跨版本兼容，把新
     * 能力的调用单独关进一个类里，判断通过了再去碰它。
     *
     * 更省事的办法是在 neoforge.mods.toml 里写好 versionRange，让加载器直接
     * 把不匹配的组合挡下来。这个数字是给"想同时支持新旧两版"的人用的。
     *
     * 版本历史
     *
     *   1  —— IPhoneApp / IAppSource / ICost / IAppPriceProvider / RequiredMod
     */
    public static final int VERSION = 1;

}
