package com.november.mcphone.core.script.server;

import com.google.gson.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** UTC 每日审计文件；先强制落盘再允许管理写入，保留天数与日容量由服主控制。 */
public final class AuditLog {
    private final Path root;private final Supplier<QuotaConfig> quotas;
    public AuditLog(Path root,Supplier<QuotaConfig> quotas){this.root=root.toAbsolutePath().normalize();this.quotas=quotas;}
    public synchronized void append(UUID actor,String action,String subject,JsonElement before,JsonElement after)throws IOException {
        Files.createDirectories(root);if(Files.isSymbolicLink(root))throw new IOException("审计目录不能是链接");LocalDate today=LocalDate.now(ZoneOffset.UTC);Path file=root.resolve(today+".jsonl");if(Files.isSymbolicLink(file))throw new IOException("审计路径不能是链接");
        JsonObject row=new JsonObject();row.addProperty("at",Instant.now().toString());row.addProperty("actor",actor==null?"console":actor.toString());row.addProperty("action",StoreRepository.clean(action,64));row.addProperty("subject",StoreRepository.clean(subject,128));if(before!=null)row.add("before",before.deepCopy());if(after!=null)row.add("after",after.deepCopy());byte[] bytes=(row+"\n").getBytes(StandardCharsets.UTF_8);if(bytes.length>4*1024*1024||(Files.exists(file)?Files.size(file):0)+bytes.length>quotas.get().get("audit.per_day"))throw new IOException("当日审计配额已满，管理写入被拒绝");
        try(FileChannel channel=FileChannel.open(file,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.APPEND)){ByteBuffer b=ByteBuffer.wrap(bytes);while(b.hasRemaining())channel.write(b);channel.force(true);}
        LocalDate oldest=today.minusDays(quotas.get().get("audit.days")-1);try(var files=Files.newDirectoryStream(root,"*.jsonl")){for(Path candidate:files){String name=candidate.getFileName().toString();if(!name.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}\\.jsonl")||Files.isSymbolicLink(candidate)||!candidate.toAbsolutePath().normalize().getParent().equals(root))continue;try{if(LocalDate.parse(name.substring(0,10)).isBefore(oldest))Files.delete(candidate);}catch(java.time.format.DateTimeParseException ignored){ }}}
    }
}
