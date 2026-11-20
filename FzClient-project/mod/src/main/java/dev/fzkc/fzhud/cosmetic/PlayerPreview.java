package dev.fzkc.fzhud.cosmetic;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelBase;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * Your own player drawn as a model (skin + cape), without needing a world: works on the title screen too.
 * The skin comes from your account (SkinCache, asked again when Mojang did not answer); until then the default skin
 * is shown.
 */
public final class PlayerPreview {
    private static final ModelPlayer WIDE = new ModelPlayer(0f, false), SLIM = new ModelPlayer(0f, true);
    private static volatile ResourceLocation skin;
    /** The account's own Mojang cape, shown when no FzClient cape is worn. */
    private static volatile ResourceLocation ownCape;
    private static volatile boolean slim;
    private static final CapeCloth.Sim PREVIEW_CAPE = new CapeCloth.Sim();
    /** A shoulder pet to draw on the preview (set around a draw call by the Cosmetics page), or null. */
    public static String shoulderPet;

    private PlayerPreview() {}

    /** The session the skin below belongs to (another account logged in: its own skin). */
    private static net.minecraft.util.Session sessionOf;
    private static GameProfile sessionProfile;

    /** Your account's profile (Session.getProfile() builds a new one on every call: once per session). */
    private static GameProfile profile() {
        Minecraft mc = Minecraft.getMinecraft();
        net.minecraft.util.Session s = mc.getSession();
        if (s != sessionOf) {
            sessionOf = s;
            sessionProfile = null;
            try {
                sessionProfile = s == null ? null : s.getProfile();
            } catch (Throwable ignored) {
            }
        }
        return sessionProfile;
    }

    private static void loadSkin() {
        Minecraft mc = Minecraft.getMinecraft();
        GameProfile p = profile();
        SkinCache.Skin own = p == null || p.getId() == null ? null : SkinCache.mojang(p.getId(), p.getName());
        // the Mojang cape comes only from the profile (in a world getLocationCape() would answer with the FzClient
        // cape while one is worn)
        ownCape = own == null ? null : own.cape;
        if (mc.thePlayer != null) {
            skin = mc.thePlayer.getLocationSkin();
            slim = "slim".equals(mc.thePlayer.getSkinType());
        } else if (own != null) {
            skin = own.skin;
            slim = own.slim;
        } else {
            skin = null;
        }
    }

    /** Your own skin texture (the default one until the real one has come), for a face on the title screen. */
    public static ResourceLocation ownSkin() {
        loadSkin();
        ResourceLocation s = skin;
        if (s != null) return s;
        GameProfile p = profile();
        return p != null && p.getId() != null ? DefaultPlayerSkin.getDefaultSkin(p.getId()) : DefaultPlayerSkin.getDefaultSkinLegacy();
    }

    /**
     * Draws the player standing at (x, footY); size = screen units per block (the player is 2 blocks tall).
     * Turned by yaw degrees (0 = facing you).
     * cape = cape texture or null.
     */
    public static void draw(float x, float footY, float size, float yaw, ResourceLocation cape, float age) {
        Minecraft mc = Minecraft.getMinecraft();
        ResourceLocation s = ownSkin();
        drawSkin(s, slim, x, footY, size, yaw, cape != null ? cape : ownCape, age);
    }

