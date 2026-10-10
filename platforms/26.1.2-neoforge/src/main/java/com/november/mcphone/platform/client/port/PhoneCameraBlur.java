package com.november.mcphone.platform.client.port;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.november.mcphone.MCphone;
import com.november.mcphone.feature.camera.client.CameraMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.client.renderer.PostChainConfig;
import net.minecraft.client.renderer.Projection;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.resources.Identifier;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** 相机软闪光在原版真正执行 GUI 模糊时处理，避免在提取阶段操作上一帧目标。 */
public final class PhoneCameraBlur {
    private static final Identifier CONFIG=Identifier.withDefaultNamespace("post_effect/blur.json");
    private static final Map<Integer,PostChain> CHAINS=new HashMap<>();
    private static Projection projection;
    private static ProjectionMatrixBuffer matrix;
    private static JsonObject template;
    private static int pending;
    private static boolean failed;
    private PhoneCameraBlur() {}
    public static void request(PhoneGraphics g,float radius) {
        pending=Math.max(0,Math.min(10,Math.round(radius)));
        if(pending>0) g.nativeGraphics().blurBeforeThisStratum();
    }
    /** 仅接管相机当前帧主动请求的模糊，普通菜单继续走原版。 */
    public static boolean processRequested() {
        int radius=pending; pending=0;
        if(radius==0||!CameraMode.isActive()) return false;
        if(failed) return false;
        var mc=Minecraft.getInstance();
        try {
            PostChain chain=CHAINS.get(radius);
            if(chain==null) {
                if(template==null) {
                    try(var reader=mc.getResourceManager().getResourceOrThrow(CONFIG).openAsReader()) {
                        template=JsonParser.parseReader(reader).getAsJsonObject();
                    }
                    projection=new Projection(); projection.setupOrtho(0.1F,1000,1,1,false);
                    matrix=new ProjectionMatrixBuffer("MCphone 相机软闪光");
                }
                JsonObject config=template.deepCopy();
                for(var pass:config.getAsJsonArray("passes")) {
                    var uniforms=pass.getAsJsonObject().getAsJsonObject("uniforms").getAsJsonArray("BlurConfig");
                    for(var uniform:uniforms) {
                        var value=uniform.getAsJsonObject();
                        if("Radius".equals(value.get("name").getAsString())) value.addProperty("value",radius);
                    }
                }
                var parsed=PostChainConfig.CODEC.parse(JsonOps.INSTANCE,config).getOrThrow();
                chain=PostChain.load(parsed,mc.getTextureManager(),Set.of(PostChain.MAIN_TARGET_ID),
                        Identifier.fromNamespaceAndPath("mcphone","camera_flash"),projection,matrix);
                CHAINS.put(radius,chain);
            }
            chain.process(mc.getMainRenderTarget(),GraphicsResourceAllocator.UNPOOLED);
            return true;
        } catch(Exception error) {
            failed=true;
            MCphone.LOGGER.warn("[MCphone] 相机软闪光加载失败，回退原版模糊",error);
            return false;
        }
    }
    public static void dispose() {
        CHAINS.values().forEach(PostChain::close);CHAINS.clear();
        if(matrix!=null) matrix.close();
        matrix=null;projection=null;template=null;pending=0;failed=false;
    }
}
