package dev.fzkc.fzhud.feature;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * Motion blur by frame accumulation: every frame the previous (already blurred) picture is laid over the new one
 * with some transparency, then the result is kept for the next frame.
 *
 *  - Without shaders it runs right after the world has been drawn and BEFORE the hand is drawn, so the hand,
 *    the HUD and the menus stay sharp (also before the Hitbox / TNT Timer drawing).
 *  - With OptiFine shaders the picture at that point is NOT the final image (it is still the raw g-buffer: unlit,
 *    HDR, several render targets). Blending there is what made the screen flicker bright / dark. So with shaders
 *    the blur is done later, when the finished picture is on the screen, just before the HUD is drawn
 *    (the HUD and menus stay sharp, the held item is blurred a little together with the world).
 *  - The blend is based on time, not on frames: the same strength looks the same at 60 and at 240 FPS.
 *  - Nothing is blended across a jump (new world, window resize, menu open, long pause), so no ghost image appears.
 *  - Strength (10-90 %) is mapped to how much of the previous picture is left after 1/60 s (see keepPerSixtieth).
 */
public final class MotionBlur {
    private MotionBlur() {}

    private static boolean registered;
    /** The previous picture (a plain OpenGL texture that is refilled every frame). */
    private static int texture = -1;
    private static int texW, texH;
    private static boolean hasPrevious;
    private static World lastWorld;
    private static long lastNanos;

    /** Overall weakness of the effect. 1.0 = the old behaviour. Lower = weaker. */
    private static final float MAX_KEEP = 0.85f;

    // OptiFine is optional, so it is asked by reflection: Config.isShaders()
    private static boolean shadersChecked;
    private static Method isShadersMethod;

    /** Client tick: registers the render hook once. */
    public static void tick() {
        if (registered) return;
        registered = true;
        MinecraftForge.EVENT_BUS.register(new Hook());
    }

    public static final class Hook {
        /** No shaders: first in line, so everything drawn after us in this step (hitboxes, TNT timers) stays sharp. */
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onWorldLast(RenderWorldLastEvent event) {
            if (shadersOn()) return;
            apply();
        }

        /** Shaders: the finished picture is on the screen here, the HUD has not been drawn yet. */
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onOverlay(RenderGameOverlayEvent.Pre event) {
            if (event.type != RenderGameOverlayEvent.ElementType.ALL) return;
            if (!shadersOn()) return;
            apply();
        }
    }

    private static boolean shadersOn() {
        if (!shadersChecked) {
            shadersChecked = true;
            try {
                isShadersMethod = Class.forName("Config").getMethod("isShaders");
            } catch (Throwable t) {
                isShadersMethod = null; // no OptiFine
            }
        }
        if (isShadersMethod == null) return false;
        try {
            return (Boolean) isShadersMethod.invoke(null);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Strength 10..90 (%) -> share of the old picture that is kept after 1/60 s. Gentler than before. */
    private static float keepPerSixtieth() {
        float s = Math.max(0.1f, Math.min(0.9f, Modules.blurStrength.get() / 100f));
        float a = MAX_KEEP * (float) Math.pow(s, 1.5);   // 10 -> 0.03, 50 -> 0.30, 70 -> 0.50, 90 -> 0.73
        return Math.max(0.02f, Math.min(0.9f, a));
    }

    private static void apply() {
        Minecraft mc = Minecraft.getMinecraft();
        // blur only the game view: not in menus, not before a world exists
        if (!Modules.motionBlur.enabled || mc.theWorld == null || mc.currentScreen != null) {
            hasPrevious = false;
            return;
        }
        Framebuffer fb = mc.getFramebuffer();
        boolean useFb = OpenGlHelper.isFramebufferEnabled() && fb != null;
        int w = useFb ? fb.framebufferWidth : mc.displayWidth;
        int h = useFb ? fb.framebufferHeight : mc.displayHeight;
        if (w <= 0 || h <= 0) return;

        if (mc.theWorld != lastWorld) { // new world / dimension: the old picture doesn't belong to it
            lastWorld = mc.theWorld;
            hasPrevious = false;
        }
        if (texture == -1) texture = GL11.glGenTextures();
        if (w != texW || h != texH) { // first use or the window changed size
            allocate(w, h);
            hasPrevious = false;
        }

        long now = System.nanoTime();
        float rawDt = lastNanos == 0 ? 1f / 60f : (now - lastNanos) / 1.0e9f;
        lastNanos = now;
        if (rawDt > 0.25f) hasPrevious = false; // long pause (F1, menu, lag): the old picture is stale
        float dt = Math.min(0.1f, rawDt);

        boolean wasDepth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean wasBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean wasAlpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        boolean wasLighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean wasFog = GL11.glIsEnabled(GL11.GL_FOG);
        boolean wasCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);

        if (hasPrevious) {
            // share of the old picture that is kept this frame; the exponent makes it independent of the FPS
            float keep = (float) Math.pow(keepPerSixtieth(), dt * 60f);

            GlStateManager.disableDepth();
            GlStateManager.depthMask(false);
            GlStateManager.disableLighting();
            GlStateManager.disableAlpha();
            GlStateManager.disableCull();
            GlStateManager.disableFog();
            GlStateManager.enableTexture2D();
            GlStateManager.enableBlend();
            // SRC_ALPHA, ONE_MINUS_SRC_ALPHA for colour; leave the alpha channel of the screen untouched
            GlStateManager.tryBlendFuncSeparate(770, 771, 0, 1);
            GlStateManager.bindTexture(texture);
            GlStateManager.color(1f, 1f, 1f, keep);

            // switch to a flat 0..1 screen for the full-screen picture
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glOrtho(0.0, 1.0, 0.0, 1.0, -1.0, 1.0);
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
            GL11.glLoadIdentity();
            GL11.glBegin(GL11.GL_QUADS);
            GL11.glTexCoord2f(0f, 0f); GL11.glVertex2f(0f, 0f);
            GL11.glTexCoord2f(1f, 0f); GL11.glVertex2f(1f, 0f);
            GL11.glTexCoord2f(1f, 1f); GL11.glVertex2f(1f, 1f);
            GL11.glTexCoord2f(0f, 1f); GL11.glVertex2f(0f, 1f);
            GL11.glEnd();
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
        }

        // keep what is on screen now (the blended picture) for the next frame
        GlStateManager.bindTexture(texture);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
        hasPrevious = true;

        // put the state back the way the rest of the drawing expects it
        GlStateManager.depthMask(true);
        if (wasDepth) GlStateManager.enableDepth();
        if (wasAlpha) GlStateManager.enableAlpha();
        if (wasLighting) GlStateManager.enableLighting();
        if (wasFog) GlStateManager.enableFog();
        if (wasCull) GlStateManager.enableCull();
        if (!wasBlend) GlStateManager.disableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0); // back to the game's normal blend function
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private static void allocate(int w, int h) {
        GlStateManager.bindTexture(texture);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, w, h, 0, GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        texW = w;
        texH = h;
    }
}
