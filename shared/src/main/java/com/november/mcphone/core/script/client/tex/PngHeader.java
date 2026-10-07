package com.november.mcphone.core.script.client.tex;

/**
 * PNG 的文件头（施工方案 §5.2）。只看前 24 个字节拿宽高，不解码像素。
 *
 * <p>先看头再解码是硬要求：一张 64 KiB 的 PNG 可以在 IHDR 里声称自己 20000×20000，
 * 等 NativeImage 读回来再看尺寸，那 1.6 GB 已经分配过了。
 *
 * <p>{@link com.november.mcphone.core.client.PhoneSkin} 里另有一份读流的同款，那份看的是
 * 资源包里自己人的贴图，不查签名；这份收的是第三方包里的字节，假扩展名要在解码前挡住。
 */
final class PngHeader {

    /** PNG 的 8 字节签名。改了后缀名的 zip / jpg 在这里就走不下去。 */
    private static final byte[] MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    private PngHeader() {
    }

    /** {宽, 高}；不是合法 PNG 返回 null。 */
    static int[] size(byte[] png) {
        if (png == null || png.length < 24) return null;
        for (int i = 0; i < MAGIC.length; i++) {
            if (png[i] != MAGIC[i]) return null;
        }
        // 第一个块必须是 IHDR。签名之后不查这个的话，畸形文件里读出来的是两个天文数字
        if (int32(png, 8) != 13 || png[12] != 'I' || png[13] != 'H' || png[14] != 'D' || png[15] != 'R') return null;

        int w = int32(png, 16);
        int h = int32(png, 20);
        // PNG 的宽高是 31 位无符号，最高位置 1 的写法不合法；按有符号读出来正好是负数
        if (w <= 0 || h <= 0) return null;
        return new int[]{w, h};
    }

    private static int int32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24)
             | ((b[off + 1] & 0xFF) << 16)
             | ((b[off + 2] & 0xFF) << 8)
             | (b[off + 3] & 0xFF);
    }
}