    /** Same as {@link #draw}, with any skin (another player's). */
    public static void drawSkin(ResourceLocation s, boolean slim, float x, float footY, float size, float yaw, ResourceLocation cape, float age) {
        Minecraft mc = Minecraft.getMinecraft();
        ModelPlayer m = slim ? SLIM : WIDE;
        idle(m, age);

        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        GlStateManager.pushMatrix();
        GlStateManager.enableColorMaterial();
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        // what was drawn before (the in-game HUD: hotbar items, other mods' HUDs) left depth behind that hid part of
        // the model (half of it cut off, the blurred menu glass showing through): start the model on a clean depth
        GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
        // 1 model block = size screen units; model y already points down like the screen
        GlStateManager.translate(x, footY, 100f);
        GlStateManager.scale(-size, size, size);
        GlStateManager.rotate(135f, 0f, 1f, 0f);
        RenderHelper.enableStandardItemLighting();
        GlStateManager.rotate(-135f, 0f, 1f, 0f);
        GlStateManager.rotate(180f + yaw, 0f, 1f, 0f);   // yaw 0 = facing you
        GlStateManager.translate(0f, -1.5f, 0f);          // feet (24 px below the neck) on footY
        GlStateManager.enableRescaleNormal();
        GlStateManager.disableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.enableAlpha();
        GlStateManager.color(1f, 1f, 1f, 1f);
        float sc = 0.0625f;
        try {
            mc.getTextureManager().bindTexture(s);
            ModelRenderer[] parts = {m.bipedHead, m.bipedBody, m.bipedRightArm, m.bipedLeftArm, m.bipedRightLeg, m.bipedLeftLeg};
            for (ModelRenderer r : parts) r.render(sc);
            ModelRenderer[] wear = SkinLayers3D.wear(m);
            boolean voxels = dev.fzkc.fzhud.Modules.skinLayers.enabled && dev.fzkc.fzhud.Modules.skinLayersPreview.get();
            for (int i = 0; i < wear.length; i++) {
                ModelRenderer r = wear[i];
                if (voxels) {
                    GlStateManager.pushMatrix();
                    r.postRender(sc);
                    boolean ok = SkinLayers3D.draw(s, slim, i, sc);
                    GlStateManager.popMatrix();
                    if (ok) continue;
                }
                r.render(sc);
            }
            if (cape != null) {
                mc.getTextureManager().bindTexture(cape);
                GlStateManager.pushMatrix();
                GlStateManager.translate(0f, 0f, 0.125f);
                if (CapeCloth.on()) {
                    // the same soft cloth as in the game, swaying gently
                    CapeCloth.step(PREVIEW_CAPE, 8f + MathHelper.sin(age * 0.05f) * 4f, 0f, 0.15f);
                    CapeCloth.draw(PREVIEW_CAPE, 0f);
                } else {
                    GlStateManager.rotate(6f + MathHelper.sin(age * 0.05f) * 3f, 1f, 0f, 0f);
                    GlStateManager.rotate(180f, 0f, 1f, 0f);
                    m.renderCape(sc);
                }
                GlStateManager.popMatrix();
            }
            if (shoulderPet != null) PetFx.drawOnShoulder(shoulderPet, false, age);
        } catch (Throwable ignored) {
        } finally {
            GlStateManager.enableCull();
            GlStateManager.disableRescaleNormal();
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

    /** A calm standing pose with the arms swaying a little. */
    private static void idle(ModelPlayer m, float age) {
        ModelRenderer[] all = {m.bipedHead, m.bipedHeadwear, m.bipedBody, m.bipedBodyWear, m.bipedRightArm, m.bipedRightArmwear,
                m.bipedLeftArm, m.bipedLeftArmwear, m.bipedRightLeg, m.bipedRightLegwear, m.bipedLeftLeg, m.bipedLeftLegwear};
        for (ModelRenderer r : all) {
            r.rotateAngleX = 0f;
            r.rotateAngleY = 0f;
            r.rotateAngleZ = 0f;
        }
        float sway = MathHelper.sin(age * 0.067f) * 0.05f;
        m.bipedRightArm.rotateAngleZ = MathHelper.cos(age * 0.09f) * 0.05f + 0.05f;
        m.bipedLeftArm.rotateAngleZ = -(MathHelper.cos(age * 0.09f) * 0.05f + 0.05f);
        m.bipedRightArm.rotateAngleX = sway;
        m.bipedLeftArm.rotateAngleX = -sway;
        // the outer layer follows its part, position included: Minecraft creates the slim model's right sleeve 10
        // pixels behind the arm and only fixes it while a real player is drawn (the sleeve looked shifted back here)
        ModelBase.copyModelAngles(m.bipedHead, m.bipedHeadwear);
        ModelBase.copyModelAngles(m.bipedBody, m.bipedBodyWear);
        ModelBase.copyModelAngles(m.bipedRightArm, m.bipedRightArmwear);
        ModelBase.copyModelAngles(m.bipedLeftArm, m.bipedLeftArmwear);
        ModelBase.copyModelAngles(m.bipedRightLeg, m.bipedRightLegwear);
        ModelBase.copyModelAngles(m.bipedLeftLeg, m.bipedLeftLegwear);
    }
}
