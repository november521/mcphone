package com.november.mcphone.core.script.engine;

/** Makes untrusted text safe to place in a single bounded log line. */
public final class LogText {

    public static final int MAX = 512;

    private LogText() {
    }

    public static String filter(String raw) {
        if (raw == null) return "";
        int length = Math.min(raw.length(), MAX);
        StringBuilder out = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            char c = raw.charAt(i);
            int type = Character.getType(c);
            if (c == '\u00A7' || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR
                    || c < 0x20 || (c >= 0x7f && c <= 0x9f)) {
                out.append(String.format("\\u%04X", (int) c));
            } else {
                out.append(c);
            }
        }
        if (raw.length() > MAX) out.append("...(truncated, original length ").append(raw.length()).append(')');
        return out.toString();
    }
}
