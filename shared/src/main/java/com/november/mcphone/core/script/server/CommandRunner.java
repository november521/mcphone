package com.november.mcphone.core.script.server;

import com.google.gson.*;
import com.november.mcphone.MCphone;
import com.november.mcphone.core.script.JsonScan;
import com.november.mcphone.core.script.JsonValues;
import com.november.mcphone.core.script.net.ScriptErrorCode;
import net.minecraft.commands.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.*;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 每次在服务器主线程，用当前 source 重新解析并立即执行；不保存 ParseResults。 */
public final class CommandRunner {
    public record Result(ScriptErrorCode code, List<Integer> results) { public Result { results=List.copyOf(results); } }
    private record Config(boolean enabled, boolean others, Map<String,CommandTemplate> templates) { }
    private final MinecraftServer server;
    private volatile Config config;
    private JsonObject source;
    public static final String FILE="serverconfig/mcphone-command-templates.json";
    public CommandRunner(MinecraftServer server) { this.server=server; reload(); }
    public void reload() {
        Path file=server.getWorldPath(LevelResource.ROOT).resolve(FILE);
        try {
            if(Files.notExists(file)) ScriptStateData.atomicWrite(file,"{\"enabled\":false,\"allow_affect_others\":false,\"permission_plugins\":[\"lp\",\"luckperms\",\"permissions\"],\"templates\":[]}".getBytes(StandardCharsets.UTF_8));
            if(Files.isSymbolicLink(file)||Files.size(file)>65536) throw new IllegalArgumentException("命令配置超额或为符号链接");
            String json=Files.readString(file,StandardCharsets.UTF_8); if(JsonScan.check(json,8)!=null) throw new IllegalArgumentException("命令配置结构无效");
            JsonObject root=JsonParser.parseString(json).getAsJsonObject();
            Config parsed=parse(root);source=root.deepCopy();config=parsed;
        } catch(java.io.IOException | RuntimeException failure) { throw new IllegalStateException("命令配置无法装载；保留旧快照",failure); }
    }
    private static Config parse(JsonObject root){
            for(String key:List.of("enabled","allow_affect_others")) if(!root.getAsJsonPrimitive(key).isBoolean()) throw new IllegalArgumentException("命令开关类型错误");
            Set<String> plugins=new HashSet<>(); for(JsonElement value:root.getAsJsonArray("permission_plugins")) {
                String name=value.getAsString(); if(!name.matches("[a-z][a-z0-9_.-]{0,31}") || !plugins.add(name) || plugins.size()>16) throw new IllegalArgumentException("权限插件列表无效");
            }
            Map<String,CommandTemplate> templates=new LinkedHashMap<>();
            for(JsonElement value:root.getAsJsonArray("templates")) {
                CommandTemplate tpl=CommandTemplate.parse(value.getAsJsonObject(),plugins);
                if(templates.putIfAbsent(tpl.id(),tpl)!=null || templates.size()>32) throw new IllegalArgumentException("命令模板重复或超额");
            }
            return new Config(root.get("enabled").getAsBoolean(),root.get("allow_affect_others").getAsBoolean(),Map.copyOf(templates));
    }
    public JsonObject snapshot(){return source.deepCopy();}
    public static void validate(JsonObject root){parse(root);}
    public void switches(boolean enabled,boolean others)throws java.io.IOException {JsonObject root=snapshot();root.addProperty("enabled",enabled);root.addProperty("allow_affect_others",others);Config next=parse(root);StoreRepository.atomic(server.getWorldPath(LevelResource.ROOT).resolve(FILE),root.toString().getBytes(StandardCharsets.UTF_8));source=root;config=next;}
    public boolean enabled(String capability) {
        Config c=config; if(c==null || !c.enabled()) return false;
        if(capability.equals("command.affect_others")) return c.others();
        if(!capability.startsWith(CapabilityCatalog.COMMAND_TEMPLATE_PREFIX)) return false;
        CommandTemplate tpl=c.templates().get(capability.substring(CapabilityCatalog.COMMAND_TEMPLATE_PREFIX.length()));
        return tpl!=null && tpl.reviewed() && (!tpl.affectOthers() || c.others());
    }
    public boolean affectsOthers(String id) { CommandTemplate tpl=config.templates().get(id); return tpl!=null && tpl.affectOthers(); }
    public Result run(ServerPlayer player, String appId, String id, String json, Runnable beforeExecute) {
        if(!server.isSameThread()) throw new IllegalStateException("命令只允许主线程");
        if(!enabled(CapabilityCatalog.COMMAND_TEMPLATE_PREFIX+id) || player==null) return new Result(ScriptErrorCode.UNAVAILABLE,List.of());
        CommandTemplate tpl=config.templates().get(id); List<String> lines;
        try { lines=tpl.render(JsonValues.object(json.getBytes(StandardCharsets.UTF_8)),player.getUUID(),(item,tag)-> {
            ResourceLocation itemId=ResourceLocation.tryParse(item), tagId=ResourceLocation.tryParse(tag);
            return itemId!=null && tagId!=null && BuiltInRegistries.ITEM.containsKey(itemId)
                    && new ItemStack(BuiltInRegistries.ITEM.get(itemId)).is(TagKey.create(Registries.ITEM,tagId));
        }); } catch(RuntimeException invalid) { return new Result(ScriptErrorCode.INVALID_ARGUMENT,List.of()); }
        var results=new ArrayList<Integer>();
        for(String line:lines) {
            // 固定 level 2，executor 当前玩家；服主或 App 都不能借玩家 OP 提升。
            CommandSourceStack source=new CommandSourceStack(CommandSource.NULL,player.position(),player.getRotationVector(),player.serverLevel(),
                    2,player.getGameProfile().getName(),player.getDisplayName(),server,player);
            var dispatcher=server.getCommands().getDispatcher();
            var parsed=dispatcher.parse(line,source);
            String path="/"+String.join("/",parsed.getContext().getNodes().stream().map(n->n.getNode().getName()).toList());
            boolean denied=parsed.getContext().getChild()!=null || parsed.getContext().getNodes().stream().anyMatch(n->n.getNode().getRedirect()!=null)
                    || !tpl.allowedPaths().contains(path);
            if(!parsed.getExceptions().isEmpty() || parsed.getReader().canRead() || denied)
                return new Result(results.isEmpty()?(denied?ScriptErrorCode.NOT_AUTHORIZED:ScriptErrorCode.INVALID_ARGUMENT):ScriptErrorCode.PARTIAL,results);
            try {
                beforeExecute.run(); int rc=dispatcher.execute(parsed); results.add(rc);
                MCphone.LOGGER.info("[MCphone] 命令审计 player={} app={} template={} command={} result={}",player.getUUID(),appId,id,line,rc);
                // 未逐族验证的插件可能异步；正返回值也不能据此宣布业务成功。
                if(!CommandTemplate.synchronousVanilla(line) || rc<=0) return new Result(ScriptErrorCode.UNKNOWN,results);
            } catch(Exception failure) {
                MCphone.LOGGER.warn("[MCphone] 命令结果未确认 player={} app={} template={} command={}",player.getUUID(),appId,id,line,failure);
                return new Result(results.isEmpty()?ScriptErrorCode.UNKNOWN:ScriptErrorCode.PARTIAL,results);
            }
        }
        return new Result(ScriptErrorCode.OK,results);
    }
}
