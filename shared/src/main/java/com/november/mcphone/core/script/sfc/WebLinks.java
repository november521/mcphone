package com.november.mcphone.core.script.sfc;

import java.net.URI;

/** The same web-only boundary is checked before committing a click and before opening the OS browser. */
public final class WebLinks {
    public static final int MAX_LENGTH = 2048;

    private WebLinks() {}

    public static String checked(Object value) {
        if (!(value instanceof String url) || url.isEmpty() || url.length() > MAX_LENGTH) throw invalid();
        try {
            URI uri = URI.create(url);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null
                    || uri.getPort() == 0 || uri.getPort() > 65535) throw invalid();
            return url;
        } catch (IllegalArgumentException e) { throw invalid(); }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("openUrl 需要合法的 HTTP/HTTPS 地址，最多 2048 字符，不能包含账号密码");
    }
}
