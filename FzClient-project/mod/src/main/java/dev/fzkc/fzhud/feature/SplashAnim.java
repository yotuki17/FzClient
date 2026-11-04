package dev.fzkc.fzhud.feature;

import net.minecraftforge.fml.common.ProgressManager;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Iterator;

/**
 * The animated FzClient loading screen (drawn from Forge's splash thread) and the numbers the title screen
 * needs to continue it seamlessly.
 * <p>
 * The logo is one 512x512 "canvas" fitted to the shorter window side; each part is a separate cropped
 * texture placed at its rectangle on that canvas:
 *  bird drops in and settles -> glow fades up and breathes -> "FzClient" slides up -> subtitle -> progress line.
 */
public final class SplashAnim {
    private SplashAnim() {}

    public static final int BIRD = 0, GLOW = 1, TEXT = 2, SUB = 3;
    public static final String[] NAMES = {"splash_bird", "splash_glow", "splash_text", "splash_sub"};
    /** x0, y0, x1, y1 on the 512 canvas (from the image generator). */
    public static final float[][] RECT = {
            {157, 126, 367, 246},
            {117, 78, 406, 292},
            {117, 294, 404, 377},
            {165, 428, 349, 450},
    };
    /** Progress line on the canvas. */
    public static final float BAR_X = 176, BAR_Y = 470, BAR_W = 160, BAR_H = 2.5f;

    // ------------------------------------------------------------ easing (shared with the title screen)

    public static float clamp(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
    public static float seg(float t, float from, float len) { return clamp((t - from) / len); }
    public static float outCubic(float p) { p = 1 - p; return 1 - p * p * p; }
    public static float inOutCubic(float p) { return p < 0.5f ? 4 * p * p * p : 1 - (float) Math.pow(-2 * p + 2, 3) / 2; }
    public static float outBack(float p) {
        float c1 = 1.70158f, c3 = c1 + 1;
        float q = p - 1;
        return 1 + c3 * q * q * q + c1 * q * q;
    }

    // ------------------------------------------------------------ splash thread

    /** True while the animated logo replaces Forge's static one. */
    public static volatile boolean active;
    private static int[] tex;
    private static boolean failed;
    private static long start;
    private static float progress;
    /** Time the splash has been on screen (seconds); the title screen skips the parts already played. */
    public static float elapsed;

    /** Draws the whole logo; called from the splash thread with Forge's projection (1 unit = 1 px, centre 320,240). */
    public static boolean draw(int dispW, int dispH) {
        if (failed) return false;
        if (tex == null && !load()) return false;
        if (start == 0) start = System.nanoTime();
        float t = (System.nanoTime() - start) / 1e9f;
        elapsed = t;

        float side = Math.min(dispW, dispH);
        float k = side / 512f, ox = 320 - side / 2f, oy = 240 - side / 2f;

        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glEnable(GL11.GL_TEXTURE_2D);

        // bird: flies in from the left edge of the window along a gentle rising arc, leaving a short trail,
        // levels out and settles (tiny overshoot); then the glow fades up and breathes
        float left = -ox / k;                                   // window's left edge on the canvas
        float startDx = left - RECT[BIRD][2] - 30f;            // fully off-screen
        float pb = seg(t, 0.05f, 1.0f);
        for (int g = 4; g >= 1; g--) {                          // trail: the bird a few moments ago
            float pp = seg(t - g * 0.035f, 0.05f, 1.0f);
            if (pp <= 0f || pp >= 1f) continue;
            float ga = (1f - pp) * (0.22f - g * 0.04f);
            birdAt(ox, oy, k, pp, startDx, ga);
        }
        float pg = seg(t, 0.85f, 0.6f);
        float glow = outCubic(pg) * (0.55f + 0.2f * (float) Math.sin((t - 0.85f) * 2.2f));
        quad(GLOW, ox, oy, k, 1f + 0.08f * (1 - outCubic(pg)), 0, 0, 0, glow);
        birdAt(ox, oy, k, pb, startDx, pb > 0 ? 1f : 0f);
        // title slides up
        float pt = seg(t, 0.8f, 0.6f);
        quad(TEXT, ox, oy, k, 1f, 0, (1 - outCubic(pt)) * 16f, 0, outCubic(pt));
        // subtitle
        float ps = seg(t, 1.05f, 0.5f);
        quad(SUB, ox, oy, k, 1f, 0, (1 - outCubic(ps)) * 8f, 0, outCubic(ps));

        // progress line (real progress of Forge's first bar, smoothed)
        float target = barProgress();
        if (target > progress) progress += (target - progress) * 0.08f;
        drawBar(ox, oy, k, outCubic(seg(t, 1.3f, 0.5f)), progress, t);

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glColor4f(1, 1, 1, 1);
        return true;
    }

    private static float barProgress() {
        try {
            Iterator<ProgressManager.ProgressBar> it = ProgressManager.barIterator();
            if (it.hasNext()) {
                ProgressManager.ProgressBar b = it.next();
                if (b.getSteps() > 0) return Math.min(1f, (b.getStep() + 0.5f) / b.getSteps());
            }
        } catch (Throwable ignored) {
        }
        return progress;
    }

    /** The bird at flight progress p (0 = off-screen left, 1 = in place). */
    private static void birdAt(float ox, float oy, float k, float p, float startDx, float alpha) {
        float e = outCubic(p);
        float dx = startDx * (1 - e);
        float dy = 46f * (1 - e) * (1 - e) - 10f * (float) Math.sin(Math.PI * e);   // rises from lower left
        float angle = -16f * (1 - e) + 4f * (float) Math.sin(Math.PI * Math.min(1f, e * 1.15f)); // nose up, levels out
        float scale = 0.8f + 0.2f * outBack(p);
        quad(BIRD, ox, oy, k, scale, dx, dy, angle, alpha);
    }

    /** One part, scaled and rotated (degrees) around its own centre and moved by dx, dy canvas units. */
    private static void quad(int i, float ox, float oy, float k, float scale, float dx, float dy, float angle, float alpha) {
        if (alpha <= 0.003f) return;
        float[] r = RECT[i];
        float cx = ox + ((r[0] + r[2]) / 2f + dx) * k, cy = oy + ((r[1] + r[3]) / 2f + dy) * k;
        float hw = (r[2] - r[0]) / 2f * scale * k, hh = (r[3] - r[1]) / 2f * scale * k;
        float c = (float) Math.cos(Math.toRadians(angle)), s = (float) Math.sin(Math.toRadians(angle));
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex[i]);
        GL11.glColor4f(1, 1, 1, alpha);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glTexCoord2f(0, 0); vertex(cx, cy, -hw, -hh, c, s);
        GL11.glTexCoord2f(0, 1); vertex(cx, cy, -hw, hh, c, s);
        GL11.glTexCoord2f(1, 1); vertex(cx, cy, hw, hh, c, s);
        GL11.glTexCoord2f(1, 0); vertex(cx, cy, hw, -hh, c, s);
        GL11.glEnd();
    }

