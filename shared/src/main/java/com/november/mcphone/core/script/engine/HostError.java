package com.november.mcphone.core.script.engine;

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

    private HostError(String code, String detail) {
        super(new IllegalArgumentException(code + ": " + LogText.filter(detail)));
        this.code = code;
        this.detail = LogText.filter(detail);
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

    public String code() {
        return code;
    }

    public String detail() {
        return detail;
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
