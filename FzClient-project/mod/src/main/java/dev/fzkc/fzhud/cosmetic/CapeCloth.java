package dev.fzkc.fzhud.cosmetic;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.MathHelper;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;
import java.util.WeakHashMap;

/**
 * Smooth capes: instead of one stiff board, the cape is 16 strips from the shoulders down. Each strip follows the
 * swing the game works out for the cape (walking, running, jumping, sneaking), the lower ones a little later than
 * the upper ones, and a soft wave runs down the cloth (stronger the faster you move). Works for every cape (Mojang,
 * OptiFine, FzClient) and uses the cape's own texture with the usual 64x32 layout (any resolution).
 * "マントを滑らかに揺らす" in Cosmetics turns it off (the game's own cape again).
 */
public final class CapeCloth {
    private CapeCloth() {}

    /** Strips from top to bottom (the cape is 16 pixels long). */
    static final int N = 16;

    /** One cape's motion. */
    public static final class Sim {
        /** Angle of each strip in degrees: 0 hangs straight down, more swings it out backwards. */
        final float[] a = new float[N];
        float side;
        long last;
        boolean init;
    }

    private static final WeakHashMap<AbstractClientPlayer, Sim> SIMS = new WeakHashMap<AbstractClientPlayer, Sim>();

    public static boolean on() {
        return Modules.cosCapeSmooth.get();
    }

    /** The cape layer of a player (called instead of the game's LayerCape). */
    public static void renderLayer(AbstractClientPlayer p, ResourceLocation tex, float pt) {
        // the swing the game itself works out for the cape (LayerCape), as the target of the cloth
        double dx = p.prevChasingPosX + (p.chasingPosX - p.prevChasingPosX) * pt - (p.prevPosX + (p.posX - p.prevPosX) * pt);
        double dy = p.prevChasingPosY + (p.chasingPosY - p.prevChasingPosY) * pt - (p.prevPosY + (p.posY - p.prevPosY) * pt);
        double dz = p.prevChasingPosZ + (p.chasingPosZ - p.prevChasingPosZ) * pt - (p.prevPosZ + (p.posZ - p.prevPosZ) * pt);
        float yaw = p.prevRenderYawOffset + (p.renderYawOffset - p.prevRenderYawOffset) * pt;
        double s = MathHelper.sin(yaw * (float) Math.PI / 180f), c = -MathHelper.cos(yaw * (float) Math.PI / 180f);
        float up = MathHelper.clamp_float((float) dy * 10f, -6f, 32f);
        float forward = (float) (dx * s + dz * c) * 100f;
        float sideways = (float) (dx * c - dz * s) * 100f;
        if (forward < 0f) forward = 0f;
        float bob = p.prevCameraYaw + (p.cameraYaw - p.prevCameraYaw) * pt;
        up += MathHelper.sin((p.prevDistanceWalkedModified + (p.distanceWalkedModified - p.prevDistanceWalkedModified) * pt) * 6f) * 32f * bob;
        if (p.isSneaking()) up += 25f;
        float base = MathHelper.clamp_float(6f + forward / 2f + up, -20f, 150f);

        Sim sim = SIMS.get(p);
        if (sim == null) SIMS.put(p, sim = new Sim());
        step(sim, base, sideways / 2f, MathHelper.clamp_float(forward / 45f, 0f, 1f));

        GlStateManager.color(1f, 1f, 1f, 1f);
        Minecraft.getMinecraft().getTextureManager().bindTexture(tex);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        GlStateManager.disableCull();
        GlStateManager.enableDepth();
        GlStateManager.depthFunc(GL11.GL_LEQUAL);
        GlStateManager.depthMask(true);
        GlStateManager.pushMatrix();
        GlStateManager.translate(0f, 0f, 0.125f);
        draw(sim, p.isSneaking() ? 2f : 0f);
        GlStateManager.popMatrix();
        if (cull) GlStateManager.enableCull();
    }

    /**
     * Moves the cloth toward {@code base} (degrees), {@code side} = sideways lean, {@code speed} 0..1 (how strong
     * the wave is). Time based, so it looks the same at any FPS.
     */
    public static void step(Sim s, float base, float side, float speed) {
        long now = System.nanoTime();
        float dt = s.last == 0 ? 0f : Math.min(0.1f, (now - s.last) / 1e9f);
        s.last = now;
        if (!s.init) {
            Arrays.fill(s.a, base);
            s.side = side;
            s.init = true;
        }
        float time = (now / 1_000_000L % 3_600_000L) / 1000f;
        float amp = 1.6f + 9f * speed;
        float freq = 4.5f + 6f * speed;
        // blown far out (running, falling): the lower part sags back down a little, like real cloth
        float droop = Math.max(0f, base - 25f);
        for (int i = 0; i < N; i++) {
            float t = i / (float) (N - 1);
            float wave = MathHelper.sin(time * freq - i * 0.42f) * amp * t;
            float target = base - droop * 0.35f * t * t + wave;
            float k = 24f - 16f * t;   // the bottom follows later than the top: the cloth bends
            s.a[i] += (target - s.a[i]) * (1f - (float) Math.exp(-k * dt));
        }
        float flutter = MathHelper.sin(time * 7.3f) * 2.5f * speed;
        s.side += (side + flutter - s.side) * (1f - (float) Math.exp(-14f * dt));
    }

