package com.november.mcphone.core.script.pkg;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** 原作者签名仍成立，服务端源码不进入客户端，删改任何前端字节都会使证明失效。 */
public final class FrontendEnvelopeTest {
    private static int checks;
    private static void check(boolean v,String why) { checks++; if(!v) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        var key=Signatures.generate(); var files=new LinkedHashMap<>(PackageSignTest.content());
        files.put("server.js","private backend source with credential".getBytes(StandardCharsets.UTF_8));
        files.put("server/lib.js","another private module".getBytes(StandardCharsets.UTF_8));
        AppPackage original=PackageSignTest.pkg(files,PackageSignTest.sigJson(key,PackageDigest.of(files),"author"));
        byte[] wire=FrontendEnvelope.write(original); AppPackage received=FrontendEnvelope.read(wire);
        check(received.digest().equals(original.digest()),"重建完整原包摘要");
        check(SigManifest.parse(received.signature()).verify(received.digest()),"同一作者公钥签名仍有效");
        check(FrontendDigest.of(received).equals(FrontendDigest.of(original)),"前端摘要保持握手一致");
        check(received.entry("server.js")==null && received.entry("server/lib.js")==null,"源码从实际客户端条目中剔除");
        check(Arrays.equals(received.entry("app.vue"),original.entry("app.vue")),"前端原文保持完整");
        byte[] trailing=Arrays.copyOf(wire,wire.length+1); boolean rejected=false;
        try { FrontendEnvelope.read(trailing); } catch(RuntimeException | IOException e) { rejected=true; }
        check(rejected,"封套尾随数据拒绝");
        DataInputStream input=new DataInputStream(new ByteArrayInputStream(wire)); int magic=input.readInt(); byte[] zip=input.readNBytes(input.readInt()); byte[] proof=input.readNBytes(input.readInt());
        Map<String,byte[]> frontend=new LinkedHashMap<>();
        try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(zip),StandardCharsets.UTF_8)) {
            for(ZipEntry e;(e=z.getNextEntry())!=null;) frontend.put(e.getName(),z.readAllBytes());
        }
        frontend.put("app.vue","modified frontend".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream alteredZip=new ByteArrayOutputStream();
        try(ZipOutputStream z=new ZipOutputStream(alteredZip,StandardCharsets.UTF_8)) {
            for(var e:frontend.entrySet()) { z.putNextEntry(new ZipEntry(e.getKey())); z.write(e.getValue()); z.closeEntry(); }
        }
        ByteArrayOutputStream altered=new ByteArrayOutputStream();
        try(DataOutputStream o=new DataOutputStream(altered)) { o.writeInt(magic); o.writeInt(alteredZip.size()); o.write(alteredZip.toByteArray()); o.writeInt(proof.length); o.write(proof); }
        rejected=false; try { FrontendEnvelope.read(altered.toByteArray()); } catch(RuntimeException | IOException e) { rejected=true; }
        check(rejected,"前端被改、后端证明与签名没改：硬拒绝");
        check(PackageDigest.of(files).equals(PackageDigest.ofLeaves(files.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,e->PackageDigest.leaf(e.getKey(),e.getValue()))))),"旧摘要算法与叶子重建逐字相同");
        System.out.println("FrontendEnvelopeTest: "+checks+" passed");
    }
}
