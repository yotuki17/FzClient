package dev.fzkc.fzhud.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

/** Small immediate-mode drawing helpers. All coordinates are GUI-scaled pixels. */
public final class R {
    private R() {}

    // Theme
    public static final int ACCENT = 0xFF5CE1E6;
    public static final int TEXT = 0xFFE6F7F8;
    public static final int MUTED = 0xFF9DB4B8;
    public static final int PANEL = 0x99101A1E;
    public static final int BORDER = 0xB07FD8DC;
    public static final int DARK = 0xFF0B1416;

    /** Special colour: rgb part of any colour equal to this means "rainbow". */
    public static final int CHROMA_RGB = 0xFE01FE;
    public static final int CHROMA = 0xFF000000 | CHROMA_RGB;
    /** Seconds for one full trip around the colour wheel. */
    public static float chromaSeconds = 3f;

    public static boolean isChroma(int argb) {
        return (argb & 0xFFFFFF) == CHROMA_RGB;
    }

    /** Current rainbow colour; offset shifts the hue (e.g. by screen x for a wave). */
    public static int chroma(float offset, int alpha) {
        float t = (System.currentTimeMillis() % (long) (chromaSeconds * 1000f)) / (chromaSeconds * 1000f);
        float hue = ((t - offset) % 1f + 1f) % 1f;
        int rgb = java.awt.Color.HSBtoRGB(hue, 0.65f, 1f) & 0xFFFFFF;
        return (alpha << 24) | rgb;
    }

    /** Resolves the chroma marker to a concrete colour (for shapes and colour blending). */
    public static int solid(int argb) {
        return isChroma(argb) ? chroma(0f, (argb >>> 24) & 0xFF) : argb;
    }

    public static FontRenderer font() {
        return Minecraft.getMinecraft().fontRendererObj;
    }

    public static int alpha(int argb, float mul) {
        int a = (argb >>> 24) & 0xFF;
        a = Math.round(a * Math.max(0f, Math.min(1f, mul)));
        return (a << 24) | (argb & 0xFFFFFF);
    }

