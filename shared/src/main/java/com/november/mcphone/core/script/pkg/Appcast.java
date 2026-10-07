package com.november.mcphone.core.script.pkg;

import com.google.common.net.InternetDomainName;
import com.november.mcphone.core.script.server.SafeFetch;
import org.w3c.dom.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import javax.xml.XMLConstants;
import javax.xml.parsers.*;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.*;

/** 有界 Sparkle appcast。先用 SAX 限制结构，再建立 DOM；两个解析器都禁外部实体。 */
public final class Appcast {
    public static final String NS = "https://mcphone.dev/xml-namespaces/update";
    public record Release(URI url, long version, String displayVersion, int minimumApi,
                          String channel, int length, byte[] zipSignature) {
        public Release { zipSignature = zipSignature.clone(); }
        @Override public byte[] zipSignature() { return zipSignature.clone(); }
    }
    private Appcast() {}
    static Document document(byte[] xml, URI feed) throws Exception {
        if (xml.length > SafeFetch.MAX_BYTES) throw new IllegalArgumentException("feed 超过 256 KiB");
        SafeFetch.validate(feed.toString(), Set.of(feed.getHost().toLowerCase(Locale.ROOT)));
        SAXParserFactory sax = SAXParserFactory.newInstance(); sax.setNamespaceAware(true);
        sax.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        sax.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        sax.setFeature("http://xml.org/sax/features/external-general-entities", false);
        sax.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        sax.setXIncludeAware(false);
        XMLReader reader = sax.newSAXParser().getXMLReader();
        reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        reader.setEntityResolver((publicId, systemId) -> { throw new SAXException("外部实体不允许"); });
        DefaultHandler limits = new DefaultHandler() {
            int depth, items;
            @Override public void startElement(String uri, String local, String name, Attributes attrs) throws SAXException {
                if (++depth > 16 || local.equals("item") && ++items > 64 || attrs.getLength() > 64)
                    throw new SAXException("feed 结构超额");
                for (int i = 0; i < attrs.getLength(); i++) if (attrs.getValue(i).length() > 2048)
                    throw new SAXException("feed 属性超过 2 KiB");
            }
            @Override public void endElement(String uri, String local, String name) { depth--; }
            @Override public void fatalError(SAXParseException failure) throws SAXException { throw failure; }
        };
        reader.setContentHandler(limits); reader.setErrorHandler(limits);
        reader.parse(new InputSource(new ByteArrayInputStream(xml)));
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setXIncludeAware(false); f.setExpandEntityReferences(false);
        f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, ""); f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder builder = f.newDocumentBuilder(); builder.setErrorHandler(limits);
        builder.setEntityResolver((publicId, systemId) -> { throw new SAXException("外部实体不允许"); });
        Document doc = builder.parse(new ByteArrayInputStream(xml));
        if (!doc.getDocumentElement().getTagName().equals("rss")) throw new IllegalArgumentException("feed 根必须是 rss");
        return doc;
    }
    public static List<Release> parse(byte[] xml,URI feed)throws Exception {
        Document doc=document(xml,feed);
        List<Release> result = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagName("item");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element item = (Element) nodes.item(i);
            List<Element> enclosures = children(item, "", "enclosure");
            if (enclosures.size() != 1) throw new IllegalArgumentException("item 必须恰有一个 enclosure");
            Element enclosure = enclosures.get(0);
            URI url = URI.create(enclosure.getAttribute("url"));
            if (url.getHost() == null) throw new IllegalArgumentException("下载地址无效");
            SafeFetch.validate(url.toString(), Set.of(url.getHost().toLowerCase(Locale.ROOT)));
            if (!registered(feed.getHost()).equals(registered(url.getHost()))) throw new IllegalArgumentException("更新跨注册域");
            long version = integer(enclosure.getAttributeNS(NS, "version"), Long.MAX_VALUE, false);
            int length = (int) integer(enclosure.getAttribute("length"), SafeFetch.MAX_BYTES, false);
            String display = enclosure.getAttributeNS(NS, "shortVersionString");
            if (display.isEmpty() || display.length() > 64 || display.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("显示版本无效");
            String api = enclosure.getAttributeNS(NS, "minScriptApi");
            int minimum = api.isEmpty() ? 1 : (int) integer(api, Integer.MAX_VALUE, false);
            if (!enclosure.getAttribute("type").equals("application/zip")) throw new IllegalArgumentException("更新类型必须 zip");
            byte[] sig = Base64.getDecoder().decode(enclosure.getAttributeNS(NS, "edSignature"));
            if (sig.length != 64) throw new IllegalArgumentException("更新签名必须 64 字节");
            List<Element> channels = children(item, NS, "channel");
            if (channels.size() > 1) throw new IllegalArgumentException("重复频道");
            String channel = channels.isEmpty() ? "stable" : channels.get(0).getTextContent();
            if (!channel.matches("[a-z][a-z0-9_-]{0,31}")) throw new IllegalArgumentException("频道无效");
            result.add(new Release(url, version, display, minimum, channel, length, sig));
        }
        return List.copyOf(result);
    }
    public static Optional<Release> latest(List<Release> releases, String channel, int api, long highest) {
        return releases.stream().filter(r -> r.channel().equals(channel) && r.minimumApi() <= api && r.version() > highest)
                .max(Comparator.comparingLong(Release::version));
    }
    private static String registered(String host) {
        InternetDomainName domain = InternetDomainName.from(host.toLowerCase(Locale.ROOT));
        if (!domain.isUnderPublicSuffix()) throw new IllegalArgumentException("更新域名缺少已知公共后缀");
        return domain.topPrivateDomain().toString();
    }
    private static List<Element> children(Element parent, String ns, String local) {
        List<Element> result = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element e
                && Objects.equals(ns.isEmpty() ? null : ns, e.getNamespaceURI()) && local.equals(e.getLocalName())) result.add(e);
        return result;
    }
    private static long integer(String text, long max, boolean zero) {
        if (!text.matches("[0-9]{1,19}")) throw new IllegalArgumentException("整数无效");
        long value = Long.parseLong(text); if (value < (zero ? 0 : 1) || value > max) throw new IllegalArgumentException("整数越界");
        return value;
    }
}
