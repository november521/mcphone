package com.november.mcphone.core.script.client;

import com.google.gson.*;
import com.november.mcphone.core.script.net.ScriptPush;
import net.minecraft.client.Minecraft;
import java.nio.charset.StandardCharsets;

/** 只有当前打开的发起 App 能触发原生确认页；旧连接与重复推送无效。 */
public final class ClientItemEscrow {
    private static long revision;
    private ClientItemEscrow(){}
    public static void clear(){revision=0;}
    public static void push(ScriptPush push){if(!push.isHost()||!push.topic().equals("mcphone:escrow.proposal")||!ClientHandshake.complete()||push.revision()<=revision)return;try{JsonObject data=JsonParser.parseString(new String(push.data(),StandardCharsets.UTF_8)).getAsJsonObject();if(!data.get("serverId").getAsString().equals(String.valueOf(ClientHandshake.serverId()))||Long.parseLong(data.get("epoch").getAsString())!=ClientHandshake.connectionEpoch())return;revision=push.revision();String app=data.get("app").getAsString();if(Minecraft.getInstance().screen instanceof com.november.mcphone.core.client.PhoneScreen phone&&phone.isScriptAppOpen(app))phone.openAddonPage(new com.november.mcphone.feature.escrow.client.ItemEscrowPage());}catch(RuntimeException invalid){com.november.mcphone.MCphone.LOGGER.warn("[MCphone] 忽略无效的物品确认推送");}}
}
