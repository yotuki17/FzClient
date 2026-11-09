package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

/**
 * Entity hitboxes. Every box (and the eye / look lines) goes into one buffer and is drawn with a single call:
 * the game's own box drawing sends three draws per entity, which adds up in a full lobby.
 */
public final class Hitboxes {
    private Hitboxes() {}

    public static void render(float pt) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.theWorld == null || mc.thePlayer == null) return;
        RenderManager rm = mc.getRenderManager();
        if (rm == null) return;

        double vx = rm.viewerPosX, vy = rm.viewerPosY, vz = rm.viewerPosZ;
        boolean playersOnly = Modules.hitboxPlayersOnly.get();
        boolean margin = Modules.hitboxMargin.get();
        boolean lines = Modules.hitboxLines.get();
        boolean self = Modules.hitboxSelf.get() && mc.gameSettings.thirdPersonView != 0;
        float range = Modules.hitboxRange.get();
        double rangeSq = range * range;
        int col = Modules.hitboxColor.get();
        int r = (col >> 16) & 0xFF, g = (col >> 8) & 0xFF, b = col & 0xFF;
        int a = Math.max(0, Math.min(255, Math.round(Modules.hitboxAlpha.get() * 255f)));

        Tessellator tess = Tessellator.getInstance();
        WorldRenderer wr = tess.getWorldRenderer();
        boolean any = false;

        for (Entity e : mc.theWorld.loadedEntityList) {
            if (e == null || e.isDead) continue;
            if (e == mc.thePlayer && !self) continue;
            if (playersOnly && !(e instanceof EntityPlayer)) continue;
            if (e.getDistanceSqToEntity(mc.thePlayer) > rangeSq) continue;
            if (e.isInvisibleToPlayer(mc.thePlayer)) continue;
            AxisAlignedBB bb = e.getEntityBoundingBox();
            if (bb == null) continue;

            // where the entity is drawn this frame, relative to the camera
            double dx = e.lastTickPosX + (e.posX - e.lastTickPosX) * pt - e.posX - vx;
            double dy = e.lastTickPosY + (e.posY - e.lastTickPosY) * pt - e.posY - vy;
            double dz = e.lastTickPosZ + (e.posZ - e.lastTickPosZ) * pt - e.posZ - vz;
            double m = margin ? e.getCollisionBorderSize() : 0.0;
            double x0 = bb.minX - m + dx, y0 = bb.minY - m + dy, z0 = bb.minZ - m + dz;
            double x1 = bb.maxX + m + dx, y1 = bb.maxY + m + dy, z1 = bb.maxZ + m + dz;

            if (!any) {
                any = true;
                wr.begin(GL11.GL_LINES, DefaultVertexFormats.POSITION_COLOR);
            }
            loop(wr, x0, y0, z0, x1, z1, r, g, b, a);
            loop(wr, x0, y1, z0, x1, z1, r, g, b, a);
            line(wr, x0, y0, z0, x0, y1, z0, r, g, b, a);
            line(wr, x1, y0, z0, x1, y1, z0, r, g, b, a);
            line(wr, x1, y0, z1, x1, y1, z1, r, g, b, a);
            line(wr, x0, y0, z1, x0, y1, z1, r, g, b, a);

            if (lines) {
                double ex = e.posX + dx, ey = e.posY + e.getEyeHeight() + dy, ez = e.posZ + dz;
                loop(wr, x0, ey, z0, x1, z1, 255, 0, 0, 255);
                Vec3 look = e.getLook(pt);
                if (look != null) {
                    line(wr, ex, ey, ez, ex + look.xCoord * 2.0, ey + look.yCoord * 2.0, ez + look.zCoord * 2.0, 0, 0, 255, 255);
                }
            }
        }
        if (!any) return;

        GlStateManager.disableLighting();
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.depthMask(false);
        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GL11.glLineWidth(Modules.hitboxWidth.get());
        tess.draw();
        GL11.glLineWidth(1f);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.depthMask(true);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /** A flat rectangle at height y. */
    private static void loop(WorldRenderer wr, double x0, double y, double z0, double x1, double z1, int r, int g, int b, int a) {
        line(wr, x0, y, z0, x1, y, z0, r, g, b, a);
        line(wr, x1, y, z0, x1, y, z1, r, g, b, a);
        line(wr, x1, y, z1, x0, y, z1, r, g, b, a);
        line(wr, x0, y, z1, x0, y, z0, r, g, b, a);
    }

    private static void line(WorldRenderer wr, double ax, double ay, double az, double bx, double by, double bz, int r, int g, int b, int a) {
        wr.pos(ax, ay, az).color(r, g, b, a).endVertex();
        wr.pos(bx, by, bz).color(r, g, b, a).endVertex();
    }
}
