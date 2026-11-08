package dev.fzkc.fzhud.cosmetic;

import dev.fzkc.fzhud.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ModelPlayer;
import net.minecraft.client.model.ModelRenderer;
import net.minecraft.client.renderer.GLAllocation;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.nio.IntBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 3D skin layers: the outer layer of a skin (hat, jacket, sleeves, pants) drawn as little cubes, one per pixel,
 * sitting on top of the body instead of a flat shell. Built once per skin into display lists, so drawing it costs
 * about the same as the flat layer.
 * <ul>
 *   <li>The pixels are read back from the skin texture the game already has (any skin, downloaded or default).</li>
 *   <li>Semi-transparent pixels can stay flat (setting), because see-through cubes look muddy.</li>
 *   <li>The cubes on the edge of a face grow by one pixel sideways so the corners of the layer are closed.</li>
 * </ul>
 */
public final class SkinLayers3D {
    private SkinLayers3D() {}

    /** Part order, the same as {@link #wear(ModelPlayer)}. */
    public static final int HEAD = 0, BODY = 1, RIGHT_ARM = 2, LEFT_ARM = 3, RIGHT_LEG = 4, LEFT_LEG = 5;

    /** The six outer-layer parts of a player model, in part order. */
    public static ModelRenderer[] wear(ModelPlayer m) {
        return new ModelRenderer[]{m.bipedHeadwear, m.bipedBodyWear, m.bipedRightArmwear, m.bipedLeftArmwear, m.bipedRightLegwear, m.bipedLeftLegwear};
    }

    private static final class Mesh {
        final int lists;   // 6 display lists, one per part
        final boolean legacy;   // 64x32 skin: only the hat exists

        Mesh(int lists, boolean legacy) {
            this.lists = lists;
            this.legacy = legacy;
        }
    }

    private static final int MAX = 48;
    private static final Map<String, Mesh> CACHE = new LinkedHashMap<String, Mesh>(16, .75f, true);
    /** The settings the cached meshes were built with (flat semi-transparent pixels, head and body size). */
    private static String lastShape = "";
    /** Skins that could not be read (not uploaded yet): tried again after a moment, not every frame. */
    private static final Map<String, Long> FAILED = new java.util.HashMap<String, Long>();

    /** True when 3D layers should be drawn for this distance (squared, in blocks) from the camera. */
    public static boolean active(boolean self, double distSq) {
        if (!Modules.skinLayers.enabled) return false;
        if (!self && !Modules.skinLayersOthers.get()) return false;
        double r = Modules.skinLayersRange.get();
        return distSq <= r * r;
    }

    /**
     * Draws one part's 3D layer. The part's transform (ModelRenderer.postRender) must already be applied and the
     * skin texture bound. Returns false when it could not (the flat layer should be drawn instead).
     */
    public static boolean draw(ResourceLocation skin, boolean slim, int part, float scale) {
        Mesh m = mesh(skin, slim);
        if (m == null) return false;
        if (m.legacy && part != HEAD) return true;   // a 64x32 skin has no other outer layer
        GlStateManager.pushMatrix();
        GlStateManager.scale(scale, scale, scale);
        GL11.glCallList(m.lists + part);
        GlStateManager.popMatrix();
        return true;
    }

    private static Mesh mesh(ResourceLocation skin, boolean slim) {
        if (skin == null) return null;
        boolean flat = Modules.skinLayersFlatSemi.get();
        float th = Modules.skinLayersHead.get(), tb = Modules.skinLayersBody.get();
        String shape = flat + "/" + th + "/" + tb;
        if (!shape.equals(lastShape)) {   // a setting changed: build everything again
            clear();
            lastShape = shape;
        }
        String key = skin.toString() + (slim ? "#slim" : "#wide");
        Mesh m = CACHE.get(key);
        if (m != null) return m;
        long now = System.currentTimeMillis();
        Long failedAt = FAILED.get(key);
        if (failedAt != null && now - failedAt < 2000L) return null;
        try {
            m = build(skin, slim, flat, th, tb);
        } catch (Throwable t) {
            m = null;
        }
        if (m == null) {
            if (FAILED.size() > 256) FAILED.clear();
            FAILED.put(key, now);
            return null;
        }
        FAILED.remove(key);
        CACHE.put(key, m);
        if (CACHE.size() > MAX) {
            Iterator<Map.Entry<String, Mesh>> it = CACHE.entrySet().iterator();
            Mesh old = it.next().getValue();
            it.remove();
            GLAllocation.deleteDisplayLists(old.lists);
        }
        return m;
    }

    public static void clear() {
        for (Mesh m : CACHE.values()) GLAllocation.deleteDisplayLists(m.lists);
        CACHE.clear();
        FAILED.clear();
    }

