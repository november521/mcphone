package com.november.mcphone.core.script.server;

import com.google.gson.*;
import java.nio.file.*;
import java.time.*;

/** 精确 App、含边界版本、UTC 日期、损坏行及输出路径不能由请求决定。 */
public final class AuditExportTest {
    private static int checks;private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[]args)throws Exception {Path root=Files.createTempDirectory("mcphone-audit-export");AuditLog audit=new AuditLog(root,()->QuotaConfig.DEFAULT);JsonObject data=new JsonObject();data.addProperty("version","56");audit.append(null,"script.land","t:a",null,data);data.addProperty("version","57");audit.append(null,"script.land","t:a",null,data);audit.append(null,"script.land","t:b",null,data);LocalDate today=LocalDate.now(ZoneOffset.UTC);var result=AuditExport.run(root,new AuditExport.Filter("t:a",56,56,today,today));check(result.rows()==1,"版本上下限都含边界，且 App 精确过滤");Path file=root.resolve("exports").resolve(Path.of(result.file()).getFileName());check(Files.isRegularFile(file)&&Files.size(file)==result.bytes(),"写入固定导出目录，字节数一致");check(JsonParser.parseString(Files.readString(file).strip()).getAsJsonObject().getAsJsonObject("after").get("version").getAsString().equals("56"),"不导出范围外的版本");check(AuditExport.run(root,new AuditExport.Filter("t:a",0,Long.MAX_VALUE,today.minusDays(2),today.minusDays(1))).rows()==0,"按 UTC 文件日期过滤");
        try{new AuditExport.Filter("../../escape",0,1,today,today);throw new AssertionError("App 可改路径");}catch(IllegalArgumentException expected){checks++;}try{new AuditExport.Filter("t:a",2,1,today,today);throw new AssertionError("范围颠倒未拒");}catch(IllegalArgumentException expected){checks++;}
        Files.writeString(root.resolve(today+".jsonl"),"broken\n",StandardOpenOption.APPEND);try{AuditExport.run(root,new AuditExport.Filter("t:a",0,99,today,today));throw new AssertionError("损坏行被静默略过");}catch(java.io.IOException expected){checks++;}try(var files=Files.newDirectoryStream(root.resolve("exports"),"*.part")){check(!files.iterator().hasNext(),"失败删除本次临时报告");}
        Path utf=Files.createTempDirectory("mcphone-audit-utf8");Files.write(utf.resolve(today+".jsonl"),new byte[]{(byte)0xc3,0x28,10});
        try{AuditExport.run(utf,new AuditExport.Filter("t:a",0,99,today,today));throw new AssertionError("非法 UTF-8 被替换后导出");}catch(java.io.IOException expected){checks++;}
        Path capacity=Files.createTempDirectory("mcphone-audit-export-full");Files.createDirectories(capacity.resolve("exports"));
        for(int i=0;i<32;i++)Files.writeString(capacity.resolve("exports").resolve(String.format("%032x.jsonl",i)),"existing");
        try{AuditExport.run(capacity,new AuditExport.Filter("t:a",0,99,today,today));throw new AssertionError("第 33 份报告未拒绝");}catch(java.io.IOException expected){checks++;}
        check(Files.readString(capacity.resolve("exports").resolve(String.format("%032x.jsonl",0))).equals("existing"),"导出满额保留原报告");
        System.out.println("AuditExportTest: "+checks+" passed");
    }
}
