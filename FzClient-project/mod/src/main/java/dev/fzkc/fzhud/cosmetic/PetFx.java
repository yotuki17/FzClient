package dev.fzkc.fzhud.cosmetic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.RenderPlayer;
import net.minecraft.client.renderer.entity.layers.LayerRenderer;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelBlaze;
import net.minecraft.client.model.ModelChicken;
import net.minecraft.client.model.ModelCreeper;
import net.minecraft.client.model.ModelGhast;
import net.minecraft.client.model.ModelOcelot;
import net.minecraft.client.model.ModelPig;
import net.minecraft.client.model.ModelSheep1;
import net.minecraft.client.model.ModelSheep2;
import net.minecraft.client.model.ModelSlime;
import net.minecraft.client.model.ModelSnowMan;
import net.minecraft.client.model.ModelWolf;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.EntitySheep;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.EnumDyeColor;
import net.minecraft.util.BlockPos;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.WeakHashMap;

/**
 * Pets: a small animal floating next to your shoulder that follows you around (a puppy, a kitten, a chick, a mini
 * blaze...). They are Minecraft's own models and textures drawn smaller, not entities: the server never knows about
 * them, they can't be hit and nothing else (hitboxes, mob counters) sees them. Other FzClient users see yours.
 * Small ones (axolotls, parrots) ride on your shoulder instead, drawn as part of the player.
 * Your own floating pet is hidden in first person (it only got in the way of the view).
 */
public final class PetFx {
    /** One kind of pet: its model(s), texture(s), size and how high it floats. */
    private static final class Kind {
        final ModelBase model, layer;
        final ResourceLocation tex, layerTex;
        final float scale;
        final boolean child;
        /** Colour of the second layer (wool, collar), or null. */
        final float[] layerRgb;
        /** Translucent second layer (the slime's outer gel). */
        final boolean layerBlend;
        /** Rides on the shoulder instead of floating beside you. */
        final boolean shoulder;
        /** Extra size in the Cosmetics tiles (tiny pets). */
        final float guiMul;
        /** Today's Minecraft texture to use once fetched (MobTextures), or null. */
        final String mobTex;

        /** A pet of our own (PetModels) on the shoulder: tex = painted stand-in until the real texture is here. */
        Kind(ModelBase model, String mobTex, ResourceLocation tex, float scale, float guiMul) {
            this.mobTex = mobTex;
            this.model = model;
            this.tex = tex;
            this.scale = scale;
            this.child = false;
            this.layer = null;
            this.layerTex = null;
            this.layerRgb = null;
            this.layerBlend = false;
            this.shoulder = true;
            this.guiMul = guiMul;
        }

        Kind(ModelBase model, String tex, float scale, boolean child, ModelBase layer, String layerTex, float[] layerRgb, boolean layerBlend) {
            this.model = model;
            this.tex = new ResourceLocation("textures/entity/" + tex);
            this.scale = scale;
            this.child = child;
            this.layer = layer;
            this.layerTex = layerTex == null ? null : new ResourceLocation("textures/entity/" + layerTex);
            this.layerRgb = layerRgb;
            this.layerBlend = layerBlend;
            this.shoulder = false;
            this.guiMul = 1f;
            this.mobTex = null;
        }
    }

    private static Kind[] kinds;

