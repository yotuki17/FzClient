package dev.fzkc.fzhud.gui;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.config.Module;
import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.R;
import dev.fzkc.fzhud.util.FrameStats;
import dev.fzkc.fzhud.util.Lang;
import dev.fzkc.fzhud.util.Tracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.settings.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

/**
 * Alt+X: an overlay that opens anywhere - in the game, on the title screen, over the inventory, a chest menu, the
 * pause menu... Left: what the game is doing right now (FPS, 1% low, frame times, CPU, memory, ping, entities).
 * Right: the switches that change the FPS most. It looks like the launcher (same colours, cards and switches).
 *
 * Over another screen it is laid on top instead of replacing it: Minecraft's displayGuiScreen would close the
 * screen below first, and for a chest menu that also closes it on the server. The screen below keeps being drawn
 * and updated, and closing the overlay hands it back untouched.
 */
public class OverlayScreen extends FzScreen {
    /** Opens the overlay together with Alt (Alt+X); the key can be changed in the controls. */
    public static final KeyBinding KEY = new KeyBinding("オーバーレイ（Alt と一緒に押す）", Keyboard.KEY_X, "FzClient");

    public static boolean altDown() {
        return Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
    }

    /** The screen it was opened over, or null in the game. */
    private final GuiScreen parent;
    /** The key repeat setting of that screen (the chat wants it on), put back when closing. */
    private boolean repeat;

    private OverlayScreen(GuiScreen parent) {
        this.parent = parent;
    }

    /** Opens the overlay over whatever is shown now. */
    public static void open(Minecraft mc) {
        GuiScreen cur = mc.currentScreen;
        if (cur instanceof OverlayScreen) return;
        if (cur == null) {
            mc.displayGuiScreen(new OverlayScreen(null));
            return;
        }
        OverlayScreen o = new OverlayScreen(cur);
        o.repeat = Keyboard.areRepeatEventsEnabled();
        ScaledResolution sr = new ScaledResolution(mc);
        o.setWorldAndResolution(mc, sr.getScaledWidth(), sr.getScaledHeight());
        mc.currentScreen = o;   // on top, without closing the screen below (see the class comment)
    }

    /** Back to the game, or to the screen below exactly as it was. */
    public void close() {
        if (parent == null) {
            mc.displayGuiScreen(null);
            return;
        }
        onGuiClosed();   // saves the switches
        Keyboard.enableRepeatEvents(repeat);
        mc.currentScreen = parent;
    }

    // ---------------------------------------------------------------- the launcher's look

    private static final int PANEL = 0xEB0F1A1E, CARD = 0xC8162429, LINE = 0x2E7FD8DC, TEXT = 0xFFE6F7F8,
            MUTED = 0xFF8FA6AA, CYAN = 0xFF5CE1E6, VIOLET = 0xFF9B8FF0, MAGENTA = 0xFFE83ECE;

    private static void panel(float x, float y, float w, float h, float o) {
        R.roundedWithOutline(x, y, w, h, 8f, R.alpha(PANEL, o), 1f, R.alpha(LINE, o));
    }

    private static void card(float x, float y, float w, float h, float hover, float o) {
        R.roundedWithOutline(x, y, w, h, 6f, R.alpha(CARD, o), 1f, R.alpha(R.lerpColor(LINE, 0x8C5CE1E6, hover), o));
    }

    /** Panel title with the launcher's cyan - violet - magenta line under it. */
    private static void heading(String text, float x, float y, float w, float o) {
        R.text(text, x, y, R.alpha(TEXT, o));
        float lw = Math.min(w, R.width(text) + 6);
        Ui.gradient(x, y + 11, lw / 2f, 1f, R.alpha(CYAN, o), R.alpha(VIOLET, o), R.alpha(CYAN, o), R.alpha(VIOLET, o));
        Ui.gradient(x + lw / 2f, y + 11, lw / 2f, 1f, R.alpha(VIOLET, o), R.alpha(MAGENTA, o), R.alpha(VIOLET, o), R.alpha(MAGENTA, o));
    }

    /** The launcher's pill switch: grey track, cyan when on, white knob sliding over. */
    private static void pill(float x, float y, float t, float o) {
        R.rounded(x, y, 20, 11, 5.5f, R.alpha(R.lerpColor(0x26FFFFFF, CYAN, t), o));
        R.rounded(x + 1.5f + t * 9f, y + 1.5f, 8, 8, 4f, R.alpha(0xFFFFFFFF, o));
    }

    private static void button(float x, float y, float w, float h, String label, float hover, float o) {
        R.roundedWithOutline(x, y, w, h, 6f, R.alpha(0xD9162429, o), 1f, R.alpha(R.lerpColor(LINE, CYAN, hover), o));
        R.textCentered(label, x + w / 2f, y + (h - 8) / 2f, R.alpha(TEXT, o));
    }

    // ---------------------------------------------------------------- the switches on the right

    /** A whole module or one of its settings. */
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

