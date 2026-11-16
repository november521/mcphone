package com.november.mcphone.core.client;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import com.google.gson.JsonObject;

/** 换肤元数据解码的版本接缝，解析和回退规则仍由 PhoneSkin 统一定义。 */
final class PhoneSkinMetadata {
    private PhoneSkinMetadata() {}
    private static final MetadataSectionSerializer<PhoneSkin.SkinMetadata> TYPE = new MetadataSectionSerializer<>() {
        public String getMetadataSectionName() { return "mcphone_skin"; }
        public PhoneSkin.SkinMetadata fromJson(JsonObject json) { return PhoneSkin.parseSkinMetadata(json); }
    };
    static PhoneSkin.SkinMetadata read(Resource resource) throws java.io.IOException {
        return resource.metadata().getSection(TYPE).orElse(new PhoneSkin.SkinMetadata(0,1));
    }
}
