package com.november.mcphone.api;

/**
 * API 的版本与兼容承诺。改 API 之前守住这几条：
 *
 * 一、已发布的接口不加抽象方法——新增能力一律走 {@code default} 方法或新接口。
 * 二、已发布的记录不改构造函数——对外只给 builder（见 {@link com.november.mcphone.api.client.store.AppInfo}）。
 * 三、不改已发布的方法签名——要改就新增重载，老的标 {@code @Deprecated} 至少留一个大版本。
 * 四、包名也是 API——挪一个类的包等于删了它再新建一个。
 * 五、这些规矩只管 api 包，其余（core、feature、compat、util）都是内部实现，附属不该引用。
 */
public final class MCphoneApi {

    private MCphoneApi() {}

    /**
     * API 代号。每次往 api 包里加东西就 +1，只增不减；附属可用 {@code MCphoneApi.VERSION >= n}
     * 判断新能力在不在。
     *
     *   1  —— IPhoneApp / RequiredMod / IPhonePage / PhoneCanvas / PhoneStyle
     *          IAppSource / AppInfo / ICost / ItemCost / EmcCost
     *          IAppPriceProvider / IEmcWallet / EmcWallets
     *   2  —— PhoneMultiLineEditBox（1.10.4）、IPhoneApp.opensInsidePhone()
     *
     * 【这个值是在静态块里赋的，别改成 {@code = 2}】
     *
     * 写成声明式里的字面量，它就成了【编译期常量】，javac 会把它内联进读它的那个类：附属编译
     * 那一刻，{@code MCphoneApi.VERSION >= 2} 里的 VERSION 就被换成了字面量，附属的字节码里连
     * 一条读这个字段的指令都不剩（{@code javap} 一看便知，整个 if 都被折叠掉了）。于是它判断的
     * 是"附属编译时看到的 MCphone"，不是"运行时装着的 MCphone"——拿新版编、装到旧版上，判断
     * 照样通过，然后在真正碰新类型的地方抛 NoClassDefFoundError；拿旧版编、装到新版上，新能力
     * 永远进不去。两个方向都错，而且都不报错。1.2.12 到 1.10.3 之间它一直是这个样子。
     *
     * 赋值挪进静态块之后它不再是编译期常量，读它的地方编出来的是 getstatic，运行时才解析——
     * 这是【重新编译过的】附属才享受得到的；1.10.4 之前编出去的那些，值已经烧死在它们自己的
     * 字节码里，救不回来。
     *
     * 【它替代不了类加载的那一层】：判断通过不等于类能加载。直接在同一个方法里引用新版才有的
     * 类型，JVM 校验这个方法时就抛 NoClassDefFoundError，轮不到那句 if——把新能力的调用单独
     * 关进一个类里，判断通过再碰它。两条坑各管各的，都得躲。
     *
     * 更省事的做法是压根不判断：在 neoforge.mods.toml 里把 versionRange 的下限写对，加载器
     * 会替你拦。判断只留给"装了新版多一个功能、旧版也能用"的软兼容。
     *
     * 逐个方法的说明见 wiki 的附属接口文档，那份是给附属开发者看的，这里只记契约。
     */
    public static final int VERSION;

    static {
        // 【别把这一句挪回上面的声明里】——那样它就成了编译期常量，会被内联进附属，理由见上
        VERSION = 2;
    }

}
