package dev.fzkc.fzhud.cosmetic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ActiveRenderInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Auras: FzClient's own little particles (hearts, flames, sparkles...) drawn as crisp pixel sprites after the world.
 * They do not go through Minecraft's particle system, so shader packs, the particle setting and other mods
 * cannot hide or darken them: they always look the same, at full brightness.
 * The same emitter also feeds the 2D preview on the Cosmetics screen.
 */
public final class AuraFx {
    static final ResourceLocation ATLAS = new ResourceLocation("fzclient", "textures/cosmetics/aura.png");
    static final int HEART = 0, DOT = 1, SPARK = 2, NOTE = 3, SNOW = 4, FLAME = 5, PETAL = 6, STAR = 7, CELLS = 8;
    private static final int MAX = 900;

    /** One sprite. Positions are in blocks (world space, or relative to the feet in the preview). */
    public static final class P {
        double x, y, z, px, py, pz, vx, vy, vz;
        int age, life, sprite, color;
        float size, sway;
        boolean add;

        P(double x, double y, double z, double vx, double vy, double vz, int life, float size, int color, int sprite, boolean add) {
            this.x = this.px = x;
            this.y = this.py = y;
            this.z = this.pz = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.life = life;
            this.size = size;
            this.color = color;
            this.sprite = sprite;
            this.add = add;
        }
    }

    private static final List<P> world = new ArrayList<P>();
    private static int tick;

    public AuraFx() {}

    // ------------------------------------------------------------------ emitting

    private static int hue(float h) {
        return java.awt.Color.HSBtoRGB(h, 0.85f, 1f) & 0xFFFFFF;
    }

    private static final int[] FIRE = {0xFFE066, 0xFFA63C, 0xFF6A2A};

    /** One tick of an aura around the feet at (x, y, z). Crisp pixel sprites, like Minecraft's own particles. */
    public static void emit(List<P> out, String aura, double x, double y, double z, int t, Random r) {
        if ("hearts".equals(aura)) {
            if (t % 7 == 0) out.add(new P(x + (r.nextDouble() - .5) * .7, y + 1.75 + r.nextDouble() * .25, z + (r.nextDouble() - .5) * .7,
                    0, .022, 0, 26, .17f, 0xFF4F7E, HEART, false));
        } else if ("flame".equals(aura)) {
            double a = t * .35;
            out.add(new P(x + Math.cos(a) * .45, y + .05, z + Math.sin(a) * .45, 0, .03 + r.nextDouble() * .01, 0,
                    14, .13f, FIRE[r.nextInt(FIRE.length)], FLAME, false));
            if (t % 3 == 0) out.add(new P(x + (r.nextDouble() - .5) * .9, y + .1, z + (r.nextDouble() - .5) * .9, 0, .05, 0,
                    18, .05f, 0xFF7A2A, DOT, false));
        } else if ("magic".equals(aura)) {
            if (t % 2 == 0) {
                double a = r.nextDouble() * Math.PI * 2, d = .25 + r.nextDouble() * .3;
                int[] cols = {0xB07CFF, 0x5CE1E6, 0xFFFFFF};
                out.add(new P(x + Math.cos(a) * d, y + .2 + r.nextDouble() * 1.7, z + Math.sin(a) * d,
                        0, .006, 0, 20, .11f, cols[r.nextInt(cols.length)], SPARK, false));
            }
        } else if ("notes".equals(aura)) {
            if (t % 8 == 0) {
                P p = new P(x + (r.nextDouble() - .5) * .8, y + 2.0, z + (r.nextDouble() - .5) * .8, 0, .02, 0,
                        34, .16f, hue(r.nextInt(12) / 12f), NOTE, false);
                p.sway = .01f;
                out.add(p);
            }
        } else if ("snow".equals(aura)) {
            if (t % 3 == 0) {
                P p = new P(x + (r.nextDouble() - .5) * 1.5, y + 2.3, z + (r.nextDouble() - .5) * 1.5, 0, -.022, 0,
                        50, .09f, 0xEEF6FF, SNOW, false);
                p.sway = .005f;
                out.add(p);
            }
        } else if ("stars".equals(aura)) {
            // little stars circling the head
            if (t % 2 == 0) {
                double a = t * .18 + (t % 4 == 0 ? Math.PI : 0);
                int[] cols = {0xFFE36B, 0xFFF6C2, 0xFFC93C};
                out.add(new P(x + Math.cos(a) * .5, y + 1.95 + Math.sin(t * .11) * .08, z + Math.sin(a) * .5, 0, .002, 0,
                        16, .1f, cols[r.nextInt(cols.length)], STAR, false));
            }
        } else if ("petals".equals(aura)) {
            if (t % 4 == 0) {
                int[] cols = {0xFFB7D5, 0xFF8FBF, 0xFFE3EE};
                P p = new P(x + (r.nextDouble() - .5) * 1.4, y + 2.2, z + (r.nextDouble() - .5) * 1.4, 0, -.018, 0,
                        60, .1f, cols[r.nextInt(cols.length)], PETAL, false);
                p.sway = .012f;
                out.add(p);
            }
        } else if ("bubbles".equals(aura)) {
            if (t % 3 == 0) {
                P p = new P(x + (r.nextDouble() - .5) * .9, y + .1, z + (r.nextDouble() - .5) * .9, 0, .028 + r.nextDouble() * .01, 0,
                        40, .05f + r.nextFloat() * .04f, r.nextBoolean() ? 0x9FE6FF : 0xD8F6FF, DOT, false);
                p.sway = .008f;
                out.add(p);
            }
        } else if ("souls".equals(aura)) {
            // blue soul fire spiralling up
            double a = t * .4;
            double h = (t * .05) % 1.9;
            int[] cols = {0x63E6FF, 0x2FB8FF, 0xB8F4FF};
            out.add(new P(x + Math.cos(a) * .45, y + h, z + Math.sin(a) * .45, 0, .01, 0,
                    16, .1f, cols[r.nextInt(cols.length)], FLAME, false));
            out.add(new P(x + Math.cos(a + Math.PI) * .45, y + h, z + Math.sin(a + Math.PI) * .45, 0, .01, 0,
                    16, .1f, cols[r.nextInt(cols.length)], FLAME, false));
        } else if ("rainbow".equals(aura)) {
            double a = t * .3;
            double h = (t * .045) % 1.9;
            out.add(new P(x + Math.cos(a) * .55, y + h, z + Math.sin(a) * .55, 0, 0, 0,
                    20, .06f, hue((t * .025f) % 1f), DOT, false));
        }
    }