    private static final float[] JY = new float[N + 1], JZ = new float[N + 1], NY = new float[N + 1], NZ = new float[N + 1];

    /**
     * Draws the cape. GL must be at the cape's hinge (the middle of the back at shoulder height: where LayerCape
     * puts it), in model units (1 = a block, y pointing down). {@code drop} = pixels the cape starts below the
     * hinge (2 while sneaking).
     */
    public static void draw(Sim s, float drop) {
        GlStateManager.pushMatrix();
        GlStateManager.rotate(s.side, 0f, 0f, 1f);
        GlStateManager.rotate(-s.side, 0f, 1f, 0f);
        GlStateManager.scale(0.0625f, 0.0625f, 0.0625f);

        // the joints between the strips (pixels), and the outward direction at each joint
        float r0 = s.a[0] * (float) Math.PI / 180f;
        float y = drop * MathHelper.cos(r0), z = drop * MathHelper.sin(r0);
        float len = 16f / N;
        for (int i = 0; i <= N; i++) {
            JY[i] = y;
            JZ[i] = z;
            if (i < N) {
                float r = s.a[i] * (float) Math.PI / 180f;
                y += len * MathHelper.cos(r);
                z += len * MathHelper.sin(r);
            }
            float ang = (i == 0 ? s.a[0] : i == N ? s.a[N - 1] : (s.a[i - 1] + s.a[i]) * 0.5f) * (float) Math.PI / 180f;
            NY[i] = -MathHelper.sin(ang);
            NZ[i] = MathHelper.cos(ang);
        }

        WorldRenderer w = Tessellator.getInstance().getWorldRenderer();
        w.begin(GL11.GL_QUADS, DefaultVertexFormats.OLDMODEL_POSITION_TEX_NORMAL);
        final float U = 1f / 64f, V = 1f / 32f;
        for (int i = 0; i < N; i++) {
            int a = i, b = i + 1;
            float v0 = (1f + 16f * a / N) * V, v1 = (1f + 16f * b / N) * V;
            // outside (what others see behind you): u 1..11, mirrored like the game's box after its half turn
            v(w, 5, a, 1, U, v0, NY[a], NZ[a]);
            v(w, -5, a, 1, 11 * U, v0, NY[a], NZ[a]);
            v(w, -5, b, 1, 11 * U, v1, NY[b], NZ[b]);
            v(w, 5, b, 1, U, v1, NY[b], NZ[b]);
            // inside (against your back): u 12..22
            v(w, 5, b, 0, 22 * U, v1, -NY[b], -NZ[b]);
            v(w, -5, b, 0, 12 * U, v1, -NY[b], -NZ[b]);
            v(w, -5, a, 0, 12 * U, v0, -NY[a], -NZ[a]);
            v(w, 5, a, 0, 22 * U, v0, -NY[a], -NZ[a]);
            // edges
            vx(w, -5, a, 0, 12 * U, v0, -1f);
            vx(w, -5, a, 1, 11 * U, v0, -1f);
            vx(w, -5, b, 1, 11 * U, v1, -1f);
            vx(w, -5, b, 0, 12 * U, v1, -1f);
            vx(w, 5, b, 0, 0, v1, 1f);
            vx(w, 5, b, 1, U, v1, 1f);
            vx(w, 5, a, 1, U, v0, 1f);
            vx(w, 5, a, 0, 0, v0, 1f);
        }
        // top and bottom ends
        float ty = -NZ[0], tz = NY[0];   // pointing up along the cloth
        v(w, 5, 0, 0, U, 0, ty, tz);
        v(w, -5, 0, 0, 11 * U, 0, ty, tz);
        v(w, -5, 0, 1, 11 * U, V, ty, tz);
        v(w, 5, 0, 1, U, V, ty, tz);
        v(w, -5, N, 0, 21 * U, 0, -ty, -tz);
        v(w, 5, N, 0, 11 * U, 0, -ty, -tz);
        v(w, 5, N, 1, 11 * U, V, -ty, -tz);
        v(w, -5, N, 1, 21 * U, V, -ty, -tz);
        Tessellator.getInstance().draw();
        GlStateManager.popMatrix();
    }

    /** A point on the inside (out = 0) or outside (out = 1) surface at joint j. */
    private static void v(WorldRenderer w, float x, int j, int out, float u, float v, float ny, float nz) {
        w.pos(x, JY[j] + NY[j] * out, JZ[j] + NZ[j] * out).tex(u, v).normal(0f, ny, nz).endVertex();
    }

    private static void vx(WorldRenderer w, float x, int j, int out, float u, float v, float nx) {
        w.pos(x, JY[j] + NY[j] * out, JZ[j] + NZ[j] * out).tex(u, v).normal(nx, 0f, 0f).endVertex();
    }
}
