package dev.seraph.wings;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.model.ModelData;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.model.ModelPartBuilder;
import net.minecraft.client.model.ModelPartData;
import net.minecraft.client.model.ModelTransform;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.Items;
import net.minecraft.util.Identifier;
import org.joml.Quaternionf;

/** Purely client-side wings. No entity spawning, no custom equipment items. */
public final class SeraphWingsClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        System.out.println("[Seraph Wings] 1.0.3 initialization");
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register((type, renderer, helper, context) -> {
            if (renderer instanceof PlayerEntityRenderer playerRenderer) {
                helper.register(new WingsRenderer(playerRenderer));
                System.out.println("[Seraph Wings] attached cosmetic player feature renderer");
            }
        });
    }

    private static final class WingsRenderer extends FeatureRenderer<PlayerEntityRenderState, PlayerEntityModel> {
        private static final int FULL_BRIGHT = 0x00F000F0;
        private final ModelPart[] angelLayers = new ModelPart[4];
        private final ModelPart[] upperLayers = new ModelPart[4];
        private final ModelPart[] lowerLayers = new ModelPart[3];
        private final Identifier[] angelTextures = new Identifier[4];
        private final Identifier[] upperTextures = new Identifier[4];
        private final Identifier[] lowerTextures = new Identifier[3];
        private final ModelPart angelGlow = plane(127, 136);
        private final ModelPart upperGlow = plane(138, 146);
        private final ModelPart lowerGlow = plane(96, 76);
        private final ModelPart halo = plane(32, 32);
        private final ModelPart haloAura = plane(32, 32);
        private final ModelPart haloGlow = plane(72, 34);
        private static Identifier texture(String name) {
            return Identifier.of("seraphwings", "textures/cosmetic/" + name + ".png");
        }
        private WingsRenderer(FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel> context) {
            super(context);
            for(int i=0;i<4;i++) {
                angelLayers[i] = plane(127,136);
                upperLayers[i] = plane(138,146);
                angelTextures[i] = texture("angel/layer_"+i);
                upperTextures[i] = texture("fallen/upper_"+i);
            }
            for(int i=0;i<3;i++) {
                lowerLayers[i] = plane(96,76);
                lowerTextures[i] = texture("fallen/lower_"+i);
            }
        }
        private static ModelPart plane(int w,int h) {
            ModelData d = new ModelData();
            ModelPartData r = d.getRoot();
            r.addChild("sheet",ModelPartBuilder.create().uv(0,0)
                .cuboid(0f,0f,0f,(float)w,(float)h,0f),ModelTransform.NONE);
            return r.createPart(w*2,h).getChild("sheet");
        }
        @Override
        public void render(MatrixStack m,OrderedRenderCommandQueue q,int light,
            PlayerEntityRenderState s,float limbAngle,float limbDistance) {
            if(s.equippedChestStack==null) return;
            boolean divine=s.equippedChestStack.isOf(Items.ELYTRA);
            boolean fallen=s.equippedChestStack.isOf(Items.NETHERITE_CHESTPLATE);
            if(!divine&&!fallen) return;
            float age=s.age;
            float gliding=s.isGliding?1f:0f;
            if(divine){
                for(int side:new int[]{-1,1}){
                    drawWing(m,q,light,angelLayers,angelTextures,angelGlow,
                        texture("angel/wing_glow"),texture("angel/wing_aura"),side,.20f,-.57f,.19f,.245f,age,gliding,0f,false);
                }
                drawHalo(m,q,halo,texture("angel/halo"),haloGlow,texture("angel/halo_glow"),texture("angel/halo_aura"),age);
            }else{
                for(int side:new int[]{-1,1}){
                    drawWing(m,q,light,upperLayers,upperTextures,upperGlow,
                        texture("fallen/upper_glow"),texture("fallen/upper_aura"),side,.22f,-.54f,.19f,.235f,age,gliding,0f,false);
                    drawWing(m,q,light,lowerLayers,lowerTextures,lowerGlow,
                        texture("fallen/lower_glow"),texture("fallen/lower_aura"),side,.24f,-.14f,.21f,.225f,age,gliding,8f,true);
                }
                drawHalo(m,q,halo,texture("fallen/halo"),haloGlow,texture("fallen/halo_glow"),texture("fallen/halo_aura"),age);
            }
        }
        private void drawWing(MatrixStack m,OrderedRenderCommandQueue q,int light,
            ModelPart[] layers,Identifier[] textures,ModelPart glow,Identifier glowTex,Identifier auraTex,
            int side,float rootX,float rootY,float rootZ,float size,float age,float glide,float lag,boolean lower) {
            m.push();
            m.translate(side*rootX,rootY,rootZ);
            float sway=(float)Math.sin((age-lag)*.078f)*(lower?.062f:.087f);
            float idle=(float)Math.sin((age-lag)*.113f)*.028f;
            float yaw=side*(.20f+sway+glide*.14f);
            float pitch=-.025f+idle-glide*.08f;
            float roll=side*(lower?-.14f:-.055f);
            m.multiply(new Quaternionf().rotationXYZ(pitch,yaw,roll));
            m.scale(side*size,size,size);
            for(int i=0;i<layers.length;i++){
                m.push();
                m.translate(0,0,.17*(layers.length-1-i));
                RenderLayer render=RenderLayers.entityCutoutNoCull(textures[i]);
                // No damage/hurt overlay: passing 0 here tinted the white artwork red.
                int wingLight = glowTex.getPath().contains("/angel/") ? FULL_BRIGHT : light;
                q.submitModelPart(layers[i],m,render,wingLight,OverlayTexture.DEFAULT_UV,null);
                m.pop();
            }
            // Soft aura drawn from an additional blurred glow mask; feather art remains byte-identical.
            m.push();
            m.translate(0,0,.17*(layers.length+1));
            m.scale(1.045f,1.045f,1.0f);
            int auraAlpha=125+(int)(22*Math.sin(age*.095f));
            q.submitModelPart(glow,m,RenderLayers.entityTranslucentEmissive(auraTex),
                FULL_BRIGHT,OverlayTexture.DEFAULT_UV,null,(auraAlpha<<24)|0xFFFFFF,null);
            m.pop();
            // Bright feather highlights: this is self-lit in vanilla; shader bloom is optional.
            m.push();
            m.translate(0,0,.17*layers.length);
            int a=205+(int)(30*Math.sin(age*.095f));
            q.submitModelPart(glow,m,RenderLayers.entityTranslucentEmissive(glowTex),
                FULL_BRIGHT,OverlayTexture.DEFAULT_UV,null,(a<<24)|0xFFFFFF,null);
            m.pop();m.pop();
        }
        private void drawHalo(MatrixStack m,OrderedRenderCommandQueue q,ModelPart ring,
            Identifier ringTex,ModelPart glow,Identifier glowTex,Identifier auraTex,float age){
            double bob=Math.sin(age*.09f)*.025;
            m.push();
            m.translate(0,-1.22-bob,0);
            m.multiply(new Quaternionf().rotationY(age*.018f));
            m.multiply(new Quaternionf().rotationX((float)Math.PI/2));
            m.translate(-.33,-.33,0);
            m.scale(.33f,.33f,.33f);
            q.submitModelPart(ring,m,RenderLayers.entityCutoutNoCull(ringTex),FULL_BRIGHT,OverlayTexture.DEFAULT_UV,null);
            m.pop();
            // Diffuse ring aura centered on the same horizontal halo plane.
            m.push();
            m.translate(0,-1.22-bob,0);
            m.multiply(new Quaternionf().rotationY(age*.018f));
            m.multiply(new Quaternionf().rotationX((float)Math.PI/2));
            m.translate(-.36,-.36,.008);
            m.scale(.36f,.36f,.36f);
            int haloAuraAlpha=170+(int)(24*Math.sin(age*.09f));
            q.submitModelPart(haloAura,m,RenderLayers.entityTranslucentEmissive(auraTex),
                FULL_BRIGHT,OverlayTexture.DEFAULT_UV,null,(haloAuraAlpha<<24)|0xFFFFFF,null);
            m.pop();
            m.push();
            m.translate(-.38,-1.24-bob,.03);
            m.scale(.18f,.18f,.18f);
            int a=200+(int)(32*Math.sin(age*.09f));
            q.submitModelPart(glow,m,RenderLayers.entityTranslucentEmissive(glowTex),
                FULL_BRIGHT,OverlayTexture.DEFAULT_UV,null,(a<<24)|0xFFFFFF,null);
            m.pop();
        }
    }
}