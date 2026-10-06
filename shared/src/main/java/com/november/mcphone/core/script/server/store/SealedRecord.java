package com.november.mcphone.core.script.server.store;

/**
 * 服务端存着的一条保险箱记录（施工方案 §17.4）。<b>服务端只搬这些字节，解不开。</b>
 *
 * <p>salt 也存在服务端 —— salt 不是秘密，而跨设备登录时客户端要能把它取回来。
 *
 * @param salt          16 字节，派生密钥用
 * @param nonce         12 字节，<b>每条记录都不一样</b>
 * @param cipher        AES-256-GCM 的密文加标签
 * @param schemaVersion 记录格式版本，进 AAD
 * @param recordVersion 单调递增，进 AAD，也是防回滚的依据（§17.4.2）
 */
public record SealedRecord(byte[] salt, byte[] nonce, byte[] cipher,
                           int schemaVersion, long recordVersion) {

    /** 当前的记录格式版本。 */
    public static final int SCHEMA = 1;

    public SealedRecord {
        if (salt == null || salt.length != VaultCrypto.SALT_BYTES) {
            throw new IllegalArgumentException("salt 要 " + VaultCrypto.SALT_BYTES + " 字节");
        }
        if (nonce == null || nonce.length != VaultCrypto.NONCE_BYTES) {
            throw new IllegalArgumentException("nonce 要 " + VaultCrypto.NONCE_BYTES + " 字节");
        }
        if (cipher == null || cipher.length == 0) throw new IllegalArgumentException("密文不能为空");
        if (cipher.length > 4096 || schemaVersion != SCHEMA) throw new IllegalArgumentException("保险箱密文超额或格式不支持");
        if (recordVersion < 1) throw new IllegalArgumentException("recordVersion 从 1 起");
        salt = salt.clone(); nonce = nonce.clone(); cipher = cipher.clone();
    }
    @Override public byte[] salt() { return salt.clone(); }
    @Override public byte[] nonce() { return nonce.clone(); }
    @Override public byte[] cipher() { return cipher.clone(); }
}
