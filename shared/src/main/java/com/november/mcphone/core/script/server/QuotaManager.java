package com.november.mcphone.core.script.server;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 配额更改先写审计再保存，再发布 volatile 快照；失败保留旧的运行配额。 */
public final class QuotaManager {
    public static final String FILE="serverconfig/mcphone-quotas.json";
    private final Path file;private volatile QuotaConfig current=QuotaConfig.DEFAULT;private final AuditLog audit;
    public QuotaManager(Path world)throws IOException {file=world.resolve(FILE);audit=new AuditLog(world.resolve("mcphone/audit"),this::current);if(Files.notExists(file))StoreRepository.atomic(file,current.json().toString().getBytes(StandardCharsets.UTF_8));reload();}
    public QuotaConfig current(){return current;}
    public static QuotaConfig read(Path world)throws IOException {Path file=world.resolve(FILE);if(Files.notExists(file))return QuotaConfig.DEFAULT;if(Files.isSymbolicLink(file)||Files.size(file)>16384)throw new IOException("配额路径无效");return QuotaConfig.parse(Files.readString(file,StandardCharsets.UTF_8));}
    public AuditLog audit(){return audit;}
    public QuotaConfig prepare()throws IOException {if(Files.isSymbolicLink(file)||Files.size(file)>16384)throw new IOException("配额配置路径或大小无效");return QuotaConfig.parse(Files.readString(file,StandardCharsets.UTF_8));}
    public void publish(QuotaConfig next){current=Objects.requireNonNull(next);}
    public void reload()throws IOException {publish(prepare());}
    public synchronized void set(UUID actor,String key,long value)throws IOException {QuotaConfig next=current.with(key,value);audit.append(actor,"quota.set",key,new com.google.gson.JsonPrimitive(current.get(key)),new com.google.gson.JsonPrimitive(value));StoreRepository.atomic(file,next.json().toString().getBytes(StandardCharsets.UTF_8));current=next;}
}
