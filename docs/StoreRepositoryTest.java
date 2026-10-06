package com.november.mcphone.core.script.pkg;

import com.november.mcphone.core.script.server.StoreRepository;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class StoreRepositoryTest {
    private static int checks;
    private static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
    private static byte[] zip(Map<String,byte[]> content,byte[] sig)throws IOException {
        ByteArrayOutputStream raw=new ByteArrayOutputStream();try(ZipOutputStream out=new ZipOutputStream(raw,StandardCharsets.UTF_8)) {
            Map<String,byte[]> all=new LinkedHashMap<>(content);if(sig!=null)all.put(PackageReader.SIG,sig);
            for(var e:all.entrySet()){out.putNextEntry(new ZipEntry(e.getKey()));out.write(e.getValue());out.closeEntry();}
        }return raw.toByteArray();
    }
    public static void main(String[]args)throws Exception {
        Path dir=Files.createTempDirectory("mcphone-store-test");StoreRepository repository=new StoreRepository(dir);UUID sender=UUID.randomUUID(),reviewer=UUID.randomUUID();
        var key=Signatures.generate();var content=PackageSignTest.content();String digest=PackageDigest.of(content);
        byte[] raw=zip(content,PackageSignTest.sigJson(key,digest,"unverified author"));
        var submitted=repository.submit(raw,sender,"name\n\r\u0000fake",10);
        check(submitted.state()==StoreRepository.State.PENDING,"提交不授予权限、不自动上架");
        check(submitted.submitter().equals(sender)&&submitted.reviewer()==null,"提交者独立于作者和审核者");
        check(!submitted.name().contains("\n")&&!submitted.name().contains("\u0000"),"名字控制字符清理");
        check(repository.submit(raw,UUID.randomUUID(),"other",11).submitter().equals(sender),"重复摘要保留第一次提交身份");
        check(Arrays.equals(repository.raw(digest),raw),"原签名包完整保存");
        repository.review(digest,reviewer,true,"reviewed","licensed",20);
        StoreRepository reopened=new StoreRepository(dir);var e=reopened.entry(digest);
        check(e.state()==StoreRepository.State.APPROVED&&e.visibility().equals("licensed"),"审核结果和可见性持久化");
        check(e.reviewer().equals(reviewer)&&e.reviewed()==20,"审核身份与时间持久化");
        reopened.review(digest,reviewer,false,"源码不符合服规","hidden",30);
        check(new StoreRepository(dir).entry(digest).state()==StoreRepository.State.REJECTED,"驳回重启后仍不显示");
        boolean refused=false;try{reopened.submit(zip(content,null),sender,"unsigned",40);}catch(IllegalArgumentException expected){refused=true;}check(refused,"商店原包必须验签");
        Files.write(dir.resolve(digest+".mcphone"),new byte[]{1,2,3});refused=false;try{new StoreRepository(dir);}catch(IOException expected){refused=true;}check(refused,"已批准原包被改后启动关闭商店");
        check(StoreRepository.clean("<b>已通过</b>\n✅",64).equals("<b>已通过</b>✅"),"保留普通文本、不解释富文本");
        System.out.println("StoreRepositoryTest: "+checks+" passed");
    }
}
