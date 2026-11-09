package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.util.Fmt;

import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityTNTPrimed;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;

/** Shows the remaining fuse above primed TNT. */
public final class TntTimer {
    private TntTimer() {}

    /** Primed TNT, collected once per tick so the render pass doesn't scan every entity each frame. */
    private static final List<EntityTNTPrimed> TNT = new ArrayList<>();

    public static void tick() {
        TNT.clear();
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || !Modules.tnt.enabled) return;
        List<Entity> list = mc.theWorld.loadedEntityList;
        for (Entity e : list) {
            if (e instanceof EntityTNTPrimed) TNT.add((EntityTNTPrimed) e);
        }
    }

    public static void render(float partialTicks) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || TNT.isEmpty()) return;
        RenderManager rm = mc.getRenderManager();
        FontRenderer fr = mc.fontRendererObj;

        int decimals = (int) Modules.tntDecimals.get();
        boolean byTime = Modules.tntColorByTime.get();
        int fixedColor = Modules.tntColor.get();
        boolean shadow = Modules.tntShadow.get();
        double range = Modules.tntRange.get();
        double rangeSq = range * range;
        int bg = (Math.round(Modules.tntBgOpacity.get() * 2.55f) << 24) | 0x101A1E;
        float sc = 0.03f * Modules.tntScale.get();

        // ---- GL state, set once for all timers and put back
        boolean wasDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean wasLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        GlStateManager.disableLighting();
        GlStateManager.depthMask(false);
        // hidden behind blocks like the TNT itself, never through walls (raw GL too, in case something switched
        // it off behind GlStateManager's back)
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(515); // GL_LEQUAL
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

        for (EntityTNTPrimed tnt : TNT) {
            if (tnt.isDead) continue;

            double x = tnt.lastTickPosX + (tnt.posX - tnt.lastTickPosX) * partialTicks - rm.viewerPosX;
            double y = tnt.lastTickPosY + (tnt.posY - tnt.lastTickPosY) * partialTicks - rm.viewerPosY + 1.4;
            double z = tnt.lastTickPosZ + (tnt.posZ - tnt.lastTickPosZ) * partialTicks - rm.viewerPosZ;
            if (x * x + (y - 1.4) * (y - 1.4) + z * z > rangeSq) continue;

            float seconds = Math.max(0f, (tnt.fuse - partialTicks) / 20f);
            String text = Fmt.fixed(seconds, decimals);
            int color = byTime ? R.lerpColor(0xFFFF4444, 0xFF55FF55, Math.min(1f, seconds / 4f)) : fixedColor;

            GlStateManager.pushMatrix();
            GlStateManager.translate(x, y, z);
            GlStateManager.rotate(-rm.playerViewY, 0f, 1f, 0f);
            GlStateManager.rotate(rm.playerViewX * (mc.gameSettings.thirdPersonView == 2 ? -1 : 1), 1f, 0f, 0f);
            GlStateManager.scale(-sc, -sc, sc);
            int w = fr.getStringWidth(text) / 2;
            if ((bg >>> 24) > 0) R.rounded(-w - 3, -2, w * 2 + 6, 12, 3f, bg);
            fr.drawString(text, -w, 0, color, shadow);
            GlStateManager.popMatrix();
        }

        // ---- put the state back the way it was
        GlStateManager.depthMask(true);
        if (wasDepth) {
            GlStateManager.enableDepth();
        } else {
            GlStateManager.disableDepth();
            GL11.glDisable(GL11.GL_DEPTH_TEST);
        }
        if (wasLighting) GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }
}
