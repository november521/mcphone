package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.core.PhoneSavedData;
import com.november.mcphone.core.script.JsonScan;
import com.november.mcphone.core.script.net.ScriptErrorCode;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 配额与幂等账本共用的持久化事务。先落盘，再允许副作用（§20.4/§20.7）。 */
public final class ScriptStateData extends PhoneSavedData {
    private static final Gson JSON = new Gson();
    private static final String FILE = "mcphone_script_state";
    public static final int MAX_BYTES = 96 * 1024 * 1024;
    private JsonObject state = new JsonObject();
    private Path checkpoint;
    private boolean locked;
    private com.november.mcphone.core.script.engine.SharedState shared;
    public void bindShared(com.november.mcphone.core.script.engine.SharedState shared) {
        this.shared = shared;
        Map<String, Map<String, String>> values = new LinkedHashMap<>();
        if (state.has("shared")) for (var app : state.getAsJsonObject("shared").entrySet()) {
            Map<String, String> entries = new LinkedHashMap<>();
            for (var e : app.getValue().getAsJsonObject().entrySet()) {
                if (!e.getValue().isJsonPrimitive() || !e.getValue().getAsJsonPrimitive().isString()) throw new IllegalStateException("shared 值损坏");
                entries.put(e.getKey(), e.getValue().getAsString());
            }
            values.put(app.getKey(), entries);
        }
        shared.restore(values);
    }
    /** 测试与迁移工具使用同一条读写路径，坏文件不生成空账。 */
    public static ScriptStateData open(Path file) {
        ScriptStateData data = new ScriptStateData(); data.checkpoint = file;
        if (!Files.notExists(file)) {
            try {
                if (Files.size(file) > MAX_BYTES) throw new IOException("脚本账本超过上限");
                data.state = parse(Files.readString(file, StandardCharsets.UTF_8));
                if (!data.state.has("version")) throw new IllegalArgumentException("脚本账本缺少格式版本");
            } catch (IOException ex) { throw new IllegalStateException("脚本账本不可读", ex); }
        }
        return data;
    }
    public static ScriptStateData get(MinecraftServer server) {
        Path backup = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve(FILE + ".dat");
        Path checkpoint = server.getWorldPath(LevelResource.ROOT).resolve("mcphone/guards")
                .resolve(ServerIdentity.idOf(server).toString()).resolve("global.dat");
        // 原子快照先读：SavedData 备份损坏不应盖过可用的快照；快照损坏则不回退旧账冒险重放。
        boolean hasCheckpoint = !Files.notExists(checkpoint);
        ScriptStateData durable = hasCheckpoint ? open(checkpoint) : null;
        ScriptStateData data = getOrCreate(server, FILE, () -> {
            if (durable != null) return durable;
            if (!Files.notExists(backup)) throw new IllegalStateException("脚本账本备份存在但无法读取");
            return new ScriptStateData();
        }, t -> {
            if (durable != null) return durable;
            ScriptStateData d = new ScriptStateData();
            d.state = parse(t.getString("state"));
            return d;
        });
        data.checkpoint = checkpoint;
        return data;
    }
    public void restore(IdempotencyLedger ledger, GuardController guards) {
        if (state.size() == 0) return;
        if (state.get("version").getAsInt() != 1) throw new IllegalStateException("未知脚本账本版本");
        Map<UUID, Map<String, IdempotencyLedger.Entry>> entries = new LinkedHashMap<>();
        for (JsonElement e : state.getAsJsonArray("ledger")) {
            JsonObject row = e.getAsJsonObject();
            UUID player = UUID.fromString(row.get("player").getAsString());
            String key = row.get("key").getAsString();
            if (!key.matches("[0-9a-f]{64}")) throw new IllegalStateException("幂等键损坏");
            IdempotencyLedger.Entry entry = new IdempotencyLedger.Entry(
                    IdempotencyLedger.State.valueOf(row.get("state").getAsString()),
                    Base64.getDecoder().decode(row.get("digest").getAsString()), row.get("at").getAsBigDecimal().longValueExact(),
                    ScriptErrorCode.valueOf(row.get("code").getAsString()),
                    Base64.getDecoder().decode(row.get("data").getAsString()), row.get("retry").getAsBigDecimal().longValueExact(), row.get("revision").getAsBigDecimal().longValueExact());
            if (entry.paramsDigest().length != 32 || entry.data().length > IdempotencyLedger.REPLAYABLE_DATA_MAX
                    || entries.computeIfAbsent(player, p -> new LinkedHashMap<>()).putIfAbsent(key, entry) != null)
                throw new IllegalStateException("账本条目损坏");
        }
        Map<String, GuardController.Reservation> reservations = new LinkedHashMap<>();
        for (var e : state.getAsJsonObject("reservations").entrySet()) {
            GuardController.Reservation r = JSON.fromJson(e.getValue(), GuardController.Reservation.class);
            reservations.put(e.getKey(), r);
        }
        guards.restore(longs(state.getAsJsonObject("counters")), longs(state.getAsJsonObject("cooldowns")),
                reservations, state.get("revision").getAsBigDecimal().longValueExact());
        guards.recover(entries);
        ledger.restore(entries);
    }
    public void commit(IdempotencyLedger ledger, GuardController guards) {
        if (locked) throw new IllegalStateException("脚本账本已锁住");
        JsonObject next = new JsonObject(); next.addProperty("version", 1);
        next.addProperty("revision", guards.revision());
        next.add("counters", JSON.toJsonTree(guards.counters())); next.add("cooldowns", JSON.toJsonTree(guards.cooldowns()));
        next.add("reservations", JSON.toJsonTree(guards.reservations()));
        JsonArray entries = new JsonArray();
        ledger.snapshot().forEach((player, box) -> box.forEach((key, entry) -> {
            JsonObject row = new JsonObject(); row.addProperty("player", player.toString()); row.addProperty("key", key);
            row.addProperty("state", entry.state().name()); row.addProperty("at", entry.at()); row.addProperty("code", entry.code().name());
            row.addProperty("digest", Base64.getEncoder().encodeToString(entry.paramsDigest()));
            row.addProperty("data", Base64.getEncoder().encodeToString(entry.data()));
            row.addProperty("retry", entry.retryAfterMs()); row.addProperty("revision", entry.stateRevision()); entries.add(row);
        }));
        next.add("ledger", entries);
        if (shared != null) next.add("shared", JSON.toJsonTree(shared.snapshot()));
        byte[] bytes = JSON.toJson(next).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) { locked = true; throw new IllegalStateException("脚本账本超过总配额"); }
        try {
            if (checkpoint != null) atomicWrite(checkpoint, bytes);
            state = next; setDirty();
        } catch (IOException ex) {
            locked = true;
            throw new IllegalStateException("脚本账本无法持久化，停止执行以防重复发奖", ex);
        }
    }
    public static void atomicWrite(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) ch.write(buffer); ch.force(true);
        }
        // 不支持原子改名就拒绝本次事务，不能偷偷降成可能截断的覆写。
        Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }
    private static JsonObject parse(String json) {
        if (json.length() > MAX_BYTES) throw new IllegalArgumentException("脚本账本超额");
        JsonScan.Problem p = JsonScan.check(json, 16);
        if (p != null) throw new IllegalArgumentException("脚本账本损坏：" + p.detail());
        return JsonParser.parseString(json).getAsJsonObject();
    }
    private static Map<String, Long> longs(JsonObject json) {
        Map<String, Long> out = new LinkedHashMap<>(); json.entrySet().forEach(e -> out.put(e.getKey(), e.getValue().getAsBigDecimal().longValueExact()));
        return out;
    }
    @Override protected CompoundTag write(CompoundTag tag) { tag.putString("state", JSON.toJson(state)); return tag; }
}
