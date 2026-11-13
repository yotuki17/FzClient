package dev.fzkc.fzhud.gui;

import dev.fzkc.fzhud.config.PixelGridSetting;
import dev.fzkc.fzhud.hud.Crosshair;
import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Keyboard;

import java.util.HashMap;
import java.util.Map;

/**
 * Draw your own crosshair on a grid of pixels: left click paints, right click erases (both can be dragged). With
 * "symmetric" on, every pixel is mirrored to the other three quarters, so a neat crosshair takes a few clicks.
 */
public class CrosshairEditor extends FzScreen {
    private static final float CELL = 12, PAD = 16, SIDE = 150, HEAD = 46;
    /** Remembered while the game runs. */
    private static boolean mirror = true;

    private final PixelGridSetting grid;
    private final Crosshair crosshair;
    private final GuiScreen parent;
    private final Anim open = new Anim(0f, 12f);
    private final Map<String, Anim> anims = new HashMap<String, Anim>();
    /** 1 = painting, 0 = erasing, -1 = no button held on the grid. */
    private int paint = -1;
    private final boolean[] before;

    public CrosshairEditor(PixelGridSetting grid, Crosshair crosshair, GuiScreen parent) {
        this.grid = grid;
        this.crosshair = crosshair;
        this.parent = parent;
        this.before = grid.cells.clone();
    }

    private int n() { return grid.size; }
    private float gridW() { return n() * CELL; }
    private float W() { return PAD + gridW() + PAD + SIDE + PAD; }
    private float H() { return HEAD + Math.max(gridW(), 100 + 10 + BUTTONS.length * 24 - 4) + PAD; }
    private float px() { return (vw - W()) / 2f; }
    private float py() { return (vh - H()) / 2f; }
    private float gx() { return px() + PAD; }
    private float gy() { return py() + HEAD; }
    private float sx() { return gx() + gridW() + PAD; }

    private Anim anim(String key) {
        Anim a = anims.get(key);
        if (a == null) {
            a = new Anim(0f, 14f);
            anims.put(key, a);
        }
        return a;
    }

    // buttons on the right, under the preview: label key -> y
    private static final String[] BUTTONS = {"mirror", "clear", "invert", "undo", "done"};

    private float buttonY(int i) {
        return gy() + 100 + 10 + i * 24;
    }

    private String label(String b) {
        if (b.equals("mirror")) return dev.fzkc.fzhud.util.Lang.t("上下左右に対称") + (mirror ? "：ON" : "：OFF");
        if (b.equals("clear")) return "すべて消す";
        if (b.equals("invert")) return "塗りを反転";
        if (b.equals("undo")) return "開いた時に戻す";
        return "完了";
    }

