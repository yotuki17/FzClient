package dev.fzkc.fzhud.gui;

import dev.fzkc.fzhud.config.ColorSetting;
import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

/**
 * Colour picker window: saturation/brightness square, hue bar, HEX box, chroma, presets.
 * Changes show right away; OK (or Enter, or clicking outside) keeps them, Cancel / Escape puts the old colour back.
 */
class ColorPopup extends Popup {
    static final int[] PRESETS = {0x5CE1E6, 0xFFFFFF, 0xFF5555, 0x22C55E, 0xFFD84A, 0xFF6AD5,
            0xA78BFA, 0xFF9F43, 0x3B82F6, 0x111111, 0x9CA3AF};
    private static final float SQ_W = 160, SQ_H = 96, HUE_W = 12, COL_W = 100, PRE = 18;

    private final ColorSetting c;
    private final int origRgb;
    private final boolean origChroma;
    private float hue;
    private boolean dragSV, dragHue;

    // HEX box
    private boolean typing;
    private String buffer = "";
    private int cursor;

    private final Anim okHover = new Anim(0f, 14f), cancelHover = new Anim(0f, 14f), chromaHover = new Anim(0f, 14f);

    ColorPopup(ColorSetting c) {
        super(c.label);
        this.c = c;
        this.origRgb = c.rgb;
        this.origChroma = c.chroma;
        this.hue = c.hsb()[0];
        // start with the HEX box ready, so a colour code can be typed or pasted straight away
        typing = true;
        buffer = c.chroma ? "" : c.hex();
        cursor = buffer.length();
    }

    @Override
    protected float w() { return 12 + SQ_W + 8 + HUE_W + 14 + COL_W + 12; }

    @Override
    protected float h() { return HEAD + 6 + SQ_H + 10 + 12 + PRE + 12 + 18 + 10; }

    // layout
    private float sqX() { return cx(); }
    private float sqY() { return cy(); }
    private float hueX() { return sqX() + SQ_W + 8; }
    private float colX() { return hueX() + HUE_W + 14; }
    private float[] hexRect() { return new float[]{colX(), sqY() + 52, COL_W, 18}; }
    private float[] chromaRect() { return new float[]{colX(), sqY() + 76, COL_W, 18}; }
    private float preY() { return sqY() + SQ_H + 10 + 12; }
    private float preX(int i) { return cx() + i * (PRE + 4); }
    private float btnY() { return preY() + PRE + 12; }
    private float btnW() { return 70; }
    private float[] okRect() { return new float[]{x() + w() - 12 - btnW(), btnY(), btnW(), 18}; }
    private float[] cancelRect() { return new float[]{x() + w() - 12 - btnW() * 2 - 8, btnY(), btnW(), 18}; }