    /** In the order of Equip.PETS (index 0 = none). */
    private static Kind kind(String id) {
        if (kinds == null) {
            float[] red = EntitySheep.getDyeRgb(EnumDyeColor.RED), pink = EntitySheep.getDyeRgb(EnumDyeColor.PINK);
            kinds = new Kind[]{
                    null,
                    new Kind(new ModelWolf(), "wolf/wolf_tame.png", 0.8f, true, new ModelWolf(), "wolf/wolf_collar.png", red, false),
                    new Kind(new ModelOcelot(), "cat/red.png", 0.9f, true, null, null, null, false),
                    new Kind(new ModelChicken(), "chicken.png", 1.05f, true, null, null, null, false),
                    new Kind(new ModelPig(), "pig/pig.png", 0.8f, true, null, null, null, false),
                    new Kind(new ModelSheep2(), "sheep/sheep.png", 0.75f, true, new ModelSheep1(), "sheep/sheep_fur.png", pink, false),
                    new Kind(new ModelSlime(16), "slime/slime.png", 0.5f, false, new ModelSlime(0), "slime/slime.png", null, true),
                    new Kind(new ModelBlaze(), "blaze.png", 0.3f, false, null, null, null, false),
                    new Kind(new ModelGhast(), "ghast/ghast.png", 0.26f, false, null, null, null, false),
                    new Kind(new ModelSnowMan(), "snowman.png", 0.32f, false, null, null, null, false),
                    new Kind(new ModelCreeper(), "creeper/creeper.png", 0.34f, false, null, null, null, false),
                    axolotl("axolotl_lucy", PetModels.axolotlSkin("axolotl", 0xFFF4A9CB, 0xFFDD4C8E)),
                    axolotl("axolotl_gold", PetModels.axolotlSkin("axolotl_gold", 0xFFF5CF52, 0xFFE58A26)),
                    axolotl("axolotl_blue", PetModels.axolotlSkin("axolotl_blue", 0xFF6577E2, 0xFF3E4CC0)),
                    parrot("parrot_red_blue", PetModels.parrotSkin("parrot", 0xFFE5281F, 0xFF2D5AD4, 0xFFF2C12E, 0)),
                    parrot("parrot_blue", PetModels.parrotSkin("parrot_blue", 0xFF2E73E0, 0xFF1E3E9E, 0xFF30B8C8, 0xFFF2C230)),
                    axolotl("axolotl_wild", PetModels.axolotlSkin("axolotl_wild", 0xFF7A5A3C, 0xFF5A3E28)),
                    axolotl("axolotl_cyan", PetModels.axolotlSkin("axolotl_cyan", 0xFFDDF1F4, 0xFFE9A5C8)),
                    parrot("parrot_green", PetModels.parrotSkin("parrot_green", 0xFF5DBB2F, 0xFF2F6E1A, 0xFFF2C12E, 0)),
                    parrot("parrot_yellow_blue", PetModels.parrotSkin("parrot_yellow", 0xFF3FA9E8, 0xFF2D5AD4, 0xFFF2C12E, 0xFFF2C12E)),
                    parrot("parrot_grey", PetModels.parrotSkin("parrot_grey", 0xFFA8A8A8, 0xFF6E6E6E, 0xFFE8E8E8, 0)),
            };
        }
        for (int i = 1; i < Equip.PETS.length && i < kinds.length; i++) if (Equip.PETS[i].equals(id)) return kinds[i];
        return null;
    }

    private static Kind axolotl(String tex, ResourceLocation painted) {
        return new Kind(new PetModels.Axolotl(), "axolotl/" + tex, painted, 0.42f, 1.6f);
    }

    private static Kind parrot(String tex, ResourceLocation painted) {
        return new Kind(new PetModels.Parrot(), "parrot/" + tex, painted, 1f, 0.8f);
    }

    /** A pet that rides on the shoulder (axolotl, parrot) rather than floating. */
    public static boolean shoulder(String id) {
        Kind k = id == null || id.isEmpty() ? null : kind(id);
        return k != null && k.shoulder;
    }

