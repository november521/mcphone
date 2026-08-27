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
     * API 代号。每次往 API 里加东西就 +1，只增不减；附属可用 {@code MCphoneApi.VERSION >= n} 判断新能力在不在。
     *
     * 注意这只能判断语义，不能替代类加载：直接引用新版才有的类型，在旧版上 JVM 校验时就抛
     * NoClassDefFoundError，轮不到那句 if——把新能力的调用单独关进一个类里，判断通过再碰它。
     *
     *   1  —— IPhoneApp / IAppSource / ICost / IAppPriceProvider / RequiredMod
     */
    public static final int VERSION = 1;

}
