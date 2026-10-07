package com.november.mcphone.core.script.pkg;

import com.google.gson.*;
import com.november.mcphone.core.script.server.RevocationPolicy;
import org.w3c.dom.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** 作者吊销签名使用独立域与 App 身份。规范 JSON 按固定字段顺序，整数写十进制字符串、集合升序。 */
public record AuthorRevocation(String app,RevocationPolicy.Rule rule) {
    public record Evidence(AuthorRevocation statement,byte[] signature){public Evidence{signature=signature.clone();}@Override public byte[] signature(){return signature.clone();}}
    public AuthorRevocation {
        if(app==null||app.length()>64||!app.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")||rule.versions().size()>256||rule.digests().size()>256||rule.reason().length()>128||rule.reason().codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("作者吊销声明无效");
        if(rule.versions().stream().anyMatch(v->v<0)||rule.digests().stream().anyMatch(d->!d.matches("[0-9a-f]{64}")))throw new IllegalArgumentException("作者吊销集合无效");
    }
    public byte[] canonical(){JsonObject o=new JsonObject();o.addProperty("format",1);o.addProperty("app",app);o.addProperty("minVersion",Long.toString(rule.minVersion()));JsonArray versions=new JsonArray(),digests=new JsonArray();rule.versions().stream().sorted().forEach(v->versions.add(Long.toString(v)));rule.digests().stream().sorted().forEach(digests::add);o.add("revokedVersions",versions);o.add("revokedDigests",digests);o.addProperty("reason",rule.reason());return ("mcphone-revocations-v1\0"+o).getBytes(StandardCharsets.UTF_8);}
    public byte[] sign(PrivateKey key)throws GeneralSecurityException {Signature s=Signature.getInstance("Ed25519");s.initSign(key);s.update(canonical());return s.sign();}
    public boolean verify(byte[] publicKey,byte[] signature)throws GeneralSecurityException {if(signature.length!=64)return false;Signature s=Signature.getInstance("Ed25519");s.initVerify(Signatures.publicKey(publicKey));s.update(canonical());return s.verify(signature);}
    /** 改坏的吊销段只被忽略，不能停用所有应用或污染版本历史。外部实体依旧拒绝整个 XML。 */
    public static Optional<AuthorRevocation> parse(byte[] xml,URI feed,String app,byte[] expectedKey)throws Exception {
        return evidence(xml,feed,app,expectedKey).map(Evidence::statement);
    }
    public static Optional<Evidence> evidence(byte[] xml,URI feed,String app,byte[] expectedKey)throws Exception {
        Document doc=Appcast.document(xml,feed);List<Element> channels=children(doc.getDocumentElement(),"","channel");if(channels.size()!=1)return Optional.empty();
        try {List<Element> declarations=children(channels.get(0),Appcast.NS,"revocation"),signatures=children(channels.get(0),Appcast.NS,"revocationSignature");if(declarations.size()!=1||signatures.size()!=1)return Optional.empty();Element node=declarations.get(0);long min=0;String reason="";Set<Long> versions=new LinkedHashSet<>();Set<String> digests=new LinkedHashSet<>();Set<String> singleton=new HashSet<>();
            for(Node child=node.getFirstChild();child!=null;child=child.getNextSibling())if(child instanceof Element e){if(!Appcast.NS.equals(e.getNamespaceURI())||e.getAttributes().getLength()>0||!children(e,"*","*").isEmpty())return Optional.empty();String text=e.getTextContent();switch(e.getLocalName()){
                case "minVersion"->{if(!singleton.add("min"))return Optional.empty();min=integer(text);}
                case "revoked"->{if(!versions.add(integer(text))||versions.size()>256)return Optional.empty();}
                case "revokedDigest"->{String d=text.replaceFirst("^sha256:","");if(!digests.add(d)||digests.size()>256)return Optional.empty();}
                case "reason"->{if(!singleton.add("reason"))return Optional.empty();reason=text;}
                default->{return Optional.empty();}
            }}
            AuthorRevocation statement=new AuthorRevocation(app,new RevocationPolicy.Rule(min,versions,digests,reason));byte[] signature=Base64.getDecoder().decode(signatures.get(0).getTextContent().trim());return statement.verify(expectedKey,signature)?Optional.of(new Evidence(statement,signature)):Optional.empty();
        }catch(IllegalArgumentException|GeneralSecurityException bad){return Optional.empty();}
    }
    private static long integer(String value){if(!value.matches("0|[1-9][0-9]{0,18}"))throw new IllegalArgumentException("吊销整数无效");return Long.parseLong(value);}
    private static List<Element> children(Element parent,String namespace,String local){List<Element> out=new ArrayList<>();for(Node n=parent.getFirstChild();n!=null;n=n.getNextSibling())if(n instanceof Element e&&(namespace.equals("*")||Objects.equals(namespace.isEmpty()?null:namespace,e.getNamespaceURI()))&&(local.equals("*")||local.equals(e.getLocalName())))out.add(e);return out;}
}
