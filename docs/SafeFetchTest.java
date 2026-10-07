package com.november.mcphone.core.script.server;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 不发外网请求的出口对抗测试。TLS / DNS 实际连接另列人工验收。 */
public final class SafeFetchTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("出口闸 #" + checks); }
    interface Probe { void run() throws Exception; }
    private static void rejects(Probe p) throws Exception {
        boolean rejected = false; try { p.run(); } catch (IOException | IllegalArgumentException e) { rejected = true; } check(rejected);
    }
    private static SafeFetch.Response response(String text, int max) throws IOException {
        return SafeFetch.readResponse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), Set.of(SafeFetch.Type.TEXT), max, () -> {});
    }
    public static void main(String[] args) throws Exception {
        check(SafeFetch.validate("https://example.com/a?q=x", Set.of("example.com")).getHost().equals("example.com"));
        for (String uri : List.of("http://example.com", "https://example.com:444", "https://evil.example.com", "https://x@example.com", "https://example.com./", "https://example.com/#x")) rejects(() -> SafeFetch.validate(uri, Set.of("example.com")));
        for (String ip : List.of("127.1.2.3", "10.1.1.1", "172.31.1.1", "192.168.1.1", "169.254.169.254", "100.64.1.1", "0.0.0.0", "224.1.1.1", "240.1.1.1", "192.0.2.1", "198.18.1.1", "203.0.113.1", "::1", "fe80::1", "fc00::1", "2002:a00:1::", "2001:db8::1")) check(!SafeFetch.publicAddress(InetAddress.getByName(ip)));
        check(SafeFetch.publicAddress(InetAddress.getByName("8.8.8.8")));
        check(SafeFetch.publicAddress(InetAddress.getByName("2606:4700:4700::1111")));
        check(new String(response("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 4\r\n\r\ntest", 4).body()).equals("test"));
        rejects(() -> response("HTTP/1.1 302 Found\r\nLocation: http://169.254.169.254\r\n\r\n", 4));
        rejects(() -> response("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 5\r\n\r\n12345", 4));
        rejects(() -> response("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n\r\n12345", 4));
        rejects(() -> response("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 4\r\n\r\nx", 4));
        check(response("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\n\r\n4\r\ntest\r\n0\r\n\r\n", 4).body().length == 4);
        rejects(() -> response("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nTransfer-Encoding: chunked\r\nContent-Length: 4\r\n\r\n", 4));
        rejects(() -> response("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Encoding: gzip\r\n\r\n", 4));
        byte[] png = new byte[33]; byte[] header = {(byte)137,80,78,71,13,10,26,10}; System.arraycopy(header,0,png,0,8);
        java.nio.ByteBuffer.wrap(png,8,4).putInt(13); System.arraycopy(new byte[]{73,72,68,82},0,png,12,4);
        java.nio.ByteBuffer.wrap(png,16,4).putInt(20000); java.nio.ByteBuffer.wrap(png,20,4).putInt(20000);
        rejects(() -> SafeFetch.magic(SafeFetch.Type.PNG, png));
        java.nio.ByteBuffer.wrap(png,16,4).putInt(512); java.nio.ByteBuffer.wrap(png,20,4).putInt(512); SafeFetch.magic(SafeFetch.Type.PNG, png); check(true);
        rejects(() -> SafeFetch.magic(SafeFetch.Type.JSON, "{bad}".getBytes(StandardCharsets.UTF_8)));
        System.out.println("SafeFetch 对抗断言 " + checks + " 条，全部通过");
    }
}
