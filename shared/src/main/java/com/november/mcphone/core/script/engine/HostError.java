package com.november.mcphone.core.script.engine;

import com.november.mcphone.core.script.net.ScriptErrorCode;
import org.mozilla.javascript.WrappedException;

/** A catchable host validation failure carrying an unforgeable host-side marker. */
public final class HostError extends WrappedException {

    public static final String INVALID = "INVALID";
    public static final String QUOTA = "QUOTA";
    public static final String UNKNOWN_VALUE = "UNKNOWN_VALUE";
    public static final String UNAVAILABLE = "UNAVAILABLE";

    private static final Object TOKEN = new Object();

    private final Object marker = TOKEN;
    private final String code;
    private final String detail;
    /** 非空表示"这不是宿主内部出错，而是一条业务拒绝"：未接住时结果码用它（S18 能力门）。 */
    private final ScriptErrorCode resultCode;
    private final String messageKey;

    private HostError(String code, String detail) {
        this(code, detail, null, "");
    }

    private HostError(String code, String detail, ScriptErrorCode resultCode, String messageKey) {
        super(new IllegalArgumentException(code + ": " + LogText.filter(detail)));
        this.code = code;
        this.detail = LogText.filter(detail);
        this.resultCode = resultCode;
        this.messageKey = messageKey == null ? "" : messageKey;
    }

    public static HostError invalid(String detail) {
        return new HostError(INVALID, detail);
    }

    public static HostError quota(String detail) {
        return new HostError(QUOTA, detail);
    }

    public static HostError unknownValue(String detail) {
        return new HostError(UNKNOWN_VALUE, detail);
    }

    public static HostError unavailable(String detail) {
        return new HostError(UNAVAILABLE, detail);
    }

    /**
     * 一条<b>业务拒绝</b>（S18 的能力门等）：脚本可以 catch、不记过失；没被接住时整次调用的
     * 结果码就是 {@code resultCode}，配 {@code messageKey} 给客户端显示。
     */
    public static HostError denied(ScriptErrorCode resultCode, String messageKey, String detail) {
        return new HostError(resultCode.name(), detail, resultCode, messageKey);
    }

    public String code() {
        return code;
    }

    public String detail() {
        return detail;
    }

    /** 业务拒绝的结果码；普通宿主参数错误是 null。 */
    public ScriptErrorCode resultCode() {
        return resultCode;
    }

    /** 业务拒绝的本地化键；没有就是空串。 */
    public String messageKey() {
        return messageKey;
    }

    @Override
    public String details() {
        return code + ": " + detail;
    }

    /** Returns a code only for an instance minted by this host class. */
    public static String classify(Throwable failure) {
        return failure instanceof HostError error && error.marker == TOKEN ? error.code : null;
    }
}
