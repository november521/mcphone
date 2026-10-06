package com.november.mcphone.core.script.client.tex;

import java.util.*;

/** 远程图片在解码前拒绝炸弹头、保持包隔离，并与包内贴图共用显存预算和关闭清理。 */
public final class RemoteImagesTest {
    private static int checks;
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        var fake=AppTexturesTest.reset();var pkg=AppTexturesTest.pkg(Map.of("assets/a.png",AppTexturesTest.png(128,128)));
        for(byte[] bad:List.of(AppTexturesTest.png(20000,20000),AppTexturesTest.png(513,1),AppTexturesTest.png(1,513),AppTexturesTest.png(0,1),AppTexturesTest.png(1,1,65537),new byte[64]))
            check(AppTextures.remotePng(pkg,bad).get("status").equals("INVALID"),"坏格式、超尺寸和超字节拒绝");
        byte[] badHeader=AppTexturesTest.png(16,16);badHeader[11]=12;
        check(AppTextures.remotePng(pkg,badHeader).get("status").equals("INVALID"),"IHDR 长度不为十三时拒绝，不能按错误偏移读取尺寸");
        check(fake.uploads==0,"拒绝在解码和上传前完成");
        byte[] png=AppTexturesTest.png(512,512);var image=AppTextures.remotePng(pkg,png);String src=(String)image.get("src");
        check(image.get("status").equals("READY")&&src.length()<=64&&src.endsWith(".png"),"原生句柄兼容现有 image 和字符串状态上限");
        check(AppTexturesTest.wh(pkg,src).equals("[512, 512]"),"远程尺寸保持 512，不缩成包内 128");
        check(AppTextures.remotePng(pkg,png).get("src").equals(src),"重复内容复用同一份原始字节");
        png[16]=0;check(AppTexturesTest.wh(pkg,src).equals("[512, 512]"),"登记内容不引用调用者可变数组");
        var other=AppTexturesTest.pkg(Map.of("assets/b.png",AppTexturesTest.png(16,16)));
        check(AppTextures.size(other,src)==null,"另一包不能使用远程图片句柄");
        var largePackage=AppTexturesTest.pkg(Map.of("assets/large.png",AppTexturesTest.png(512,512)));
        check(AppTextures.resultOf(largePackage,"assets/large.png")==AppTextures.Result.TOO_BIG,"包内素材原有 128 限制仍成立");
        AppTextures.of(pkg,src);check(fake.uploads==1&&AppTextures.uploaded(pkg,src),"可见时才上传远程 PNG");
        for(int i=1;i<16;i++)check(AppTextures.remotePng(pkg,AppTexturesTest.png(i,2)).get("status").equals("READY"),"最多十六张远程原始图");
        check(AppTextures.remotePng(pkg,AppTexturesTest.png(17,2)).get("status").equals("QUOTA_EXCEEDED"),"第十七张不能无限扩大内存");
        int epoch=AppTextures.epoch();AppTextures.clearRemote();
        check(fake.live==0&&fake.releases==1&&AppTextures.epoch()!=epoch,"撤权立即释放显存并触发布局更新");
        check(AppTextures.size(pkg,src)==null&&AppTextures.size(pkg,"assets/a.png")!=null,"撤权移除远程字节，包内图片仍可显示");
        fake=AppTexturesTest.reset();
        for(int i=0;i<200;i++){var result=AppTextures.remotePng(pkg,AppTexturesTest.png(16,16));AppTextures.of(pkg,(String)result.get("src"));AppTextures.release(pkg);}
        check(fake.live==0&&fake.uploads==200&&fake.releases==200,"开关两百次不积累纹理");
        check(AppTextures.cachedEntries()==0,"关页同步清除原始字节和句柄");
        AppTextures.uploader(null);System.out.println("RemoteImagesTest: "+checks+" passed");
    }
}