    // ------------------------------------------------------------------ building

    /** Box of each part in model pixels (x, y, z, w, h, d) and where its outer layer is on the skin (u, v). */
    private static int[] box(int part, boolean slim) {
        switch (part) {
            case HEAD: return new int[]{-4, -8, -4, 8, 8, 8, 32, 0};
            case BODY: return new int[]{-4, 0, -2, 8, 12, 4, 16, 32};
            case RIGHT_ARM: return slim ? new int[]{-2, -2, -2, 3, 12, 4, 40, 32} : new int[]{-3, -2, -2, 4, 12, 4, 40, 32};
            case LEFT_ARM: return slim ? new int[]{-1, -2, -2, 3, 12, 4, 48, 48} : new int[]{-1, -2, -2, 4, 12, 4, 48, 48};
            case RIGHT_LEG: return new int[]{-2, 0, -2, 4, 12, 4, 0, 32};
            default: return new int[]{-2, 0, -2, 4, 12, 4, 0, 48};
        }
    }

    private static Mesh build(ResourceLocation skin, boolean slim, boolean flatSemi, float th, float tb) {
        Minecraft mc = Minecraft.getMinecraft();
        mc.getTextureManager().bindTexture(skin);
        int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
        if (w < 64 || h < 32 || w > 1024 || h > 1024) return null;
        IntBuffer buf = BufferUtils.createIntBuffer(w * h);
        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, buf);
        int[] px = new int[w * h];
        buf.get(px);
        int k = w / 64;   // HD skins: one cube per 64x64 pixel, sampled from its top-left texel
        boolean legacy = h * 2 <= w;
        Skin s = new Skin(px, w, h, k);