    /**
     * Draws a pet. GL must be at the spot under the pet, in model units (1 = a block, y pointing DOWN like inside
     * the game's entity renderer), facing the pet's front. {@code age} in ticks (animations).
     */
    static void drawModel(String id, float age) {
        Kind k = kind(id);
        if (k == null) return;
        Minecraft mc = Minecraft.getMinecraft();
        GlStateManager.pushMatrix();
        GlStateManager.scale(k.scale, k.scale, k.scale);
        GlStateManager.translate(0f, -1.5078125f, 0f);   // the models stand 24 px below their origin
        GlStateManager.enableRescaleNormal();
        GlStateManager.disableCull();
        GlStateManager.enableAlpha();
        GlStateManager.color(1f, 1f, 1f, 1f);
        // the third value is an animation input: tail height for the dog, wing flap for the chick, time for the rest
        float anim = k.model instanceof ModelWolf ? 1.75f
                : k.model instanceof ModelChicken ? (MathHelper.sin(age * 0.9f) + 1f) * 0.7f : age;
        float look = MathHelper.sin(age * 0.035f) * 18f;   // looks around a little
        try {
            k.model.isChild = k.child;
            if (k.model instanceof ModelWolf) poseWolf((ModelWolf) k.model);
            if (k.layer instanceof ModelWolf) poseWolf((ModelWolf) k.layer);
            ResourceLocation real = k.mobTex == null ? null : MobTextures.get(k.mobTex);
            mc.getTextureManager().bindTexture(real != null ? real : k.tex);
            k.model.render(null, 0f, 0f, anim, look, 0f, 0.0625f);
            if (k.layer != null) {
                k.layer.isChild = k.child;
                mc.getTextureManager().bindTexture(k.layerTex);
                if (k.layerRgb != null) GlStateManager.color(k.layerRgb[0], k.layerRgb[1], k.layerRgb[2], 1f);
                if (k.layerBlend) {
                    GlStateManager.enableNormalize();
                    GlStateManager.enableBlend();
                    GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                }
                k.layer.render(null, 0f, 0f, anim, look, 0f, 0.0625f);
                if (k.layerBlend) {
                    GlStateManager.disableBlend();
                    GlStateManager.disableNormalize();
                }
            }
        } catch (Throwable ignored) {
        }
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.enableCull();
        GlStateManager.disableRescaleNormal();
        GlStateManager.popMatrix();
    }

    /**
     * The game lays the wolf's body flat and puts its legs in place only while drawing a real wolf (from the
     * entity): the same standing pose here, or the body stands upright like a column.
     */
    private static void poseWolf(ModelWolf m) {
        m.wolfBody.setRotationPoint(0f, 14f, 2f);
        m.wolfBody.rotateAngleX = (float) Math.PI / 2f;
        m.wolfBody.rotateAngleZ = 0f;
        m.wolfLeg1.setRotationPoint(-2.5f, 16f, 7f);
        m.wolfLeg2.setRotationPoint(0.5f, 16f, 7f);
        m.wolfLeg3.setRotationPoint(-2.5f, 16f, -4f);
        m.wolfLeg4.setRotationPoint(0.5f, 16f, -4f);
        m.wolfLeg1.rotateAngleX = m.wolfLeg2.rotateAngleX = m.wolfLeg3.rotateAngleX = m.wolfLeg4.rotateAngleX = 0f;
        m.wolfHeadMain.rotateAngleZ = 0f;
        try {
            net.minecraft.client.model.ModelRenderer mane = ((dev.fzkc.fzhud.mixin.AccessorModelWolf) m).fzhud$getMane();
            net.minecraft.client.model.ModelRenderer tail = ((dev.fzkc.fzhud.mixin.AccessorModelWolf) m).fzhud$getTail();
            mane.setRotationPoint(-1f, 14f, -3f);
            mane.rotateAngleX = (float) Math.PI / 2f;
            mane.rotateAngleZ = 0f;
            tail.setRotationPoint(-1f, 12f, 8f);
            tail.rotateAngleZ = 0f;
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ following

    /** Where one player's pet is (blocks), last tick and now, and which way it faces. */
    private static final class Sim {
        double x, y, z, px, py, pz;
        float yaw, pyaw;
        boolean init;
    }

    private static final WeakHashMap<EntityPlayer, Sim> SIMS = new WeakHashMap<EntityPlayer, Sim>();
    private static int ticks;

    public PetFx() {}

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            SIMS.clear();
            return;
        }
        if (mc.isGamePaused()) return;
        addLayers(mc);
        ticks++;
        List<EntityPlayer> all = mc.theWorld.playerEntities;
        for (int i = 0; i < all.size(); i++) {
            EntityPlayer p = all.get(i);
            Equip e = Cosmetics.of(p);
            if (e == null || e.pet.isEmpty() || shoulder(e.pet)) {
                SIMS.remove(p);
                continue;
            }
            Sim s = SIMS.get(p);
            if (s == null) SIMS.put(p, s = new Sim());
            follow(s, p);
        }
    }

