package dev.fzkc.fzhud.gui;

import dev.fzkc.fzhud.config.ChoiceSetting;
import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.R;
import org.lwjgl.input.Keyboard;

/**
 * Shows every option of a choice setting side by side, so you can see them all and click the one you want
 * (instead of stepping through them with ‹ ›). Options with a picture (e.g. crosshair shapes) get big tiles.
 * Arrow keys move the selection, Enter keeps it, Escape puts the old choice back.
 */
class ChoicePopup extends Popup {
    private static final float GAP = 6;

    private final ChoiceSetting setting;
    private final int original;
    private final Anim[] hover;
    private final Anim scroll = new Anim(0f, 16f);
    private float scrollTarget;

    ChoicePopup(ChoiceSetting setting) {
        super(setting.label);
        this.setting = setting;
        this.original = setting.index;
        this.hover = new Anim[setting.options.length];
        for (int i = 0; i < hover.length; i++) hover[i] = new Anim(0f, 16f);
    }

    private boolean pictures() { return setting.preview != null; }

    private int n() { return setting.options.length; }

    private float tileW() {
        if (pictures()) return 70;
        float m = 0;
        for (int i = 0; i < n(); i++) m = Math.max(m, R.width(setting.label(i)));
        return Math.max(90, m + 24);
    }

    private float tileH() { return pictures() ? 78 : 24; }

    private int cols() {
        int want = pictures() ? 5 : 3;
        int fit = (int) Math.max(1, (vw - 60 + GAP) / (tileW() + GAP));
        return Math.max(1, Math.min(n(), Math.min(want, fit)));
    }

    private int rows() { return (n() + cols() - 1) / cols(); }

    private float gridH() { return rows() * (tileH() + GAP) - GAP; }

    /** Height of the visible grid (scrolls when the screen is too short for all of it). */
    private float viewH() { return Math.min(gridH(), vh - 40 - HEAD - 18); }

    @Override
    protected float w() { return Math.max(160, cols() * (tileW() + GAP) - GAP + 24); }

    @Override
    protected float h() { return HEAD + 6 + viewH() + 12; }

    private float tileX(int i) { return cx() + (i % cols()) * (tileW() + GAP); }

    private float tileY(int i) { return cy() + (i / cols()) * (tileH() + GAP) - scroll.value; }

    @Override
    protected void render(int mx, int my, float o) {
        float maxScroll = Math.max(0, gridH() - viewH());
        scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
        scroll.update(scrollTarget);
        float tw = tileW(), th = tileH();
        Ui.scissor(cx() - 2, cy() - 2, cw() + 4, viewH() + 4);
        for (int i = 0; i < n(); i++) {
            float x = tileX(i), y = tileY(i);
            if (y + th < cy() - 2 || y > cy() + viewH() + 2) continue;
            boolean sel = i == setting.index;
            boolean over = Ui.within(mx, my, x, y, tw, th) && my >= cy() && my < cy() + viewH();
            float hv = hover[i].update(over ? 1 : 0);
            Ui.card(x, y, tw, th, hv, sel ? 1f : 0f, o);
            if (sel) R.roundedOutline(x, y, tw, th, Ui.rad(5f), 1.5f, R.alpha(Ui.accent, o));
            String label = Ui.trim(setting.label(i), tw - 8);
            if (pictures()) {
                float s = 50;
                setting.preview.draw(i, x + (tw - s) / 2f, y + 6, s, o);
                R.textCentered(label, x + tw / 2f, y + th - 15, R.alpha(sel ? Ui.accent : Ui.text, o));
            } else {
                R.textCentered(label, x + tw / 2f, y + (th - 8) / 2f, R.alpha(sel ? Ui.accent : Ui.text, o));
            }
        }
        Ui.endScissor();
        if (maxScroll > 0) {
            float barH = Math.max(20, viewH() * viewH() / gridH());
            float barY = cy() + (viewH() - barH) * (scroll.value / maxScroll);
            R.rounded(x() + w() - 7, barY, 3, barH, 1.5f, R.alpha(0x70FFFFFF, o));
        }
    }

    @Override
    protected void click(int mx, int my, int button) {
        if (button != 0 || my < cy() || my >= cy() + viewH()) return;
        for (int i = 0; i < n(); i++) {
            if (Ui.inside(mx, my, tileX(i), tileY(i), tileW(), tileH())) {
                setting.index = i;
                close(true);
                return;
            }
        }
    }

    @Override
    void scroll(int mx, int my, int amount) {
        scrollTarget += amount * 30;
    }

    @Override
    protected boolean key(char c, int keyCode) {
        int i = setting.index;
        if (keyCode == Keyboard.KEY_LEFT) i = (i - 1 + n()) % n();
        else if (keyCode == Keyboard.KEY_RIGHT) i = (i + 1) % n();
        else if (keyCode == Keyboard.KEY_UP) i = Math.max(0, i - cols());
        else if (keyCode == Keyboard.KEY_DOWN) i = Math.min(n() - 1, i + cols());
        else if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            close(true);
            return true;
        } else return false;
        setting.index = i;
        // keep the chosen tile in view
        float top = (i / cols()) * (tileH() + GAP);
        if (top < scrollTarget) scrollTarget = top;
        else if (top + tileH() > scrollTarget + viewH()) scrollTarget = top + tileH() - viewH();
        return true;
    }

    @Override
    protected void close(boolean keep) {
        if (!keep) setting.index = original;
        super.close(keep);
    }
}
