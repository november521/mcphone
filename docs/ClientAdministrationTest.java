package com.november.mcphone.core.script.client;

import com.google.gson.JsonObject;
import com.november.mcphone.core.script.net.*;
import java.nio.charset.StandardCharsets;

/** 离线身份提示只能来自当前连接的完整策略，不能被旧服的推送清除或打开。 */
public final class ClientAdministrationTest {
    private static int checks;
    private static void check(boolean result,String why){checks++;if(!result)throw new AssertionError(why);}
    private static ScriptPush policy(long revision,long epoch,Object online){
        JsonObject o=new JsonObject();o.addProperty("serverId",ClientHandshakeTest.SERVER.toString());o.addProperty("epoch",Long.toString(epoch));o.addProperty("admin",false);o.addProperty("appLimit",32);
        if(online instanceof Boolean value)o.addProperty("onlineMode",value);else if(online!=null)o.addProperty("onlineMode",online.toString());
        return new ScriptPush(ScriptProtocol.HOST_APP_ID,"mcphone:network.policy",o.toString().getBytes(StandardCharsets.UTF_8),revision);
    }
    public static void main(String[] args){
        ClientAdministration.clear();ClientHandshake.clear();check(!ClientAdministration.offlineIdentity(),"未连接不显示上一服的警示");
        ClientHandshake.onPush(ClientHandshakeTest.begin(1,ClientHandshakeTest.EPOCH,0));ClientHandshake.onPush(ClientHandshakeTest.end(2,ClientHandshakeTest.EPOCH));
        ClientAdministration.push(policy(3,ClientHandshakeTest.EPOCH,false));check(ClientAdministration.offlineIdentity(),"当前服未认证时显示身份警示");
        ClientAdministration.push(policy(4,ClientHandshakeTest.EPOCH+1,true));check(ClientAdministration.offlineIdentity(),"错误连接不能清除警示");
        ClientAdministration.push(policy(2,ClientHandshakeTest.EPOCH,true));check(ClientAdministration.offlineIdentity(),"迟到策略不能清除警示");
        ClientAdministration.push(policy(5,ClientHandshakeTest.EPOCH,"true"));check(ClientAdministration.offlineIdentity(),"字符串不能冒充认证布尔值");
        ClientAdministration.push(policy(6,ClientHandshakeTest.EPOCH,true));check(!ClientAdministration.offlineIdentity(),"当前服正版认证策略解除提示");
        ClientAdministration.push(policy(7,ClientHandshakeTest.EPOCH,null));check(!ClientAdministration.offlineIdentity(),"旧服省略新增字段保持兼容");
        ClientAdministration.clear();ClientHandshake.clear();check(!ClientAdministration.offlineIdentity(),"断线清除提示");System.out.println("ClientAdministrationTest: "+checks+" passed");
    }
}