    /** One tick: glide toward a spot just behind the right shoulder, turning the way you face. */
    private static void follow(Sim s, EntityPlayer p) {
        float yaw = p.renderYawOffset;
        double r = Math.toRadians(yaw);
        double rightX = -Math.cos(r), rightZ = -Math.sin(r), backX = Math.sin(r), backZ = -Math.cos(r);
        double tx = p.posX + rightX * 0.75 + backX * 0.4;
        double ty = p.posY + (p.isSneaking() ? 1.05 : 1.35);
        double tz = p.posZ + rightZ * 0.75 + backZ * 0.4;
        s.px = s.x;
        s.py = s.y;
        s.pz = s.z;
        s.pyaw = s.yaw;
        double far = (tx - s.x) * (tx - s.x) + (ty - s.y) * (ty - s.y) + (tz - s.z) * (tz - s.z);
        if (!s.init || far > 64) {   // first time, or teleported far away: just appear there
            s.x = s.px = tx;
            s.y = s.py = ty;
            s.z = s.pz = tz;
            s.yaw = s.pyaw = yaw;
            s.init = true;
            return;
        }
        // close behind even while sprinting (a slow follow left it trailing into the camera)
        s.x += (tx - s.x) * 0.38;
        s.y += (ty - s.y) * 0.25;
        s.z += (tz - s.z) * 0.38;
        s.yaw += MathHelper.wrapAngleTo180_float(yaw - s.yaw) * 0.3f;
    }

    @SubscribeEvent
    public void onRender(RenderWorldLastEvent event) {
        if (SIMS.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return;
        Entity v = mc.getRenderViewEntity();
        if (v == null) return;
        float pt = event.partialTicks;
        double cx = v.lastTickPosX + (v.posX - v.lastTickPosX) * pt;
        double cy = v.lastTickPosY + (v.posY - v.lastTickPosY) * pt;
        double cz = v.lastTickPosZ + (v.posZ - v.lastTickPosZ) * pt;
        float age = ticks + pt;
        boolean begun = false;
        List<EntityPlayer> all = mc.theWorld.playerEntities;
        for (int i = 0; i < all.size(); i++) {
            EntityPlayer p = all.get(i);
            Sim s = SIMS.get(p);
            if (s == null || !s.init) continue;
            Equip e = Cosmetics.of(p);
            if (e == null || e.pet.isEmpty()) continue;
            if (p.getDistanceSqToEntity(v) > 48 * 48) continue;
            // your own pet in first person: right next to the camera it only blocked the view
            if (p == v && mc.gameSettings.thirdPersonView == 0) continue;
            double x = s.px + (s.x - s.px) * pt, y = s.py + (s.y - s.py) * pt, z = s.pz + (s.z - s.pz) * pt;
            float bob = MathHelper.sin((age + p.getEntityId() * 7) * 0.12f) * 0.06f;
            if (!begun) {
                begun = true;
                RenderHelper.enableStandardItemLighting();
                mc.entityRenderer.enableLightmap();
                GlStateManager.enableDepth();
                GlStateManager.depthMask(true);
                GlStateManager.enableTexture2D();
                GlStateManager.disableBlend();
            }
            // the light where the pet floats, like any mob there
            int light = mc.theWorld.getCombinedLight(new BlockPos(x, y + 0.3, z), 0);
            OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, light % 65536, light / 65536);
            float yaw = s.pyaw + MathHelper.wrapAngleTo180_float(s.yaw - s.pyaw) * pt;
            GlStateManager.pushMatrix();
            GlStateManager.translate(x - cx, y - cy + bob, z - cz);
            GlStateManager.rotate(180f - yaw, 0f, 1f, 0f);
            GlStateManager.scale(-1f, -1f, 1f);
            drawModel(e.pet, age + p.getEntityId() * 13);
            GlStateManager.popMatrix();
        }
        if (begun) {
            RenderHelper.disableStandardItemLighting();
            mc.entityRenderer.disableLightmap();
            GlStateManager.color(1f, 1f, 1f, 1f);
        }
    }

