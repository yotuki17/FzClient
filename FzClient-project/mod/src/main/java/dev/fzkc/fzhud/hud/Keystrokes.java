package dev.fzkc.fzhud.hud;

import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.config.ColorSetting;
import dev.fzkc.fzhud.config.SliderSetting;
import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;

/** W / A S D / space keystrokes with an eased press animation. */
public class Keystrokes extends HudElement {
    private static final float K = 22f, GAP = 2f, SPACE_H = 12f;

    private final BoolSetting showSpace;
    private final ColorSetting pressColor;
    private final SliderSetting fadeSpeed;
    private final Anim[] press = {
            new Anim(0, 18), new Anim(0, 18), new Anim(0, 18), new Anim(0, 18), new Anim(0, 18)
    };

    public Keystrokes(float defX, float defY) {
        super("keystrokes", "Keystrokes", "WASDとスペースの押下表示", defX, defY);
        showSpace = add(new BoolSetting("space", "スペースキーを表示", true));
        pressColor = add(new ColorSetting("presscolor", "押した時の色", 0xFF5CE1E6));
        fadeSpeed = add(new SliderSetting("fade", "色が変わる速さ", 4, 40, 1, 18, ""));
        withStyle();
    }

    @Override public float width() { return K * 3 + GAP * 2; }
    @Override public float height() { return K * 2 + GAP + (showSpace.get() ? GAP + SPACE_H : 0); }

    /** Key boxes: x, y, w, h (the space bar spans the full width K * 3 + GAP * 2). */
    private static final float[][] POS = {
            {K + GAP, 0, K, K},
            {0, K + GAP, K, K},
            {K + GAP, K + GAP, K, K},
            {(K + GAP) * 2, K + GAP, K, K},
            {0, (K + GAP) * 2, K * 3 + GAP * 2, SPACE_H}
    };
    private final KeyBinding[] binds = new KeyBinding[5];
    /** Key names, looked up again only when a key is rebound or the language / resources change. */
    private final int[] labelCode = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
    private final String[] labelText = new String[5];
    private int labelResources = Integer.MIN_VALUE;

    private String label(int i) {
        int code = binds[i].getKeyCode();
        if (labelResources != dev.fzkc.fzhud.util.Stamp.resources) {
            labelResources = dev.fzkc.fzhud.util.Stamp.resources;
            java.util.Arrays.fill(labelCode, Integer.MIN_VALUE);
        }
        if (code != labelCode[i]) {
            String label = GameSettings.getKeyDisplayString(code);
            if (label.length() > 3) label = label.substring(0, 3);
            labelText[i] = label;
            labelCode[i] = code;
        }
        return labelText[i];
    }

    @Override
    public void draw(float alpha, boolean preview) {
        GameSettings gs = Minecraft.getMinecraft().gameSettings;
        binds[0] = gs.keyBindForward;
        binds[1] = gs.keyBindLeft;
        binds[2] = gs.keyBindBack;
        binds[3] = gs.keyBindRight;
        binds[4] = gs.keyBindJump;
        int on = pressColor.get();
        int onFill = (0xE0 << 24) | (on & 0xFFFFFF);
        for (int i = 0; i < binds.length; i++) {
            if (i == 4 && !showSpace.get()) continue;
            press[i].speed = fadeSpeed.get();
            float p = press[i].update(binds[i].isKeyDown() ? 1f : 0f);

            float[] r = POS[i];
            float s = 1f - 0.06f * p;
            float cx = r[0] + r[2] / 2f, cy = r[1] + r[3] / 2f;
            GlStateManager.pushMatrix();
            GlStateManager.translate(cx, cy, 0);
            GlStateManager.scale(s, s, 1f);
            float x = -r[2] / 2f, y = -r[3] / 2f;
            panel(x, y, r[2], r[3], R.lerpColor(fillColor(alpha), R.alpha(onFill, alpha), p), alpha);
            int tc = R.alpha(R.lerpColor(R.TEXT, R.DARK, p), alpha);
            if (i == 4) {
                R.rect(-14, -0.5f, 28, 1.5f, tc);
            } else {
                R.textCentered(label(i), 0, -3.5f, tc);
            }
            GlStateManager.popMatrix();
        }
    }
}
