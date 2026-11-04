package dev.fzkc.fzhud.hud;

import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.config.ColorSetting;
import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.R;
import dev.fzkc.fzhud.util.Tracker;

/** Left / right clicks per second, with a pulse on each click. */
public class CpsPanel extends HudElement {
    private final Anim pulse = new Anim(0f, 10f);
    private long lastSeen;
    private final BoolSetting showRight, pulseOn;
    private final ColorSetting textColor;

    public CpsPanel(float defX, float defY) {
        super("cps", "CPS", "1秒あたりのクリック数", defX, defY);
        withBackground();
        showRight = add(new BoolSetting("right", "右クリックも表示", true));
        pulseOn = add(new BoolSetting("pulse", "クリックで光る", true));
        textColor = add(new ColorSetting("color", "文字の色", 0xFFFFFFFF));
    }

    @Override public float width() { return 70f; }
    @Override public float height() { return 20f; }

    @Override
    public void draw(float alpha, boolean preview) {
        if (Tracker.lastClick != lastSeen) {
            lastSeen = Tracker.lastClick;
            if (pulseOn.get()) pulse.snap(1f);
        }
        float p = pulse.update(0f);
        if (background.get()) {
            panel(0, 0, width(), height(), R.lerpColor(fillColor(alpha), R.alpha(0xC05CE1E6, alpha), p * 0.6f), alpha);
        }
        R.textCentered(text(), width() / 2f, 6f, R.alpha(textColor.get(), alpha));
    }

    // the text is only rebuilt when a number changes
    private int textLeft = Integer.MIN_VALUE, textRight = Integer.MIN_VALUE;
    private String text = "";

    private String text() {
        boolean both = showRight.get();
        int l = Tracker.leftCps(), r = both ? Tracker.rightCps() : -1;
        if (l != textLeft || r != textRight) {
            textLeft = l;
            textRight = r;
            text = both ? l + " | " + r + " CPS" : l + " CPS";
        }
        return text;
    }
}
