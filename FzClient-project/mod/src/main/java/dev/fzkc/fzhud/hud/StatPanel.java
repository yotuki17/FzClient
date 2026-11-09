package dev.fzkc.fzhud.hud;

import dev.fzkc.fzhud.config.ColorSetting;
import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.R;

import java.util.function.Supplier;

/** A single value in a themed panel, e.g. "142 FPS". Width animates smoothly as the text changes. */
public class StatPanel extends HudElement {
    private final Supplier<String> value;
    private final float minWidth;
    private final Anim w;
    private final ColorSetting textColor;

    public StatPanel(String id, String name, String description, float defX, float defY, float minWidth, Supplier<String> value) {
        this(id, name, description, defX, defY, minWidth, value, false);
    }

    public StatPanel(String id, String name, String description, float defX, float defY, float minWidth, Supplier<String> value, boolean enabledByDefault) {
        super(id, name, description, defX, defY, CENTER, enabledByDefault);
        this.value = value;
        this.minWidth = minWidth;
        this.w = new Anim(minWidth, 12f);
        withBackground();
        textColor = add(new ColorSetting("color", "文字の色", 0xFFFFFFFF));
    }

    /** Optional: when this returns false the panel hides (e.g. Reach Display while you haven't hit anything). */
    public java.util.function.BooleanSupplier showWhen;

    @Override
    public boolean active() {
        return showWhen == null || showWhen.getAsBoolean();
    }

    private String text() {
        String s = value.get();
        return s == null ? "--" : s;
    }

    @Override public float width() { return Math.max(minWidth, w.value); }
    @Override public float height() { return 20f; }

    @Override
    public void draw(float alpha, boolean preview) {
        String s = text();
        w.update(Math.max(minWidth, R.width(s) + 16));
        float ww = width();
        bg(0, 0, ww, height(), alpha);
        R.textCentered(s, ww / 2f, 6f, R.alpha(textColor.get(), alpha));
    }
}
