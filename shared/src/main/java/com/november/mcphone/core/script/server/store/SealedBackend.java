package com.november.mcphone.core.script.server.store;

/**
 * sealed 存储的内部后端（施工方案 §16.5、§17.4.5、§32.7）。
 *
 * <h2>服务端只搬字节</h2>
 *
 * 服务端<b>解不开</b>这些字节，所以：
 *
 * <ul>
 *   <li>只有存入与取出两个操作</li>
 *   <li><b>绝不许基于 sealed 值做任何判断</b> —— 不提供、也不许将来提供这类 API。
 *       想写 {@code if (ctx.sealed.get('token') === '...')} 的人要明白：服务端拿到的是密文</li>
 * </ul>
 *
 * <p>当前脚本面只挂有真实消费路径的 {@code ctx.sealed.get}。写入消费方接通前不暴露
 * 永远失败的 {@code ctx.sealed.put} 空壳。
 *
 * <p>解密只发生在客户端，见 {@link VaultClient}。
 */
public interface SealedBackend {

    /** 存一条。记录里的 recordVersion 由客户端给（防回滚要的单调递增）。 */
    void put(String appId, String key, SealedRecord record);

    /** 取一条，没有返回 null。 */
    SealedRecord get(String appId, String key);
}
