package dev.fzkc.fzhud.gui;

import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.R;

/**
 * A small window that opens on top of a menu (the option picker, the colour picker).
 * The menu behind is dimmed and does not react while the window is open.
 * Clicking outside the window closes it.
 */
abstract class Popup {
    protected static final float HEAD = 26;

    /** Virtual screen size, set by the owner before every call. */
    protected int vw, vh;
    protected final String title;
    private final Anim open = new Anim(0f, 14f);
    private final Anim closeHover = new Anim(0f, 14f);
    boolean closed;

    protected Popup(String title) {
        this.title = title;
    }

    protected abstract float w();

    protected abstract float h();

    protected float x() { return Math.round((vw - w()) / 2f); }

    protected float y() { return Math.round((vh - h()) / 2f); }

    /** Content area (below the title bar). */
    protected float cx() { return x() + 12; }

    protected float cy() { return y() + HEAD + 6; }

    protected float cw() { return w() - 24; }

    final void draw(int mx, int my, float alpha) {
        float o = open.update(1f) * alpha;
        R.rect(0, 0, vw, vh, R.alpha(0x70000000, o));
        float x = x(), y = y(), w = w();
        Ui.panel(x, y, w, h(), o);
        R.textScaled(title, x + 12, y + 10, 1.25f, R.alpha(Ui.text, o));
        float[] cr = closeRect();
        boolean over = Ui.within(mx, my, cr[0], cr[1], cr[2], cr[3]);
        Ui.button(cr[0], cr[1], cr[2], cr[3], "×", closeHover.update(over ? 1 : 0), o);
        R.rect(x + 12, y + HEAD, w - 24, 1, R.alpha(Ui.LINE, o));
        render(mx, my, o);
    }

    private float[] closeRect() {
        return new float[]{x() + w() - 26, y() + 7, 18, 16};
    }

    final void mouseDown(int mx, int my, int button) {
        float[] cr = closeRect();
        if (button == 0 && Ui.inside(mx, my, cr[0], cr[1], cr[2], cr[3])) {
            close(true);
            return;
        }
        if (!Ui.within(mx, my, x(), y(), w(), h())) {
            close(true);
            return;
        }
        click(mx, my, button);
    }

    /** Escape: closes without keeping changes (unless a subclass uses Escape itself). */
    void keyDown(char c, int keyCode) {
        if (key(c, keyCode)) return;
        if (keyCode == org.lwjgl.input.Keyboard.KEY_ESCAPE) close(false);
    }

    /** keep = false means "cancel": put things back the way they were when the window opened. */
    protected void close(boolean keep) {
        closed = true;
    }

    protected abstract void render(int mx, int my, float o);

    protected void click(int mx, int my, int button) {}

    void drag(int mx, int my) {}

    void release(int mx, int my) {}

    void scroll(int mx, int my, int amount) {}

    /** Return true when the key was used. */
    protected boolean key(char c, int keyCode) { return false; }
}
