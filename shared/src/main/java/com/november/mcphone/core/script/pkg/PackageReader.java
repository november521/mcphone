package com.november.mcphone.core.script.pkg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * 把一串陌生人给的字节变成校验过的 {@link AppPackage}（施工方案 §3.4）。
 *
 * <p>全程在内存里，一个临时文件都不建。上限见下面几个常量，任一条命中即拒整个包。
 *
 * <p>不用 {@code ZipInputStream}/{@code ZipFile}：JDK 的 {@code ZipEntry} 不暴露 external
 * attributes，符号链接判不了。
 *
 * <p>包必须是规范形状的 ZIP：条目首尾相接铺满中央目录之前的全部字节，中央目录紧接其后，
 * EOCD 紧接中央目录并且结束于文件末尾。不钉死这条，攻击者就能让这个读取器和 {@code ZipFile}
 * 看见两套不同的条目，而摘要只覆盖我们看见的那套 —— 签名于是签在一个「里面还有别的东西」
 * 的文件上。
 */
public final class PackageReader {

    /** 压缩后总字节上限。 */
    public static final int MAX_COMPRESSED = 256 * 1024;
    /** 解压后总字节上限，挡解压炸弹。 */
    public static final int MAX_TOTAL_INFLATED = 1024 * 1024;
    public static final int HARD_COMPRESSED=1024*1024,HARD_INFLATED=4*1024*1024;
    /** 单条目解压后上限。 */
    public static final int MAX_ENTRY_INFLATED = 256 * 1024;
    /** 条目数上限，目录条目也算在内。 */
    public static final int MAX_ENTRIES = 64;
    /** 单条压缩比上限。 */
    public static final int MAX_RATIO = 100;

    /** 清单文件名，必须在包根。 */
    public static final String MANIFEST = "manifest.json";
    /** 不计入摘要的那个目录（§12.4）。 */
    public static final String META_DIR = "META/";
    /**
     * META/ 下允许的文件，<b>封闭列举</b>。
     *
     * <p>§12.3 原文写的是"除 sig.json 外任何文件即拒绝"，而 §12.6 又要求把轮换声明放
     * {@code META/rotate.json} —— 两条直接打架，照 §12.3 写死的话轮换功能永远进不来。
     * 裁定是封闭列举这两个：<b>都不进摘要，都必须自带签名</b>。
     *
     * <p>不留"摘要不覆盖"的自由空间，是这条白名单存在的全部理由。
     */
    public static final String SIG = "META/sig.json";

    /** 密钥轮换声明（§12.6）。同样不进摘要、同样要自带旧密钥的签名。 */
    public static final String ROTATE = "META/rotate.json";

    private static final long SIG_EOCD = 0x06054b50L;
    private static final long SIG_CENTRAL = 0x02014b50L;
    private static final long SIG_LOCAL = 0x04034b50L;
    private static final long SIG_DESCRIPTOR = 0x08074b50L;

    private static final int EOCD_LENGTH = 22;
    private static final int CENTRAL_HEADER = 46;
    private static final int LOCAL_HEADER = 30;

    private static final int METHOD_STORED = 0;
    private static final int METHOD_DEFLATED = 8;

    /** flag 第 3 位：两个长度写在数据后面的描述符里，本地头里那两个是 0。 */
    private static final int FLAG_DESCRIPTOR = 1 << 3;
    private static final int FLAG_ENCRYPTED = 1;

    /** versionMadeBy 的高字节：3 是 UNIX，只有它的 external attributes 里才有文件模式。 */
    private static final int HOST_UNIX = 3;
    private static final int S_IFMT = 0xF000;
    private static final int S_IFLNK = 0xA000;

    private PackageReader() {
    }

