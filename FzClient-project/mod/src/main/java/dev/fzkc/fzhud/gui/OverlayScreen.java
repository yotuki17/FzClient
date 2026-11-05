package dev.fzkc.fzhud.gui;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.config.Module;
import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.R;
import dev.fzkc.fzhud.util.FrameStats;
import dev.fzkc.fzhud.util.Tracker;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;

/**
 * Alt+X: an overlay on top of the running game (it is not paused and not blurred). Left: what the game is doing
 * right now (FPS, 1% low, frame times, CPU, memory, ping, entities). Right: the switches that change the FPS most,
 * so they can be tried while looking at the numbers.
 */
public class OverlayScreen extends FzScreen {
    /** Opens the overlay together with Alt (Alt+X); the key can be changed in the controls. */
    public static final KeyBinding KEY = new KeyBinding("オーバーレイ（Alt と一緒に押す）", Keyboard.KEY_X, "FzClient");

    public static boolean altDown() {
        return Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
    }

    private final Anim open = new Anim(0f, 10f);

    private static final float PW = 200, PAD = 12, M = 8, ROW = 18;

    /** A switch on the right: a whole module or one of its settings. */
    private static final class Switch {
        final String label;
        final Module module;
        final BoolSetting setting;
        /** The module the setting belongs to: the row is dimmed while that one is off. */
        final Module owner;

        Switch(String label, Module module) { this(label, module, null, null); }
        Switch(String label, BoolSetting setting, Module owner) { this(label, null, setting, owner); }
        private Switch(String label, Module module, BoolSetting setting, Module owner) {
            this.label = label; this.module = module; this.setting = setting; this.owner = owner;
        }

        boolean on() { return module != null ? module.enabled : setting.value; }
        void flip() { if (module != null) module.enabled = !module.enabled; else setting.value = !setting.value; }
        boolean live() { return owner == null || owner.enabled; }
    }

    private static final Switch[] SWITCHES = {
            new Switch("Performance（遠く・壁の向こうを描かない）", Modules.performance),
            new Switch("壁の向こうのモブ・アイテムを描かない", Modules.perfCull, Modules.performance),
            new Switch("雨・雪を描かない", Modules.perfNoWeather, Modules.performance),
            new Switch("雲を描かない", Modules.perfNoClouds, Modules.performance),
            new Switch("Optimization（見た目を変えずに軽く）", Modules.optimize),
            new Switch("ホログラム（浮いている文字）を軽く描く", Modules.optHolograms, Modules.optimize),
            new Switch("エンチャントの光沢を描かない", Modules.optNoGlint, Modules.optimize),
            new Switch("モーションブラー", Modules.motionBlur),
            new Switch("Particles（殴った時の粒子を増やす）", Modules.particles),
    };
    private final Anim[] switchAnim = new Anim[SWITCHES.length];
    private final Anim[] rowHover = new Anim[SWITCHES.length];
    private final Anim allHover = new Anim(0f, 14f);

    // numbers are refreshed a few times a second, not every frame (low1 sorts 1000 values)
    private long statsAt;
    private float fps, low, ms;
    private String entities = "", particles = "";

    @Override
    public void initGui() {
        computeScale();   // no Glass.enter(): the game behind stays sharp, this is for looking at it
        for (int i = 0; i < SWITCHES.length; i++) {
            if (switchAnim[i] == null) switchAnim[i] = new Anim(SWITCHES[i].on() ? 1f : 0f, 14f);
            if (rowHover[i] == null) rowHover[i] = new Anim(0f, 14f);
        }
    }

    private float leftX() { return M; }
    private float rightX() { return vw - M - PW; }
    private float rowY(int i) { return M + 30 + i * ROW; }
    private float allY() { return rowY(SWITCHES.length) + 8; }

    @Override
    protected void render(int mx, int my, float pt) {
        float o = open.update(1f);
        refreshStats();
        drawStats(o);
        drawSwitches(mx, my, o);
        R.textCentered("Alt+X / Esc で閉じる", vw / 2f, vh - 14, R.alpha(Ui.MUTED, o));
    }

    private void refreshStats() {
        FrameStats.sampleCpu();
        long now = System.nanoTime();
        if (now - statsAt < 250_000_000L) return;
        statsAt = now;
        fps = FrameStats.fps();
        low = FrameStats.low1();
        ms = FrameStats.count() > 0 ? FrameStats.ago(0) : 0;
        if (mc.renderGlobal != null) entities = shortEntities(mc.renderGlobal.getDebugInfoEntities());
        if (mc.effectRenderer != null) particles = mc.effectRenderer.getStatistics();
    }

    /** "E: 12/340, B: 5, I: 0" -> "12 / 340". */
    private static String shortEntities(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("E: (\\d+)/(\\d+)").matcher(s == null ? "" : s);
        return m.find() ? m.group(1) + " / " + m.group(2) : "-";
    }

    // ---------------------------------------------------------------- left: numbers

