package dev.fzkc.fzhud.hud;

import dev.fzkc.fzhud.feature.Gameplay;
import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.config.ColorSetting;
import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

/** Movement state ("Flying", "Sprinting", ...) with a slide/cross-fade when it changes. */
public class StatusPanel extends HudElement {
    private String current = "Standing", previous = "";
    private final Anim swap = new Anim(1f, 10f);
    private final BoolSetting showToggled;
    private final BoolSetting hideStanding;
    private final ColorSetting textColor;

    public StatusPanel(float defX, float defY) {
        super("status", "Movement", "Flying・Sprintingなどの移動状態", defX, defY);
        withBackground();
        showToggled = add(new BoolSetting("toggled", "(Toggled) を表示", true));
        hideStanding = add(new BoolSetting("hideidle", "止まっている時は隠す", false));
        textColor = add(new ColorSetting("color", "文字の色", 0xFF5CE1E6));
    }

    @Override public float width() { return 124f; }
    @Override public float height() { return 20f; }

    @Override
    public boolean active() {
        return !(hideStanding.get() && "Standing".equals(state()));
    }

    private String state() {
        EntityPlayerSP p = Minecraft.getMinecraft().thePlayer;
        if (p == null) return "Standing";
        boolean t = showToggled.get();
        if (p.capabilities.isFlying) return "Flying";
        if (p.isRiding()) return "Riding";
        if (p.isInWater()) return "Swimming";
        if (p.isSneaking()) return t && Gameplay.sneakToggled ? "Sneaking (Toggled)" : "Sneaking";
        if (p.isSprinting()) return t && Gameplay.sprintToggled ? "Sprinting (Toggled)" : "Sprinting";
        double dx = p.posX - p.prevPosX, dz = p.posZ - p.prevPosZ;
        if (dx * dx + dz * dz > 1.0E-4) return "Walking";
        return "Standing";
    }

    @Override
    public void draw(float alpha, boolean preview) {
        String s = state();
        if (!s.equals(current)) {
            previous = current;
            current = s;
            swap.snap(0f);
        }
        float t = swap.update(1f);
        float w = width(), h = height();
        bg(0, 0, w, h, alpha);
        if (t < 1f && !previous.isEmpty()) {
            R.textCentered(previous, w / 2f, 6f - 5f * t, R.alpha(R.TEXT, alpha * (1f - t)));
        }
        R.textCentered(current, w / 2f, 6f + 5f * (1f - t), R.alpha(textColor.get(), alpha * t));
    }
}
