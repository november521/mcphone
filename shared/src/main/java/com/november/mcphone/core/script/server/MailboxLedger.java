package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.api.sdk.mailbox.IMailbox;
import com.november.mcphone.core.script.JsonScan;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.LongSupplier;

/** 全服统一收件箱。发出前持久化 DELIVERING，重启后转 UNKNOWN，绝不自动重发。 */
public final class MailboxLedger {
    public enum State { AVAILABLE, DELIVERING, UNKNOWN, RECEIVING }
    public record Entry(String id, String stack, String reason, long expiresAt, State state,boolean incoming) {
        public Entry(String id,String stack,String reason,long expiresAt,State state){this(id,stack,reason,expiresAt,state,false);}
    }
    private final Map<UUID, List<Entry>> boxes = new LinkedHashMap<>();
    private final LongSupplier clock;
    private final Path file;
    private boolean locked;
    private java.util.function.Supplier<QuotaConfig> quotas=()->QuotaConfig.DEFAULT;
    private java.util.function.ToIntFunction<UUID> otherSlots=uuid->0;
    public MailboxLedger quotas(java.util.function.Supplier<QuotaConfig> quotas,java.util.function.ToIntFunction<UUID> otherSlots){this.quotas=quotas;this.otherSlots=otherSlots;return this;}
    public static final long RETENTION_MS = IMailbox.RETENTION_DAYS * 86_400_000L;
    public static final int MAX_BYTES = 64 * 1024 * 1024;
    public MailboxLedger(Path file, LongSupplier clock) {
        this.file = file; this.clock = clock;
        if (file != null && !Files.notExists(file)) {
            try {
                if (Files.size(file) > MAX_BYTES) throw new IllegalArgumentException("收件箱超额");
                String json = Files.readString(file, StandardCharsets.UTF_8);
                if (JsonScan.check(json, 8) != null) throw new IllegalArgumentException("收件箱结构损坏");
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                if (root.get("version").getAsInt() != 1) throw new IllegalArgumentException("未知收件箱版本");
                for (var row : root.getAsJsonObject("players").entrySet()) {
                    UUID player = UUID.fromString(row.getKey()); var entries = new ArrayList<Entry>();
                    for (JsonElement item : row.getValue().getAsJsonArray()) {
                        var e = item.getAsJsonObject(); State state = State.valueOf(e.get("state").getAsString());
                        var entry = new Entry(e.get("id").getAsString(), e.get("stack").getAsString(),
                                e.get("reason").getAsString(), e.get("expiresAt").getAsBigDecimal().longValueExact(),
                                state == State.DELIVERING || state == State.RECEIVING ? State.UNKNOWN : state,
                                state==State.RECEIVING||e.has("incoming")&&e.get("incoming").getAsBoolean());
                        if (!entry.id().matches("[0-9a-f]{32}") || entry.stack().length() > 65536
                                || entries.stream().anyMatch(old -> old.id().equals(entry.id()))) throw new IllegalArgumentException("收件箱条目损坏");
                        entries.add(entry);
                    }
                    if (entries.size() > 54) throw new IllegalArgumentException("收件箱容量损坏");
                    boxes.put(player, entries);
                }
                persist();
            } catch (java.io.IOException ex) { throw new IllegalStateException("收件箱无法读取", ex); }
        }
    }
    public List<Entry> entries(UUID player) { return List.copyOf(boxes.getOrDefault(player, List.of())); }
    public Map<UUID,List<Entry>> snapshot(){Map<UUID,List<Entry>> snapshot=new LinkedHashMap<>();boxes.forEach((player,entries)->snapshot.put(player,List.copyOf(entries)));return Map.copyOf(snapshot);}
    public boolean deposit(UUID player, List<String> stacks, String reason) {
        return !beginDeposit(player,stacks,reason,false).isEmpty();
    }
    public boolean fits(UUID player,List<String> stacks){
        if (player == null || stacks == null || stacks.isEmpty() || stacks.size() > 54)
            throw new IllegalArgumentException("收件箱批次无效");
        for (String stack : stacks) if (stack == null || stack.isEmpty() || stack.length() > 65536)
            throw new IllegalArgumentException("收件箱物品无效");
        var current = boxes.getOrDefault(player, List.of());
        long size=current.size()+stacks.size();return size<=quotas.get().get("mailbox.per_player")&&size+otherSlots.applyAsInt(player)<=quotas.get().get("items.per_player");
    }
    /** 搬入前保存 RECEIVING；扣除与玩家存档确认后才能转 AVAILABLE。崩溃留下转移方向供人工核对。 */
    public List<String> beginDeposit(UUID player,List<String> stacks,String reason,boolean incoming){
        if(!fits(player,stacks))return List.of();
        var current=boxes.getOrDefault(player,List.of());
        List<Entry> next = new ArrayList<>(current);
        List<String> ids=new ArrayList<>();
        for (String stack : stacks) next.add(new Entry(UUID.randomUUID().toString().replace("-", ""), stack,
                reason == null ? "" : reason.substring(0, Math.min(reason.length(), 128)),
                Math.addExact(clock.getAsLong(), RETENTION_MS), incoming?State.RECEIVING:State.AVAILABLE,incoming));
        for(int i=current.size();i<next.size();i++)ids.add(next.get(i).id());
        boxes.put(player, next); persist(); return List.copyOf(ids);
    }
    public void received(UUID player,List<String> ids){
        List<Entry> selected=ids.stream().map(id->find(player,id)).toList();
        if(selected.isEmpty()||new HashSet<>(ids).size()!=ids.size()||selected.stream().anyMatch(e->e.state()!=State.RECEIVING))throw new IllegalArgumentException("搬入收件箱的状态不匹配");
        for(Entry e:selected)replace(player,e,new Entry(e.id(),e.stack(),e.reason(),e.expiresAt(),State.AVAILABLE));persist();
    }
    public Entry begin(UUID player, String id) {
        Entry entry = find(player, id);
        if (entry.state() != State.AVAILABLE) throw new IllegalStateException("这件物品待管理员核对");
        if (clock.getAsLong() >= entry.expiresAt()) throw new IllegalStateException("物品已过期");
        replace(player, entry, new Entry(entry.id(), entry.stack(), entry.reason(), entry.expiresAt(), State.DELIVERING));
        persist(); return entry;
    }
    public void complete(UUID player, String id) {
        Entry e = find(player, id);
        if (e.state() != State.DELIVERING) throw new IllegalStateException("领取状态不匹配");
        remove(player, e); persist();
    }
    public void unknown(UUID player, String id) {
        Entry e = find(player, id); replace(player, e, new Entry(e.id(), e.stack(), e.reason(), e.expiresAt(), State.UNKNOWN,e.incoming())); persist();
    }
    public void resolve(UUID player, String id, boolean delivered) {
        Entry e = find(player, id);
        if (e.state() != State.UNKNOWN) throw new IllegalStateException("不是待核对物品");
        if (delivered!=e.incoming()) remove(player, e);
        else replace(player, e, new Entry(e.id(), e.stack(), e.reason(), e.expiresAt(), State.AVAILABLE));
        persist();
    }
    public List<Map.Entry<UUID, Entry>> expire() {
        return expire(entry->{});
    }
    public List<Map.Entry<UUID,Entry>> expire(java.util.function.Consumer<Map.Entry<UUID,Entry>> beforeRemove){
        List<Map.Entry<UUID, Entry>> expired = new ArrayList<>(); long now = clock.getAsLong();
        for (var box : new ArrayList<>(boxes.entrySet())) for (Entry e : new ArrayList<>(box.getValue())) {
            // 结果不明的物品不能被过期清理，避免抹掉人工核对证据。
            if (e.state() == State.AVAILABLE && now >= e.expiresAt()) { expired.add(Map.entry(box.getKey(), e)); }
        }
        for(var entry:expired)beforeRemove.accept(entry);
        for(var entry:expired)remove(entry.getKey(),entry.getValue());
        if (!expired.isEmpty()) persist(); return expired;
    }
    private Entry find(UUID player, String id) {
        return entries(player).stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("没有这件物品"));
    }
    private void replace(UUID player, Entry old, Entry next) { var list = boxes.get(player); list.set(list.indexOf(old), next); }
    private void remove(UUID player, Entry e) { var list = boxes.get(player); list.remove(e); if (list.isEmpty()) boxes.remove(player); }
    private void persist() {
        if (locked) throw new IllegalStateException("收件箱持久化已锁住");
        JsonObject root = new JsonObject(); root.addProperty("version", 1);
        JsonObject players = new JsonObject(); boxes.forEach((p, es) -> players.add(p.toString(), new Gson().toJsonTree(es)));
        root.add("players", players); byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        try {
            if (bytes.length > MAX_BYTES) throw new IllegalStateException("收件箱达到全服配额");
            if (file != null) ScriptStateData.atomicWrite(file, bytes);
        } catch (Exception ex) { locked = true; throw new IllegalStateException("收件箱保存失败，停止发出物品", ex); }
    }
}