    // ------------------------------------------------------------------ on the shoulder

    private static boolean layersAdded;

    /** Once: shoulder pets become a layer of the player (both skin models), like the cape. */
    private static void addLayers(Minecraft mc) {
        if (layersAdded) return;
        layersAdded = true;
        try {
            for (RenderPlayer r : mc.getRenderManager().getSkinMap().values()) r.addLayer(new Shoulder());
        } catch (Throwable ignored) {
        }
    }

    /**
     * Draws a shoulder pet on the left shoulder. GL must be in the player model's space (where the game draws the
     * cape: 1 = a block, y down, origin at the neck).
     */
    static void drawOnShoulder(String id, boolean sneaking, float age) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(0.4f, sneaking ? 0.2f : 0f, 0f);
        Kind k = kind(id);
        // the axolotl's long tail: moved forward so it lies across the shoulder instead of hanging off the back
        if (k != null && k.model instanceof PetModels.Axolotl) GlStateManager.translate(0f, 0f, -0.18f);
        drawModel(id, age);
        GlStateManager.popMatrix();
    }

    private static final class Shoulder implements LayerRenderer<AbstractClientPlayer> {
        @Override
        public void doRenderLayer(AbstractClientPlayer p, float limbSwing, float limbAmount, float pt, float age,
                                  float headYaw, float headPitch, float scale) {
            try {
                if (p.isInvisible()) return;
                Equip e = Cosmetics.of(p);
                if (e == null || !shoulder(e.pet)) return;
                drawOnShoulder(e.pet, p.isSneaking(), age + p.getEntityId() * 13);
                GlStateManager.enableRescaleNormal();   // the player renderer still needs it
            } catch (Throwable ignored) {
            }
        }

        @Override
        public boolean shouldCombineTextures() {
            return false;
        }
    }

    // ------------------------------------------------------------------ screens

    /**
     * A pet on a screen (Cosmetics page): standing at (x, footY), size = screen units per block, turned by yaw
     * degrees (0 = facing you). Depth is left for the caller to clear.
     */
    public static void drawGui(String id, float x, float footY, float size, float yaw, float age) {
        if (kind(id) == null) return;
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        GlStateManager.pushMatrix();
        GlStateManager.enableColorMaterial();
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.translate(x, footY, 120f);
        size *= kind(id).guiMul;
        GlStateManager.scale(-size, size, size);
        GlStateManager.rotate(135f, 0f, 1f, 0f);
        RenderHelper.enableStandardItemLighting();
        GlStateManager.rotate(-135f, 0f, 1f, 0f);
        GlStateManager.rotate(180f + yaw, 0f, 1f, 0f);
        GlStateManager.enableTexture2D();
        try {
            drawModel(id, age);
        } finally {
            GlStateManager.popMatrix();
            RenderHelper.disableStandardItemLighting();
            GlStateManager.setActiveTexture(OpenGlHelper.lightmapTexUnit);
            GlStateManager.disableTexture2D();
            GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
            GlStateManager.enableTexture2D();
            if (!depth) GlStateManager.disableDepth();
            GlStateManager.enableBlend();
            GlStateManager.color(1f, 1f, 1f, 1f);
        }
    }
}
