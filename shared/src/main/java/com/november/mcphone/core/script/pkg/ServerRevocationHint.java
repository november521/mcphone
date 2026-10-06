package com.november.mcphone.core.script.pkg;

import com.november.mcphone.core.script.server.RevocationPolicy;
import java.util.*;

/** 服主下限作用于整个 App；作者/版本见证下限只作用于相同完整公钥。提示不代替服务器实际授权。 */
public record ServerRevocationHint(String digest,byte[] publicKey,long minimum,long ownerMinimum,String reason,boolean revoked,boolean ownerRevoked) {
    public ServerRevocationHint{publicKey=publicKey.clone();if(!digest.matches("[a-f0-9]{64}")||minimum<0||ownerMinimum<0||reason.length()>128||reason.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("吊销提示字段无效");if(publicKey.length>0)Signatures.publicKey(publicKey);}
    @Override public byte[] publicKey(){return publicKey.clone();}
    public RevocationPolicy.Rule rejected(long version,String packageDigest,byte[] key){boolean same=Arrays.equals(publicKey,key);long effective=Math.max(ownerMinimum,same?minimum:0);return version<effective||(ownerRevoked||same&&revoked)&&digest.equals(packageDigest)?new RevocationPolicy.Rule(effective,Set.of(),Set.of(),reason):null;}
}