    private final Anim open = new Anim(0f, 10f);
    private final Anim[] switchAnim = new Anim[SWITCHES.length];
    private final Anim[] rowHover = new Anim[SWITCHES.length];
    private final Anim allHover = new Anim(0f, 14f);

    private static final float PW = 210, PAD = 12, M = 10, ROW = 17, ROW_GAP = 3;

    private float leftX() { return M; }
    private float rightX() { return vw - M - PW; }
    private float rowY(int i) { return M + 32 + i * (ROW + ROW_GAP); }
    private float allY() { return rowY(SWITCHES.length) + 4; }

    // numbers are refreshed a few times a second, not every frame (low1 sorts 1000 values)
    private long statsAt;
    private float fps, low, ms;
    private String entities = "-", particles = "-";

    @Override
    public void initGui() {
        computeScale();   // no Glass.enter(): what is behind stays sharp, this is for looking at it
        for (int i = 0; i < SWITCHES.length; i++) {
            if (switchAnim[i] == null) switchAnim[i] = new Anim(SWITCHES[i].on() ? 1f : 0f, 14f);
            if (rowHover[i] == null) rowHover[i] = new Anim(0f, 14f);
        }
    }

    @Override
    public void setWorldAndResolution(Minecraft mc, int width, int height) {
        super.setWorldAndResolution(mc, width, height);
        // the window was resized while open: the screen below has to be laid out again too
        if (parent != null && (parent.width != width || parent.height != height)) parent.setWorldAndResolution(mc, width, height);
    }

    /** Over the pause menu the game stays paused; in the game it keeps running. */
    @Override
    public boolean doesGuiPauseGame() {
        return parent != null && parent.doesGuiPauseGame();
    }

    @Override
    public void updateScreen() {
        if (parent != null) parent.updateScreen();   // the screen below keeps running (timers, server pings...)
    }

    @Override
    protected void drawBehind(int mx, int my, float pt) {
        if (parent == null) return;
        parent.drawScreen(-1000, -1000, pt);   // no hover effects below
        // what screens below leave switched on (items in a chest menu turn on lighting and depth)
        RenderHelper.disableStandardItemLighting();
        GlStateManager.disableLighting();
        GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT);
        GlStateManager.enableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    @Override
    protected void render(int mx, int my, float pt) {
        R.noShadow = true;   // flat text like the launcher (FzScreen puts it back)
        float o = open.update(1f);
        // a dark veil like the launcher's dialogs: lighter in the game, so it stays visible
        R.rect(0, 0, vw, vh, R.alpha(parent != null ? 0x80000000 : 0x40000000, o));
        refreshStats();
        drawStats(o);
        drawSwitches(mx, my, o);
        R.textCentered("Alt+X / Esc で閉じる", vw / 2f, vh - 16, R.alpha(MUTED, o));
    }

    private void refreshStats() {
        FrameStats.sampleCpu();
        long now = System.nanoTime();
        if (now - statsAt < 250_000_000L) return;
        statsAt = now;
        fps = FrameStats.fps();
        low = FrameStats.low1();
        ms = FrameStats.count() > 0 ? FrameStats.ago(0) : 0;
        boolean world = mc.theWorld != null;
        entities = world && mc.renderGlobal != null ? shortEntities(mc.renderGlobal.getDebugInfoEntities()) : "-";
        particles = world && mc.effectRenderer != null ? mc.effectRenderer.getStatistics() : "-";
    }

    /** "E: 12/340, B: 5, I: 0" -> "12 / 340". */
    private static String shortEntities(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("E: (\\d+)/(\\d+)").matcher(s == null ? "" : s);
        return m.find() ? m.group(1) + " / " + m.group(2) : "-";
    }

    // ---------------------------------------------------------------- left: the numbers