        int lists = GLAllocation.generateDisplayLists(6);
        for (int part = 0; part < 6; part++) {
            GL11.glNewList(lists + part, GL11.GL_COMPILE);
            if (!legacy || part == HEAD) {
                WorldRenderer wr = Tessellator.getInstance().getWorldRenderer();
                wr.begin(GL11.GL_QUADS, DefaultVertexFormats.OLDMODEL_POSITION_TEX_NORMAL);
                part(wr, s, box(part, slim), flatSemi, part == HEAD ? th : tb);
                Tessellator.getInstance().draw();
            }
            GL11.glEndList();
        }
        return new Mesh(lists, legacy);
    }

    private static final class Skin {
        final int[] px;
        final int w, h, k;

        Skin(int[] px, int w, int h, int k) {
            this.px = px;
            this.w = w;
            this.h = h;
            this.k = k;
        }

        /** ARGB of skin pixel (x, y) in 64-wide coordinates. */
        int at(int x, int y) {
            int ix = x * k, iy = y * k;
            if (ix < 0 || iy < 0 || ix >= w || iy >= h) return 0;
            return px[iy * w + ix];
        }

        float u(int x) { return (x + .5f) / 64f; }

        float v(int y) { return (y + .5f) / (h / (float) k); }
    }

    /**
     * The six faces of one part. Each face is a grid of skin pixels; an opaque pixel becomes a cube 1 pixel thick
     * on the outside of the body, a semi-transparent one (when kept flat) a flat square half way out.
     */
    private static void part(WorldRenderer wr, Skin s, int[] b, boolean flatSemi, float t) {
        float flatOut = t / 2f;   // t = how far the cubes stand out, in pixels (setting)
        int x1 = b[0], y1 = b[1], z1 = b[2], W = b[3], H = b[4], D = b[5], u = b[6], v = b[7];
        int x2 = x1 + W, y2 = y1 + H, z2 = z1 + D;
        // face: 0 +X, 1 -X, 2 -Y (top), 3 +Y (bottom), 4 -Z (front), 5 +Z (back)
        int[][] rect = {
                {u + D + W, v + D, D, H}, {u, v + D, D, H}, {u + D, v, W, D}, {u + D + W, v, W, D}, {u + D, v + D, W, H}, {u + D + W + D, v + D, W, H}};
        for (int f = 0; f < 6; f++) {
            int ou = rect[f][0], ov = rect[f][1], cols = rect[f][2], rows = rect[f][3];
            for (int j = 0; j < rows; j++) {
                for (int i = 0; i < cols; i++) {
                    int c = s.at(ou + i, ov + j);
                    int a = c >>> 24;
                    if (a == 0) continue;
                    float tu = s.u(ou + i), tv = s.v(ov + j);
                    // the cell on the body surface: [ax, bx] x [ay, by] x [az, bz] and the outward direction
                    float ax, bx, ay, by, az, bz;
                    int nx = 0, ny = 0, nz = 0;
                    boolean extX = false, extZ = false;
                    switch (f) {
                        case 0: ax = x2; bx = x2; ay = y1 + j; by = ay + 1; az = z1 + i; bz = az + 1; nx = 1; break;
                        case 1: ax = x1; bx = x1; ay = y1 + j; by = ay + 1; az = z2 - 1 - i; bz = az + 1; nx = -1; break;
                        case 2: ax = x1 + i; bx = ax + 1; ay = y1; by = y1; az = z2 - 1 - j; bz = az + 1; ny = -1; extX = true; extZ = true; break;
                        case 3: ax = x1 + i; bx = ax + 1; ay = y2; by = y2; az = z2 - 1 - j; bz = az + 1; ny = 1; extX = true; extZ = true; break;
                        case 4: ax = x1 + i; bx = ax + 1; ay = y1 + j; by = ay + 1; az = z1; bz = z1; nz = -1; extX = true; break;
                        default: ax = x2 - 1 - i; bx = ax + 1; ay = y1 + j; by = ay + 1; az = z2; bz = z2; nz = 1; extX = true; break;
                    }
                    if (flatSemi && a < 255) {
                        flat(wr, ax + nx * flatOut, bx + nx * flatOut, ay + ny * flatOut, by + ny * flatOut,
                                az + nz * flatOut, bz + nz * flatOut, nx, ny, nz, tu, tv);
                        continue;
                    }
                    // push the cell out into a cube t pixels thick
                    if (nx != 0) { if (nx > 0) bx += t; else ax -= t; }
                    if (ny != 0) { if (ny > 0) by += t; else ay -= t; }
                    if (nz != 0) { if (nz > 0) bz += t; else az -= t; }
                    // close the corners: cubes on the edge of the face reach over the edge of the body
                    if (extX) {
                        if (ax <= x1) ax = x1 - t;
                        if (bx >= x2) bx = x2 + t;
                    }
                    if (extZ) {
                        if (az <= z1) az = z1 - t;
                        if (bz >= z2) bz = z2 + t;
                    }
                    cube(wr, ax, bx, ay, by, az, bz, -nx, -ny, -nz, tu, tv);
                }
            }
        }
    }

    /** A cube without the face that lies on the body (direction sx, sy, sz). All faces show the one pixel. */
    private static void cube(WorldRenderer wr, float ax, float bx, float ay, float by, float az, float bz,
                             int sx, int sy, int sz, float u, float v) {
        if (sx != 1) quad(wr, u, v, 1, 0, 0, bx, ay, az, bx, by, az, bx, by, bz, bx, ay, bz);
        if (sx != -1) quad(wr, u, v, -1, 0, 0, ax, ay, bz, ax, by, bz, ax, by, az, ax, ay, az);
        if (sy != 1) quad(wr, u, v, 0, 1, 0, ax, by, az, ax, by, bz, bx, by, bz, bx, by, az);
        if (sy != -1) quad(wr, u, v, 0, -1, 0, ax, ay, bz, ax, ay, az, bx, ay, az, bx, ay, bz);
        if (sz != 1) quad(wr, u, v, 0, 0, 1, bx, ay, bz, bx, by, bz, ax, by, bz, ax, ay, bz);
        if (sz != -1) quad(wr, u, v, 0, 0, -1, ax, ay, az, ax, by, az, bx, by, az, bx, ay, az);
    }

    /** A flat square facing outward (players are drawn without face culling, so one side is enough). */
    private static void flat(WorldRenderer wr, float ax, float bx, float ay, float by, float az, float bz,
                             int nx, int ny, int nz, float u, float v) {
        if (nx != 0) quad(wr, u, v, nx, 0, 0, ax, ay, az, ax, by, az, ax, by, bz, ax, ay, bz);
        else if (ny != 0) quad(wr, u, v, 0, ny, 0, ax, ay, az, ax, ay, bz, bx, ay, bz, bx, ay, az);
        else quad(wr, u, v, 0, 0, nz, ax, ay, az, ax, by, az, bx, by, az, bx, ay, az);
    }

    private static void quad(WorldRenderer wr, float u, float v, float nx, float ny, float nz,
                             float x0, float y0, float z0, float x1, float y1, float z1,
                             float x2, float y2, float z2, float x3, float y3, float z3) {
        wr.pos(x0, y0, z0).tex(u, v).normal(nx, ny, nz).endVertex();
        wr.pos(x1, y1, z1).tex(u, v).normal(nx, ny, nz).endVertex();
        wr.pos(x2, y2, z2).tex(u, v).normal(nx, ny, nz).endVertex();
        wr.pos(x3, y3, z3).tex(u, v).normal(nx, ny, nz).endVertex();
    }
}
