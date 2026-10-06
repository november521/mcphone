package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.MCphone;
import com.november.mcphone.api.sdk.resources.*;
import com.november.mcphone.core.script.JsonScan;
import com.november.mcphone.core.script.engine.HostError;
import com.november.mcphone.core.script.net.ScriptErrorCode;
import com.november.mcphone.platform.ResourcePlatform;
import net.minecraft.core.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.storage.LevelResource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 资源提供者只在主线程查询。方块查询额外要求服主固定谓词，脚本不能换成宽松的谓词。 */
public final class ResourceRegistry {
    public record Spec(ResourceType type,String provider,boolean isDefault) { }
    public record Config(int range,String predicate,List<Spec> resources) {public Config{resources=List.copyOf(resources);}}
    public static final String FILE="serverconfig/mcphone-resources.json";
    private final Map<String,IResourceProvider> providers=new LinkedHashMap<>();
    private volatile Config config;
    public ResourceRegistry(Config config){this.config=config;
        int n=0;for(IResourceProvider provider:ServiceLoader.load(IResourceProvider.class)){if(++n>32)break;try {String id=provider.type().id().toString();providers.putIfAbsent(id,provider);}catch(RuntimeException|LinkageError bad){MCphone.LOGGER.warn("[MCphone] 资源适配器不可用：{}",bad.toString());}}
    }
    public void reload(Config fresh){config=fresh;}
    public JsonObject snapshot(){Config cfg=config;JsonObject out=new JsonObject();out.addProperty("range",cfg.range());out.addProperty("block_predicate",cfg.predicate());JsonArray array=new JsonArray();for(Spec spec:cfg.resources()){JsonObject row=new JsonObject();row.addProperty("id",spec.type().id().toString());row.addProperty("kind",spec.type().kind().name());row.addProperty("name",spec.type().displayName().getString());row.addProperty("unit",spec.type().unit());row.addProperty("decimals",spec.type().decimals());row.addProperty("provider",spec.provider());row.addProperty("default",spec.isDefault());array.add(row);}out.add("resources",array);return out;}
    public static Config load(MinecraftServer server){Path path=server.getWorldPath(LevelResource.ROOT).resolve(FILE);
        try {if(Files.notExists(path)){JsonObject root=new JsonObject();root.addProperty("range",8);root.addProperty("block_predicate","");JsonArray resources=new JsonArray();
            for(String kind:List.of("energy","fluid")){JsonObject o=new JsonObject();o.addProperty("id","mcphone:"+kind);o.addProperty("name",kind.equals("energy")?"Energy":"Fluid");o.addProperty("unit",kind.equals("energy")?"FE":ResourcePlatform.fluidUnit());o.addProperty("kind",kind.toUpperCase(Locale.ROOT));o.addProperty("provider","forge_"+kind);o.addProperty("default",true);resources.add(o);}root.add("resources",resources);StoreRepository.atomic(path,root.toString().getBytes(StandardCharsets.UTF_8));}
            if(Files.isSymbolicLink(path)||Files.size(path)>65536)throw new IOException("资源配置超额或是符号链接");String json=Files.readString(path,StandardCharsets.UTF_8);if(JsonScan.check(json,6)!=null)throw new IllegalArgumentException("资源配置 JSON 无效");
            JsonObject root=JsonParser.parseString(json).getAsJsonObject();if(!root.keySet().equals(Set.of("range","block_predicate","resources")))throw new IllegalArgumentException("资源配置字段无效");int range=root.get("range").getAsBigDecimal().intValueExact();if(range<1||range>8)throw new IllegalArgumentException("资源范围须在1–8");
            String predicate=root.get("block_predicate").getAsString();if(!predicate.isEmpty()&&(predicate.length()>64||ResourceLocation.tryParse(predicate)==null))throw new IllegalArgumentException("资源谓词无效");
            JsonArray array=root.getAsJsonArray("resources");if(array.size()>32)throw new IllegalArgumentException("资源最多32种");Set<ResourceLocation> ids=new HashSet<>();Set<ResourceType.Kind> defaults=new HashSet<>();List<Spec> specs=new ArrayList<>();
            for(var value:array){JsonObject o=value.getAsJsonObject();if(!Set.of("id","kind","name","unit","decimals","provider","default").containsAll(o.keySet()))throw new IllegalArgumentException("资源条目字段无效");
                ResourceType type=new ResourceType(ResourceLocation.parse(o.get("id").getAsString()),ResourceType.Kind.valueOf(o.get("kind").getAsString()),Component.literal(StoreRepository.clean(o.get("name").getAsString(),64)),o.get("unit").getAsString(),o.has("decimals")?o.get("decimals").getAsBigDecimal().intValueExact():0,null);
                String provider=o.get("provider").getAsString();if(provider.length()>64||!provider.matches("[a-z0-9_.:/-]+"))throw new IllegalArgumentException("资源提供者无效");boolean def=o.get("default").getAsBoolean();
                if(!ids.add(type.id())||def&&!defaults.add(type.kind()))throw new IllegalArgumentException("资源 ID 或默认类型重复");specs.add(new Spec(type,provider,def));}
            return new Config(range,predicate,specs);
        }catch(IOException|RuntimeException bad){throw new IllegalStateException("资源配置不可用，保留旧配置",bad);}
    }
    private boolean available(Spec spec){if(spec.provider.startsWith("forge_"))return ResourcePlatform.supported(spec.provider);IResourceProvider p=providers.get(spec.provider);return p!=null&&p.isAvailable();}
    public List<ResourceType> list(){return config.resources.stream().filter(this::available).map(Spec::type).toList();}
    public ResourceType defaultType(String kind){ResourceType.Kind k;try{k=ResourceType.Kind.valueOf(kind);}catch(IllegalArgumentException bad){throw HostError.invalid("资源种类无效");}return config.resources.stream().filter(s->s.isDefault&&s.type.kind()==k&&available(s)).map(Spec::type).findFirst().orElse(null);}
    private Spec require(String id){return config.resources.stream().filter(s->s.type.id().toString().equals(id)&&available(s)).findFirst().orElseThrow(()->HostError.denied(ScriptErrorCode.UNAVAILABLE,"mcphone.script.unavailable","资源适配器不可用"));}
    public ResourceReading item(ServerPlayer player,String type,int slot){Spec spec=require(type);if(player==null||slot<0||slot>35&&slot!=40)throw HostError.invalid("只可查询本人背包或副手");
        var stack=player.getInventory().getItem(slot).copy();return spec.provider.startsWith("forge_")?ResourcePlatform.readItem(spec.provider,stack):providers.get(spec.provider).readItem(stack);}
    public ResourceReading block(ServerPlayer player,String type,BlockPos pos,Direction side){Spec spec=require(type);Config cfg=config;
        if(ScriptHost.current()==null||!ScriptHost.current().capabilities().boundary().remoteMachineRead())throw HostError.denied(ScriptErrorCode.UNAVAILABLE,"mcphone.script.unavailable","服主关闭了机器远程读取");
        if(player==null||cfg.predicate.isEmpty())throw HostError.denied(ScriptErrorCode.UNAVAILABLE,"mcphone.script.unavailable","服主尚未配置资源方块谓词");
        var level=player.serverLevel();if(Math.abs((long)pos.getX())>30_000_000||Math.abs((long)pos.getZ())>30_000_000||pos.getY()<level.getMinBuildHeight()||pos.getY()>=level.getMaxBuildHeight()
            ||player.position().distanceToSqr(Vec3.atCenterOf(pos))>(long)cfg.range*cfg.range||!level.hasChunkAt(pos))throw HostError.invalid("目标超出范围或区块未加载");
        if(!Boolean.TRUE.equals(com.november.mcphone.platform.Predicates.testAt(player,ResourceLocation.parse(cfg.predicate),Vec3.atCenterOf(pos))))throw HostError.denied(ScriptErrorCode.NOT_AUTHORIZED,"mcphone.script.not_authorized","资源方块谓词未通过");
        return spec.provider.startsWith("forge_")?ResourcePlatform.readBlock(spec.provider,level,pos,side):providers.get(spec.provider).readBlock(level,pos,side);
    }
}