    private void drawStats(float o) {
        float x = leftX(), y = M;
        float tw = (PW - PAD * 2 - 6) / 2f, th = 29, gap = 5, gh = 46;
        float h = 32 + 4 * (th + gap) + 14 + gh + PAD;
        panel(x, y, PW, h, o);
        heading(Lang.t("パフォーマンス"), x + PAD, y + 10, PW - PAD * 2, o);

        float cores = FrameStats.gameCores(), sys = FrameStats.systemLoad();
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) / 1048576L, max = rt.maxMemory() / 1048576L;
        String[] label = {"FPS", "1% Low", "CPU（ゲーム）", "CPU（PC 全体）", "メモリ", "Ping", "エンティティ（描画 / 全部）", "パーティクル"};
        String[] value = {
                Integer.toString(Math.round(fps)),
                low > 0 ? Integer.toString(Math.round(low)) : "-",
                // cores kept busy: 1.8.9 draws on one thread, so on a many-core CPU the task manager shows a few %
                cores < 0 ? "-" : String.format("%.1f ", cores) + Lang.t("コア分"),
                sys < 0 ? "-" : Math.round(sys * 100) + "%",
                used + " / " + max + " MB",
                mc.theWorld != null ? Tracker.ping() : "-",
                entities,
                particles,
        };
        int[] color = {fpsColor(fps), fpsColor(low), TEXT, TEXT, TEXT, TEXT, TEXT, TEXT};
        for (int i = 0; i < label.length; i++) {
            float tx = x + PAD + (i % 2) * (tw + 6), ty = y + 32 + (i / 2) * (th + gap);
            card(tx, ty, tw, th, 0f, o);
            R.text(Ui.trim(Lang.t(label[i]), tw - 10), tx + 6, ty + 5, R.alpha(MUTED, o));
            if (i < 2) R.textScaled(value[i], tx + 6, ty + 15, 1.15f, R.alpha(color[i], o));
            else R.text(Ui.trim(value[i], tw - 10), tx + 6, ty + 17, R.alpha(color[i], o));
        }
        float gy = y + 32 + 4 * (th + gap);
        R.text(Lang.t("フレーム時間") + String.format("  %.1f ms", ms), x + PAD, gy + 1, R.alpha(MUTED, o));
        drawGraph(x + PAD, gy + 13, PW - PAD * 2, gh, o);
    }

    private static int fpsColor(float f) {
        return f <= 0 ? MUTED : f >= 120 ? CYAN : f >= 60 ? 0xFFC6F35A : f >= 30 ? 0xFFFFB454 : 0xFFFF6B6B;
    }

    /** One bar per frame, newest on the right; the line is 60 FPS (16.7 ms). */
    private void drawGraph(float x, float y, float w, float h, float o) {
        R.roundedWithOutline(x, y, w, h, 6f, R.alpha(0x99050A0C, o), 1f, R.alpha(LINE, o));
        int n = Math.min(FrameStats.count(), (int) (w - 4));
        float top = 33.3f;   // 30 FPS at the top, more when a frame took longer
        for (int i = 0; i < n; i++) if (FrameStats.ago(i) > top) top = Math.min(100f, FrameStats.ago(i));
        float gh = h - 4;
        R.batchBegin();
        for (int i = 0; i < n; i++) {
            float v = FrameStats.ago(i);
            float bh = Math.max(1f, Math.min(gh, v / top * gh));
            int c = v <= 8.4f ? CYAN : v <= 16.8f ? VIOLET : v <= 33.4f ? 0xFFFFB454 : 0xFFFF6B6B;
            R.batchRect(x + w - 3 - i, y + 2 + gh - bh, 1, bh, R.alpha((c & 0xFFFFFF) | 0xC0000000, o));
        }
        float line = y + 2 + gh - 16.7f / top * gh;
        R.batchRect(x + 2, line, w - 4, 0.5f, R.alpha(0x66FFFFFF, o));
        R.batchEnd();
        R.text("60", x + 4, line - 9, R.alpha(MUTED, o));
    }

    // ---------------------------------------------------------------- right: the switches

    private void drawSwitches(int mx, int my, float o) {
        float x = rightX(), y = M;
        float h = allY() + 20 + PAD - M;
        panel(x, y, PW, h, o);
        heading(Lang.t("クイック設定"), x + PAD, y + 10, PW - PAD * 2, o);
        for (int i = 0; i < SWITCHES.length; i++) {
            Switch s = SWITCHES[i];
            // settings of a module sit indented under it, like the launcher's groups
            float indent = s.setting != null ? 8 : 0;
            float rx = x + PAD + indent, ry = rowY(i), rw = PW - PAD * 2 - indent;
            float hv = rowHover[i].update(Ui.inside(mx, my, rx, ry, rw, ROW) ? 1 : 0);
            float a = s.live() ? o : o * 0.45f;
            card(rx, ry, rw, ROW, hv, a);
            R.text(Ui.trim(Lang.t(s.label), rw - 36), rx + 6, ry + 5, R.alpha(TEXT, a));
            pill(rx + rw - 26, ry + 3, switchAnim[i].update(s.on() ? 1 : 0), a);
        }
        float bx = x + PAD, by = allY(), bw = PW - PAD * 2;
        button(bx, by, bw, 20, Lang.t("すべての設定を開く"), allHover.update(Ui.inside(mx, my, bx, by, bw, 20) ? 1 : 0), o);
    }

    // ---------------------------------------------------------------- input

    @Override
    protected void click(int mx, int my, int button) {
        if (button != 0) return;
        float x = rightX();
        for (int i = 0; i < SWITCHES.length; i++) {
            float indent = SWITCHES[i].setting != null ? 8 : 0;
            if (Ui.inside(mx, my, x + PAD + indent, rowY(i), PW - PAD * 2 - indent, ROW)) {
                SWITCHES[i].flip();
                return;
            }
        }
        if (Ui.inside(mx, my, x + PAD, allY(), PW - PAD * 2, 20)) mc.displayGuiScreen(new ModsScreen(parent));
    }

    @Override
    protected void key(char c, int keyCode) {
        // Alt+X itself is handled before this (FzHud.onOverlayKey), so only Esc is left here
        if (keyCode == Keyboard.KEY_ESCAPE) close();
    }
}
