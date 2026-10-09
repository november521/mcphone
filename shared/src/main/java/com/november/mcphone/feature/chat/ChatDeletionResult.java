package com.november.mcphone.feature.chat;

/** 删除确认的共享值类型；显式线格式编号不随枚举顺序变化，界面不依赖服务端业务类。 */
public enum ChatDeletionResult {
    OK(0), FORBIDDEN(1), NOT_FOUND(2), BUSY(3), STORAGE_ERROR(4);
    private final int wireId;
    ChatDeletionResult(int wireId) { this.wireId = wireId; }
    public int wireId() { return wireId; }
    public static ChatDeletionResult fromWire(int value) {
        for (var result : values()) if (result.wireId == value) return result;
        throw new IllegalArgumentException("未知删除结果: " + value);
    }
}
