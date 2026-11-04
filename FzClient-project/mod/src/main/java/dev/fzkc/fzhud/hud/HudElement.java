package dev.fzkc.fzhud.hud;

import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.config.ColorSetting;
import dev.fzkc.fzhud.config.Module;
import dev.fzkc.fzhud.config.SliderSetting;
import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.R;

/**
 * Base class for a movable HUD element.
 * Position is stored as a fraction of the scaled screen. Horizontally the element is
 * anchored to whichever side it sits closest to (left / centre / right), so elements
 * whose width changes (scoreboard, tab, potions) grow away from the screen edge.
 */
public abstract class HudElement extends Module {
    public static final int LEFT = 0, CENTER = 1, RIGHT = 2;

    public final float defX, defY;
    public final int defAnchor;

    public float relX, relY;
    public int anchor;

    public final SliderSetting scaleSetting;
    /** Optional "background" toggle for elements drawn on a panel. */
    protected BoolSetting background;

    /** Fade/slide when toggled, when it becomes active, or when joining a world. */
    public final Anim shown = new Anim(0f, 9f);
    /** Hover highlight in the editor. */
    public final Anim hover = new Anim(0f, 14f);

    protected HudElement(String id, String name, String description, float defX, float defY) {
        this(id, name, description, defX, defY, CENTER);
    }

    protected HudElement(String id, String name, String description, float defX, float defY, int defAnchor) {
        this(id, name, description, defX, defY, defAnchor, true);
    }

    protected HudElement(String id, String name, String description, float defX, float defY, int defAnchor, boolean enabledByDefault) {
        super(id, name, Category.HUD, description, enabledByDefault);
        this.defX = defX;
        this.defY = defY;
        this.defAnchor = defAnchor;
        this.scaleSetting = add(new SliderSetting("scale", "大きさ", 0.5, 2.5, 0.05, 1.0, "x"));
        resetPosition();
    }

    protected void withBackground() {
        background = add(new BoolSetting("background", "背景を表示", true));
        withStyle();
    }

    // ---- Panel style: background colour, border and corner roundness (every panel element has these)
    protected ColorSetting styleFill, styleBorder;
    protected SliderSetting styleFillAlpha, styleBorderAlpha, styleBorderWidth, styleRadius;
    protected BoolSetting styleBorderOn;

    protected void withStyle() {
        if (styleFill != null) return;
        styleFill = add(new ColorSetting("bgcolor", "背景の色", 0xFF101A1E));
        styleFillAlpha = add(new SliderSetting("bgalpha", "背景の濃さ", 0, 1, 0.05, 0.6, ""));
        styleBorderOn = add(new BoolSetting("border", "枠線を表示", true));
        styleBorder = add(new ColorSetting("bordercolor", "枠線の色", 0xFF7FD8DC));
        styleBorderAlpha = add(new SliderSetting("borderalpha", "枠線の濃さ", 0, 1, 0.05, 0.7, ""));
        styleBorderWidth = add(new SliderSetting("borderwidth", "枠線の太さ", 0.5, 4, 0.25, 1.5, ""));
        styleRadius = add(new SliderSetting("radius", "角の丸さ（0で四角）", 0, 10, 0.5, 4, ""));
    }

    private static int withAlpha(int argb, float a) {
        return (Math.round(Math.max(0f, Math.min(1f, a)) * 255f) << 24) | (argb & 0xFFFFFF);
    }

    /** Background colour (ARGB, chroma marker kept) multiplied by the fade alpha. */
    public int fillColor(float alpha) {
        if (styleFill == null) return R.alpha(R.PANEL, alpha);
        return R.alpha(withAlpha(styleFill.get(), styleFillAlpha.get()), alpha);
    }

    /** Border colour, or 0 (nothing drawn) when the border is off. */
    public int borderColor(float alpha) {
        if (styleFill == null) return R.alpha(R.BORDER, alpha);
        if (!styleBorderOn.get()) return 0;
        return R.alpha(withAlpha(styleBorder.get(), styleBorderAlpha.get()), alpha);
    }

    public float radius() { return styleRadius == null ? 4f : styleRadius.get(); }
    public float borderWidth() { return styleBorderWidth == null ? 1.5f : styleBorderWidth.get(); }

    /** Panel in this element's style. */
    protected void panel(float x, float y, float w, float h, float alpha) {
        panel(x, y, w, h, fillColor(alpha), alpha);
    }

    /** Panel with a custom fill (e.g. a key lighting up) but this element's border and corners. */
    protected void panel(float x, float y, float w, float h, int fill, float alpha) {
        float r = radius();
        int b = borderColor(alpha);
        boolean border = ((b >>> 24) & 0xFF) != 0;
        float bw = borderWidth();
        if (r <= 0.01f) {
            // square: body and the four border strips in one draw
            R.batchBegin();
            R.batchRect(x, y, w, h, fill);
            if (border) {
                R.batchRect(x, y, w, bw * 0.5f, b);
                R.batchRect(x, y + h - bw * 0.5f, w, bw * 0.5f, b);
                R.batchRect(x, y + bw * 0.5f, bw * 0.5f, h - bw, b);
                R.batchRect(x + w - bw * 0.5f, y + bw * 0.5f, bw * 0.5f, h - bw, b);
            }
            R.batchEnd();
        } else if (border) {
            R.roundedWithOutline(x, y, w, h, r, fill, bw, b);
        } else {
            R.rounded(x, y, w, h, r, fill);
        }
    }

    public void resetPosition() {
        relX = defX;
        relY = defY;
        anchor = defAnchor;
    }

    public float scale() { return scaleSetting.get(); }

    /** Draws the themed panel unless the background setting is off. */
    protected void bg(float x, float y, float w, float h, float alpha) {
        if (background == null || background.get()) panel(x, y, w, h, alpha);
    }

    /** Unscaled size in GUI pixels. */
    public abstract float width();
    public abstract float height();

    /** Draw at local (0,0). alpha is 0..1; preview = drawn inside the editor. */
    public abstract void draw(float alpha, boolean preview);

    /** Whether the element should currently be on screen (besides being enabled). */
    public boolean active() { return true; }

    /** Drawn by the normal HUD pass (false for things drawn from their own hook, e.g. the crosshair). */
    public boolean drawnInHudPass() { return true; }

    /** Whether the editor lets you drag it. */
    public boolean movable() { return true; }

    public float scaledW() { return width() * scale(); }
    public float scaledH() { return height() * scale(); }

    public float screenX(int sw) {
        float x;
        if (anchor == LEFT) x = relX * sw;
        else if (anchor == RIGHT) x = relX * sw - scaledW();
        else x = relX * sw - scaledW() / 2f;
        return Math.max(0f, Math.min(sw - scaledW(), x));
    }

    public float screenY(int sh) {
        float y = relY * sh;
        return Math.max(0f, Math.min(sh - scaledH(), y));
    }

    public void setScreenPos(float x, float y, int sw, int sh) {
        float cx = x + scaledW() / 2f;
        if (cx < sw / 3f) {
            anchor = LEFT;
            relX = x / sw;
        } else if (cx > sw * 2f / 3f) {
            anchor = RIGHT;
            relX = (x + scaledW()) / sw;
        } else {
            anchor = CENTER;
            relX = cx / sw;
        }
        relY = y / sh;
    }

    public boolean contains(int mx, int my, int sw, int sh) {
        float x = screenX(sw), y = screenY(sh);
        return mx >= x && mx <= x + scaledW() && my >= y && my <= y + scaledH();
    }
}