    /** 从磁盘读。文件本身超限时连读都不读。 */
    public static AppPackage readFile(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_COMPRESSED) {
            throw PackageError.of(PackageError.Code.E_PKG_TOO_LARGE, size, MAX_COMPRESSED);
        }
        return read(Files.readAllBytes(file));
    }

    /** 主入口。校验不过一律抛 {@link PackageError}，不返回半个包。 */
    public static AppPackage read(byte[] zip) {
        return read(zip,MAX_COMPRESSED,MAX_TOTAL_INFLATED);
    }
    /** 配额只由宿主传入；绝不从 manifest 读取。单条目、压缩比与路径纪律仍固定。 */
    public static AppPackage read(byte[] zip,int compressed,int expanded) {
        if(compressed<65536||compressed>HARD_COMPRESSED||expanded<262144||expanded>HARD_INFLATED)throw new IllegalArgumentException("包配额超出宿主允许范围");
        if (zip.length > compressed) {
            throw PackageError.of(PackageError.Code.E_PKG_TOO_LARGE, zip.length, compressed);
        }

        int eocd = findEocd(zip);
        long cdOffset = u32(zip, eocd + 16);
        List<Central> records = readCentralDirectory(zip, eocd);
        for (Central cd : records) checkName(cd);

        Map<String, byte[]> content = new LinkedHashMap<>();
        List<String> contentPaths = new ArrayList<>();
        MetaFiles meta = walk(zip, records, cdOffset, content, contentPaths,expanded);

        // 撞车要整组一起看：逐条查的时候还不知道后面有没有一个只差大小写的。
        String[] collision = PackageError.PathRules.firstCollision(allPaths(records));
        if (collision != null) {
            throw PackageError.of(PackageError.Code.E_PKG_DUP_PATH, collision[0], collision[1]);
        }

        byte[] manifestBytes = content.get(MANIFEST);
        if (manifestBytes == null) {
            throw PackageError.of(PackageError.Code.E_PKG_NO_MANIFEST);
        }
        Manifest manifest = Manifest.parse(new String(manifestBytes, StandardCharsets.UTF_8));
        BackgroundDeclaration.parse(new String(manifestBytes,StandardCharsets.UTF_8));
        manifest.requireEntries(contentPaths);

        return new AppPackage(manifest, content, meta.signature(), meta.rotate());
    }

    // ============================================================
    //  判据
    // ============================================================

    /** 符号链接与路径判据。不碰内容，所以在解压之前就能跑完。 */
    private static void checkName(Central cd) {
        if (cd.host == HOST_UNIX && (cd.unixMode() & S_IFMT) == S_IFLNK) {
            throw PackageError.of(PackageError.Code.E_PKG_SYMLINK, cd.name);
        }
        if (cd.directory()) {
            // 目录条目没有扩展名，白名单对它不成立，只按形状与深度判。放行它的名字的话，
            // 一个叫 ../evil/ 的目录条目就是静默跳过。
            String shaped = strippedName(cd);
            PackageError.PathRules.Reason why = PackageError.PathRules.reject(shaped);
            if (why != null) {
                throw PackageError.of(PackageError.Code.E_PKG_BAD_PATH, shaped, why.text());
            }
            PackageError.PathRules.requireDepth(shaped);
            return;
        }
        PackageError.PathRules.require(cd.name);
    }

    private static String strippedName(Central cd) {
        return cd.directory() ? cd.name.substring(0, cd.name.length() - 1) : cd.name;
    }

    /** 中央目录里全部条目的名字，目录条目去掉尾斜杠 —— 撞车判据按名字看，不分文件与目录。 */
    private static List<String> allPaths(List<Central> records) {
        List<String> out = new ArrayList<>();
        for (Central cd : records) out.add(strippedName(cd));
        return out;
    }

    // ============================================================
    //  ZIP 结构
    // ============================================================

    private record Central(String name, int method, int flags, long crc, long compressedSize,
                           long uncompressedSize, long localOffset, int host, int externalAttributes) {
        int unixMode() {
            return (externalAttributes >>> 16) & 0xFFFF;
        }

        boolean directory() {
            return name.endsWith("/");
        }
    }

    /**
     * EOCD 后面只能跟着它自己声明的那段注释，再没有别的。
     *
     * <p>回扫取第一个撞见的签名就完事的话会与 {@code ZipFile} 分叉 —— 注释里再塞一个假
     * EOCD，两边就看见两个不同的中央目录。
     */
    private static int findEocd(byte[] zip) {
        if (zip.length < EOCD_LENGTH) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_ZIP, "太短，放不下一个 ZIP 尾");
        }
        int limit = Math.max(0, zip.length - EOCD_LENGTH - 0xFFFF);
        for (int i = zip.length - EOCD_LENGTH; i >= limit; i--) {
            if (u32(zip, i) != SIG_EOCD) continue;
            if (i + EOCD_LENGTH + u16(zip, i + 20) == zip.length) return i;
        }
        throw PackageError.of(PackageError.Code.E_PKG_BAD_ZIP, "找不到 ZIP 尾，这不是一个 zip");
    }

    private static List<Central> readCentralDirectory(byte[] zip, int eocd) {
        int records = u16(zip, eocd + 10);
        long cdSize = u32(zip, eocd + 12);
        long cdOffset = u32(zip, eocd + 16);

        // 上限只有 256 KiB，用得上 ZIP64 的包不存在 —— 出现即是构造出来的。
        if (records == 0xFFFF || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_ZIP, "不接受 ZIP64");
        }
        if (cdOffset + cdSize != eocd) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_ZIP, "中央目录与 ZIP 尾之间有空隙");
        }
        if (records > MAX_ENTRIES) {
            throw PackageError.of(PackageError.Code.E_PKG_TOO_MANY_ENTRIES, records, MAX_ENTRIES);
        }

        List<Central> out = new ArrayList<>();
        int p = (int) cdOffset;
        for (int i = 0; i < records; i++) {
            if (p + CENTRAL_HEADER > eocd || u32(zip, p) != SIG_CENTRAL) {
                throw PackageError.of(PackageError.Code.E_PKG_BAD_ZIP,
                        "中央目录第 " + (i + 1) + " 条的头不对");
            }
            int host = u16(zip, p + 4) >>> 8;
            int flags = u16(zip, p + 8);
            int method = u16(zip, p + 10);
            long crc = u32(zip, p + 16);
            long csize = u32(zip, p + 20);
            long usize = u32(zip, p + 24);
            int nameLen = u16(zip, p + 28);
            int extraLen = u16(zip, p + 30);
            int commentLen = u16(zip, p + 32);
            int extAttrs = (int) u32(zip, p + 38);
            long localOffset = u32(zip, p + 42);

            if (p + CENTRAL_HEADER + nameLen > eocd) {
                throw PackageError.of(PackageError.Code.E_PKG_BAD_ZIP,
                        "中央目录第 " + (i + 1) + " 条的文件名越界");
            }
            String name = utf8(zip, p + CENTRAL_HEADER, nameLen);
            out.add(new Central(name, method, flags, crc, csize, usize, localOffset, host, extAttrs));
            p += CENTRAL_HEADER + nameLen + extraLen + commentLen;
        }
        // 走完 records 条还没到 EOCD，说明中央目录里还有 EOCD 没数进去的记录：ZipFile 按
        // cdSize 走，它看得见那些。
        if (p != eocd) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_ZIP, "中央目录里有 ZIP 尾没数进去的记录");
        }
        return out;
    }

    /**
     * 按本地头的位置顺序走一遍，要求条目首尾相接铺满 {@code [0, cdOffset)}。
     *
     * <p>这一条同时挡住三样：中央目录没列的条目、EOCD 少数了的条目、STORED 条目把后面的
     * 字节吃进自己的内容。返回 {@code META/sig.json} 的内容，没有则 null。
     */
    /** {@code META/} 下那两个文件的内容，没有就是 null。 */
    record MetaFiles(byte[] signature, byte[] rotate) {
    }

    private static MetaFiles walk(byte[] zip, List<Central> records, long cdOffset,
                               Map<String, byte[]> content, List<String> contentPaths,int expanded) {
        List<Central> ordered = new ArrayList<>(records);
        ordered.sort(Comparator.comparingLong(Central::localOffset));

        byte[] signature = null;
        byte[] rotate = null;
        long pos = 0;
        long totalInflated = 0;

        for (Central cd : ordered) {
            if (cd.localOffset != pos) {
                throw PackageError.of(PackageError.Code.E_PKG_BAD_ZIP,
                        "条目 '" + cd.name + "' 的本地头不接在上一条的末尾");
            }
            int data = dataOffset(zip, cd);
            Extracted ex = extract(zip, cd, data, totalInflated,expanded);
            totalInflated += ex.data.length;
            pos = data + ex.compressed;
            if ((cd.flags & FLAG_DESCRIPTOR) != 0) {
                pos += descriptorLength(zip, pos, ex.compressed, cd);
            }

            if (cd.directory()) continue;

            if (cd.name.startsWith(META_DIR)) {
                if (SIG.equals(cd.name)) {
                    signature = ex.data;
                } else if (ROTATE.equals(cd.name)) {
                    rotate = ex.data;
                } else {
                    throw PackageError.of(PackageError.Code.E_PKG_META_EXTRA, cd.name);
                }
                continue;
            }
            contentPaths.add(cd.name);
            content.put(cd.name, ex.data);
        }

        if (pos != cdOffset) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_ZIP,
                    "中央目录之前有 " + (cdOffset - pos) + " 个字节不属于任何条目");
        }
        return new MetaFiles(signature, rotate);
    }

    private record Extracted(byte[] data, long compressed) {
    }

    /**
     * 取一条的内容。解压长度是数出来的，压缩长度取 {@link Inflater#getBytesRead()}，CRC 自己
     * 算一遍对上头里写的 —— 三个数互相钉住，单改 ZIP 头里哪一个都对不上。
     */
    private static Extracted extract(byte[] zip, Central cd, int data, long alreadyInflated,int expanded) {
        if ((cd.flags & FLAG_ENCRYPTED) != 0) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_ZIP, "条目 '" + cd.name + "' 是加密的");
        }
        if (cd.method != METHOD_STORED && cd.method != METHOD_DEFLATED) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_ZIP,
                    "条目 '" + cd.name + "' 用了不认识的压缩方法 " + cd.method);
        }

        Extracted ex = cd.method == METHOD_STORED
                ? stored(zip, cd, data, alreadyInflated,expanded)
                : inflate(zip, cd, data, alreadyInflated,expanded);

        // 交叉相乘，不是相除：整数除法会把 100.9:1 算成 100 放行。
        if (ex.data.length > (long) MAX_RATIO * ex.compressed) {
            long ratio = ex.compressed == 0 ? ex.data.length : ex.data.length / ex.compressed;
            throw PackageError.of(PackageError.Code.E_PKG_RATIO, cd.name, ratio, MAX_RATIO);
        }

        // 头里的两个长度不是拿来用的，是拿来对的：用了就等于信它，对了才说明这个包自洽。
        if (ex.compressed != cd.compressedSize || ex.data.length != cd.uncompressedSize) {
            throw PackageError.of(PackageError.Code.E_PKG_CENTRAL_MISMATCH,
                    "'" + cd.name + "' 头里写 " + cd.compressedSize + "/" + cd.uncompressedSize
                            + " 字节，实际是 " + ex.compressed + "/" + ex.data.length);
        }

        CRC32 crc = new CRC32();
        crc.update(ex.data, 0, ex.data.length);
        if (crc.getValue() != cd.crc) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_ZIP,
                    "条目 '" + cd.name + "' 的 CRC 与头里写的对不上");
        }
        return ex;
    }

    private static Extracted stored(byte[] zip, Central cd, int data, long alreadyInflated,int expanded) {
        // STORED 的长度只能从头里取，那个头就必须自洽。描述符形态下本地头里写的是 0，
        // 无从交叉验证，直接拒。
        if ((cd.flags & FLAG_DESCRIPTOR) != 0) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_ZIP,
                    "条目 '" + cd.name + "' 是 STORED 却把长度写在数据描述符里");
        }
        long len = cd.compressedSize;
        if (len > MAX_ENTRY_INFLATED) {
            throw PackageError.of(PackageError.Code.E_PKG_ENTRY_TOO_LARGE, cd.name, MAX_ENTRY_INFLATED);
        }
        if (alreadyInflated + len > expanded) {
            throw PackageError.of(PackageError.Code.E_PKG_TOTAL_TOO_LARGE, expanded);
        }
        if (data + len > zip.length) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_ZIP, "条目 '" + cd.name + "' 的数据越界");
        }
        byte[] out = new byte[(int) len];
        System.arraycopy(zip, data, out, 0, (int) len);
        return new Extracted(out, len);
    }

    private static Extracted inflate(byte[] zip, Central cd, int data, long alreadyInflated,int expanded) {
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(zip, data, zip.length - data);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            long produced = 0;
            while (!inflater.finished()) {
                int n;
                try {
                    n = inflater.inflate(buf);
                } catch (DataFormatException e) {
                    throw PackageError.of(PackageError.Code.E_PKG_BAD_ZIP,
                            "条目 '" + cd.name + "' 的压缩数据坏了");
                }
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw PackageError.of(PackageError.Code.E_PKG_BAD_ZIP,
                                "条目 '" + cd.name + "' 的压缩数据没写完");
                    }
                    continue;
                }
                produced += n;
                // 在循环里判，不是解完再判：解完再判的话炸弹已经把堆吃光了。
                if (produced > MAX_ENTRY_INFLATED) {
                    throw PackageError.of(PackageError.Code.E_PKG_ENTRY_TOO_LARGE, cd.name, MAX_ENTRY_INFLATED);
                }
                if (alreadyInflated + produced > expanded) {
                    throw PackageError.of(PackageError.Code.E_PKG_TOTAL_TOO_LARGE, expanded);
                }
                out.write(buf, 0, n);
            }
            return new Extracted(out.toByteArray(), inflater.getBytesRead());
        } finally {
            inflater.end();
        }
    }

    /** 本地头必须与中央目录说的是同一条：名字一致，非描述符形态下压缩长度也一致。 */
    private static int dataOffset(byte[] zip, Central cd) {
        long off = cd.localOffset;
        if (off + LOCAL_HEADER > zip.length || u32(zip, (int) off) != SIG_LOCAL) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_ZIP, "条目 '" + cd.name + "' 的本地头不对");
        }
        int nameLen = u16(zip, (int) off + 26);
        int extraLen = u16(zip, (int) off + 28);
        if (off + LOCAL_HEADER + nameLen > zip.length) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_ZIP, "条目 '" + cd.name + "' 的本地头越界");
        }
        String localName = utf8(zip, (int) off + LOCAL_HEADER, nameLen);
        if (!localName.equals(cd.name)) {
            throw PackageError.of(PackageError.Code.E_PKG_CENTRAL_MISMATCH,
                    "'" + cd.name + "' 在本地头里叫 '" + localName + "'");
        }
        if ((cd.flags & FLAG_DESCRIPTOR) == 0) {
            long localCsize = u32(zip, (int) off + 18);
            if (localCsize != cd.compressedSize) {
                throw PackageError.of(PackageError.Code.E_PKG_CENTRAL_MISMATCH,
                        "'" + cd.name + "' 的压缩长度本地头写 " + localCsize
                                + "、中央目录写 " + cd.compressedSize);
            }
        }
        long data = off + LOCAL_HEADER + nameLen + extraLen;
        if (data > zip.length) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_ZIP, "条目 '" + cd.name + "' 的数据起点越界");
        }
        return (int) data;
    }

    /** 数据描述符：可选的 4 字节签名 + crc + 压缩长度 + 解压长度。 */
    private static int descriptorLength(byte[] zip, long pos, long compressed, Central cd) {
        boolean signed = pos + 4 <= zip.length && u32(zip, (int) pos) == SIG_DESCRIPTOR;
        int base = (int) pos + (signed ? 4 : 0);
        if (base + 12 > zip.length) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_ZIP,
                    "条目 '" + cd.name + "' 的数据描述符越界");
        }
        if (u32(zip, base) != cd.crc || u32(zip, base + 4) != compressed) {
            throw PackageError.of(PackageError.Code.E_PKG_CENTRAL_MISMATCH,
                    "'" + cd.name + "' 的数据描述符与中央目录对不上");
        }
        return signed ? 16 : 12;
    }

    /** 条目名必须是合法 UTF-8：坏字节被替换成 U+FFFD 的话，不同的包会算出同一个摘要。 */
    private static String utf8(byte[] b, int off, int len) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(b, off, len)).toString();
        } catch (CharacterCodingException e) {
            throw PackageError.of(PackageError.Code.E_PKG_BAD_ENTRY_NAME);
        }
    }

    private static int u16(byte[] b, int off) {
        if (off < 0 || off + 2 > b.length) throw PackageError.of(PackageError.Code.E_PKG_BAD_ZIP, "读越界");
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int off) {
        if (off < 0 || off + 4 > b.length) throw PackageError.of(PackageError.Code.E_PKG_BAD_ZIP, "读越界");
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }
}
