package com.november.mcphone.core.script.server;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** HTTPS 出口：一次 DNS、直连已验证 IP，原域名验证证书和 SNI；永不重定向。 */
public final class SafeFetch {
    public enum Type { TEXT, JSON, XML, PNG, ZIP }
    public record Response(Type type, byte[] body) { public Response { body = body.clone(); } @Override public byte[] body() { return body.clone(); } public int size(){return body.length;} }
    public static final int MAX_BYTES = 262144;
    private static final ThreadPoolExecutor DNS = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), r -> { var t = new Thread(r, "MCphone-DNS"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.AbortPolicy());
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "MCphone-HTTPS-deadline"); t.setDaemon(true); return t;
    });
    private SafeFetch() {}
    public static URI validate(String url, Set<String> allowedHosts) {
        URI uri = URI.create(url);
        String host = uri.getHost();
        if (url.length() > 2048 || !"https".equals(uri.getScheme()) || host == null
                || uri.getRawUserInfo() != null || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || !host.matches("[a-zA-Z0-9.-]+") || host.endsWith(".") || !allowedHosts.contains(host.toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("HTTPS 域名未授权或 URL 无效");
        return uri;
    }
    public static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255;
            return a != 0 && a != 10 && a != 127 && a < 224 && !(a == 100 && b >= 64 && b <= 127)
                    && !(a == 169 && b == 254) && !(a == 172 && b >= 16 && b <= 31)
                    && !(a == 192 && (b == 168 || b == 0 || (b == 2)))
                    && !(a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
                    && !(a == 203 && b == 0 && c == 113);
        }
        if (bytes.length != 16) return false;
        // 只允许 2000::/3 的全局单播；拒 IPv4 映射、ULA、链路本地和隧道前缀。
        int first = bytes[0] & 255;
        if ((first & 0xE0) != 0x20) return false;
        if (first == 0x20 && (bytes[1] & 255) == 0x01 && ((bytes[2] & 255) < 2 || (bytes[2] & 255) == 0x0D && (bytes[3] & 255) == 0xB8)) return false;
        return !(first == 0x20 && (bytes[1] & 255) == 0x02); // 6to4 可映射内网 IPv4。
    }
    public static Response get(String url, Set<String> hosts, Set<Type> types, int maxBytes, String authorization) throws IOException {
        URI uri = validate(url, hosts);
        if (maxBytes < 1 || maxBytes > MAX_BYTES) throw new IllegalArgumentException("响应上限无效");
        if (authorization != null && (authorization.length() > 2048 || authorization.chars().anyMatch(c -> c < 32 || c > 126)))
            throw new IllegalArgumentException("凭证格式无效");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        InetAddress[] addresses;
        Future<InetAddress[]> lookup;
        try { lookup = DNS.submit(() -> InetAddress.getAllByName(uri.getHost())); }
        catch (RejectedExecutionException busy) { throw new IOException("DNS 出口繁忙"); }
        try { addresses = lookup.get(15, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { lookup.cancel(true); Thread.currentThread().interrupt(); throw new IOException("DNS 已取消"); }
        catch (ExecutionException | TimeoutException failure) { lookup.cancel(true); throw new IOException("DNS 不可用"); }
        if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(a -> !publicAddress(a))) throw new IOException("域名含非公网地址");
        Socket raw = new Socket();
        ScheduledFuture<?> timeout = DEADLINES.schedule(() -> { try { raw.close(); } catch (IOException ignored) {} }, Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
        try (raw) {
            // InetSocketAddress(InetAddress, ...) 不会第二次解析域名。
            raw.connect(new InetSocketAddress(addresses[0], 443), Math.min(5000, remaining(deadline)));
            SSLSocketFactory tlsFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket tls = (SSLSocket) tlsFactory.createSocket(raw, uri.getHost(), 443, true)) {
                SSLParameters params = tls.getSSLParameters(); params.setEndpointIdentificationAlgorithm("HTTPS");
                params.setServerNames(List.of(new SNIHostName(uri.getHost()))); tls.setSSLParameters(params);
                tls.setSoTimeout(remaining(deadline)); tls.startHandshake();
                String path = uri.getRawPath(); if (path == null || path.isEmpty()) path = "/";
                if (uri.getRawQuery() != null) path += "?" + uri.getRawQuery();
                String version = com.november.mcphone.MCphone.getVersion().replaceAll("[^A-Za-z0-9._-]", "");
                String request = "GET " + path + " HTTP/1.1\r\nHost: " + uri.getHost() + "\r\nUser-Agent: MCphone/" + (version.isEmpty() ? "dev" : version) + "\r\nAccept-Encoding: identity\r\nConnection: close\r\n"
                        + (authorization == null ? "" : "Authorization: " + authorization + "\r\n") + "\r\n";
                tls.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII)); tls.getOutputStream().flush();
                return readResponse(tls.getInputStream(), types, maxBytes, () -> {
                    try { tls.setSoTimeout(remaining(deadline)); } catch (IOException failure) { throw new UncheckedIOException(failure); }
                });
            }
        } catch (UncheckedIOException failure) { throw failure.getCause(); }
        finally { timeout.cancel(false); }
    }
    private static int remaining(long deadline) throws SocketTimeoutException {
        long nanos = deadline - System.nanoTime(); if (nanos <= 0) throw new SocketTimeoutException("HTTPS 总超时");
        return (int) Math.min(15000, Math.max(1, TimeUnit.NANOSECONDS.toMillis(nanos)));
    }
    /** 可用内存流测试所有 HTTP framing 闸；每次读前更新绝对超时。 */
    static Response readResponse(InputStream input, Set<Type> types, int max, Runnable beforeRead) throws IOException {
        String status = line(input, beforeRead);
        if (!status.matches("HTTP/1\\.[01] 200(?: .*)?")) throw new IOException("HTTPS 状态不接受，重定向不会跟随");
        Map<String, String> headers = new HashMap<>(); int total = 0;
        while (true) {
            String header = line(input, beforeRead); total += header.length(); if (total > 16384) throw new IOException("HTTP 头超额");
            if (header.isEmpty()) break;
            int colon = header.indexOf(':'); if (colon <= 0 || header.startsWith(" ") || header.startsWith("\t")) throw new IOException("HTTP 头无效");
            String key = header.substring(0, colon).toLowerCase(Locale.ROOT), value = header.substring(colon + 1).trim();
            if (headers.putIfAbsent(key, value) != null) throw new IOException("重复 HTTP 头");
        }
        String encoding = headers.getOrDefault("content-encoding", "identity"); if (!encoding.equals("identity")) throw new IOException("压缩响应不接受");
        Type type = contentType(headers.getOrDefault("content-type", "").split(";", 2)[0].trim().toLowerCase(Locale.ROOT));
        if (!types.contains(type)) throw new IOException("响应类型未授权");
        if (type == Type.PNG) max = Math.min(max, 65536);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String transfer = headers.get("transfer-encoding"), length = headers.get("content-length");
        if (transfer != null) {
            if (!transfer.equals("chunked") || length != null) throw new IOException("HTTP 长度有歧义");
            while (true) {
                String size = line(input, beforeRead); if (!size.matches("[0-9a-fA-F]{1,8}")) throw new IOException("chunk 长度无效");
                long n = Long.parseLong(size, 16); if (n > max - out.size()) throw new IOException("响应超过字节上限");
                if (n == 0) { if (!line(input, beforeRead).isEmpty()) throw new IOException("不接受 trailers"); break; }
                exact(input, out, (int) n, beforeRead); if (!line(input, beforeRead).isEmpty()) throw new IOException("chunk 尾部无效");
            }
        } else if (length != null) {
            if (!length.matches("[0-9]{1,10}")) throw new IOException("长度无效");
            long n = Long.parseLong(length); if (n > max) throw new IOException("响应超过字节上限"); exact(input, out, (int) n, beforeRead);
        } else {
            byte[] chunk = new byte[4096]; int n;
            while (true) { beforeRead.run(); n = input.read(chunk, 0, Math.min(chunk.length, max - out.size() + 1));
                if (n < 0) break; if (n + out.size() > max) throw new IOException("响应超过字节上限"); out.write(chunk, 0, n); }
        }
        byte[] body = out.toByteArray(); magic(type, body); return new Response(type, body);
    }
    private static Type contentType(String type) throws IOException {
        return switch (type) {
            case "text/plain" -> Type.TEXT;
            case "application/json" -> Type.JSON;
            case "application/xml", "text/xml", "application/rss+xml" -> Type.XML;
            case "image/png" -> Type.PNG;
            case "application/zip" -> Type.ZIP;
            default -> throw new IOException("Content-Type 不在白名单");
        };
    }
    private static String line(InputStream input, Runnable beforeRead) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (out.size() <= 2048) {
            beforeRead.run(); int b = input.read(); if (b < 0) throw new EOFException("HTTP 行被截断");
            if (b == '\r') { beforeRead.run(); if (input.read() != '\n') throw new IOException("HTTP 换行无效"); return out.toString(StandardCharsets.US_ASCII); }
            if (b == '\n' || b == 0 || b > 127) throw new IOException("HTTP 行无效"); out.write(b);
        }
        throw new IOException("HTTP 行超过 2 KiB");
    }
    private static void exact(InputStream input, ByteArrayOutputStream out, int size, Runnable beforeRead) throws IOException {
        byte[] bytes = new byte[4096];
        while (size > 0) { beforeRead.run(); int n = input.read(bytes, 0, Math.min(size, bytes.length));
            if (n < 0) throw new EOFException("响应被截断"); if (n == 0) continue; out.write(bytes, 0, n); size -= n; }
    }
    static void magic(Type type, byte[] bytes) throws IOException {
        if (type == Type.PNG) {
            byte[] signature = {(byte)137, 80, 78, 71, 13, 10, 26, 10};
            if (bytes.length < 33 || !Arrays.equals(signature, Arrays.copyOf(bytes, 8))
                    || java.nio.ByteBuffer.wrap(bytes, 8, 4).getInt() != 13
                    || !Arrays.equals(Arrays.copyOfRange(bytes, 12, 16), new byte[]{73,72,68,82})) throw new IOException("PNG 头无效");
            int w = java.nio.ByteBuffer.wrap(bytes, 16, 4).getInt(), h = java.nio.ByteBuffer.wrap(bytes, 20, 4).getInt();
            if (w <= 0 || h <= 0 || w > 512 || h > 512 || (long) w * h > 262144) throw new IOException("PNG 尺寸超额");
        } else if (type == Type.ZIP && (bytes.length < 4 || bytes[0] != 80 || bytes[1] != 75 || bytes[2] != 3 || bytes[3] != 4)) throw new IOException("ZIP 魔数无效");
        else if (type == Type.JSON || type == Type.XML || type == Type.TEXT) {
            String text;
            try { text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
            catch (java.nio.charset.CharacterCodingException bad) { throw new IOException("文本不是 UTF-8"); }
            if (type == Type.JSON && com.november.mcphone.core.script.JsonScan.check(text, 16) != null) throw new IOException("JSON 无效");
            if (type == Type.XML && !text.stripLeading().startsWith("<")) throw new IOException("XML 魔数无效");
        }
    }
}