    private void drawStats(float o) {
        float x = leftX(), y = M;
        float tw = (PW - PAD * 2 - 6) / 2f, th = 28, gap = 5;
        float gh = 44;
        float h = 30 + 4 * (th + gap) + 14 + gh + PAD;
        Ui.panel(x, y, PW, h, o);
        R.text("パフォーマンス", x + PAD, y + 11, R.alpha(Ui.accent, o));

        float cores = FrameStats.gameCores(), sys = FrameStats.systemLoad();
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / 1048576L, max = rt.maxMemory() / 1048576L;
        String[][] tiles = {
                {"FPS", Math.round(fps) + "", fpsColor(fps)},
                {"1% Low", low > 0 ? Math.round(low) + "" : "-", fpsColor(low)},
                {"CPU（ゲーム）", cores < 0 ? "-" : String.format("%.1f ", cores) + dev.fzkc.fzhud.util.Lang.t("コア分"), null},
                {"CPU（PC 全体）", sys < 0 ? "-" : Math.round(sys * 100) + "%", null},
                {"メモリ", used + " / " + max + " MB", null},
                {"Ping", Tracker.ping(), null},
                {"エンティティ（描画 / 全部）", entities, null},
                {"パーティクル", particles, null},
        };
        for (int i = 0; i < tiles.length; i++) {
            float tx = x + PAD + (i % 2) * (tw + 6), ty = y + 30 + (i / 2) * (th + gap);
            Ui.card(tx, ty, tw, th, 0f, 0f, o);
            R.text(Ui.trim(dev.fzkc.fzhud.util.Lang.t(tiles[i][0]), tw - 8), tx + 5, ty + 4, R.alpha(Ui.MUTED, o));
            int c = tiles[i][2] != null ? Integer.parseInt(tiles[i][2]) : Ui.text;
            R.text(Ui.trim(tiles[i][1], tw - 8), tx + 5, ty + 16, R.alpha(c, o));
        }
        // "1.0 コア分" is one core kept fully busy: 1.8.9 draws on one thread, so with many cores the
        // task manager shows only a few percent for it
        float gy = y + 30 + 4 * (th + gap);
        R.text(dev.fzkc.fzhud.util.Lang.t("フレーム時間") + String.format("  %.1f ms", ms), x + PAD, gy + 1, R.alpha(Ui.MUTED, o));
        drawGraph(x + PAD, gy + 13, PW - PAD * 2, gh, o);
    }

    private static String fpsColor(float f) {
        int c = f <= 0 ? Ui.MUTED : f >= 120 ? 0xFF55FF55 : f >= 60 ? 0xFFC6F35A : f >= 30 ? 0xFFFFAA00 : 0xFFFF5555;
        return Integer.toString(c);
    }

    /** One bar per frame, newest on the right; the line is 60 FPS (16.7 ms). */
    private void drawGraph(float x, float y, float w, float h, float o) {
        R.rounded(x, y, w, h, Ui.rad(4f), R.alpha(0x50000000, o));
        int n = Math.min(FrameStats.count(), (int) w);
        float top = 33.3f;   // 30 FPS at the top
        for (int i = 0; i < n; i++) if (FrameStats.ago(i) > top) top = Math.min(100f, FrameStats.ago(i));
        R.batchBegin();
        for (int i = 0; i < n; i++) {
            float v = FrameStats.ago(i);
            float bh = Math.max(1f, Math.min(h - 2, v / top * (h - 2)));
            int c = v <= 8.4f ? 0xFF55FF55 : v <= 16.8f ? 0xFFC6F35A : v <= 33.4f ? 0xFFFFAA00 : 0xFFFF5555;
            R.batchRect(x + w - 1 - i, y + h - 1 - bh, 1, bh, R.alpha(c & 0xB0FFFFFF, o));
        }
        float line = y + h - 1 - 16.7f / top * (h - 2);
        R.batchRect(x, line, w, 0.5f, R.alpha(0x60FFFFFF, o));
        R.batchEnd();
        R.text("60", x + 2, line - 9, R.alpha(Ui.MUTED, o * 0.8f));
    }

    // ---------------------------------------------------------------- right: switches

    private void drawSwitches(int mx, int my, float o) {
        float x = rightX(), y = M;
        float h = allY() + 22 + PAD - M;
        Ui.panel(x, y, PW, h, o);
        R.text("クイック設定", x + PAD, y + 11, R.alpha(Ui.accent, o));
        for (int i = 0; i < SWITCHES.length; i++) {
            Switch s = SWITCHES[i];
            float ry = rowY(i);
            float hv = rowHover[i].update(Ui.inside(mx, my, x + 4, ry - 1, PW - 8, ROW) ? 1 : 0);
            if (hv > 0.01f) R.rounded(x + 4, ry - 1, PW - 8, ROW, Ui.rad(4f), R.alpha(Ui.accentA((int) (0x22 * hv)), o));
            float a = s.live() ? o : o * 0.45f;
            // settings of a module are indented under it
            float tx = x + PAD + (s.setting != null ? 8 : 0);
            R.text(Ui.trim(dev.fzkc.fzhud.util.Lang.t(s.label), PW - PAD * 2 - 32 - (tx - x - PAD)), tx, ry + 4, R.alpha(Ui.text, a));
            Ui.toggle(x + PW - PAD - 24, ry + 2, switchAnim[i].update(s.on() ? 1 : 0), a);
        }
        float bx = x + PAD, by = allY(), bw = PW - PAD * 2;
        Ui.button(bx, by, bw, 20, "すべての設定を開く", allHover.update(Ui.inside(mx, my, bx, by, bw, 20) ? 1 : 0), o);
    }

    // ---------------------------------------------------------------- input

    @Override
    protected void click(int mx, int my, int button) {
        if (button != 0) return;
        float x = rightX();
        for (int i = 0; i < SWITCHES.length; i++) {
            if (Ui.inside(mx, my, x + 4, rowY(i) - 1, PW - 8, ROW)) {
                SWITCHES[i].flip();
                return;
            }
        }
        if (Ui.inside(mx, my, x + PAD, allY(), PW - PAD * 2, 20)) mc.displayGuiScreen(new ModsScreen(null));
    }

    @Override
    protected void key(char c, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE || (keyCode == KEY.getKeyCode() && keyCode != 0)) mc.displayGuiScreen(null);
    }
}
