package com.november.mcphone.core.script.pkg;

import com.november.mcphone.core.script.server.*;
import java.nio.file.*;
import java.util.*;

/** 真实作者签名前端封套、完整公钥、客户端版本见证与运行期更新策略。 */
public final class ServerFrontendUpdateTest {
    private static int checks;private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static void reject(Runnable run,String why){boolean bad=false;try{run.run();}catch(RuntimeException expected){bad=true;}check(bad,why);}
    public static void main(String[] args)throws Exception{
        var key=Signatures.generate();var other=Signatures.generate();AppPackage one=VersionWitnessTest.pkg(key,"example:demo",1,"one"),two=VersionWitnessTest.pkg(key,"example:demo",2,"two");
        AppPackage front=FrontendEnvelope.read(FrontendEnvelope.write(two));ServerFrontendUpdate.Expected expected=new ServerFrontendUpdate.Expected("example:demo",two.digest(),key.getPublic().getEncoded(),2,FrontendDigest.of(two));ServerFrontendUpdate.require(front,expected);check(true,"服务器前端恢复作者原包身份");
        check(ServerFrontendUpdate.sameAuthor(one,front),"同一完整公钥可沿用授权");check(!ServerFrontendUpdate.sameAuthor(one,VersionWitnessTest.pkg(other,"example:demo",2,"other")),"更换作者完整公钥绝不自动替换");
        reject(()->ServerFrontendUpdate.require(front,new ServerFrontendUpdate.Expected("example:demo",two.digest(),other.getPublic().getEncoded(),2,FrontendDigest.of(two))),"元信息公钥不能只比短指纹");
        reject(()->ServerFrontendUpdate.require(front,new ServerFrontendUpdate.Expected("example:other",two.digest(),key.getPublic().getEncoded(),2,FrontendDigest.of(two))),"App 身份绑定");
        reject(()->ServerFrontendUpdate.require(front,new ServerFrontendUpdate.Expected("example:demo",one.digest(),key.getPublic().getEncoded(),2,FrontendDigest.of(two))),"原包摘要绑定");
        reject(()->ServerFrontendUpdate.require(front,new ServerFrontendUpdate.Expected("example:demo",two.digest(),key.getPublic().getEncoded(),3,FrontendDigest.of(two))),"整数版本必须在作者签名内");
        reject(()->ServerFrontendUpdate.require(front,new ServerFrontendUpdate.Expected("example:demo",two.digest(),key.getPublic().getEncoded(),2,"a".repeat(64))),"前端摘要与服务器部署一致");
        ServerRevocationHint scoped=new ServerRevocationHint(two.digest(),key.getPublic().getEncoded(),2,0,"作者最低版本",false,false);check(scoped.rejected(1,one.digest(),key.getPublic().getEncoded())!=null,"版本下限适用于相同完整作者公钥");check(scoped.rejected(1,one.digest(),other.getPublic().getEncoded())==null,"作者下限不能错误禁用另一把完整公钥");ServerRevocationHint owner=new ServerRevocationHint(two.digest(),key.getPublic().getEncoded(),2,2,"服主最低版本",false,false);check(owner.rejected(1,one.digest(),other.getPublic().getEncoded())!=null,"服主下限仍适用于整个 App");ServerRevocationHint revoked=new ServerRevocationHint(two.digest(),key.getPublic().getEncoded(),0,0,"作者撤销",true,false);check(revoked.rejected(2,two.digest(),other.getPublic().getEncoded())==null,"META 中签名公钥不同，即使规范内容摘要相同也不继承作者撤销");check(revoked.rejected(2,two.digest(),key.getPublic().getEncoded())!=null,"撤销同作者当前摘要");
        Path file=Files.createTempDirectory("mcphone-client-versions").resolve("versions.json");VersionWitness history=new VersionWitness(file);check(history.observe(one,true),"客户端已用版本持久化");check(history.observe(front,false),"新前端观察不撤销旧页");check(history.policy("example:demo",key.getPublic().getEncoded()).rejected("example:demo",1,one.digest())==null,"候选包不提高最低已用版本");check(history.seen("example:demo",key.getPublic().getEncoded(),2,front.digest()),"前端签名证据可直接持久化");check(history.observe(front,true),"原子切换前保存不可降级下限");check(!history.observe(one,true),"本地旧包不能绕过服务器前端版本记录");
        AppPackage fork=FrontendEnvelope.read(FrontendEnvelope.write(VersionWitnessTest.pkg(key,"example:demo",1,"fork")));check(!history.observe(fork,false),"旧版本同版本冲突仍可见证");check(new VersionWitness(file).policy("example:demo",key.getPublic().getEncoded()).rejected("example:demo",2,two.digest())!=null,"客户端重启后仍拒绝作者签名冲突");
        TrustStore trust=new TrustStore();String fp=Signatures.fingerprint(other.getPublic());trust.record(fp,"corrupt alias",key.getPublic().getEncoded(),1);trust.trust(fp,"example:demo");AppPackage otherPkg=VersionWitnessTest.pkg(other,"example:demo",2,"other");check(TrustState.of(otherPkg,"example:demo",trust).state()==TrustState.State.INVALID,"短指纹对应错误完整公钥时不能当 trusted");reject(()->trust.record(fp,"overwrite",other.getPublic().getEncoded(),2),"指纹别名不能覆盖已经钉扎的完整公钥");
        check(ScriptRuntimeConfig.parse("{\"cost_strategy\":\"unsupported\",\"cycle\":{\"zone\":\"UTC\"}}").frontendUpdate("example:demo").equals("optional"),"内源默认 optional");check(ScriptRuntimeConfig.parse("{\"cost_strategy\":\"unsupported\",\"cycle\":{\"zone\":\"UTC\"},\"app_policy\":[{\"id\":\"example:demo\",\"frontend_update\":\"forced\"}]}").frontendUpdate("example:demo").equals("forced"),"内源 forced 显式配置");reject(()->ScriptRuntimeConfig.parse("{\"cost_strategy\":\"unsupported\",\"cycle\":{\"zone\":\"UTC\"},\"app_policy\":[{\"id\":\"example:demo\",\"frontend_update\":true}]}"),"拒绝错误策略类型");reject(()->ScriptRuntimeConfig.parse("{\"cost_strategy\":\"unsupported\",\"cycle\":{\"zone\":\"UTC\"},\"app_policy\":[{\"id\":\"example:demo\",\"frontend_update\":\"silent\"}]}"),"未知更新策略不静默降级");
        System.out.println("ServerFrontendUpdateTest: "+checks+" passed");
    }
}
