package com.november.mcphone.core.script.pkg;

import com.google.gson.*;
import com.november.mcphone.core.script.JsonScan;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** 前端分发封套：签名仍校验作者原包摘要，被剔除的后端只有不可逆的逐文件哈希。 */
public final class FrontendEnvelope {
    public static final int MAX_BYTES=PackageReader.HARD_COMPRESSED+16384+12;
    private static final int MAGIC=0x4d435031;
    private FrontendEnvelope() { }
    public static byte[] write(AppPackage pkg) throws IOException {
        if(!pkg.signed() || !SigManifest.parse(pkg.signature()).verify(pkg.digest())) throw new IllegalArgumentException("前端分发必须带有效作者签名");
        JsonObject proof=new JsonObject(); Map<String,byte[]> front=new LinkedHashMap<>();
        for(var entry:pkg.entries().entrySet()) {
            if(FrontendDigest.isServerSide(entry.getKey())) proof.addProperty(entry.getKey(),Base64.getEncoder().encodeToString(PackageDigest.leaf(entry.getKey(),entry.getValue())));
            else front.put(entry.getKey(),entry.getValue());
        }
        front.put(PackageReader.SIG,pkg.signature()); if(pkg.rotate()!=null) front.put(PackageReader.ROTATE,pkg.rotate());
        ByteArrayOutputStream zip=new ByteArrayOutputStream();
        try(ZipOutputStream out=new ZipOutputStream(zip,StandardCharsets.UTF_8)) {
            out.setLevel(Deflater.BEST_COMPRESSION);
            for(var entry:front.entrySet()) {
                ZipEntry e=new ZipEntry(entry.getKey()); e.setTime(0);
                // 原包可能把重复文本按 STORED 保存。重新压缩不能把一个合法包变成压缩炸弹拒绝项。
                Deflater probe=new Deflater(Deflater.BEST_COMPRESSION,true);int compressed=0;
                try {probe.setInput(entry.getValue());probe.finish();byte[] buffer=new byte[4096];while(!probe.finished())compressed+=probe.deflate(buffer);}
                finally {probe.end();}
                if(entry.getValue().length>(long)Math.max(1,compressed)*PackageReader.MAX_RATIO) {
                    CRC32 crc=new CRC32();crc.update(entry.getValue());e.setMethod(ZipEntry.STORED);e.setSize(entry.getValue().length);e.setCompressedSize(entry.getValue().length);e.setCrc(crc.getValue());
                }
                out.putNextEntry(e); out.write(entry.getValue()); out.closeEntry();
            }
        }
        byte[] raw=zip.toByteArray(), meta=proof.toString().getBytes(StandardCharsets.UTF_8);
        if(raw.length>PackageReader.HARD_COMPRESSED || meta.length>16384) throw new IllegalArgumentException("前端封套超过上限");
        // 先用与客户端相同的 ZIP 闸复验重新打包结果。
        PackageReader.read(raw,PackageReader.HARD_COMPRESSED,PackageReader.HARD_INFLATED);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(DataOutputStream out=new DataOutputStream(bytes)) { out.writeInt(MAGIC); out.writeInt(raw.length); out.write(raw); out.writeInt(meta.length); out.write(meta); }
        return bytes.toByteArray();
    }
    public static AppPackage read(byte[] bytes) throws IOException {
        if(bytes.length>MAX_BYTES) throw new IllegalArgumentException("前端封套超额");
        try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(bytes))) {
            if(input.readInt()!=MAGIC) throw new IllegalArgumentException("前端封套标记错误");
            int length=input.readInt(); if(length<1 || length>PackageReader.HARD_COMPRESSED) throw new IllegalArgumentException("前端 ZIP 长度无效");
            byte[] zip=input.readNBytes(length); if(zip.length!=length) throw new EOFException(); AppPackage front=PackageReader.read(zip,PackageReader.HARD_COMPRESSED,PackageReader.HARD_INFLATED);
            if(front.paths().stream().anyMatch(FrontendDigest::isServerSide)) throw new IllegalArgumentException("前端封套夹带后端源码");
            int metaLength=input.readInt(); if(metaLength<2 || metaLength>16384) throw new IllegalArgumentException("前端证明长度无效");
            byte[] meta=input.readNBytes(metaLength); if(meta.length!=metaLength || input.read()!=-1) throw new IllegalArgumentException("前端封套截断或尾随数据");
            String json=new String(meta,StandardCharsets.UTF_8); if(JsonScan.check(json,2)!=null) throw new IllegalArgumentException("前端证明不是严格 JSON");
            JsonObject object=JsonParser.parseString(json).getAsJsonObject(); Map<String,byte[]> proof=new LinkedHashMap<>();
            if(object.size()+front.paths().size()>PackageReader.MAX_ENTRIES) throw new IllegalArgumentException("证明条目超额");
            for(var entry:object.entrySet()) {
                String path=entry.getKey();
                PackageError.PathRules.require(path);
                if(!FrontendDigest.isServerSide(path)) throw new IllegalArgumentException("后端证明路径无效");
                byte[] hash=Base64.getDecoder().decode(entry.getValue().getAsString()); if(hash.length!=32) throw new IllegalArgumentException("证明哈希长度无效"); proof.put(path,hash);
            }
            var allPaths=new ArrayList<>(front.paths()); allPaths.addAll(proof.keySet()); PackageError.PathRules.requireAll(allPaths);
            AppPackage pkg=new AppPackage(front.manifest(),front.entries(),front.signature(),front.rotate(),proof);
            if(!pkg.signed() || !SigManifest.parse(pkg.signature()).verify(pkg.digest())) throw new IllegalArgumentException("前端证明与作者签名不匹配");
            return pkg;
        }
    }
}
