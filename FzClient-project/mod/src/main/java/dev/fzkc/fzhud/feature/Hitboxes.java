package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.Vec3;
import org.lwjgl.opengl.GL11;

public final class Hitboxes {
    private Hitboxes() {}

    public static void render(float pt) {
        Minecraft mc = Minecraft.getMinecraft();

        if (mc == null || mc.theWorld == null || mc.thePlayer == null) {
            return;
        }

        RenderManager rm = mc.getRenderManager();

        if (rm == null) {
            return;
        }

        double vx = rm.viewerPosX;
        double vy = rm.viewerPosY;
        double vz = rm.viewerPosZ;

        boolean playersOnly = Modules.hitboxPlayersOnly.get();
        boolean margin = Modules.hitboxMargin.get();
        boolean lines = Modules.hitboxLines.get();
        boolean self = Modules.hitboxSelf.get() &&
                mc.gameSettings.thirdPersonView != 0;

        float range = Modules.hitboxRange.get();
        double rangeSq = range * range;

        int col = Modules.hitboxColor.get();
        int r = (col >> 16) & 0xFF;
        int g = (col >> 8) & 0xFF;
        int b = col & 0xFF;

        int a = Math.max(
                0,
                Math.min(
                        255,
                        Math.round(Modules.hitboxAlpha.get() * 255f)
                )
        );

        boolean wasDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean wasLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);

        GlStateManager.disableLighting();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.depthMask(false);

        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

        GL11.glLineWidth(Modules.hitboxWidth.get());

        for (Entity e : mc.theWorld.loadedEntityList) {

            if (e == null) {
                continue;
            }

            if (e.isDead) {
                continue;
            }

            if (e == mc.thePlayer && !self) {
                continue;
            }

            if (e.isInvisibleToPlayer(mc.thePlayer)) {
                continue;
            }

            if (playersOnly && !(e instanceof EntityPlayer)) {
                continue;
            }

            // NPE修正
            if (e.getDistanceSqToEntity(mc.thePlayer) > rangeSq) {
                continue;
            }

            double dx =
                    e.lastTickPosX +
                            (e.posX - e.lastTickPosX) * pt -
                            e.posX;

            double dy =
                    e.lastTickPosY +
                            (e.posY - e.lastTickPosY) * pt -
                            e.posY;

            double dz =
                    e.lastTickPosZ +
                            (e.posZ - e.lastTickPosZ) * pt -
                            e.posZ;

            AxisAlignedBB bb = e.getEntityBoundingBox();

            if (bb == null) {
                continue;
            }

            float m = margin ? e.getCollisionBorderSize() : 0f;

            AxisAlignedBB box = bb
                    .expand(m, m, m)
                    .offset(dx - vx, dy - vy, dz - vz);

            RenderGlobal.drawOutlinedBoundingBox(
                    box,
                    r, g, b, a
            );

            if (lines) {

                double ex = e.posX + dx - vx;
                double ey = e.posY + e.getEyeHeight() + dy - vy;
                double ez = e.posZ + dz - vz;

                RenderGlobal.drawOutlinedBoundingBox(
                        new AxisAlignedBB(
                                box.minX,
                                ey,
                                box.minZ,
                                box.maxX,
                                ey,
                                box.maxZ
                        ),
                        255,
                        0,
                        0,
                        255
                );

                Vec3 look = e.getLook(pt);

                if (look != null) {
                    GL11.glColor4f(0f, 0f, 1f, 1f);

                    GL11.glBegin(GL11.GL_LINES);
                    GL11.glVertex3d(ex, ey, ez);
                    GL11.glVertex3d(
                            ex + look.xCoord * 2.0,
                            ey + look.yCoord * 2.0,
                            ez + look.zCoord * 2.0
                    );
                    GL11.glEnd();
                }
            }
        }

        GL11.glLineWidth(1f);

        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GlStateManager.depthMask(true);

        if (wasLighting) {
            GlStateManager.enableLighting();
        }

        if (!wasDepth) {
            GlStateManager.disableDepth();
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }

        GlStateManager.resetColor();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }
}