    private static void vertex(float cx, float cy, float x, float y, float c, float s) {
        GL11.glVertex2f(cx + x * c - y * s, cy + x * s + y * c);
    }

    private static void drawBar(float ox, float oy, float k, float alpha, float p, float t) {
        if (alpha <= 0.003f) return;
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        float x = ox + BAR_X * k, y = oy + BAR_Y * k, w = BAR_W * k, h = Math.max(1f, BAR_H * k);
        GL11.glColor4f(1, 1, 1, 0.10f * alpha);
        rect(x, y, x + w, y + h);
        float fw = w * p;
        GL11.glShadeModel(GL11.GL_SMOOTH);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glColor4f(0.36f, 0.88f, 0.90f, alpha); GL11.glVertex2f(x, y); GL11.glVertex2f(x, y + h);
        GL11.glColor4f(0.91f, 0.24f, 0.81f, alpha); GL11.glVertex2f(x + fw, y + h); GL11.glVertex2f(x + fw, y);
        GL11.glEnd();
        GL11.glShadeModel(GL11.GL_FLAT);
        // a small light running along the filled part so it never looks frozen
        float s = (t * 0.9f) % 1f;
        float sx = x + fw * s, sw = Math.min(fw, 18f * k);
        GL11.glColor4f(1, 1, 1, 0.45f * alpha * (float) Math.sin(s * Math.PI));
        rect(Math.max(x, sx - sw / 2f), y, Math.min(x + fw, sx + sw / 2f), y + h);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private static void rect(float x0, float y0, float x1, float y1) {
        if (x1 <= x0) return;
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x0, y0); GL11.glVertex2f(x0, y1); GL11.glVertex2f(x1, y1); GL11.glVertex2f(x1, y0);
        GL11.glEnd();
    }

    private static boolean load() {
        try {
            int[] ids = new int[NAMES.length];
            for (int i = 0; i < NAMES.length; i++) ids[i] = upload("/assets/fzclient/textures/" + NAMES[i] + ".png");
            tex = ids;
            return true;
        } catch (Throwable e) {
            failed = true; // fall back to the static logo
            System.err.println("[FzClient] animated loading screen disabled: " + e);
            return false;
        }
    }

    private static int upload(String path) throws Exception {
        InputStream in = SplashAnim.class.getResourceAsStream(path);
        if (in == null) throw new IllegalStateException(path);
        BufferedImage img;
        try {
            img = ImageIO.read(in);
        } finally {
            in.close();
        }
        int w = img.getWidth(), h = img.getHeight();
        int[] px = img.getRGB(0, 0, w, h, null, 0, w);
        ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
        for (int p : px) buf.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
        buf.flip();
        int id = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_CLAMP);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        return id;
    }

    /** Frees the splash textures (called once the title screen has taken over). */
    public static void release() {
        if (tex == null) return;
        try {
            for (int id : tex) GL11.glDeleteTextures(id);
        } catch (Throwable ignored) {
        }
        tex = null;
        failed = true;
    }
}