    /** Moves every sprite one tick and drops the old ones. */
    public static void update(List<P> list) {
        for (Iterator<P> it = list.iterator(); it.hasNext(); ) {
            P p = it.next();
            p.px = p.x;
            p.py = p.y;
            p.pz = p.z;
            p.x += p.vx + (p.sway != 0 ? Math.sin((p.age + p.color) * .2) * p.sway : 0);
            p.y += p.vy;
            p.z += p.vz;
            if (++p.age >= p.life) it.remove();
        }
        while (list.size() > MAX) list.remove(0);
    }

    // ------------------------------------------------------------------ world

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null) {
            world.clear();
            return;
        }
        if (mc.isGamePaused()) return;
        tick++;
        update(world);
        Entity view = mc.getRenderViewEntity();
        if (view == null) return;
        java.util.List<EntityPlayer> all = mc.theWorld.playerEntities;
        for (int i = 0; i < all.size(); i++) {
            EntityPlayer p = all.get(i);
            Equip e = Cosmetics.of(p);
            if (e == null || e.aura.isEmpty()) continue;
            if (p == view && mc.gameSettings.thirdPersonView == 0) continue;   // never in your own view
            if (p.getDistanceSqToEntity(view) > 48 * 48) continue;
            emit(world, e.aura, p.posX, p.posY, p.posZ, tick + p.getEntityId(), p.getRNG());
        }
    }

    @SubscribeEvent
    public void onRender(RenderWorldLastEvent event) {
        if (world.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        Entity v = mc.getRenderViewEntity();
        if (v == null) return;
        float pt = event.partialTicks;
        double cx = v.lastTickPosX + (v.posX - v.lastTickPosX) * pt;
        double cy = v.lastTickPosY + (v.posY - v.lastTickPosY) * pt;
        double cz = v.lastTickPosZ + (v.posZ - v.lastTickPosZ) * pt;
        float rx = ActiveRenderInfo.getRotationX(), rz = ActiveRenderInfo.getRotationZ();
        float ryz = ActiveRenderInfo.getRotationYZ(), rxy = ActiveRenderInfo.getRotationXY(), rxz = ActiveRenderInfo.getRotationXZ();

        begin(mc);
        for (int pass = 0; pass < 2; pass++) {
            boolean add = pass == 1;
            if (add) GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            else GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            WorldRenderer w = Tessellator.getInstance().getWorldRenderer();
            w.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
            for (P p : world) {
                if (p.add != add) continue;
                double x = p.px + (p.x - p.px) * pt - cx, y = p.py + (p.y - p.py) * pt - cy, z = p.pz + (p.z - p.pz) * pt - cz;
                float s = p.size * scaleOf(p, pt);
                int a = alphaOf(p, pt);
                float u0 = p.sprite / (float) CELLS, u1 = (p.sprite + 1) / (float) CELLS;
                int r = (p.color >> 16) & 255, g = (p.color >> 8) & 255, b = p.color & 255;
                w.pos(x - rx * s - rxy * s, y - rxz * s, z - rz * s - ryz * s).tex(u1, 1).color(r, g, b, a).endVertex();
                w.pos(x - rx * s + rxy * s, y + rxz * s, z - rz * s + ryz * s).tex(u1, 0).color(r, g, b, a).endVertex();
                w.pos(x + rx * s + rxy * s, y + rxz * s, z + rz * s + ryz * s).tex(u0, 0).color(r, g, b, a).endVertex();
                w.pos(x + rx * s - rxy * s, y - rxz * s, z + rz * s - ryz * s).tex(u0, 1).color(r, g, b, a).endVertex();
            }
            Tessellator.getInstance().draw();
        }
        end();
    }

    // ------------------------------------------------------------------ 2D (Cosmetics screen preview)

    /**
     * Draws sprites whose positions are relative to the feet, around a point on the screen.
     * px = screen pixels per block.
     */
    public static void draw2D(List<P> list, float footX, float footY, float px, float pt) {
        if (list.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        mc.getTextureManager().bindTexture(ATLAS);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        for (int pass = 0; pass < 2; pass++) {
            boolean add = pass == 1;
            if (add) GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            else GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
            WorldRenderer w = Tessellator.getInstance().getWorldRenderer();
            w.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX_COLOR);
            for (P p : list) {
                if (p.add != add) continue;
                double x = footX + (p.px + (p.x - p.px) * pt) * px;
                double y = footY - (p.py + (p.y - p.py) * pt) * px;
                float s = p.size * scaleOf(p, pt) * px;
                int a = alphaOf(p, pt);
                float u0 = p.sprite / (float) CELLS, u1 = (p.sprite + 1) / (float) CELLS;
                int r = (p.color >> 16) & 255, g = (p.color >> 8) & 255, b = p.color & 255;
                w.pos(x - s, y + s, 0).tex(u0, 1).color(r, g, b, a).endVertex();
                w.pos(x + s, y + s, 0).tex(u1, 1).color(r, g, b, a).endVertex();
                w.pos(x + s, y - s, 0).tex(u1, 0).color(r, g, b, a).endVertex();
                w.pos(x - s, y - s, 0).tex(u0, 0).color(r, g, b, a).endVertex();
            }
            Tessellator.getInstance().draw();
        }
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.enableAlpha();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    // ------------------------------------------------------------------ shared

    /** Fully visible, fading out only at the very end. */
    private static int alphaOf(P p, float pt) {
        float t = (p.age + pt) / p.life;
        float out = t > .75f ? 1f - (t - .75f) / .25f : 1f;
        return Math.max(0, Math.min(255, (int) (255 * out)));
    }

    /** Flames get a little smaller as they rise; the rest keep their size. */
    private static float scaleOf(P p, float pt) {
        if (p.sprite != FLAME) return 1f;   // (soul fire uses the flame sprite too)
        float t = (p.age + pt) / p.life;
        return 1f - t * .5f;
    }

    private static void begin(Minecraft mc) {
        GlStateManager.pushMatrix();
        mc.entityRenderer.enableLightmap();
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240f, 240f);   // full brightness
        mc.getTextureManager().bindTexture(ATLAS);
        GlStateManager.enableTexture2D();
        GlStateManager.disableLighting();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.enableDepth();
        GlStateManager.depthMask(false);   // sprites never hide each other
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private static void end() {
        GlStateManager.depthMask(true);
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableCull();
        Minecraft.getMinecraft().entityRenderer.disableLightmap();
        GlStateManager.popMatrix();
    }
}