    @Override
    protected void render(int mx, int my, float pt) {
        float o = open.update(1f);
        Ui.backdrop(vw, vh, o);
        Ui.panel(px(), py(), W(), H(), o);
        R.textScaled("クロスヘアを作る", px() + PAD, py() + 12, 1.4f, R.alpha(Ui.text, o));
        R.text("左クリックで塗る・右クリックで消す（ドラッグもできる）", px() + PAD, py() + 30, R.alpha(Ui.MUTED, o));

        // the grid
        int n = n(), c = n / 2;
        float gx = gx(), gy = gy();
        R.rect(gx - 1, gy - 1, gridW() + 2, gridW() + 2, R.alpha(0xFF0A1012, o));
        int on = 0xFF000000 | (crosshair.colorRgb() & 0xFFFFFF);
        int hx = (int) Math.floor((mx - gx) / CELL), hy = (int) Math.floor((my - gy) / CELL);
        boolean hover = hx >= 0 && hy >= 0 && hx < n && hy < n;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float cx = gx + x * CELL, cy = gy + y * CELL;
                boolean lit = grid.get(x, y);
                int bg = ((x + y) & 1) == 0 ? 0xFF1A2327 : 0xFF151D21;
                if (x == c || y == c) bg = ((x + y) & 1) == 0 ? 0xFF1F2B30 : 0xFF1A252A;   // the centre lines
                R.rect(cx, cy, CELL - 1, CELL - 1, R.alpha(lit ? on : bg, o));
                if (hover && mirrorHit(x, y, hx, hy)) R.rect(cx, cy, CELL - 1, CELL - 1, R.alpha(0x40FFFFFF, o));
            }
        }
        R.roundedOutline(gx + c * CELL - 1, gy + c * CELL - 1, CELL + 1, CELL + 1, 1f, 1f, R.alpha(Ui.accent, o));

        // preview: real size on a dark and a light background
        float sx = sx(), sy = gy;
        R.rounded(sx, sy, SIDE / 2f, 100, 4f, R.alpha(0xFF2B3A2F, o));
        R.rounded(sx + SIDE / 2f, sy, SIDE / 2f, 100, 4f, R.alpha(0xFFB9C7CF, o));
        R.text("拡大", sx + 4, sy + 4, R.alpha(0xB0FFFFFF, o));
        R.text("実際の大きさ", sx + SIDE / 2f + 4, sy + 4, R.alpha(0xB0101010, o));
        crosshair.drawType(Crosshair.CUSTOM, sx + SIDE / 4f, sy + 54, 2.5f, o);   // big, to see each pixel
        crosshair.drawType(Crosshair.CUSTOM, sx + SIDE * 3 / 4f, sy + 54, crosshair.scale() / Ui.k, o);   // as big as in the game

        for (int i = 0; i < BUTTONS.length; i++) {
            String b = BUTTONS[i];
            float by = buttonY(i);
            float h = anim(b).update(Ui.within(mx, my, sx, by, SIDE, 20) ? 1 : 0);
            if (b.equals("done")) Ui.primaryButton(sx, by, SIDE, 20, label(b), h, o);
            else Ui.button(sx, by, SIDE, 20, label(b), h, o);
        }
    }

    /** Whether cell (x, y) is changed by a click on (hx, hy) (itself, or its mirror images). */
    private boolean mirrorHit(int x, int y, int hx, int hy) {
        if (x == hx && y == hy) return true;
        if (!mirror) return false;
        int m = n() - 1;
        return (x == m - hx || x == hx) && (y == m - hy || y == hy);
    }

    private void apply(int mx, int my) {
        int x = (int) Math.floor((mx - gx()) / CELL), y = (int) Math.floor((my - gy()) / CELL);
        int n = n();
        if (x < 0 || y < 0 || x >= n || y >= n || paint < 0) return;
        boolean v = paint == 1;
        grid.set(x, y, v);
        if (mirror) {
            grid.set(n - 1 - x, y, v);
            grid.set(x, n - 1 - y, v);
            grid.set(n - 1 - x, n - 1 - y, v);
        }
    }

    @Override
    protected void click(int mx, int my, int button) {
        if (Ui.within(mx, my, gx(), gy(), gridW(), gridW())) {
            paint = button == 1 ? 0 : 1;
            UiSound.hit();
            apply(mx, my);
            return;
        }
        for (int i = 0; i < BUTTONS.length; i++) {
            if (!Ui.within(mx, my, sx(), buttonY(i), SIDE, 20) || button != 0) continue;
            UiSound.hit();
            String b = BUTTONS[i];
            if (b.equals("mirror")) mirror = !mirror;
            else if (b.equals("clear")) java.util.Arrays.fill(grid.cells, false);
            else if (b.equals("invert")) for (int k = 0; k < grid.cells.length; k++) grid.cells[k] = !grid.cells[k];
            else if (b.equals("undo")) System.arraycopy(before, 0, grid.cells, 0, before.length);
            else mc.displayGuiScreen(parent);
            return;
        }
    }

    @Override
    protected void drag(int mx, int my) {
        apply(mx, my);
    }

    @Override
    protected void release(int mx, int my) {
        paint = -1;
    }

    @Override
    protected void key(char c, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RETURN) mc.displayGuiScreen(parent);
    }
}
