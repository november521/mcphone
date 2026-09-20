package com.november.mcphone.core.script.server;

import com.november.mcphone.MCphone;
import com.november.mcphone.core.PhoneSavedData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;

import java.util.UUID;

/**
 * 服务器身份（施工方案 §13.5）：首次启动生成一个 UUID，存在<b>存档</b>里（{@code world/data/}），不是 config。
 *
 * <p>复制世界 = 复制身份，<b>这是有意的</b>：复制出来的测试服应当继承授权。
 *
 * <h2>绝不能用 {@code PhoneScreenRegistry.currentWorldKey()}</h2>
 *
 * 那是 {@code "server_" + sanitize(server.ip)}：会碰撞（{@code 1.2.3.4:25565} 与 {@code 1.2.3.4_25565}
 * 归一后同一个键），而且同一 IP 换服主就复用（旧授权会落到新服主头上）。它作为"安装状态的便捷分组键"
 * 没问题，但授权、保险箱、敏感 KV <b>一律不许用它</b>（§13.5/§13.9）。
 *
 * <p>客户端按服务端握手时下发的 {@code serverId} 分桶，<b>不自己推断</b>。
 */
public final class ServerIdentity extends PhoneSavedData {

    static final String FILE_NAME = MCphone.MODID + "_server_identity";
    private static final String KEY = "serverId";

    private UUID id = UUID.randomUUID();

    /** 新世界用：生成一个随机身份。 */
    public ServerIdentity() {
    }

    ServerIdentity(UUID id) {
        this.id = id;
    }

    public UUID id() {
        return id;
    }

    /** 服务器身份。开服时在主线程上调；首次会生成并落盘。 */
    public static UUID idOf(MinecraftServer server) {
        return get(server).id();
    }

    public static ServerIdentity get(MinecraftServer server) {
        return getOrCreate(server, FILE_NAME, ServerIdentity::new, ServerIdentity::load);
    }

    /** 旧存档里没有这一段时生成一个新的，并标脏让它落盘。 */
    static ServerIdentity load(CompoundTag tag) {
        if (tag.hasUUID(KEY)) return new ServerIdentity(tag.getUUID(KEY));
        ServerIdentity fresh = new ServerIdentity();
        fresh.setDirty();
        return fresh;
    }

    @Override
    protected CompoundTag write(CompoundTag tag) {
        tag.putUUID(KEY, id);
        return tag;
    }
}
