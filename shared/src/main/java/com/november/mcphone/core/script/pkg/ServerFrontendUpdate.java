package com.november.mcphone.core.script.pkg;

import com.november.mcphone.core.script.server.RevocationPolicy;
import java.util.*;

/** 原生服务器元信息与作者签名内容绑定；短指纹与展示版本不能代替完整公钥和整数版本。 */
public final class ServerFrontendUpdate {
    public record Expected(String app,String digest,byte[] publicKey,long version,String frontendDigest){
        public Expected{publicKey=publicKey.clone();if(!digest.matches("[a-f0-9]{64}")||!frontendDigest.matches("[a-f0-9]{64}")||version<0)throw new IllegalArgumentException("服务器元信息无效");Signatures.publicKey(publicKey);}
        @Override public byte[] publicKey(){return publicKey.clone();}
    }
    private ServerFrontendUpdate(){}
    public static void require(AppPackage incoming,Expected expected){
        if(!incoming.signed()||!incoming.digest().equals(expected.digest)||!incoming.manifest().id().equals(expected.app)||RevocationPolicy.versionOf(incoming)!=expected.version||!FrontendDigest.of(incoming).equals(expected.frontendDigest))throw new IllegalArgumentException("服务器信息与作者包不一致");
        SigManifest sig=SigManifest.parse(incoming.signature());if(!sig.verify(incoming.digest())||!Arrays.equals(sig.pubkey(),expected.publicKey))throw new IllegalArgumentException("服务器完整公钥与作者包不一致");
    }
    public static boolean sameAuthor(AppPackage a,AppPackage b){return a!=null&&b!=null&&a.signed()&&b.signed()&&Arrays.equals(SigManifest.parse(a.signature()).pubkey(),SigManifest.parse(b.signature()).pubkey());}
}
