package com.november.mcphone.core.script.client;

import com.google.gson.*;
import com.november.mcphone.core.script.net.*;
import com.november.mcphone.core.script.pkg.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** 使用实际握手、推送和 RPC 发包路径，证明 forced 绑定真实旧包版本且重放不能关闭它。 */
public final class ClientServerUpdatesTest {
    private static int checks;private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static ScriptPush policy(JsonObject data,long revision){return new ScriptPush(ScriptProtocol.HOST_APP_ID,"mcphone:store.update",data.toString().getBytes(StandardCharsets.UTF_8),revision);}
    public static void main(String[] args)throws Exception{
        var key=Signatures.generate();AppPackage one=VersionWitnessTest.pkg(key,"example:demo",1,"one"),two=VersionWitnessTest.pkg(key,"example:demo",2,"two");ScriptApp old=ScriptAppFolder.fromPackage("one.zip",one),fresh=ScriptAppFolder.fromPackage("two.zip",two);
        ClientHandshake.clear();ClientHandshake.onPush(ClientHandshakeTest.begin(1,99,1));ClientHandshake.onPush(ClientHandshakeTest.deployment(2,"example:demo",two.digest(),FrontendDigest.of(two),List.of("claim")));ClientHandshake.onPush(ClientHandshakeTest.end(3,99));
        JsonObject meta=new JsonObject();meta.addProperty("id","example:demo");meta.addProperty("digest",two.digest());meta.addProperty("frontendDigest",FrontendDigest.of(two));meta.addProperty("pubkey",Base64.getEncoder().encodeToString(key.getPublic().getEncoded()));meta.addProperty("versionCode","2");meta.addProperty("serverId",ClientHandshakeTest.SERVER.toString());meta.addProperty("epoch","99");meta.addProperty("frontendUpdate","optional");
        List<ScriptRpc> sent=new ArrayList<>();ScriptCall.installSender(sent::add);ScriptCall.clear();ClientHandshake.onPush(policy(meta,4));check(!ClientServerUpdates.required(old),"optional 不阻断旧前端");ScriptCall.call("example:demo","claim",new byte[0],old.frontendDigest(),one.digest(),r->{});check(sent.get(0).deployRev().equals(two.digest()),"optional 仍协商当前服务端协议");ScriptCall.clear();
        meta.addProperty("frontendUpdate","forced");ClientHandshake.onPush(policy(meta,5));check(ClientServerUpdates.required(old),"forced 要求旧前端同步");check(!ClientServerUpdates.required(fresh),"已匹配原包前端可直接打开");ScriptCall.call("example:demo","claim",new byte[0],old.frontendDigest(),one.digest(),r->{});check(sent.get(1).deployRev().equals(one.digest()),"实际旧包版本被送往版本闸");check(sent.get(1).frontendDigest().equals(old.frontendDigest()),"内容摘要仍是单独显示轴");ScriptCall.clear();
        meta.addProperty("frontendUpdate","off");ClientHandshake.onPush(policy(meta,4));check(ClientServerUpdates.required(old),"旧推送不能撤掉 forced");meta.addProperty("serverId",UUID.randomUUID().toString());ClientHandshake.onPush(policy(meta,6));check(ClientServerUpdates.required(old),"其他世界 UUID 不能改变策略");meta.addProperty("serverId",ClientHandshakeTest.SERVER.toString());meta.addProperty("epoch","98");ClientHandshake.onPush(policy(meta,7));check(ClientServerUpdates.required(old),"旧连接不能改变策略");meta.addProperty("epoch","99");ClientHandshake.onPush(policy(meta,8));check(!ClientServerUpdates.required(old),"服主新 off 策略被应用");ClientHandshake.clear();check(!ClientServerUpdates.forced("example:demo"),"断线释放策略，不留到下一服");
        System.out.println("ClientServerUpdatesTest: "+checks+" passed");
    }
}
