package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.core.script.JsonScan;
import com.november.mcphone.core.script.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 内建页面的固定 RPC 目录。身份永远取登录玩家，不接受参数里的 UUID。 */
public final class HostControls {
    public static final Set<String> ACTIONS = Set.of("mailbox.list", "mailbox.claim", "gift.list", "gift.claim", "gift.configure", "gift.edit", "vault.get", "vault.put", "storage.show", "storage.clear",
            "store.begin","store.file.begin","store.chunk","store.commit","store.list","store.current","store.meta","store.read","store.queue","store.inspect","store.review","store.request","store.roles","notify.sync","notify.sync.part","notify.list","notify.read","quota.roles","quota.show","quota.top","quota.set","escrow.list","escrow.confirm","escrow.accept","escrow.cancel","escrow.dismiss","policy.check","admin.audit.export","admin.audit.status","admin.list","admin.preview","admin.previewPage","admin.commit");
    private final ServerMailbox mailbox;
    private NativeAdminControls administration;
    public HostControls administration(NativeAdminControls value){administration=value;return this;}
    private final ServerGifts gifts;
    private final com.november.mcphone.core.script.server.store.DurablePlayerStore store;
    private java.util.function.Consumer<UUID> seedStore = uuid -> { };
    private ServerStore serverStore;
    private ServerNotifications notifications;
    private QuotaControls quotas;
    private ServerItemEscrow escrow;
    private AuditExportControls exports;
    public HostControls exports(AuditExportControls value){exports=value;return this;}
    public HostControls escrow(ServerItemEscrow value){escrow=value;return this;}
    public HostControls quotas(QuotaControls value){quotas=value;return this;}
    public HostControls notifications(ServerNotifications value){notifications=value;return this;}
    public HostControls serverStore(ServerStore value) {serverStore=value;return this;}
    public HostControls seedStore(java.util.function.Consumer<UUID> seed) { seedStore=seed; return this; }
    public HostControls(ServerMailbox mailbox) { this(mailbox, null); }
    public HostControls(ServerMailbox mailbox, ServerGifts gifts) { this(mailbox,gifts,null); }
    public HostControls(ServerMailbox mailbox, ServerGifts gifts,com.november.mcphone.core.script.server.store.DurablePlayerStore store) {
        this.mailbox = mailbox; this.gifts = gifts; this.store=store;
    }
    public ScriptRpcResult handle(ScriptRpc rpc, PlayerSnapshot player) {
        String json = rpc.params().length == 0 ? "{}" : new String(rpc.params(), StandardCharsets.UTF_8);
        if (JsonScan.check(json, 4) != null) return ScriptRpcResult.fail(rpc.requestId(), ScriptErrorCode.INVALID_ARGUMENT);
        JsonObject args = JsonParser.parseString(json).getAsJsonObject();
        if(rpc.actionId().startsWith("storage.")){
            if(!args.keySet().equals(Set.of("app"))||!args.get("app").isJsonPrimitive()||!args.getAsJsonPrimitive("app").isString())return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.INVALID_ARGUMENT);
            String app=args.get("app").getAsString();if(app.length()>64||!app.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.INVALID_ARGUMENT);
            ScriptHost host=ScriptHost.current();if(store==null||host==null)return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.UNAVAILABLE);seedStore.accept(player.uuid());var before=store.appUsage(player.uuid(),app);
            if(rpc.actionId().equals("storage.clear")){try{host.quotas().audit().append(player.uuid(),"storage.clear",app,new Gson().toJsonTree(before),new JsonPrimitive("本人普通 KV 清理"));}catch(java.io.IOException failure){throw new IllegalStateException("审计未保存，未清理数据",failure);}store.clearAppKv(player.uuid(),app);}else if(!rpc.actionId().equals("storage.show"))return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.INVALID_ARGUMENT);
            return ScriptRpcResult.ok(rpc.requestId(),new Gson().toJson(store.appUsage(player.uuid(),app)).getBytes(StandardCharsets.UTF_8),0);
        }
        if(rpc.actionId().startsWith("admin.audit."))return exports==null?ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.UNAVAILABLE):exports.handle(rpc,player,args);
        if(rpc.actionId().startsWith("admin.")){if(administration==null)return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.UNAVAILABLE);try{return administration.handle(rpc,player,args);}catch(java.io.IOException bad){return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.UNAVAILABLE);}}
        if(rpc.actionId().equals("policy.check")){
            if(!args.keySet().equals(Set.of("app","version","digest","publicKey")))throw new IllegalArgumentException("版本检查字段无效");String app=args.get("app").getAsString(),digest=args.get("digest").getAsString();long version=args.get("version").getAsBigDecimal().longValueExact();String publicKey=args.get("publicKey").getAsString();if(app.length()>64||!app.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||!digest.matches("[0-9a-f]{64}")||version<0||publicKey.length()>64)throw new IllegalArgumentException("版本检查无效");byte[] key=Base64.getDecoder().decode(publicKey);if(key.length!=0)com.november.mcphone.core.script.pkg.Signatures.publicKey(key);ScriptHost host=ScriptHost.current();var rule=host.revocations(app,key).rejected(app,version,digest);JsonObject data=new JsonObject();data.addProperty("revoked",rule!=null);data.addProperty("reason",rule==null?"":rule.reason());data.addProperty("minVersion",Long.toString(rule==null?0:rule.minVersion()));return ScriptRpcResult.ok(rpc.requestId(),data.toString().getBytes(StandardCharsets.UTF_8),0);
        }
        if(rpc.actionId().startsWith("escrow."))return escrow==null?ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.UNAVAILABLE):escrow.handle(rpc,player,args);
        if(rpc.actionId().startsWith("quota.")) {
            if(quotas==null)return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.UNAVAILABLE);
            try{return quotas.handle(rpc,player,args);}catch(java.io.IOException bad){return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.UNAVAILABLE);}
        }
        if(rpc.actionId().startsWith("notify.")) {
            if(notifications==null)return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.UNAVAILABLE);
            try{return notifications.handle(rpc,player,args);}catch(java.io.IOException bad){return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.UNAVAILABLE);}
        }
        if(rpc.actionId().startsWith("store.")) {
            if(serverStore==null)return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.UNAVAILABLE);
            if(rpc.actionId().equals("store.roles")) {
                if(args.size()!=0)return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.INVALID_ARGUMENT);
                JsonObject data=new JsonObject();data.addProperty("reviewer",serverStore.reviewer(player.uuid()));
                return ScriptRpcResult.ok(rpc.requestId(),data.toString().getBytes(StandardCharsets.UTF_8),0);
            }
            return serverStore.handle(rpc,player,args);
        }
        if(rpc.actionId().startsWith("vault.")) {
            if(store==null || args.size()!=(rpc.actionId().equals("vault.get")?2:3)) return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.INVALID_ARGUMENT);
            String app=args.get("app").getAsString(), key=args.get("key").getAsString();
            if(app.length()>64 || !app.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || !key.matches("[A-Za-z0-9_.-]{1,64}"))
                return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.INVALID_ARGUMENT);
            ScriptHost host=ScriptHost.current(); if(host==null) return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.UNAVAILABLE);
            if(host.capabilityPolicy().check("sealed.store",Set.of())!=CapabilityPolicy.Verdict.OK)
                return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.UNAVAILABLE);
            seedStore.accept(player.uuid());
            String namespace=com.november.mcphone.core.script.server.store.ScriptKv.namespace(host.serverId().toString(),"app:"+app,app,1);
            if(rpc.actionId().equals("vault.put")) {
                store.sealed(player.uuid(),namespace,key,com.november.mcphone.core.script.server.store.DurablePlayerStore.decodeRecord(args.getAsJsonObject("record")));
                return ScriptRpcResult.ok(rpc.requestId(),"{}".getBytes(StandardCharsets.UTF_8),0);
            }
            var value=store.sealed(player.uuid(),namespace,key); JsonObject data=new JsonObject();
            data.add("record",value==null?JsonNull.INSTANCE:com.november.mcphone.core.script.server.store.DurablePlayerStore.encodeRecord(value));
            byte[] bytes=data.toString().getBytes(StandardCharsets.UTF_8);
            if(bytes.length>ScriptProtocol.DATA_MAX) return ScriptRpcResult.fail(rpc.requestId(),ScriptErrorCode.UNAVAILABLE);
            return ScriptRpcResult.ok(rpc.requestId(),bytes,0);
        }
        if (rpc.actionId().startsWith("gift.")) return gifts == null ? ScriptRpcResult.fail(rpc.requestId(), ScriptErrorCode.UNAVAILABLE) : gifts.handle(rpc, player, args);
        if (rpc.actionId().equals("mailbox.list")) {
            int offset = args.has("offset") ? args.get("offset").getAsBigDecimal().intValueExact() : 0;
            if (offset < 0 || offset > 54 || args.size() > 1) return ScriptRpcResult.fail(rpc.requestId(), ScriptErrorCode.INVALID_ARGUMENT);
            JsonArray all = mailbox.list(player.uuid()), page = new JsonArray();
            for (int i = offset; i < Math.min(all.size(), offset + 5); i++) page.add(all.get(i));
            JsonObject result = new JsonObject(); result.add("items", page); result.addProperty("total", all.size());
            byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > ScriptProtocol.DATA_MAX) return ScriptRpcResult.fail(rpc.requestId(), ScriptErrorCode.UNAVAILABLE);
            return new ScriptRpcResult(rpc.requestId(), ScriptErrorCode.OK, bytes, "", List.of(), 0, 0);
        }
        if (args.size() != 1 || !args.has("id") || !args.get("id").getAsString().matches("[0-9a-f]{32}"))
            return ScriptRpcResult.fail(rpc.requestId(), ScriptErrorCode.INVALID_ARGUMENT);
        return ScriptRpcResult.fail(rpc.requestId(), mailbox.claim(player.uuid(), args.get("id").getAsString()));
    }
}
