package com.november.mcphone.core.script.pkg;

import com.google.gson.*;
import com.november.mcphone.core.script.JsonScan;
import com.november.mcphone.core.script.server.ScriptStateData;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 最高已安装版本与同版本摘要证据。必须在验签通过之后记录，不能信任未签名 feed 的整数。 */
public final class UpdateHistory {
    private static final Object IO_LOCK=new Object();
    private final Path file;
    private final Map<String, JsonObject> rows = new LinkedHashMap<>();
    public UpdateHistory(Path file) throws java.io.IOException {
        this.file=file;
        if (Files.exists(file)) {
            if (Files.size(file)>1048576) throw new IllegalArgumentException("版本历史超过 1 MiB");
            String raw=Files.readString(file); if (JsonScan.check(raw,5)!=null) throw new IllegalArgumentException("版本历史损坏");
            JsonObject root=JsonParser.parseString(raw).getAsJsonObject();
            if (root.size()>1024) throw new IllegalArgumentException("版本历史条目超额");
            for (var entry:root.entrySet()) {
                JsonObject row=entry.getValue().getAsJsonObject();
                long highest=row.get("highest").getAsBigDecimal().longValueExact();
                if (highest<0 || !row.get("digest").getAsString().matches("[0-9a-f]{64}")) throw new IllegalArgumentException("版本历史无效");
                if (row.has("conflict") && !row.get("conflict").getAsString().matches("[0-9a-f]{64}")) throw new IllegalArgumentException("冲突摘要无效");
                rows.put(entry.getKey(),row);
            }
        }
    }
    public synchronized long highest(String identity, long installedVersion) {
        JsonObject row=rows.get(identity); return Math.max(installedVersion,row==null?0:row.get("highest").getAsLong());
    }
    public synchronized boolean conflicted(String identity) { JsonObject row=rows.get(identity); return row!=null && row.has("conflict"); }
    /** @return false 表示同一签名版本出现两个摘要；保留证据并停用后续更新。 */
    public synchronized boolean record(String identity,long version,String digest) throws java.io.IOException {
        synchronized(IO_LOCK) {
        if (version<0 || identity.length()>256 || !digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("版本记录无效");
        UpdateHistory latest=new UpdateHistory(file);rows.clear();rows.putAll(latest.rows);
        JsonObject previous=rows.get(identity);
        if (previous!=null && previous.has("conflict")) return false;
        if (previous!=null && previous.get("highest").getAsLong()>version) return false;
        if (previous!=null && previous.get("highest").getAsLong()==version) {
            if (previous.get("digest").getAsString().equals(digest)) return true;
            JsonObject conflict=previous.deepCopy(); conflict.addProperty("conflict",digest); persist(identity,conflict); return false;
        }
        JsonObject row=new JsonObject(); row.addProperty("highest",version); row.addProperty("digest",digest); return persist(identity,row);
        }
    }
    private boolean persist(String identity,JsonObject row) throws java.io.IOException {
        synchronized(IO_LOCK) {
        UpdateHistory latest=new UpdateHistory(file);rows.clear();rows.putAll(latest.rows);
        boolean accepted=!row.has("conflict");JsonObject current=rows.get(identity);
        if(current!=null){long old=current.get("highest").getAsLong(),next=row.get("highest").getAsLong();
            if(current.has("conflict")||old>next)return false;
            if(old==next&&!current.get("digest").getAsString().equals(row.get("digest").getAsString())){JsonObject conflict=current.deepCopy();conflict.addProperty("conflict",row.get("digest").getAsString());row=conflict;accepted=false;}
        }
        if (!rows.containsKey(identity) && rows.size()>=1024) throw new IllegalArgumentException("版本记录满了");
        JsonObject root=new JsonObject(); rows.forEach(root::add); root.add(identity,row);
        byte[] data=root.toString().getBytes(StandardCharsets.UTF_8); if(data.length>1048576) throw new IllegalArgumentException("版本历史超过 1 MiB");
        ScriptStateData.atomicWrite(file,data); rows.put(identity,row);
        return accepted;
        }
    }
}
