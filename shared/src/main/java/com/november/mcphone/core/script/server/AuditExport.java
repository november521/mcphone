package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.core.script.JsonScan;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** 按 App、版本区间及 UTC 日期过滤。输入、行长、输出与运行时均有上限；只写固定导出目录。 */
public final class AuditExport {
    public record Filter(String app,long minimum,long maximum,LocalDate from,LocalDate to){public Filter{if(app==null||app.length()>64||!app.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||minimum<0||maximum<minimum||from==null||to==null||from.isAfter(to)||from.plusDays(365).isBefore(to))throw new IllegalArgumentException("审计过滤条件无效");}}
    public record Result(String file,long rows,long bytes){}
    private AuditExport(){}
    public static Result run(Path audit,Filter filter)throws IOException {synchronized(AuditExport.class){return export(audit,filter);}}
    private static Result export(Path audit,Filter filter)throws IOException {Path root=audit.toAbsolutePath().normalize(),exports=root.resolve("exports");if(Files.isSymbolicLink(root)||Files.isSymbolicLink(exports))throw new IOException("审计目录不能是链接");Files.createDirectories(exports);long occupied=capacity(exports);String id=UUID.randomUUID().toString().replace("-","");Path tmp=exports.resolve(id+".part"),target=exports.resolve(id+".jsonl");long deadline=System.nanoTime()+30_000_000_000L,rows=0,bytes=0,read=0;List<Path> files=new ArrayList<>();try(var directory=Files.newDirectoryStream(root,"*.jsonl")){for(Path file:directory){String name=file.getFileName().toString();if(!name.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}\\.jsonl"))continue;LocalDate day;try{day=LocalDate.parse(name.substring(0,10));}catch(DateTimeException bad){continue;}if(!day.isBefore(filter.from())&&!day.isAfter(filter.to()))files.add(file);}}files.sort(Comparator.comparing(p->p.getFileName().toString()));
        boolean complete=false;try(OutputStream out=Files.newOutputStream(tmp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){for(Path file:files){if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new IOException("审计导出超过 30 秒，请缩小日期范围");if(Files.isSymbolicLink(file)||!Files.isRegularFile(file)||Files.size(file)>64*1024*1024L)throw new IOException("审计源文件异常");read=Math.addExact(read,Files.size(file));if(read>512*1024*1024L)throw new IOException("输入超过 512 MiB，请缩小日期范围");try(InputStream input=new BufferedInputStream(Files.newInputStream(file))){ByteArrayOutputStream line=new ByteArrayOutputStream();int next;while((next=input.read())!=-1){if(next!=10){if((line.size()&4095)==0&&(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline))throw new IOException("审计导出超过 30 秒");if(line.size()>=4*1024*1024)throw new IOException("审计行超额");line.write(next);continue;}byte[] raw=line.toByteArray();line.reset();if(Thread.currentThread().isInterrupted()||System.nanoTime()>deadline)throw new IOException("审计导出超过 30 秒");if(matches(raw,filter)){bytes+=raw.length+1;if(bytes>16*1024*1024||bytes>64*1024*1024L-occupied)throw new IOException("输出超过 16 MiB，请缩小版本或日期范围");out.write(raw);out.write(10);rows++;}}if(line.size()!=0)throw new IOException("审计尾行未完成，稍后重试");}}out.flush();complete=true;
        }finally{if(!complete&&tmp.toAbsolutePath().normalize().getParent().equals(exports))Files.deleteIfExists(tmp);}
        try{try(FileChannel channel=FileChannel.open(tmp,StandardOpenOption.WRITE)){channel.force(true);}Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE);return new Result("mcphone/audit/exports/"+target.getFileName(),rows,bytes);}finally{Files.deleteIfExists(tmp);}
    }
    /** 固定导出目录总量 64 MiB/32 份。满额保留旧报告，由服主归档后再创建。 */
    private static long capacity(Path exports)throws IOException {
        long bytes=0;int count=0;
        try(var files=Files.newDirectoryStream(exports)){for(Path file:files){
            if(!file.getFileName().toString().matches("[a-f0-9]{32}\\.(jsonl|part)"))continue;
            if(Files.isSymbolicLink(file)||!Files.isRegularFile(file))throw new IOException("导出报告异常");
            count++;bytes=Math.addExact(bytes,Files.size(file));
            if(count>=32||bytes>=64*1024*1024L)throw new IOException("导出目录已满，请服主归档旧报告后再试");
        }}return bytes;
    }
    private static boolean matches(byte[] bytes,Filter filter)throws IOException {try{String raw=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();if(JsonScan.check(raw,24)!=null)throw new IllegalArgumentException();JsonObject row=JsonParser.parseString(raw).getAsJsonObject();if(!row.has("subject")||!row.get("subject").getAsString().equals(filter.app())||!row.has("after")||!row.get("after").isJsonObject()||!row.getAsJsonObject("after").has("version"))return false;long version=row.getAsJsonObject("after").get("version").getAsBigDecimal().longValueExact();return version>=filter.minimum()&&version<=filter.maximum();}catch(RuntimeException|java.nio.charset.CharacterCodingException bad){throw new IOException("审计行损坏，禁止生成不完整报告",bad);}}
}
