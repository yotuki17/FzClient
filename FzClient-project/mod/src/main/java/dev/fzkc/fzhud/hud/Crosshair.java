package dev.fzkc.fzhud.hud;

import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.config.ChoiceSetting;
import dev.fzkc.fzhud.config.ColorSetting;
import dev.fzkc.fzhud.config.SliderSetting;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition;

/** Custom crosshair with several shapes. Always centred; drawn from the crosshair hook. */
public class Crosshair extends HudElement {
    private static final float SIZE = 21f;

    public static final String[] TYPES = {
            "シェブロン ^", "十字", "十字＋点", "点", "円", "円＋点", "四隅", "X", "T字", "カッコ [ ]",
            "隙間なし十字", "細い十字", "4つの点", "ひし形", "三角", "自作"};
    /** The "hand-made" type (drawn from {@link #custom}). */
    public static final int CUSTOM = 15;

    private final ChoiceSetting type;
    private final ColorSetting color, outlineColor, targetColor;
    private final SliderSetting length, thickness, gap, spread;
    private final BoolSetting dot, outline, whiteCore, targetOn, invert;
    private final SliderSetting opacity;
    private final dev.fzkc.fzhud.config.PixelGridSetting custom;
    /** True while drawing a picture for the settings (always plain colour, never see-through). */
    private boolean picture;

    /** 0 = normal colour, 1 = "over a player" colour; eased so the colour fades instead of snapping. */
    private float tint;
    private long lastFrame;

    public Crosshair() {
        super("crosshair", "Crosshair", "画面中央の照準（種類を選べる）", 0.5f, 0.5f, CENTER);
        type = add(new ChoiceSetting("type", "種類", 0, TYPES));
        custom = add(new dev.fzkc.fzhud.config.PixelGridSetting("custom", "自作クロスヘア（種類で「自作」を選ぶと使われる）", 15));
        color = add(new ColorSetting("color", "色", 0xFF22C55E));
        targetOn = add(new BoolSetting("targetcolor", "プレイヤーに重なると色を変える", true));
        targetColor = add(new ColorSetting("targetcolorc", "重なった時の色", 0xFFFF5555));
        length = add(new SliderSetting("length", "線の長さ", 1, 9, 0.5, 5, ""));
        thickness = add(new SliderSetting("thickness", "線の太さ", 0.5, 4, 0.5, 1.5, ""));
        gap = add(new SliderSetting("gap", "中心からの隙間", 0, 6, 0.5, 2, ""));
        spread = add(new SliderSetting("spread", "シェブロンの開き具合", 10, 80, 1, 38, "°"));
        dot = add(new BoolSetting("dot", "中心の点（シェブロン）", true));
        whiteCore = add(new BoolSetting("white", "線の中を白くする（シェブロン）", true));
        outline = add(new BoolSetting("outline", "黒いフチをつける", true));
        outlineColor = add(new ColorSetting("outlinecolor", "フチの色", 0xFF0B1416));
        invert = add(new BoolSetting("invert", "後ろの色を反転して見せる（バニラ風）", false));
        opacity = add(new SliderSetting("opacity", "不透明度", 10, 100, 5, 100, "%"));
    }

    /** The crosshair colour as RGB (white while it is chroma), for the editor grid. */
    public int colorRgb() {
        return color.chroma ? 0xFFFFFF : color.rgb;
    }

    /** Switches to the hand-made type (opening the editor). */
    public void useCustom() {
        type.index = CUSTOM;
    }

    public dev.fzkc.fzhud.config.PixelGridSetting customGrid() {
        return custom;
    }

    @Override public float width() { return SIZE; }
    @Override public float height() { return SIZE; }
    @Override public boolean drawnInHudPass() { return false; }
    @Override public boolean movable() { return false; }

    @Override
    public float screenY(int sh) {
        return sh / 2f - scaledH() / 2f;
    }

    @Override
    public float screenX(int sw) {
        return sw / 2f - scaledW() / 2f;
    }

    /**
     * True while the crosshair is on a player that is close enough to hit. The game itself picks the entity under
     * the crosshair (objectMouseOver), so this changes colour exactly when a click would hit.
     */
    private static boolean overPlayer() {
        MovingObjectPosition hit = Minecraft.getMinecraft().objectMouseOver;
        return hit != null
                && hit.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY
                && hit.entityHit instanceof EntityPlayer
                && !hit.entityHit.isInvisible();   // never reveal invisible players (that would be a cheat)
    }