    @Override
    protected void render(int mx, int my, float o) {
        float[] hsb = c.hsb();
        int pure = java.awt.Color.HSBtoRGB(hue, 1f, 1f) | 0xFF000000;
        float bx = sqX(), by = sqY();
        // saturation / brightness square
        Ui.gradient(bx, by, SQ_W, SQ_H, R.alpha(0xFFFFFFFF, o), R.alpha(pure, o), R.alpha(0xFF000000, o), R.alpha(0xFF000000, o));
        Ui.gradient(bx, by, SQ_W, SQ_H, 0x00000000, 0x00000000, R.alpha(0xFF000000, o), R.alpha(0xFF000000, o));
        R.roundedOutline(bx, by, SQ_W, SQ_H, 0.01f, 1f, R.alpha(0x40FFFFFF, o));
        if (!c.chroma) {
            float kx = bx + hsb[1] * SQ_W, ky = by + (1 - hsb[2]) * SQ_H;
            R.roundedOutline(kx - 4, ky - 4, 8, 8, 4f, 1.5f, R.alpha(0xFFFFFFFF, o));
            R.roundedOutline(kx - 5, ky - 5, 10, 10, 5f, 1f, R.alpha(0x80000000, o));
        }
        // hue bar
        float hx = hueX();
        for (int i = 0; i < 6; i++) {
            int a = java.awt.Color.HSBtoRGB(i / 6f, 1f, 1f) | 0xFF000000;
            int b = java.awt.Color.HSBtoRGB((i + 1) / 6f, 1f, 1f) | 0xFF000000;
            Ui.gradient(hx, by + i * SQ_H / 6f, HUE_W, SQ_H / 6f, R.alpha(a, o), R.alpha(a, o), R.alpha(b, o), R.alpha(b, o));
        }
        float hy = by + hue * SQ_H;
        R.rect(hx - 2, hy - 1, HUE_W + 4, 2, R.alpha(0xFFFFFFFF, o));

        // new / old colour
        float px = colX();
        String after = "変更後", before = "変更前";
        R.text(after, px, by, R.alpha(Ui.MUTED, o));
        R.text(before, px + COL_W - R.width(before), by, R.alpha(Ui.MUTED, o));
        R.rounded(px, by + 11, COL_W / 2f, 26, 3f, R.alpha(c.get(), o));
        int old = origChroma ? R.CHROMA : (0xFF000000 | origRgb);
        R.rounded(px + COL_W / 2f, by + 11, COL_W / 2f, 26, 3f, R.alpha(old, o));
        R.roundedOutline(px, by + 11, COL_W, 26, 3f, 1f, R.alpha(0x60FFFFFF, o));

        // HEX box
        float[] hr = hexRect();
        String txt = typing ? buffer : (c.chroma ? "" : c.hex());
        Ui.textBox(hr[0], hr[1], hr[2], hr[3], txt, c.chroma ? "クロマ" : "#RRGGBB", typing, typing ? clamp(cursor, txt) : txt.length(), o);
        if (typing && !buffer.isEmpty() && !valid(buffer)) {
            R.roundedOutline(hr[0], hr[1], hr[2], hr[3], Ui.rad(3f), 1f, R.alpha(0xFFFF5555, o));
        }

        // chroma
        float[] cr = chromaRect();
        boolean chOver = Ui.within(mx, my, cr[0], cr[1], cr[2], cr[3]);
        float ch = chromaHover.update(chOver ? 1 : 0);
        R.rounded(cr[0], cr[1], cr[2], cr[3], 9f, R.alpha(c.chroma ? 0xFF1C2D33 : R.lerpColor(0xFF111A1D, 0xFF1A2428, ch), o));
        R.roundedOutline(cr[0], cr[1], cr[2], cr[3], 9f, 1f, R.alpha(c.chroma ? Ui.accent : 0x50FFFFFF, o));
        R.textCentered("クロマ", cr[0] + cr[2] / 2f, cr[1] + 5, c.chroma ? R.alpha(R.CHROMA, o) : R.alpha(Ui.MUTED, o));

        // presets
        R.text("おすすめ", cx(), preY() - 11, R.alpha(Ui.MUTED, o));
        for (int i = 0; i < PRESETS.length; i++) {
            float x = preX(i), y = preY();
            boolean sel = !c.chroma && c.rgb == PRESETS[i];
            R.rounded(x, y, PRE, PRE, 4f, R.alpha(0xFF000000 | PRESETS[i], o));
            R.roundedOutline(x, y, PRE, PRE, 4f, sel ? 1.5f : 1f, R.alpha(sel ? Ui.accent : 0x60FFFFFF, o));
        }

        // buttons
        float[] cn = cancelRect(), ok = okRect();
        Ui.button(cn[0], cn[1], cn[2], cn[3], "キャンセル", cancelHover.update(Ui.within(mx, my, cn[0], cn[1], cn[2], cn[3]) ? 1 : 0), o);
        Ui.primaryButton(ok[0], ok[1], ok[2], ok[3], "OK", okHover.update(Ui.within(mx, my, ok[0], ok[1], ok[2], ok[3]) ? 1 : 0), o);
    }

    // ---------------------------------------------------------------- input

    @Override
    protected void click(int mx, int my, int button) {
        float[] hr = hexRect();
        if (Ui.within(mx, my, hr[0], hr[1], hr[2], hr[3])) {
            if (button == 1) {
                setBuffer("");
                cursor = 0;
                typing = true;
                return;
            }
            if (!typing) {
                buffer = c.chroma ? "" : c.hex();
                cursor = buffer.length();
            }
            typing = true;
            cursor = Ui.cursorFromX(buffer, hr[0], hr[2], clamp(cursor, buffer), mx);
            return;
        }
        if (button != 0) return;
        typing = false;
        float[] cr = chromaRect(), ok = okRect(), cn = cancelRect();
        if (Ui.within(mx, my, sqX(), sqY(), SQ_W, SQ_H)) {
            dragSV = true;
            setSV(mx, my);
        } else if (Ui.within(mx, my, hueX() - 2, sqY(), HUE_W + 4, SQ_H)) {
            dragHue = true;
            setHue(my);
        } else if (Ui.inside(mx, my, cr[0], cr[1], cr[2], cr[3])) {
            c.chroma = !c.chroma;
        } else if (Ui.inside(mx, my, ok[0], ok[1], ok[2], ok[3])) {
            close(true);
        } else if (Ui.inside(mx, my, cn[0], cn[1], cn[2], cn[3])) {
            UiSound.cancel();
            close(false);
        } else {
            for (int i = 0; i < PRESETS.length; i++) {
                if (Ui.inside(mx, my, preX(i), preY(), PRE, PRE)) {
                    c.rgb = PRESETS[i];
                    c.chroma = false;
                    hue = c.hsb()[0];
                }
            }
        }
    }

