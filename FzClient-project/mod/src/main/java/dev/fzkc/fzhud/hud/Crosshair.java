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
            "シェブロン ^", "十字", "十字＋点", "点",
            "円", "円＋点", "四角", "X", "T字", "カッコ [ ]"};

    private final ChoiceSetting type;
    private final ColorSetting color, outlineColor, targetColor;
    private final SliderSetting length, thickness, gap, spread;
    private final BoolSetting dot, outline, whiteCore, targetOn;

    /** 0 = normal colour, 1 = "over a player" colour; eased so the colour fades instead of snapping. */
    private float tint;
    private long lastFrame;

    public Crosshair() {
        super("crosshair", "Crosshair", "画面中央の照準（種類を選べる）", 0.5f, 0.5f, CENTER);
        type = add(new ChoiceSetting("type", "種類", 0, TYPES));
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
        type.preview = this::drawPreview;
    }

    /** One shape for the option picker, drawn with the current colour / size settings and scaled into the square. */
    private void drawPreview(int index, float x, float y, float size, float alpha) {
        float scale = size / (SIZE * 1.6f);
        GlStateManager.pushMatrix();
        GlStateManager.translate(x + size / 2f, y + size / 2f, 0f);
        GlStateManager.scale(scale, scale, 1f);
        GlStateManager.translate(-SIZE / 2f, -SIZE / 2f, 0f);
        drawShape(index, alpha, R.alpha(color.get(), alpha), R.alpha(outlineColor.get(), alpha * 0.85f));
        GlStateManager.popMatrix();
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

    /** A bar drawn with an optional outline pass underneath. */
    private void bar(float x, float y, float w, float h, int c, int o, boolean withOutline) {
        if (withOutline) R.rect(x - 0.5f, y - 0.5f, w + 1f, h + 1f, o);
        R.rect(x, y, w, h, c);
    }

    /** Same as {@link #bar}, as part of an R.batchBegin() / R.batchEnd() group. */
    private void batchBar(float x, float y, float w, float h, int c, int o, boolean withOutline) {
        if (withOutline) R.batchRect(x - 0.5f, y - 0.5f, w + 1f, h + 1f, o);
        R.batchRect(x, y, w, h, c);
    }

    private void ring(float cx, float cy, float r, float t, int c) {
        R.roundedOutline(cx - r, cy - r, r * 2, r * 2, r, t, c);
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

        drawShape(type.get(), alpha, R.alpha(base, alpha), R.alpha(outlineColor.get(), alpha * 0.85f));
    }

    /** Draws shape {@code t} (index into TYPES) in the 0..SIZE square. */
    private void drawShape(int t, float alpha, int col, int out) {
        float c = SIZE / 2f;
        boolean ol = outline.get();
        float len = length.get(), th = thickness.get(), g = gap.get();
        float h = th / 2f;

        switch (t) {
            case 0: { // chevron "^"
                for (int side = -1; side <= 1; side += 2) {
                    GlStateManager.pushMatrix();
                    GlStateManager.translate(c, c, 0f);
                    GlStateManager.rotate(side * -spread.get(), 0f, 0f, 1f);
                    R.rect(-th, 1.5f, th * 2, len + 1.5f, col);
                    if (whiteCore.get()) R.rect(-th / 3f, 2.2f, th * 2 / 3f, len, R.alpha(0xFFFFFFFF, alpha));
                    GlStateManager.popMatrix();
                }
                if (dot.get()) {
                    R.rect(c - 1.5f, c - 1.5f, 3f, 3f, col);
                    R.rect(c - 0.5f, c - 0.5f, 1f, 1f, out);
                }
                break;
            }
            case 1: case 2: { // cross (+ dot), all bars in one draw
                R.batchBegin();
                batchBar(c - h, c - g - len, th, len, col, out, ol);          // up
                batchBar(c - h, c + g, th, len, col, out, ol);                // down
                batchBar(c - g - len, c - h, len, th, col, out, ol);          // left
                batchBar(c + g, c - h, len, th, col, out, ol);                // right
                if (t == 2) batchBar(c - h, c - h, th, th, col, out, ol);
                R.batchEnd();
                break;
            }
            case 3: { // dot
                float d = Math.max(1.5f, th * 1.5f);
                if (ol) R.rounded(c - d / 2 - 0.6f, c - d / 2 - 0.6f, d + 1.2f, d + 1.2f, d / 2 + 0.6f, out);
                R.rounded(c - d / 2, c - d / 2, d, d, d / 2, col);
                break;
            }
            case 4: case 5: { // circle (+ dot)
                float r = g + len / 2f + 1f;
                if (ol) ring(c, c, r, th + 1.5f, out);
                ring(c, c, r, th, col);
                if (t == 5) {
                    if (ol) R.rect(c - 1.2f, c - 1.2f, 2.4f, 2.4f, out);
                    R.rect(c - 0.7f, c - 0.7f, 1.4f, 1.4f, col);
                }
                break;
            }
            case 6: { // square
                float s = g + len / 2f + 1f;
                if (ol) R.roundedOutline(c - s, c - s, s * 2, s * 2, 0.01f, th + 1.5f, out);
                R.roundedOutline(c - s, c - s, s * 2, s * 2, 0.01f, th, col);
                break;
            }
            case 7: { // X
                for (int k = 0; k < 4; k++) {
                    GlStateManager.pushMatrix();
                    GlStateManager.translate(c, c, 0f);
                    GlStateManager.rotate(45f + k * 90f, 0f, 0f, 1f);
                    bar(-h, g, th, len, col, out, ol);
                    GlStateManager.popMatrix();
                }
                break;
            }
            case 8: { // T (no top line)
                R.batchBegin();
                batchBar(c - h, c + g, th, len, col, out, ol);
                batchBar(c - g - len, c - h, len, th, col, out, ol);
                batchBar(c + g, c - h, len, th, col, out, ol);
                R.batchEnd();
                break;
            }
            default: { // brackets [ ]
                float s = g + 2f, l = len;
                R.batchBegin();
                // left bracket
                batchBar(c - s - th, c - l, th, l * 2, col, out, ol);
                batchBar(c - s - th, c - l, l * 0.6f, th, col, out, ol);
                batchBar(c - s - th, c + l - th, l * 0.6f, th, col, out, ol);
                // right bracket
                batchBar(c + s, c - l, th, l * 2, col, out, ol);
                batchBar(c + s + th - l * 0.6f, c - l, l * 0.6f, th, col, out, ol);
                batchBar(c + s + th - l * 0.6f, c + l - th, l * 0.6f, th, col, out, ol);
                R.batchEnd();
                break;
            }
        }
    }
}