    @Override
    public void draw(float alpha, boolean preview) {
        if (!preview && Minecraft.getMinecraft().gameSettings.thirdPersonView != 0) return;   // first person only

        // ---- colour: eased towards the "over a player" colour while one is under the crosshair
        long now = System.nanoTime();
        float dt = lastFrame == 0 ? 0f : Math.min(0.1f, (now - lastFrame) / 1.0e9f);
        lastFrame = now;
        float want = !preview && targetOn.get() && overPlayer() ? 1f : 0f;
        tint += (want - tint) * (1f - (float) Math.exp(-30f * dt));
        if (Math.abs(want - tint) < 0.01f) tint = want;
        int base = tint > 0f ? R.lerpColor(color.get(), targetColor.get(), tint) : color.get();
        shape(type.get(), base, alpha);
    }

    /** The type setting (its options are shown as pictures in the settings). */
    public ChoiceSetting typeSetting() {
        return type;
    }

    /** One crosshair type with the current colour and sizes, centred on (cx, cy), for the type picker. */
    public void drawType(int t, float cx, float cy, float scale, float alpha) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(cx - SIZE / 2f * scale, cy - SIZE / 2f * scale, 0f);
        GlStateManager.scale(scale, scale, 1f);
        picture = true;
        try {
            shape(t, color.get(), alpha);
        } finally {
            picture = false;
        }
        GlStateManager.popMatrix();
    }

    private void shape(int t, int base, float alpha) {
        boolean inv = !picture && invert.get();
        float op = picture ? 1f : (float) (opacity.get() / 100.0);
        int col, out;
        if (inv) {
            // like the game's own crosshair: what is behind is turned to its opposite colour; a grey of strength
            // "opacity" mixes between the background (0) and fully inverted (1). No outline (it would invert too).
            int v = Math.round(255 * op * alpha);
            col = 0xFF000000 | v << 16 | v << 8 | v;
            out = 0;
            R.invert = true;
        } else {
            col = R.alpha(base, alpha * op);
            out = R.alpha(outlineColor.get(), alpha * 0.85f * op);
        }
        try {
            if (t == 0) chevron(col, out, alpha * op, inv);
            else pixelShape(t, col, out, inv);
        } finally {
            R.invert = false;
        }
    }

    /** The chevron "^" (drawn turned, so it is not snapped to pixels). */
    private void chevron(int col, int out, float alpha, boolean inv) {
        float c = SIZE / 2f, th = thickness.get(), len = length.get();
        for (int side = -1; side <= 1; side += 2) {
            GlStateManager.pushMatrix();
            GlStateManager.translate(c, c, 0f);
            GlStateManager.rotate(side * -spread.get(), 0f, 0f, 1f);
            R.rect(-th, 1.5f, th * 2, len + 1.5f, col);
            if (whiteCore.get() && !inv) R.rect(-th / 3f, 2.2f, th * 2 / 3f, len, R.alpha(0xFFFFFFFF, alpha));
            GlStateManager.popMatrix();
        }
        if (dot.get()) {
            R.rect(c - 1.5f, c - 1.5f, 3f, 3f, col);
            if (!inv) R.rect(c - 0.5f, c - 0.5f, 1f, 1f, out);
        }
    }

    // ---------------------------------------------------------------- pixel crosshairs
    // Every other type is laid out in whole screen pixels around the centre, so lines are sharp and the same width
    // on both sides at any GUI scale (fractional sizes would smear a line over two pixels).

    private static final java.nio.FloatBuffer MATRIX = org.lwjgl.BufferUtils.createFloatBuffer(16);
    /** Filled rectangles of the shape being built: x, y, w, h in pixels. */
    private final java.util.List<int[]> parts = new java.util.ArrayList<int[]>();

    private void add(int x, int y, int w, int h) {
        if (w > 0 && h > 0) parts.add(new int[]{x, y, w, h});
    }

    private void pixelShape(int type, int col, int out, boolean inv) {
        Minecraft mc = Minecraft.getMinecraft();
        float sf = new net.minecraft.client.gui.ScaledResolution(mc).getScaleFactor();
        MATRIX.clear();
        org.lwjgl.opengl.GL11.glGetFloat(org.lwjgl.opengl.GL11.GL_MODELVIEW_MATRIX, MATRIX);
        float sx = MATRIX.get(0), sy = MATRIX.get(5), tx = MATRIX.get(12), ty = MATRIX.get(13);
        if (sx <= 0f || sy <= 0f) return;
        float px = sx * sf;   // screen pixels per crosshair unit
        // the centre, on the nearest pixel corner
        float cx = Math.round((sx * SIZE / 2f + tx) * sf), cy = Math.round((sy * SIZE / 2f + ty) * sf);

        int t = Math.max(1, Math.round(thickness.get() * px));
        int l = Math.max(1, Math.round(length.get() * px));
        int g = Math.max(0, Math.round(gap.get() * px));
        // the centre cell is t wide: [lo, hi) on both axes (odd t: a centre pixel; even t: a pixel corner)
        int lo = -(t / 2), hi = lo + t;
        parts.clear();
        build(type, t, l, g, lo, hi, px);

        GlStateManager.pushMatrix();
        GlStateManager.translate((cx / sf - tx) / sx, (cy / sf - ty) / sy, 0f);
        GlStateManager.scale(1f / (sf * sx), 1f / (sf * sy), 1f);
        draw(col, out, outline.get() && !inv ? Math.max(1, Math.round(px * 0.5f)) : 0);
        GlStateManager.popMatrix();
    }

    /**
     * Draws the parts as one shape: overlaps are merged first (so see-through and inverted crosshairs have no darker
     * spots where two pieces cross), and the outline is only the ring around it, never under it.
     */
    private void draw(int col, int out, int o) {
        if (parts.isEmpty()) return;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
        for (int[] r : parts) {
            x0 = Math.min(x0, r[0]);
            y0 = Math.min(y0, r[1]);
            x1 = Math.max(x1, r[0] + r[2]);
            y1 = Math.max(y1, r[1] + r[3]);
        }
        x0 -= o;
        y0 -= o;
        x1 += o;
        y1 += o;
        int w = x1 - x0, h = y1 - y0;
        if (w <= 0 || h <= 0 || (long) w * h > 400000L) return;
        boolean[] fill = new boolean[w * h];
        for (int[] r : parts) {
            for (int y = r[1]; y < r[1] + r[3]; y++) {
                int row = (y - y0) * w;
                for (int x = r[0]; x < r[0] + r[2]; x++) fill[row + x - x0] = true;
            }
        }
        R.batchBegin();
        if (o > 0) {
            boolean[] ring = new boolean[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (!fill[y * w + x]) continue;
                    for (int dy = -o; dy <= o; dy++) {
                        int yy = y + dy;
                        if (yy < 0 || yy >= h) continue;
                        for (int dx = -o; dx <= o; dx++) {
                            int xx = x + dx;
                            if (xx >= 0 && xx < w && !fill[yy * w + xx]) ring[yy * w + xx] = true;
                        }
                    }
                }
            }
            runs(ring, w, h, x0, y0, out);
        }
        runs(fill, w, h, x0, y0, col);
        R.batchEnd();
    }

    /** One rectangle per run of set pixels in each row. */
    private static void runs(boolean[] grid, int w, int h, int x0, int y0, int argb) {
        for (int y = 0; y < h; y++) {
            int start = -1;
            for (int x = 0; x <= w; x++) {
                boolean on = x < w && grid[y * w + x];
                if (on && start < 0) start = x;
                else if (!on && start >= 0) {
                    R.batchRect(x0 + start, y0 + y, x - start, 1, argb);
                    start = -1;
                }
            }
        }
    }

    private void build(int type, int t, int l, int g, int lo, int hi, float px) {
        switch (type) {
            case CUSTOM: { // hand-made: one cell = one crosshair unit, the middle cell on the centre
                int u = Math.max(1, Math.round(px)), c = custom.size / 2, base = -(u / 2);
                for (int y = 0; y < custom.size; y++) {
                    for (int x = 0; x < custom.size; x++) {
                        if (custom.get(x, y)) add(base + (x - c) * u, base + (y - c) * u, u, u);
                    }
                }
                break;
            }
            case 1: case 2: // cross (+ dot)
                bars(t, l, g, lo, hi);
                if (type == 2 || g == 0) add(lo, lo, t, t);   // no gap: a solid middle
                break;
            case 3: { // dot
                int e = Math.max(1, t / 2);
                add(lo - e, lo - e, t + e * 2, t + e * 2);
                break;
            }
            case 4: case 5: { // circle (+ dot)
                ring(g + l / 2f + t, t, lo, hi);
                if (type == 5) add(lo, lo, t, t);
                break;
            }
            case 6: { // four corners
                int s = g + Math.max(2, l / 2);
                int left = lo - s - t, right = hi + s + t, a = Math.max(t + 1, (right - left) / 3);
                add(left, left, a, t);
                add(left, left, t, a);
                add(right - a, left, a, t);
                add(right - t, left, t, a);
                add(left, right - t, a, t);
                add(left, right - a, t, a);
                add(right - a, right - t, a, t);
                add(right - t, right - a, t, a);
                break;
            }
            case 7: // X: four steps of t x t squares
                for (int k = 1; k <= l; k++) {
                    int off = g + k;
                    add(lo - off, lo - off, t, t);
                    add(hi + off - t, lo - off, t, t);
                    add(lo - off, hi + off - t, t, t);
                    add(hi + off - t, hi + off - t, t, t);
                }
                break;
            case 8: // T (no top line)
                add(lo, hi + g, t, l);
                add(lo - g - l, lo, l, t);
                add(hi + g, lo, l, t);
                break;
            case 9: { // brackets [ ]
                int s = g + Math.max(t + 1, l / 2), h = Math.max(l, t * 2), a = Math.max(t + 1, l / 3);
                int lx = lo - s - t, rx = hi + s;
                add(lx, lo - h, t, hi - lo + h * 2);
                add(lx, lo - h, a, t);
                add(lx, hi + h - t, a, t);
                add(rx, lo - h, t, hi - lo + h * 2);
                add(rx + t - a, lo - h, a, t);
                add(rx + t - a, hi + h - t, a, t);
                break;
            }
            case 10: // cross without a gap
                add(lo, lo - l, t, l * 2 + t);
                add(lo - l, lo, l, t);
                add(hi, lo, l, t);
                break;
            case 11: { // thin cross: always one pixel line (like the game's own)
                int th = Math.max(1, Math.round(px * 0.5f)), c0 = -(th / 2), len = l + g + t;
                add(c0, c0 - len, th, len * 2 + th);
                add(c0 - len, c0, len, th);
                add(c0 + th, c0, len, th);
                break;
            }
            case 12: { // four dots around the centre
                int d = g + l / 2 + t;
                add(lo, lo - d - t, t, t);
                add(lo, hi + d, t, t);
                add(lo - d - t, lo, t, t);
                add(hi + d, lo, t, t);
                break;
            }
            case 13: { // diamond outline
                int r = g + l / 2 + t;
                for (int dx = -r; dx <= r; dx++) {
                    int dy = r - Math.abs(dx);
                    add(lo + dx, lo - dy, t, t);
                    if (dy != 0) add(lo + dx, lo + dy, t, t);
                }
                break;
            }
            default: { // triangle under the centre, pointing up
                int rows = Math.max(3, l / Math.max(1, t / 2 + 1));
                int step = Math.max(1, t / 2 + 1);
                int top = hi + g;
                for (int r = 0; r < rows; r++) add(lo - r * step, top + r * step, t + r * step * 2, step);
                break;
            }
        }
    }

    /** The four arms of a cross. */
    private void bars(int t, int l, int g, int lo, int hi) {
        add(lo, lo - g - l, t, l);    // up
        add(lo, hi + g, t, l);        // down
        add(lo - g - l, lo, l, t);    // left
        add(hi + g, lo, l, t);        // right
    }

    /** A pixel ring of width t whose middle line is r pixels from the centre cell's middle. */
    private void ring(float r, int t, int lo, int hi) {
        float c = (lo + hi) / 2f, half = t / 2f + (t <= 2 ? 0.25f : 0f);
        int R0 = (int) Math.ceil(r + t / 2f + 1);
        for (int y = (int) Math.floor(c - R0); y < c + R0; y++) {
            int start = Integer.MIN_VALUE;
            for (int x = (int) Math.floor(c - R0); x <= (int) Math.ceil(c + R0); x++) {
                float dx = x + 0.5f - c, dy = y + 0.5f - c;
                boolean in = Math.abs((float) Math.sqrt(dx * dx + dy * dy) - r) <= half;
                if (in && start == Integer.MIN_VALUE) start = x;
                else if (!in && start != Integer.MIN_VALUE) {
                    add(start, y, x - start, 1);
                    start = Integer.MIN_VALUE;
                }
            }
        }
    }
}
