package dev.fzkc.fzhud.hud;

import dev.fzkc.fzhud.FzHud;
import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.config.SliderSetting;
import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

/** Smooth scrolling compass strip with cardinal letters and degree ticks. */
public class Compass extends HudElement {
    private static final String[] CARDINALS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final float H = 30f;
    private final Anim heading = new Anim(0f, 16f);
    private final SliderSetting widthSetting, zoom, smooth;
    private final BoolSetting numbers, readout;

    public Compass(float defX, float defY) {
        super("compass", "Compass", "画面上の方角コンパス", defX, defY);
        widthSetting = add(new SliderSetting("width", "幅", 100, 320, 5, 200, ""));
        zoom = add(new SliderSetting("zoom", "目盛りの間隔", 0.8, 3.0, 0.1, 1.4, "x"));
        smooth = add(new SliderSetting("smooth", "回転の追従の速さ", 4, 40, 1, 16, ""));
        numbers = add(new BoolSetting("numbers", "角度の数字を表示", true));
        readout = add(new BoolSetting("readout", "中央に現在の角度", true));
    }

    @Override public float width() { return widthSetting.get(); }
    @Override public float height() { return H; }

    @Override
    public void draw(float alpha, boolean preview) {
        EntityPlayerSP p = Minecraft.getMinecraft().thePlayer;
        float yaw = 0f;
        if (p != null) {
            float pt = FzHud.partialTicks;
            yaw = p.prevRotationYaw + (p.rotationYaw - p.prevRotationYaw) * pt;
        }
        // Minecraft yaw 180 = north; convert to a compass bearing (N = 0, E = 90)
        float target = ((yaw + 180f) % 360f + 360f) % 360f;
        heading.speed = smooth.get();
        float bearing = smooth.get() >= 40f ? target : heading.updateAngle(target);
        if (smooth.get() >= 40f) heading.snap(target);
        float W = width();
        float PX_PER_DEG = zoom.get();

        float cx = W / 2f;
        float half = W / 2f;
        float range = half / PX_PER_DEG;
        int start = (int) Math.floor((bearing - range) / 5f) * 5;

        R.triangleDown(cx, 0f, 3f, R.alpha(R.TEXT, alpha));
        R.rect(cx - half * 0.85f, H - 2f, half * 1.7f, 1f, R.alpha(R.BORDER, alpha * 0.6f));

        // tick marks between two labels go out together in one draw (same order as before: the group is
        // finished right before each label is drawn)
        R.batchBegin();
        for (int d = start; d <= bearing + range; d += 5) {
            float off = (d - bearing) * PX_PER_DEG;
            float edge = Math.abs(off) / half;
            float fade = alpha * Math.max(0f, 1f - edge * edge);
            if (fade <= 0.02f) continue;
            float x = cx + off;
            int norm = ((d % 360) + 360) % 360;
            if (norm % 45 == 0) {
                String label = CARDINALS[norm / 45];
                boolean main = norm % 90 == 0;
                float sc = main ? 1.6f : 1.25f;
                int col = norm == 0 ? 0xFFFF6B6B : (main ? R.TEXT : R.MUTED);
                R.batchEnd();
                R.textScaledCentered(label, x, main ? 5f : 7f, sc, R.alpha(col, fade));
                R.batchBegin();
            } else if (norm % 15 == 0) {
                R.batchRect(x - 0.5f, 6f, 1f, 6f, R.alpha(R.TEXT, fade * 0.8f));
                if (numbers.get()) {
                    R.batchEnd();
                    R.textScaledCentered(degrees(norm), x, 15f, 0.55f, R.alpha(R.MUTED, fade));
                    R.batchBegin();
                }
            } else {
                R.batchRect(x - 0.5f, 7f, 1f, 3f, R.alpha(R.MUTED, fade * 0.7f));
            }
        }
        R.batchEnd();
        // centre readout
        if (readout.get()) R.textScaledCentered(degrees(Math.round(bearing) % 360), cx, 22f, 0.6f, R.alpha(R.ACCENT, alpha));
    }

    /** "0" ... "359", made once instead of every frame. */
    private static final String[] DEGREES = new String[360];

    private static String degrees(int d) {
        if (d < 0 || d >= DEGREES.length) return String.valueOf(d);
        String s = DEGREES[d];
        if (s == null) DEGREES[d] = s = String.valueOf(d);
        return s;
    }
}
