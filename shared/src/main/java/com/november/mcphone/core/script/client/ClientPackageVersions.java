package com.november.mcphone.core.script.client;

import com.november.mcphone.core.script.pkg.*;
import com.november.mcphone.core.script.server.*;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.io.IOException;
import java.util.*;

/** 本地导入、外部更新和服务器前端共用的完整作者版本见证；渲染只读已加载快照。 */
public final class ClientPackageVersions {
    private static VersionWitness witness;
    private static boolean failed;
    private ClientPackageVersions(){}
    private static VersionWitness witness()throws IOException{if(failed)throw new IOException("客户端版本历史不可用");if(witness==null){try{witness=new VersionWitness(Minecraft.getInstance().gameDirectory.toPath().resolve("config/mcphone/package-versions.json"));}catch(IOException bad){failed=true;throw bad;}}return witness;}
    public static synchronized void observe(ScriptApp app){if(app.pkg()==null||!app.pkg().signed())return;try{witness().observe(app.pkg(),false);}catch(IOException|RuntimeException bad){com.november.mcphone.MCphone.LOGGER.warn("[MCphone] 客户端版本见证拒绝 {}",app.id(),bad);}}
    public static synchronized boolean observe(AppPackage pkg)throws IOException{return witness().observe(pkg,false);}
    public static synchronized void accept(ScriptApp app)throws IOException{if(app.pkg()==null||!app.pkg().signed())return;VersionWitness v=witness();if(!v.manualAllowed(app.id().toString(),app.authorKey(),app.versionCode(),app.pkg().digest()))v.require(app.pkg(),true);}
    public static synchronized void approveManual(ScriptApp app)throws IOException{if(app.pkg()!=null&&app.pkg().signed())witness().approveManual(app.pkg());}
    public static synchronized boolean manualPossible(ScriptApp app)throws IOException{if(app.pkg()==null||!app.pkg().signed())return false;VersionWitness v=witness();v.observe(app.pkg(),false);var rule=v.policy(app.id().toString(),app.authorKey()).rule(app.id().toString());return rule==null||!rule.versions().contains(Long.MAX_VALUE);}
    public static synchronized long highest(ScriptApp app){try{return witness().approved(app.id().toString(),app.authorKey());}catch(IOException bad){return Long.MAX_VALUE;}}
    public static synchronized Component blocked(ScriptApp app){if(app.pkg()==null||!app.pkg().signed())return null;if(failed)return Component.literal("客户端版本历史不可用，请修复后重启");if(witness==null)return Component.literal("客户端版本历史尚未加载");RevocationPolicy.Rule rule=witness.policy(app.id().toString(),app.authorKey()).rejected(app.id().toString(),app.versionCode(),app.pkg().digest());if(rule!=null&&!witness.manualAllowed(app.id().toString(),app.authorKey(),app.versionCode(),app.pkg().digest()))return Component.literal(rule.reason());return witness.seen(app.id().toString(),app.authorKey(),app.versionCode(),app.pkg().digest())?null:Component.literal("此包的版本证据未成功保存");}
}
