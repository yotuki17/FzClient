package dev.fzkc.fzhud.cosmetic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;

/**
 * Halos: a glowing ring floating above the head. Drawn after the world at full brightness, like the auras, so
 * shaders and the light level do not change how it looks. Never in your own first person view.
 */
public final class HaloFx {
    private static final int SEGMENTS = 28;
    private static final float R_IN = .2f, R_OUT = .29f;

    public HaloFx() {}

    /** The ring color of a halo at a point of the ring (a = 0..1 around it) and time t (seconds). */
    static int color(String halo, float a, float t) {
        if ("rainbow".equals(halo)) return java.awt.Color.HSBtoRGB((a + t * .25f) % 1f, .75f, 1f) & 0xFFFFFF;
        if ("dark".equals(halo)) return a * 4 % 1 < .5f ? 0x9B4DFF : 0x5A1FBF;
        if ("ice".equals(halo)) return 0xA8ECFF;
        return 0xFFD54A;   // gold
    }

    @SubscribeEvent
    public void onRender(RenderWorldLastEvent event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) return;
        Entity v = mc.getRenderViewEntity();
        if (v == null) return;
        float pt = event.partialTicks;
        double cx = v.lastTickPosX + (v.posX - v.lastTickPosX) * pt;
        double cy = v.lastTickPosY + (v.posY - v.lastTickPosY) * pt;
        double cz = v.lastTickPosZ + (v.posZ - v.lastTickPosZ) * pt;
        float t = (mc.theWorld.getTotalWorldTime() + pt) / 20f;
        boolean begun = false;
        for (EntityPlayer p : new ArrayList<EntityPlayer>(mc.theWorld.playerEntities)) {
            Equip e = Cosmetics.of(p);
            if (e == null || e.halo.isEmpty()) continue;
            if (p == v && mc.gameSettings.thirdPersonView == 0) continue;
            if (p.getDistanceSqToEntity(v) > 48 * 48) continue;
            if (!begun) {
                begin(mc);
                begun = true;
            }
            double x = p.lastTickPosX + (p.posX - p.lastTickPosX) * pt - cx;
            double y = p.lastTickPosY + (p.posY - p.lastTickPosY) * pt - cy + (p.isSneaking() ? 1.82 : 2.12)
                    + Math.sin(t * 2 + p.getEntityId()) * .025;
            double z = p.lastTickPosZ + (p.posZ - p.lastTickPosZ) * pt - cz;
            ring(e.halo, x, y, z, t);
        }
        if (begun) end(mc);
    }

    /** The ring flat in the world: a soft glow (added light), then the solid band. */
    private static void ring(String halo, double x, double y, double z, float t) {
        WorldRenderer w = Tessellator.getInstance().getWorldRenderer();
        for (int pass = 0; pass < 2; pass++) {
            boolean glow = pass == 0;
            if (glow) GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            else GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            float in = glow ? R_IN - .05f : R_IN, out = glow ? R_OUT + .05f : R_OUT;
            int alpha = glow ? 70 : 235;
            w.begin(GL11.GL_QUAD_STRIP, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i <= SEGMENTS; i++) {
                float a = i / (float) SEGMENTS;
                double ang = a * Math.PI * 2 + t * .6;
                double c = Math.cos(ang), s = Math.sin(ang);
                int col = color(halo, a, t);
                int r = (col >> 16) & 255, g = (col >> 8) & 255, b = col & 255;
                w.pos(x + c * in, y, z + s * in).color(r, g, b, alpha).endVertex();
                w.pos(x + c * out, y, z + s * out).color(r, g, b, alpha).endVertex();
            }
            Tessellator.getInstance().draw();
        }
    }

    /**
     * The halo on the Cosmetics screen preview: the ring seen slightly from above, as an ellipse.
     * px = screen pixels per block, footX/footY = the feet.
     */
    public static void draw2D(String halo, float footX, float footY, float px) {
        if (halo == null || halo.isEmpty()) return;
        float t = (System.currentTimeMillis() % 1000000L) / 1000f;
        float cy = footY - (2.12f + (float) Math.sin(t * 2) * .025f) * px;
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        WorldRenderer w = Tessellator.getInstance().getWorldRenderer();
        for (int pass = 0; pass < 2; pass++) {
            boolean glow = pass == 0;
            if (glow) GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            else GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            float in = (glow ? R_IN - .05f : R_IN) * px, out = (glow ? R_OUT + .05f : R_OUT) * px;
            int alpha = glow ? 70 : 235;
            w.begin(GL11.GL_QUAD_STRIP, DefaultVertexFormats.POSITION_COLOR);
            for (int i = 0; i <= SEGMENTS; i++) {
                float a = i / (float) SEGMENTS;
                double ang = a * Math.PI * 2 + t * .6;
                double c = Math.cos(ang), s = Math.sin(ang) * .32;
                int col = color(halo, a, t);
                int r = (col >> 16) & 255, g = (col >> 8) & 255, b = col & 255;
                w.pos(footX + c * in, cy + s * in, 0).color(r, g, b, alpha).endVertex();
                w.pos(footX + c * out, cy + s * out, 0).color(r, g, b, alpha).endVertex();
            }
            Tessellator.getInstance().draw();
        }
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.enableAlpha();
        GlStateManager.enableTexture2D();
        GlStateManager.enableCull();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private static void begin(Minecraft mc) {
        GlStateManager.pushMatrix();
        mc.entityRenderer.disableLightmap();
        GlStateManager.disableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.enableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240f, 240f);
    }

    private static void end(Minecraft mc) {
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.depthMask(true);
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.popMatrix();
    }
}