    public static int lerpColor(int c1, int c2, float t) {
        c1 = solid(c1);
        c2 = solid(c2);
        t = Math.max(0f, Math.min(1f, t));
        int a = (int) (((c1 >>> 24) & 0xFF) + ((((c2 >>> 24) & 0xFF) - ((c1 >>> 24) & 0xFF)) * t));
        int r = (int) (((c1 >> 16) & 0xFF) + ((((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t));
        int g = (int) (((c1 >> 8) & 0xFF) + ((((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t));
        int b = (int) ((c1 & 0xFF) + (((c2 & 0xFF) - (c1 & 0xFF)) * t));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static void begin(int argb) {
        argb = solid(argb);
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.disableAlpha();
        GlStateManager.disableCull();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
                (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
    }

    private static void end() {
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.enableCull();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    public static void rect(float x, float y, float w, float h, int argb) {
        if (((argb >>> 24) & 0xFF) == 0) return;
        begin(argb);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x, y + h);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x + w, y);
        GL11.glEnd();
        end();
    }

    // ---------------------------------------------------------------- several rectangles in one draw

    private static boolean batchOpen;

    /**
     * Starts a group of plain rectangles drawn with one draw call instead of one each (same look and order as
     * calling {@link #rect} for each). Nothing else may be drawn until {@link #batchEnd}.
     */
    public static void batchBegin() {
        batchOpen = false;
    }

    public static void batchRect(float x, float y, float w, float h, int argb) {
        if (((argb >>> 24) & 0xFF) == 0) return;
        if (!batchOpen) {
            begin(0xFFFFFFFF);
            GL11.glBegin(GL11.GL_QUADS);
            batchOpen = true;
        }
        argb = solid(argb);
        GL11.glColor4f(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f,
                ((argb >>> 24) & 0xFF) / 255f);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x, y + h);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x + w, y);
    }

    public static void batchEnd() {
        if (!batchOpen) return;
        GL11.glEnd();
        batchOpen = false;
        GlStateManager.resetColor(); // the colours were set directly; make GlStateManager send the next one
        end();
    }

    private static void roundedPath(float x, float y, float w, float h, float r) {
        corner(x + w - r, y + r, r, -90);
        corner(x + w - r, y + h - r, r, 0);
        corner(x + r, y + h - r, r, 90);
        corner(x + r, y + r, r, 180);
    }

    /** cos / sin for every 15 degrees from -90 to 270 (corner points), so drawing does no trig. */
    private static final float[] COS = new float[25], SIN = new float[25];
    static {
        for (int k = 0; k < 25; k++) {
            double a = Math.toRadians(-90 + k * 15);
            COS[k] = (float) Math.cos(a);
            SIN[k] = (float) Math.sin(a);
        }
    }

    private static void corner(float cx, float cy, float r, int startDeg) {
        int k0 = (startDeg + 90) / 15;
        for (int i = 0; i <= 6; i++) {
            GL11.glVertex2f(cx + COS[k0 + i] * r, cy + SIN[k0 + i] * r);
        }
    }

    public static void rounded(float x, float y, float w, float h, float r, int argb) {
        if (((argb >>> 24) & 0xFF) == 0) return;
        r = Math.min(r, Math.min(w, h) / 2f);
        begin(argb);
        GL11.glBegin(GL11.GL_POLYGON);
        roundedPath(x, y, w, h, r);
        GL11.glEnd();
        end();
    }

    public static void roundedOutline(float x, float y, float w, float h, float r, float lineWidth, int argb) {
        if (((argb >>> 24) & 0xFF) == 0) return;
        r = Math.min(r, Math.min(w, h) / 2f);
        begin(argb);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(lineWidth);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        roundedPath(x, y, w, h, r);
        GL11.glEnd();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(1f);
        end();
    }

    /**
     * {@link #rounded} followed by {@link #roundedOutline} on the same rectangle, without switching the drawing
     * state off and on in between (same look).
     */
    public static void roundedWithOutline(float x, float y, float w, float h, float r, int fill, float lineWidth, int border) {
        boolean doFill = ((fill >>> 24) & 0xFF) != 0, doBorder = ((border >>> 24) & 0xFF) != 0;
        if (!doFill || !doBorder) {
            rounded(x, y, w, h, r, fill);
            roundedOutline(x, y, w, h, r, lineWidth, border);
            return;
        }
        r = Math.min(r, Math.min(w, h) / 2f);
        begin(fill);
        GL11.glBegin(GL11.GL_POLYGON);
        roundedPath(x, y, w, h, r);
        GL11.glEnd();
        border = solid(border);
        GlStateManager.color(((border >> 16) & 0xFF) / 255f, ((border >> 8) & 0xFF) / 255f,
                (border & 0xFF) / 255f, ((border >>> 24) & 0xFF) / 255f);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(lineWidth);
        GL11.glBegin(GL11.GL_LINE_LOOP);
        roundedPath(x, y, w, h, r);
        GL11.glEnd();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth(1f);
        end();
    }

    /** Standard themed panel: translucent body + thin accent outline. */
    public static void panel(float x, float y, float w, float h, float alpha) {
        rounded(x, y, w, h, 4f, alpha(PANEL, alpha));
        roundedOutline(x, y, w, h, 4f, 1.5f, alpha(BORDER, alpha));
    }

    public static void triangleDown(float cx, float y, float size, int argb) {
        begin(argb);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2f(cx - size, y);
        GL11.glVertex2f(cx, y + size);
        GL11.glVertex2f(cx + size, y);
        GL11.glEnd();
        end();
    }

    /** A triangle pointing right (a play mark), its middle at cx, cy. */
    public static void triangleRight(float cx, float cy, float size, int argb) {
        begin(argb);
        GL11.glBegin(GL11.GL_TRIANGLES);
        GL11.glVertex2f(cx - size * 0.7f, cy - size);
        GL11.glVertex2f(cx - size * 0.7f, cy + size);
        GL11.glVertex2f(cx + size, cy);
        GL11.glEnd();
        end();
    }

    /** Text with shadow; skips fully transparent text (the font renderer treats alpha 0 as opaque). */
    public static void text(String s, float x, float y, int argb) {
        s = dev.fzkc.fzhud.util.Lang.t(s);
        int a = (argb >>> 24) & 0xFF;
        if (a < 6) return;
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        if (isChroma(argb)) {
            chromaText(s, x, y, a);
            return;
        }
        font().drawString(s, x, y, argb, !noShadow);
    }

    /** Set while a light ("dashboard") menu draws: dark text on a light panel looks muddy with a shadow. */
    public static boolean noShadow;

    /** One-character strings for the common characters, so rainbow text doesn't make new ones every frame. */
    private static final String[] CHARS = new String[256];

    private static String charString(char c) {
        if (c < CHARS.length) {
            String s = CHARS[c];
            if (s == null) CHARS[c] = s = String.valueOf(c);
            return s;
        }
        return String.valueOf(c);
    }

    /** Rainbow text: each character gets a slightly different hue so the colours flow along the text. */
    private static void chromaText(String s, float x, float y, int alpha) {
        FontRenderer fr = font();
        s = dev.fzkc.fzhud.feature.NameHider.apply(s);   // letter by letter below: the name is swapped first
        // only text with colour codes needs them stripped (the regex is the slow part)
        String plain = s.indexOf('§') < 0 ? s : net.minecraft.util.EnumChatFormatting.getTextWithoutFormattingCodes(s);
        if (plain == null) plain = s;
        float cx = x;
        for (int i = 0; i < plain.length(); i++) {
            String c = charString(plain.charAt(i));
            fr.drawString(c, cx, y, chroma(cx / 240f, Math.max(alpha, 6)), !noShadow);
            cx += measure(c);
        }
    }

    public static void textCentered(String s, float cx, float y, int argb) {
        s = dev.fzkc.fzhud.util.Lang.t(s);
        text(s, cx - measure(s) / 2f, y, argb);
    }

    public static void textScaled(String s, float x, float y, float scale, int argb) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0f);
        GlStateManager.scale(scale, scale, 1f);
        text(s, 0, 0, argb);
        GlStateManager.popMatrix();
    }

    public static void textScaledCentered(String s, float cx, float y, float scale, int argb) {
        s = dev.fzkc.fzhud.util.Lang.t(s);
        textScaled(s, cx - measure(s) * scale / 2f, y, scale, argb);
    }

    public static int width(String s) {
        return measure(dev.fzkc.fzhud.util.Lang.t(s));
    }

    // ---------------------------------------------------------------- text width memory

    private static final java.util.HashMap<String, Integer> WIDTHS = new java.util.HashMap<String, Integer>(1024);
    private static int widthsResources = Integer.MIN_VALUE, widthsEpoch = Integer.MIN_VALUE;
    private static boolean widthsUnicode;
    private static int widthsHider;
    private static FontRenderer widthsFont;
    /** Changes whenever the remembered widths are thrown away (layouts built from widths follow it). */
    public static int widthGen;

    /**
     * Font width of text as it is (no translation). The font measures every character with a slow table search
     * and the HUD asks for the same few strings every frame, so the results are remembered. The memory is cleared
     * when resources reload, when the unicode font is switched, when the Name Hider changes what a text looks
     * like, and every 5 seconds anyway (so it stays small and catches any other kind of font change).
     */
    public static int measure(String s) {
        FontRenderer fr = font();
        int epoch = dev.fzkc.fzhud.util.Stamp.ticks / 100;
        boolean unicode = fr.getUnicodeFlag();
        if (dev.fzkc.fzhud.feature.NameHider.rawNow()) return fr.getStringWidth(s);   // shown as typed just now: not remembered
        int hider = dev.fzkc.fzhud.feature.NameHider.stamp();
        if (widthsResources != dev.fzkc.fzhud.util.Stamp.resources || widthsEpoch != epoch || widthsHider != hider
                || widthsUnicode != unicode || widthsFont != fr || WIDTHS.size() > 4096) {
            WIDTHS.clear();
            widthsResources = dev.fzkc.fzhud.util.Stamp.resources;
            widthsEpoch = epoch;
            widthsUnicode = unicode;
            widthsHider = hider;
            widthsFont = fr;
            widthGen++;
        }
        Integer w = WIDTHS.get(s);
        if (w == null) {
            w = fr.getStringWidth(s);
            WIDTHS.put(s, w);
        }
        return w;
    }
}