    private void setSV(int mx, int my) {
        float s = Math.max(0, Math.min(1, (mx - sqX()) / SQ_W));
        float b = 1 - Math.max(0, Math.min(1, (my - sqY()) / SQ_H));
        c.setHsb(hue, s, b);
    }

    private void setHue(int my) {
        hue = Math.max(0, Math.min(0.999f, (my - sqY()) / SQ_H));
        float[] hsb = c.hsb();
        float sat = c.chroma || hsb[1] < 0.05f ? 1f : hsb[1];
        float bri = c.chroma || hsb[2] < 0.05f ? 1f : hsb[2];
        c.setHsb(hue, sat, bri);
    }

    @Override
    void drag(int mx, int my) {
        if (dragSV) setSV(mx, my);
        if (dragHue) setHue(my);
    }

    @Override
    void release(int mx, int my) {
        dragSV = false;
        dragHue = false;
    }

    // ---------------------------------------------------------------- HEX typing

    private static int clamp(int cur, String t) {
        return Math.max(0, Math.min(cur, t.length()));
    }

    private static boolean valid(String s) {
        String t = s.trim();
        if (t.startsWith("#")) t = t.substring(1);
        if (t.equalsIgnoreCase("chroma") || t.equalsIgnoreCase("rainbow")) return true;
        if (t.length() != 3 && t.length() != 6 && t.length() != 8) return false;
        for (char ch : t.toCharArray()) if (Character.digit(ch, 16) < 0) return false;
        return true;
    }

    /** Changes the HEX text and, as soon as it is a complete colour, applies it (so the preview follows the typing). */
    private void setBuffer(String s) {
        buffer = s;
        if (valid(s) && c.setHex(s) && !c.chroma) {
            float[] hsb = c.hsb();
            if (hsb[1] > 0.02f) hue = hsb[0];
        }
    }

    private void insert(String text) {
        if (text == null) return;
        String t = buffer;
        int cur = clamp(cursor, t);
        for (char ch : text.toCharArray()) {
            if (t.length() >= 9) break;
            if (ch != '#' && Character.digit(ch, 16) < 0 && !Character.isLetter(ch)) continue;
            t = t.substring(0, cur) + ch + t.substring(cur);
            cur++;
        }
        setBuffer(t);
        cursor = cur;
    }

    @Override
    protected boolean key(char ch, int keyCode) {
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            if (typing && !buffer.isEmpty() && !valid(buffer)) return true;  // not a colour yet: stay open
            close(true);
            return true;
        }
        if (!typing) {
            // start typing a colour code straight away
            if (ch == '#' || Character.digit(ch, 16) >= 0 || (GuiScreen.isCtrlKeyDown() && keyCode == Keyboard.KEY_V)) {
                typing = true;
                buffer = "";
                cursor = 0;
            } else return false;
        }
        String t = buffer;
        cursor = clamp(cursor, t);
        if (keyCode == Keyboard.KEY_ESCAPE) return false;   // cancel the whole window
        if (keyCode == Keyboard.KEY_LEFT) cursor = Math.max(0, cursor - 1);
        else if (keyCode == Keyboard.KEY_RIGHT) cursor = Math.min(t.length(), cursor + 1);
        else if (keyCode == Keyboard.KEY_HOME) cursor = 0;
        else if (keyCode == Keyboard.KEY_END) cursor = t.length();
        else if (keyCode == Keyboard.KEY_DELETE) {
            if (cursor < t.length()) setBuffer(t.substring(0, cursor) + t.substring(cursor + 1));
        } else if (keyCode == Keyboard.KEY_BACK) {
            if (cursor > 0) {
                setBuffer(t.substring(0, cursor - 1) + t.substring(cursor));
                cursor--;
            }
        } else if (GuiScreen.isCtrlKeyDown() && keyCode == Keyboard.KEY_V) {
            insert(GuiScreen.getClipboardString());
        } else if (GuiScreen.isCtrlKeyDown() && keyCode == Keyboard.KEY_C) {
            GuiScreen.setClipboardString(c.chroma ? "chroma" : c.hex());
        } else if (ch >= ' ') {
            insert(String.valueOf(ch));
        }
        return true;
    }

    @Override
    protected void close(boolean keep) {
        if (!keep) {
            c.rgb = origRgb;
            c.chroma = origChroma;
        }
        super.close(keep);
    }
}
