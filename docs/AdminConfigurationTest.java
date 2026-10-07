package com.november.mcphone.core.script.server;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** 三档完整配置、固定路径事务、崩溃回滚和连接绑定确认的回归。 */
public final class AdminConfigurationTest {
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static void reject(Runnable action,String why){try{action.run();throw new AssertionError(why);}catch(IllegalArgumentException expected){checks++;}}
    private static JsonObject preset(String value){JsonObject op=new JsonObject();op.addProperty("kind","preset");op.addProperty("value",value);return op;}
    private static void write(Path world,String path,JsonObject object)throws Exception{Path file=world.resolve(path);Files.createDirectories(file.getParent());Files.writeString(file,object.toString(),StandardCharsets.UTF_8);}
    public static void main(String[]args)throws Exception{
        Path world=Files.createTempDirectory("mcphone-admin-test");write(world,CapabilityConfig.FILE,JsonParser.parseString(CapabilityConfig.template()).getAsJsonObject());write(world,QuotaManager.FILE,QuotaConfig.DEFAULT.json());
        write(world,ScriptRuntimeConfig.FILE,JsonParser.parseString("{\"cost_strategy\":\"unsupported\",\"cycle\":{\"zone\":\"UTC\"},\"server_scripts\":true,\"admin\":{\"deployment_approvers\":[\"00000000-0000-0000-0000-000000000001\"]},\"net\":{\"server_fetch\":true,\"allowed_hosts\":[\"example.com\"],\"allow_client_fetch\":false}}").getAsJsonObject());
        var manager=new AdminConfiguration(world,new AuditLog(world.resolve("mcphone/audit"),()->QuotaConfig.DEFAULT));var before=manager.read();
        for(String name:List.of("hardcore","standard","open")){
            var after=AdminConfiguration.change(before,preset(name));var quotas=QuotaConfig.parse(after.file(QuotaManager.FILE).toString());var runtime=ScriptRuntimeConfig.parse(after.file(ScriptRuntimeConfig.FILE).toString());var caps=CapabilityConfig.parse(after.file(CapabilityConfig.FILE).toString());boolean hard=name.equals("hardcore"),open=name.equals("open");
            check(quotas.get("escrow.per_player")== (hard?0:open?54:27),name+" 托管格数");check(quotas.get("mailbox.per_player")== (hard?9:27),name+" 收件箱格数");check(quotas.get("kv.per_player_app")== (hard?4096:open?16384:8192),name+" 玩家 KV");check(quotas.get("apps.per_player")== (hard?16:open?64:32),name+" App 数量");
            check(!runtime.serverScripts(),name+" 不代替服主开启服务端脚本");check(!after.file(ScriptRuntimeConfig.FILE).get("cross_server_trust").getAsBoolean(),name+" 不开启跨服信任");check(runtime.net().enabled()==open&&runtime.net().clientAllowed()!=hard,name+" 两个外网开关");check(runtime.net().hosts().equals(Set.of("example.com")),name+" 保留服主域名白名单");check(caps.isDisabled("trade.escrow")==hard,name+" 市场开关");for(String capability:List.of("resource.read.block","resource.move","container.read"))check(caps.isDisabled(capability)!=open,name+" "+capability);
            check(!AdminConfiguration.differences(before,after).isEmpty(),name+" 差异可查看");check(runtime.deploymentApprovers().size()==1,name+" 不删除管理员名单");
        }
        var hard=AdminConfiguration.change(before,preset("hardcore"));JsonObject override=new JsonObject();override.addProperty("kind","boundary");override.addProperty("id","resource_move");override.addProperty("enabled",true);var overridden=AdminConfiguration.change(hard,override);check(overridden.file(CapabilityConfig.FILE).getAsJsonObject("boundary").get("resource_move").getAsBoolean(),"显式项可覆盖预设");
        reject(()->AdminConfiguration.change(before,preset("wild")),"未知预设拒绝");JsonObject extra=preset("open");extra.addProperty("file","../other.json");reject(()->AdminConfiguration.change(before,extra),"客户端不能选择文件路径");
        manager.commit(UUID.randomUUID(),before,hard);check(manager.read().revision().equals(hard.revision()),"三份配置均写入");check(Files.list(world.resolve("mcphone/audit")).findAny().isPresent(),"管理写入已有审计");
        try{manager.commit(UUID.randomUUID(),before,overridden);throw new AssertionError("旧预览覆盖新配置");}catch(IllegalArgumentException expected){checks++;}
        AdminConfiguration.recover(world);check(manager.read().revision().equals(hard.revision()),"已提交事务重启统一保留新配置");check(!Files.exists(world.resolve(AdminConfiguration.JOURNAL)),"恢复后移除自己的事务日志");
        write(world,ScriptRuntimeConfig.FILE,overridden.file(ScriptRuntimeConfig.FILE));JsonObject manual=hard.file(ScriptRuntimeConfig.FILE);manual.addProperty("server_scripts",true);write(world,ScriptRuntimeConfig.FILE,manual);AdminConfiguration.recover(world);check(manager.read().file(ScriptRuntimeConfig.FILE).get("server_scripts").getAsBoolean(),"已完成事务不覆盖服主后来的手动编辑");
        List<String> complete=new ArrayList<>();String longText="完整公钥与字段 "+"\\\"😀汉".repeat(600);AdminConfiguration.addLines(complete,longText);check(String.join("",complete).equals(longText),"分页不截断字符串或拆开代理对");for(String line:complete)check(new JsonPrimitive(line).toString().getBytes(StandardCharsets.UTF_8).length<=702,"转义后的差异页有明确字节上限");
        JsonObject transaction=new JsonObject();transaction.addProperty("format",1);transaction.addProperty("committed",false);transaction.add("before",new Gson().toJsonTree(before.files()));transaction.add("after",new Gson().toJsonTree(hard.files()));write(world,AdminConfiguration.JOURNAL,transaction);write(world,CapabilityConfig.FILE,hard.file(CapabilityConfig.FILE));AdminConfiguration.recover(world);check(manager.read().revision().equals(before.revision()),"只写了部分文件后强杀，重启回滚全部");
        JsonObject unsafe=transaction.deepCopy();unsafe.getAsJsonObject("before").add("../outside.json",new JsonObject());write(world,AdminConfiguration.JOURNAL,unsafe);try{AdminConfiguration.recover(world);throw new AssertionError("事务越界路径接受");}catch(java.io.IOException expected){checks++;}check(!Files.exists(world.resolve("../outside.json")),"坏事务不触碰外部文件");
        AtomicLong now=new AtomicLong();var tokens=new AdminConfirmations<String>(now::get);UUID owner=UUID.randomUUID(),other=UUID.randomUUID();String token=tokens.issue(owner,1,"changes");check(token.matches("[0-9a-f]{32}"),"宿主随机令牌");reject(()->tokens.get(other,1,token),"令牌绑定玩家");reject(()->tokens.get(owner,2,token),"令牌绑定连接");check(tokens.consume(owner,1,token).equals("changes"),"匹配连接能确认");reject(()->tokens.get(owner,1,token),"确认不能重复消费");String old=tokens.issue(owner,1,"old"),replacement=tokens.issue(owner,1,"new");reject(()->tokens.get(owner,1,old),"新预览作废旧确认");now.set(120000);reject(()->tokens.get(owner,1,replacement),"两分钟确认过期");String fresh=tokens.issue(owner,3,"fresh");tokens.forget(owner);reject(()->tokens.get(owner,3,fresh),"断线丢弃确认");
        for(String action:List.of("admin.list","admin.preview","admin.previewPage","admin.commit"))check(HostControls.ACTIONS.contains(action),"宿主路由开放 "+action);
        String signedEpoch=tokens.issue(owner,-123,"signed");check(tokens.consume(owner,-123,signedEpoch).equals("signed"),"连接 epoch 可为负数，只有零无效");
        check(CapabilityConfig.load(world.resolve("new-capabilities.json")).isDisabled("resource.read.block"),"新服标准模板关闭远程资源读取");
        System.out.println("AdminConfigurationTest: "+checks+" passed");
    }
}
