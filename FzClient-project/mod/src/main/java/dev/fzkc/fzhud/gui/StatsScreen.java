package dev.fzkc.fzhud.gui;

import dev.fzkc.fzhud.cosmetic.PlayerPreview;
import dev.fzkc.fzhud.cosmetic.SkinCache;
import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.R;
import dev.fzkc.fzhud.stats.Game;
import dev.fzkc.fzhud.stats.History;
import dev.fzkc.fzhud.stats.PlayerData;
import dev.fzkc.fzhud.stats.StatsApi;
import dev.fzkc.fzhud.stats.StatsCommand;
import dev.fzkc.fzhud.stats.Tags;
import dev.fzkc.fzhud.util.Lang;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /pv: a player's stats in a window.
 * <ul>
 *   <li>Left: the player's skin (drag to turn it), name, where the numbers come from, tags, and a search box.</li>
 *   <li>Top: the games as tabs with their item; under them the mode (a list that opens) and the period.</li>
 *   <li>Body: single numbers (the star, a streak) as tiles; every "A, B, A÷B" row (wins, losses, WLR) as one card
 *       with the ratio big and a bar of A against B; anything else as tiles again.</li>
 * </ul>
 */
public class StatsScreen extends FzScreen {
    /** Width of the profile column. */
    private static final float SB = 150;
    private static final float TAB_H = 36, BAR_H = 16, GAP = 6, CHIP_H = 16;
    /** Third values that are the first divided by the second (such a row becomes one card with a bar), in card order. */
    private static final String[] RATIOS = {"FKDR", "WLR", "BBLR", "KDR", "勝率"};
    private static final int[] CODE_RGB = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
    /** Short names for the two numbers under a ratio card's bar, for a card too narrow for the full ones. */
    private static final Map<String, String> SHORT = new HashMap<>();
    /**
     * Minecraft's dark colours (black, dark blue, dark red, dark purple, dark grey) hardly show on the dark panels: a
     * value in one of them is drawn in this lighter version there (0 = as it is). Still darker than the light colour
     * next to it on a scale (dark red stays below red), so the steps of a scale stay apart.
     */
    private static final int[] LIGHTER = new int[16];
    static {
        String[] s = {"勝ち", "W", "負け", "L", "ベッド破壊", "BB", "ベッドロスト", "BL", "キル", "K", "デス", "D",
                "タッチ", "T", "爆発", "D", "試合", "G"};
        for (int i = 0; i < s.length; i += 2) SHORT.put(s[i], s[i + 1]);
        LIGHTER[0x0] = 0x808080;
        LIGHTER[0x1] = 0x6A6AD8;
        LIGHTER[0x4] = 0xD06060;
        LIGHTER[0x5] = 0xC860C8;
        LIGHTER[0x8] = 0x808080;
    }

    private final Anim open = new Anim(0f, 12f);
    /** 0 -> 1 whenever the body shows something new (the cards come in one after another). */
    private final Anim enter = new Anim(1f, 9f);
    private final Anim lineX = new Anim(0f, 16f), lineW = new Anim(0f, 16f);   // underline of the chosen game
    private final Anim segX = new Anim(0f, 16f), segW = new Anim(0f, 16f);     // the chosen period
    private final Anim listAnim = new Anim(0f, 16f);                            // the mode list
    private final Anim stage = new Anim(0f, 10f);                               // height of the skin's stage
    private boolean lineSet, segSet, listOpen, stageSet;
    private final Map<Object, Anim> anims = new HashMap<>();

    private Game game;
    private Game.Mode mode;
    private History.Period period;

    private String name;
    private UUID id;
    private PlayerData data;
    private String error;
    private boolean loading;
    /** Counts lookups, so an older answer that arrives late is ignored. */
    private int request;

    private StatsCommand.View view;
    private Game viewGame;
    private Game.Mode viewMode;
    private History.Period viewPeriod;
    private PlayerData viewData;
    private int viewVersion;

    /** The view the body was laid out for, and its pieces: single numbers, ratio cards, other rows. */
    private StatsCommand.View split;
    private String[] tops = new String[0];
    private final List<Ratio> ratios = new ArrayList<>();
    private final List<String[]> rest = new ArrayList<>();

    private String search = "";

    private float yaw = 25f, yawTarget = 25f;
    private boolean turning;
    private int turnX;
    private final long openedAt = System.currentTimeMillis();

    /** Drawn over everything at the end of the frame (a tag's reason), or null. */
    private String tip;
    private int tipX, tipY;

    /** One "A, B, A÷B" row: the ratio big, A and B under a bar that shows A against B. */
    private static final class Ratio {
        String label, value, aLabel, aValue, bLabel, bValue;
        /** A's share of the bar, 0..1, or -1 when a number is missing. */
        float share = -1f;
        /** A is a part of B (wins of games, the win rate): B is the whole bar instead of its other end. */
        boolean partOf;
        int rank;
    }

    public StatsScreen(String name, Game game, Game.Mode mode, History.Period period) {
        this.game = game;
        this.mode = mode == null ? Game.Mode.OVERALL : mode;
        this.period = period;
        load(name);
    }

    public StatsScreen(String name, Game game, History.Period period) {
        this(name, game, Game.Mode.OVERALL, period);
    }

    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);
    }

    // ---------------------------------------------------------------- loading

    private void load(String who) {
        name = who;
        id = null;
        data = null;
        error = null;
        loading = true;
        final int req = ++request;
        UUID known = StatsCommand.uuidInTab(who);
        if (known != null) fetch(req, known, who);
        else {
            new Thread(() -> {
                UUID found = StatsCommand.lookup(who);
                net.minecraft.client.Minecraft.getMinecraft().addScheduledTask(() -> {
                    if (req != request) return;
                    if (found == null) {
                        loading = false;
                        error = Lang.f("{0} というプレイヤーが見つかりません", who);
                    } else fetch(req, found, who);
                });
            }, "FzClient-NameLookup").start();
        }
    }

    private void fetch(int req, UUID uuid, String who) {
        id = uuid;
        Tags.get(uuid);            // tags and the skin load at the same time as the stats
        SkinCache.get(uuid, who);
        StatsApi.fetch(uuid, who, d -> {
            if (req != request) return;
            loading = false;
            data = d;
            name = d.name;
        }, err -> {
            if (req != request) return;
            loading = false;
            error = Lang.t("統計を取得できませんでした: ") + err;
        });
    }

    private StatsCommand.View view() {
        if (data == null) return null;
        if (view == null || viewData != data || viewGame != game || viewMode != mode || viewPeriod != period
                || viewVersion != dev.fzkc.fzhud.stats.Sessions.version) {
            viewVersion = dev.fzkc.fzhud.stats.Sessions.version;
            view = StatsCommand.view(data, game, mode, period);
            viewData = data;
            viewGame = game;
            viewMode = mode;
            viewPeriod = period;
        }
        return view;
    }

    /** Sorts a view's rows into single numbers (tiles on top), ratio cards and the other rows (tiles below). */
    private void split(StatsCommand.View v) {
        split = v;
        ratios.clear();
        rest.clear();
        List<String> top = new ArrayList<>();
        for (String[] row : v.rows) {
            if (row.length == 2) {
                top.add(row[0]);
                top.add(row[1]);
                continue;
            }
            int rank = row.length == 6 ? rank(row[4]) : -1;
            if (rank < 0) {
                rest.add(row);
                continue;
            }
            Ratio r = new Ratio();
            r.aLabel = row[0];
            r.aValue = row[1];
            r.bLabel = row[2];
            r.bValue = row[3];
            r.label = row[4];
            r.value = row[5];
            r.rank = rank;
            r.partOf = "勝率".equals(row[4]);
            float a = number(row[1]), b = number(row[3]);
            float whole = r.partOf ? b : a + b;
            if (!Float.isNaN(a) && !Float.isNaN(b) && whole > 0) r.share = Math.max(0f, Math.min(1f, a / whole));
            ratios.add(r);
        }
        ratios.sort((p, q) -> p.rank - q.rank);
        tops = top.toArray(new String[0]);
    }

    private static int rank(String label) {
        for (int i = 0; i < RATIOS.length; i++) if (RATIOS[i].equals(label)) return i;
        return -1;
    }

    /** "§a1,234" -> 1234, "§f56.7%" -> 56.7; NaN for "?" and anything else that is not a number. */
    private static float number(String v) {
        if (v == null) return Float.NaN;
        String s = v.replaceAll("§.", "").replace(",", "").replace("%", "").trim();
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    // ---------------------------------------------------------------- layout

    private float W() { return Ui.menuW(vw, 620); }
    private float H() { return Ui.menuH(vh, 350); }
    private float px() { return Ui.menuX(vw, W()); }
    private float py() { return (vh - H()) / 2f; }
    private float x0() { return px() + SB + 14; }
    private float cw() { return W() - SB - 28; }
    private float tabY() { return py() + 10; }
    private float barY() { return tabY() + TAB_H + 9; }
    private float bodyY() { return barY() + BAR_H + 10; }
    private float bodyBottom() { return py() + H() - 20; }

    private float searchY() { return py() + H() - 28; }

    /** Height of the skin's stage: what the name, the tags and the search box leave free (at most 210). */
    private float stageTarget() {
        float free = searchY() - 8 - (py() + 10) - 9 - infoH();
        return Math.max(100f, Math.min(210f, free));
    }

    private boolean overSkin(int mx, int my) { return Ui.within(mx, my, px() + 10, py() + 10, SB - 20, stage.value); }

    private Anim anim(Object key) {
        Anim a = anims.get(key);
        if (a == null) {
            a = new Anim(0, 14f);
            anims.put(key, a);
        }
        return a;
    }

    private float tabW() { return cw() / Game.values().length; }

    /** A game's name on its tab: Murder Mystery is shortened when the full names do not fit. */
    private String tabLabel(Game g) {
        if (g == Game.MURDER) {
            float room = tabW() - 8;
            for (Game o : Game.values()) if (R.width(o.label) > room) return "Murder";
        }
        return g.label;
    }

    private static String periodLabel(History.Period p) {
        boolean en = Lang.english();
        switch (p) {
            case DAILY: return en ? "24h" : "24時間";
            case WEEKLY: return en ? "7d" : "7日";
            case MONTHLY: return en ? "30d" : "30日";
            default: return en ? "All time" : "全期間";
        }
    }

    /** The period switch on the right of the second row: x, y, w, h of each part. */
    private float[][] periodSegs() {
        History.Period[] ps = History.Period.values();
        float[][] r = new float[ps.length][];
        float total = 0;
        for (History.Period p : ps) total += R.width(periodLabel(p)) + 16;
        float x = x0() + cw() - total;
        for (int i = 0; i < ps.length; i++) {
            float w = R.width(periodLabel(ps[i])) + 16;
            r[i] = new float[]{x, barY(), w, BAR_H};
            x += w;
        }
        return r;
    }

    /** "モード" in front of the mode's name, unless that would run into the period switch. */
    private boolean modeWord() {
        float full = R.width(Lang.t("モード")) + 6 + R.width(mode.label) + 26;
        return x0() + full + 8 <= periodSegs()[0][0];
    }

    /** The button that opens the mode list (x, y, w, h), or null for a game without modes. */
    private float[] modeRect() {
        if (game.modes().length <= 1) return null;
        float w = R.width(mode.label) + 26 + (modeWord() ? R.width(Lang.t("モード")) + 6 : 0);
        return new float[]{x0(), barY(), w, BAR_H};
    }

    private int listCols() {
        float w = Math.min(cw(), 300f) - 16;
        return Math.max(2, Math.min(4, (int) ((w + 4) / 84f)));
    }

    /** The open mode list under its button, or null. */
    private float[] listRect() {
        float[] m = modeRect();
        if (m == null) return null;
        int cols = listCols(), rows = (game.modes().length + cols - 1) / cols;
        return new float[]{m[0], m[1] + m[3] + 4, Math.min(cw(), 300f), 16 + rows * CHIP_H + (rows - 1) * 4};
    }

    private float[] chipRect(float[] list, int i) {
        int cols = listCols();
        float w = (list[2] - 16 - (cols - 1) * 4) / cols;
        return new float[]{list[0] + 8 + (i % cols) * (w + 4), list[1] + 8 + (i / cols) * (CHIP_H + 4), w, CHIP_H};
    }

    // ---------------------------------------------------------------- colours

    /** Text on a filled accent (the chosen period / mode): dark on a light accent, white on a dark one. */
    private static int onAccent() {
        return darkOnAccent() ? 0xFF0A1418 : 0xFFFFFFFF;
    }

    /** The accent is light, so the text on it is dark (and drawn without Minecraft's shadow, which only smudges it). */
    private static boolean darkOnAccent() {
        return Ui.luma(Ui.accent) > 0.6f;
    }

    /**
     * On the light "dashboard" panels Minecraft's light colours (white, yellow, light green...) are unreadable:
     * they are swapped for their darker versions there. Other styles get the text as it is.
     */
    private static String fit(String s) {
        if (!Ui.flat() || s == null || s.indexOf('§') < 0) return s;
        char[] c = s.toCharArray();
        for (int i = 0; i + 1 < c.length; i++) {
            if (c[i] != '§') continue;
            switch (Character.toLowerCase(c[i + 1])) {
                case 'f': c[i + 1] = '0'; break;
                case 'e': c[i + 1] = '6'; break;
                case 'a': c[i + 1] = '2'; break;
                case 'b': c[i + 1] = '3'; break;
                case '7': c[i + 1] = '8'; break;
                case 'd': c[i + 1] = '5'; break;
                default: break;
            }
            i++;
        }
        return new String(c);
    }

    /** RGB of the first Minecraft colour code in a text (white without one). */
    private static int rgbOf(String s) {
        if (s != null) {
            for (int i = 0; i + 1 < s.length(); i++) {
                if (s.charAt(i) != '§') continue;
                int k = "0123456789abcdef".indexOf(Character.toLowerCase(s.charAt(i + 1)));
                if (k >= 0) return CODE_RGB[k];
            }
        }
        return 0xFFFFFF;
    }

    /** A value with a colour, so the font does not draw it in the label colour. */
    private static String value(String v) {
        return v == null ? "§8?" : v.startsWith("§") ? v : "§f" + v;
    }

    /** Draws a value in its colour; one of the dark colours is drawn lighter on the dark panels (see LIGHTER). */
    private static void valueText(String raw, float x, float y, float scale, float a) {
        String v = fit(value(raw));
        int argb = Ui.text;
        if (!Ui.flat() && v.length() > 2 && v.charAt(0) == '§' && v.indexOf('§', 2) < 0) {
            int k = "0123456789abcdef".indexOf(Character.toLowerCase(v.charAt(1)));
            if (k >= 0 && LIGHTER[k] != 0) {
                v = v.substring(2);
                argb = 0xFF000000 | LIGHTER[k];
            }
        }
        if (scale == 1f) R.text(v, x, y, R.alpha(argb, a));
        else R.textScaled(v, x, y, scale, R.alpha(argb, a));
    }

    private static List<String> wrap(String s, float w) {
        return R.font().listFormattedStringToWidth(s, Math.max(20, (int) w));
    }

    /** Wrapped as narrow as it can be without needing another line, so the last line is not one lonely word. */
    private static List<String> balanced(String s, float w) {
        List<String> lines = wrap(s, w);
        if (lines.size() < 2) return lines;
        int lo = Math.max(20, (int) (w / 2f)), hi = Math.max(20, (int) w);
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (wrap(s, mid).size() <= lines.size()) hi = mid;
            else lo = mid + 1;
        }
        return wrap(s, hi);
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void render(int mx, int my, float pt) {
        float o = open.update(1f);
        tip = null;
        Ui.backdrop(vw, vh, o);
        if (!Ui.flat() && Glass.on()) {
            // the glass lets the HUD and the world through, and over them the numbers were hard to read: a darker
            // layer under this window (stronger with the blur on, when the glass itself is thinner)
            int a = (int) ((Glass.blurActive() ? 0xBC : 0x80) * Ui.opacity());
            R.rounded(px(), py(), W(), H(), Ui.rad(12f), R.alpha((Math.min(255, a) << 24) | Ui.panelRgb(), o));
        }
        Ui.panel(px(), py(), W(), H(), o);
        Ui.sidebar(px(), py(), SB, H(), o);

        drawProfile(mx, my, o);
        drawTabs(mx, my, o);
        drawFilters(mx, my, o);
        drawBody(o);
        drawList(mx, my, o);
        drawTip(o);

        Decoration.corner(vw, vh, o);
    }

    private void drawProfile(int mx, int my, float o) {
        float x = px() + 10, w = SB - 20, top = py() + 10, cx = px() + SB / 2f;
        float target = stageTarget();
        if (!stageSet) {
            stage.snap(target);
            stageSet = true;
        }
        float sh = stage.update(target);
        boolean light = Ui.flat();
        // the stage: a soft glow rising from the floor, and the floor's shadow under the feet
        Glass.fill(x, top, w, sh, Ui.rad(8f), 0, R.alpha(Ui.accentA(light ? 0x0A : 0x0E), o),
                R.alpha(Ui.accentA(light ? 0x2A : 0x36), o));
        float foot = top + sh - 13;
        yaw += (yawTarget - yaw) * 0.25f;
        SkinCache.Skin sk = id == null ? null : SkinCache.get(id, name);
        if (sk != null && !sk.loading()) {
            R.rounded(cx - 26, foot - 3, 52, 7, 3.5f, R.alpha(light ? 0x2A000000 : 0x66000000, o));
            float age = (System.currentTimeMillis() - openedAt) / 50f;
            if (o > 0.5f) PlayerPreview.drawSkin(sk.skin, sk.slim, cx, foot, Math.max(30f, (sh - 42f) / 2f), yaw, sk.cape, age);
            if (overSkin(mx, my) && !turning && !listOpen) R.textCentered(Lang.t("ドラッグで回転"), cx, top + 6, R.alpha(Ui.MUTED, o));
        } else if (error != null && id == null) {
            R.textScaledCentered("?", cx, top + sh / 2f - 16f, 4f, R.alpha(Ui.textA(0x38), o));
        } else {
            // the name or the skin is on its way: three dots that light up in turn
            double t = System.currentTimeMillis() / 160.0;
            for (int i = 0; i < 3; i++) {
                float p = 0.5f + 0.5f * (float) Math.sin(t - i * 0.9);
                R.rounded(cx - 11 + i * 8, top + sh / 2f - 3, 6, 6, 3f, R.alpha(Ui.textA((int) (0x28 + 0x78 * p)), o));
            }
        }

        // name (with the rank), fitted to the column
        float y = top + sh + 9;
        String shown = fit(data != null ? data.display() : "§f" + name);
        float sc = Math.max(0.6f, Math.min(1.25f, w / Math.max(1f, R.width(shown))));
        R.textScaledCentered(shown, cx, y, sc, R.alpha(Ui.text, o));
        y += 8 * sc + 6;
        if (data != null) {
            long mins = (System.currentTimeMillis() - data.dataAt) / 60_000L;
            String age = mins < 1 ? Lang.t("たった今") : mins < 120 ? Lang.f("{0} 分前", mins)
                    : mins < 48 * 60 ? Lang.f("{0} 時間前", mins / 60) : Lang.f("{0} 日前", mins / 1440);
            R.textCentered(Ui.trim(data.source + " · " + age, w), cx, y, R.alpha(Ui.MUTED, o));
            y += 14;
        }
        if (id != null) drawTags(mx, my, x, y, w, searchY() - 6, o, true);

        Ui.textBox(x, searchY(), w, 18, search, Ui.trim("⌕ " + Lang.t("名前を入力して Enter"), w - 14), true, search.length(), o);
    }

    /** Height of what goes under the stage: the name, where the numbers come from, the tags and lookup problems. */
    private float infoH() {
        float w = SB - 20;
        String shown = fit(data != null ? data.display() : "§f" + name);
        float sc = Math.max(0.6f, Math.min(1.25f, w / Math.max(1f, R.width(shown))));
        // room for the source line and one line of tags is kept while loading, so the stage does not jump then
        return 8 * sc + 6 + 14 + (id != null ? drawTags(0, 0, 0, 0, w, Float.MAX_VALUE, 0f, false) : 14);
    }

    /**
     * Tags as coloured chips in centred rows (the reason shows when the mouse is on one), then lookup problems.
     * Returns the y under them; with {@code draw} false it only measures.
     */
    private float drawTags(int mx, int my, float x, float y, float w, float limit, float o, boolean draw) {
        float cx = x + w / 2f;
        List<Tags.Tag> tags = Tags.get(id);
        if (tags.isEmpty()) {
            if (draw) {
                boolean any = Tags.watcherOn() || Tags.xadiaOn() || Tags.coralOn();
                String none = Tags.loading(id) ? Lang.t("タグを確認中…") : any ? Lang.t("タグなし") : Lang.t("タグの確認はオフ");
                R.textCentered(none, cx, y, R.alpha(Ui.MUTED, o));
            }
            y += 14;
        } else {
            int n = tags.size();
            String[] text = new String[n];
            float[] tw = new float[n];
            for (int i = 0; i < n; i++) {
                String t = tags.get(i).text;
                int end = t.indexOf("§r");   // the name only; the reason goes into the tooltip
                text[i] = fit(end > 0 ? t.substring(0, end) : t);
                tw[i] = Math.min(w, R.width(text[i]) + 10);
            }
            int i = 0;
            while (i < n && y + 13 <= limit) {
                int j = i;
                float rowW = 0;
                while (j < n && (j == i || rowW + 4 + tw[j] <= w)) {
                    rowW += (j == i ? 0 : 4) + tw[j];
                    j++;
                }
                float rx = cx - rowW / 2f;
                for (int k = i; k < j && draw; k++) {
                    int rgb = rgbOf(tags.get(k).text);
                    R.rounded(rx, y, tw[k], 13, Ui.rad(4f), R.alpha(((Ui.flat() ? 0x24 : 0x30) << 24) | rgb, o));
                    R.roundedOutline(rx, y, tw[k], 13, Ui.rad(4f), 1f, R.alpha(0x70000000 | rgb, o));
                    R.text(Ui.trim(text[k], tw[k] - 8), rx + 5, y + 3, R.alpha(Ui.text, o));
                    if (Ui.within(mx, my, rx, y, tw[k], 13)) {
                        tip = tags.get(k).source + ": " + tags.get(k).text;
                        tipX = mx;
                        tipY = my;
                    }
                    rx += tw[k] + 4;
                }
                y += 17;
                i = j;
            }
        }
        // why a lookup failed (a wrong key, a limit), so a missing tag is not a mystery
        for (String p : Tags.problems(id)) {
            for (String line : wrap("§c" + Lang.t(p), w)) {
                if (y + 9 > limit) return y;
                if (draw) R.textCentered(fit(line), cx, y, R.alpha(Ui.text, o));
                y += 10;
            }
        }
        return y;
    }

    private void drawTabs(int mx, int my, float o) {
        Game[] gs = Game.values();
        float tw = tabW(), y = tabY();
        for (int i = 0; i < gs.length; i++) {
            float x = x0() + i * tw;
            boolean on = gs[i] == game;
            float hv = anim("tab" + i).update(!listOpen && Ui.within(mx, my, x, y, tw, TAB_H) ? 1 : 0);
            float sel = anim("tabsel" + i).update(on ? 1 : 0);
            int bg = R.lerpColor(R.alpha(Ui.flat() ? 0x12000000 : 0x16FFFFFF, hv), Ui.accentA(Ui.flat() ? 0x1E : 0x26), sel);
            R.rounded(x + 2, y, tw - 4, TAB_H - 4, Ui.rad(7f), R.alpha(bg, o));
            if (o > 0.6f) item(icon(gs[i]), x + tw / 2f - 8, y + 4, 16);
            String l = tabLabel(gs[i]);
            float room = tw - 8, lw = R.width(l);
            float s = Math.max(0.75f, Math.min(1f, room / Math.max(1f, lw)));
            if (lw * s > room) l = Ui.trim(l, room / s);
            R.textScaledCentered(l, x + tw / 2f, y + 23, s, R.alpha(on ? Ui.text : Ui.textA(0x9C), o));
        }
        // the underline slides to the chosen game
        String l = tabLabel(game);
        float uw = Math.min(tw - 10, R.width(l) + 8);
        float ux = x0() + game.ordinal() * tw + (tw - uw) / 2f;
        if (!lineSet) {
            lineX.snap(ux);
            lineW.snap(uw);
            lineSet = true;
        }
        R.rounded(lineX.update(ux), y + TAB_H - 3, lineW.update(uw), 2f, 1f, R.alpha(Ui.accent, o));
        R.rect(x0(), y + TAB_H, cw(), 1, R.alpha(Ui.LINE, o));
    }

    private void drawFilters(int mx, int my, float o) {
        float[] m = modeRect();
        if (m != null) {
            float hv = anim("mode").update(listOpen || Ui.within(mx, my, m[0], m[1], m[2], m[3]) ? 1 : 0);
            Ui.button(m[0], m[1], m[2], m[3], "", hv, o);
            float tx = m[0] + 8, ty = m[1] + (m[3] - 8) / 2f;
            if (modeWord()) {
                R.text(Lang.t("モード"), tx, ty, R.alpha(Ui.MUTED, o));
                tx += R.width(Lang.t("モード")) + 6;
            }
            R.text(mode.label, tx, ty, R.alpha(Ui.buttonText(), o));
            // the arrow turns over while the list is open
            GlStateManager.pushMatrix();
            GlStateManager.translate(m[0] + m[2] - 10f, m[1] + m[3] / 2f, 0f);
            GlStateManager.rotate(180f * listAnim.value, 0f, 0f, 1f);
            R.triangleDown(0f, -1.5f, 3f, R.alpha(Ui.MUTED, o));
            GlStateManager.popMatrix();
        }

        // periods: one bar, the chosen part filled (the fill slides); dimmed for a mode, which rarely has them
        float[][] p = periodSegs();
        History.Period[] ps = History.Period.values();
        float x = p[0][0], total = p[p.length - 1][0] + p[p.length - 1][2] - x;
        boolean dim = !mode.overall();
        R.rounded(x, p[0][1], total, BAR_H, Ui.rad(5f), R.alpha(Ui.flat() ? 0xFFE2E7ED : 0x3A000000, o));
        int sel = period.ordinal();
        if (!segSet) {
            segX.snap(p[sel][0]);
            segW.snap(p[sel][2]);
            segSet = true;
        }
        float sx = segX.update(p[sel][0]), sw = segW.update(p[sel][2]);
        R.rounded(sx + 1, p[0][1] + 1, sw - 2, BAR_H - 2, Ui.rad(4f), R.alpha(dim ? Ui.accentA(0x66) : Ui.accent, o));
        for (int i = 0; i < ps.length; i++) {
            boolean on = i == sel;
            float hv = anim("p" + i).update(!on && !listOpen && Ui.within(mx, my, p[i][0], p[i][1], p[i][2], BAR_H) ? 1 : 0);
            if (hv > 0.01f) R.rounded(p[i][0] + 1, p[i][1] + 1, p[i][2] - 2, BAR_H - 2, Ui.rad(4f), R.alpha(Ui.flat() ? 0x14000000 : 0x1CFFFFFF, o * hv));
            int col = on ? (dim ? Ui.text : onAccent()) : Ui.textA(dim ? 0x66 : 0xB4);
            boolean ns = R.noShadow;
            R.noShadow = ns || on && !dim && darkOnAccent();
            R.textCentered(periodLabel(ps[i]), p[i][0] + p[i][2] / 2f, p[i][1] + 4, R.alpha(col, o));
            R.noShadow = ns;
        }
    }

    private void drawBody(float o) {
        float x = x0(), y = bodyY(), w = cw(), h = bodyBottom() - y;
        if (loading && data == null) {
            skeleton(x, y, w, o);
            footer(Lang.f("{0} の統計を取得中…", name), o);
            return;
        }
        if (error != null) {
            empty(x, y, w, h, "§c" + error, Lang.t("名前を確認して、左下の欄からもう一度検索してください"), o);
            return;
        }
        StatsCommand.View v = view();
        if (v == null) return;
        if (v != split) {
            split(v);
            enter.snap(0f);
        }
        float t = enter.update(1f);
        if (v.message != null) {
            empty(x, y, w, h, v.message, null, o * Math.min(1f, t * 1.6f));
            footer(foot(v), o);
            return;
        }

        // card heights: a little taller when there is room, smaller when it is tight
        int tileRows = (tops.length > 0 ? 1 : 0) + rest.size();
        int ratioRows = (ratios.size() + 1) / 2;
        int rows = tileRows + ratioRows;
        float tileH = 40f, ratioH = 58f;
        float need = tileRows * tileH + ratioRows * ratioH + Math.max(0, rows - 1) * GAP;
        if (need > h) {
            float k = h / need;
            tileH = Math.max(30f, tileH * k);
            ratioH = Math.max(44f, ratioH * k);
        } else {
            // the room left over makes the cards taller: a ratio row twice as much as a tile row, at most +10 / +20
            float g = Math.min(10f, (h - need) / Math.max(1, tileRows + 2 * ratioRows));
            tileH += g;
            ratioH += 2f * g;
        }

        int i = 0;
        if (tops.length > 0) {
            tiles(tops, x, y, w, tileH, t, i, o);
            i += tops.length / 2;
            y += tileH + GAP;
        }
        for (int r = 0; r < ratios.size(); r += 2) {
            boolean two = r + 1 < ratios.size();
            float cw = two ? (w - GAP) / 2f : w;
            ratioCard(ratios.get(r), x, y, cw, ratioH, stagger(t, i++), o);
            if (two) ratioCard(ratios.get(r + 1), x + cw + GAP, y, cw, ratioH, stagger(t, i++), o);
            y += ratioH + GAP;
        }
        for (String[] row : rest) {
            tiles(row, x, y, w, tileH, t, i, o);
            i += row.length / 2;
            y += tileH + GAP;
        }
        footer(foot(v), o);
    }

    /** How far card number i has come in (0..1) when the whole entrance is at t. */
    private static float stagger(float t, int i) {
        return Math.max(0f, Math.min(1f, (t - Math.min(i, 6) * 0.06f) / 0.6f));
    }

    /** A row of tiles, one per label/value pair: the label small, the value big. */
    private void tiles(String[] row, float x, float y, float w, float h, float t, int first, float o) {
        int n = row.length / 2;
        if (n == 0) return;
        float tw = (w - GAP * (n - 1)) / n;
        for (int k = 0; k < n; k++) {
            float ti = stagger(t, first + k);
            float a = o * ti, ty = y + (1f - ti) * 5f, tx = x + k * (tw + GAP);
            Ui.card(tx, ty, tw, h, 0f, -1f, a);
            R.text(Ui.trim(Lang.t(row[k * 2]), tw - 18), tx + 9, ty + 7, R.alpha(Ui.MUTED, a));
            String v = fit(value(row[k * 2 + 1]));
            float s = Math.max(1f, Math.min(2f, (tw - 18) / Math.max(1f, R.width(v))));
            valueText(row[k * 2 + 1], tx + 9, ty + 16 + Math.max(0f, (h - 22 - 8 * s) / 2f), s, a);
        }
    }

    /** The ratio big; under it a bar of A (green, from the left) against B (red, from the right) and both numbers. */
    private void ratioCard(Ratio r, float x, float y, float w, float h, float t, float o) {
        float a = o * t;
        y += (1f - t) * 5f;
        Ui.card(x, y, w, h, 0f, -1f, a);
        boolean light = Ui.flat();
        int good = light ? 0xFF1F9D57 : 0xFF5BD68A, bad = light ? 0xFFD6455A : 0xFFE8636E;
        int track = light ? 0xFFE3E8EE : 0x50000000;
        float bx = x + 9, bw = w - 18, barY = y + h - 21;

        R.text(Lang.t(r.label), bx, y + 7, R.alpha(Ui.MUTED, a));
        valueText(r.value, bx, y + 16 + Math.max(0f, (barY - y - 16 - 16) / 2f), 2f, a);

        R.rounded(bx, barY, bw, 3f, 1.5f, R.alpha(track, a));
        if (r.share >= 0f) {
            // both ends grow toward the point where they meet
            float room = r.partOf || r.share <= 0f || r.share >= 1f ? bw : bw - 1f;
            float g = room * r.share * t, b = r.partOf ? 0f : room * (1f - r.share) * t;
            if (g > 0.5f) R.rounded(bx, barY, g, 3f, 1.5f, R.alpha(good, a));
            if (b > 0.5f) R.rounded(bx + bw - b, barY, b, 3f, 1.5f, R.alpha(bad, a));
        }

        // A under the left end, B under the right end, with their names: in full, short ("W", "L") on a narrow card,
        // none when even those do not fit; always both sides the same way
        float cy = y + h - 13;
        String av = fit(value(r.aValue)), bv = fit(value(r.bValue));
        String al = Lang.t(r.aLabel), bl = Lang.t(r.bLabel);
        float nums = 7 + R.width(av) + 8 + R.width(bv) + 7 + 8;
        if (nums + R.width(al) + R.width(bl) > bw) {
            if (SHORT.containsKey(r.aLabel)) al = SHORT.get(r.aLabel);
            if (SHORT.containsKey(r.bLabel)) bl = SHORT.get(r.bLabel);
            if (nums + R.width(al) + R.width(bl) > bw) al = bl = null;
        }
        R.rounded(bx, cy + 2, 4, 4, 2f, R.alpha(good, a));
        float lx = bx + 7;
        if (al != null) {
            R.text(al, lx, cy, R.alpha(Ui.MUTED, a));
            lx += R.width(al) + 4;
        }
        valueText(r.aValue, lx, cy, 1f, a);
        R.rounded(bx + bw - 4, cy + 2, 4, 4, 2f, R.alpha(r.partOf ? (light ? 0xFFB8C2CC : 0xFF60747B) : bad, a));
        float rx = bx + bw - 7 - R.width(bv);
        valueText(r.bValue, rx, cy, 1f, a);
        if (bl != null) R.text(bl, rx - 4 - R.width(bl), cy, R.alpha(Ui.MUTED, a));
    }

    /** Grey card shapes that pulse while the numbers are on their way. */
    private void skeleton(float x, float y, float w, float o) {
        float p = 0.55f + 0.45f * (float) Math.sin(System.currentTimeMillis() / 240.0);
        int bar = R.alpha(Ui.flat() ? 0x16000000 : 0x1EFFFFFF, p);
        float th = 44, rh = 64, half = (w - GAP) / 2f;
        for (int i = 0; i < 2; i++) {
            float cx = x + i * (half + GAP);
            Ui.card(cx, y, half, th, 0f, -1f, o);
            R.rounded(cx + 9, y + 8, 36, 6, 3f, R.alpha(bar, o));
            R.rounded(cx + 9, y + 21, 64, 14, 4f, R.alpha(bar, o));
        }
        y += th + GAP;
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 2; c++) {
                float cx = x + c * (half + GAP);
                Ui.card(cx, y, half, rh, 0f, -1f, o);
                R.rounded(cx + 9, y + 8, 30, 6, 3f, R.alpha(bar, o));
                R.rounded(cx + 9, y + 22, 52, 14, 4f, R.alpha(bar, o));
                R.rounded(cx + 9, y + rh - 21, half - 18, 3, 1.5f, R.alpha(bar, o));
                R.rounded(cx + 9, y + rh - 12, 40, 6, 3f, R.alpha(bar, o));
                R.rounded(cx + half - 49, y + rh - 12, 40, 6, 3f, R.alpha(bar, o));
            }
            y += rh + GAP;
        }
    }

    /** A message instead of numbers: a round sign ("!" for problems, "i" otherwise) over the wrapped text. */
    private void empty(float x, float y, float w, float h, String msg, String hint, float o) {
        float cx = x + w / 2f;
        boolean light = Ui.flat();
        boolean bad = msg.startsWith("§c"), warn = msg.startsWith("§e");
        int rgb = bad ? (light ? 0xD6455A : 0xFF6B6B) : warn ? (light ? 0xC27C0E : 0xFFC34D) : (Ui.MUTED & 0xFFFFFF);
        float r = 11f, iy = y + Math.min(34f, h * 0.15f);
        R.rounded(cx - r, iy, r * 2, r * 2, r, R.alpha(0x26000000 | rgb, o));
        R.roundedOutline(cx - r, iy, r * 2, r * 2, r, 1.5f, R.alpha(0xFF000000 | rgb, o));
        R.textScaledCentered(bad || warn ? "!" : "i", cx + 0.5f, iy + r - 5.5f, 1.5f, R.alpha(0xFF000000 | rgb, o));
        float ty = iy + r * 2 + 12, ww = Math.min(w - 40, 300);
        for (String line : balanced(fit(msg), ww)) {
            R.textCentered(line, cx, ty, R.alpha(Ui.text, o));
            ty += 11;
        }
        if (hint != null) {
            ty += 4;
            for (String line : balanced(hint, ww)) {
                R.textCentered(line, cx, ty, R.alpha(Ui.MUTED, o));
                ty += 10;
            }
        }
    }

    private String foot(StatsCommand.View v) {
        String foot = v.note;
        String hp = StatsApi.hypixelProblem();
        if (hp != null) foot = (foot == null ? "" : foot + "  ") + Lang.t(hp);
        return foot;
    }

    private void footer(String s, float o) {
        if (s == null || s.isEmpty()) return;
        R.text(Ui.trim(s, cw()), x0(), py() + H() - 15, R.alpha(Ui.MUTED, o));
    }

    /** The mode list: the modes in a grid on a solid card, over the cards below. */
    private void drawList(int mx, int my, float o) {
        float a = listAnim.update(listOpen ? 1f : 0f);
        float[] r = listRect();
        if (a < 0.02f || r == null) return;
        float al = o * a, yo = (1f - a) * -5f, y = r[1] + yo;
        // the cards under the list step back, so the parts the list does not cover do not compete with it
        R.rounded(x0() - 3, bodyY() - 3, cw() + 6, bodyBottom() - bodyY() + 6, Ui.rad(8f),
                R.alpha(Ui.flat() ? 0xA6F4F6F9 : 0x8C000000 | Ui.panelRgb(), al));
        Glass.shadow(r[0], y, r[2], r[3], Ui.rad(8f), al);
        R.rounded(r[0], y, r[2], r[3], Ui.rad(7f), R.alpha(Ui.flat() ? Ui.FLAT_CARD : 0xF6000000 | Ui.panelRgb(), al));
        Ui.card(r[0], y, r[2], r[3], 0f, 0.3f, al);
        Game.Mode[] ms = game.modes();
        for (int i = 0; i < ms.length; i++) {
            float[] c = chipRect(r, i);
            boolean on = ms[i] == mode;
            float hv = anim("ml" + game.key + i).update(Ui.within(mx, my, c[0], c[1], c[2], c[3]) ? 1 : 0);
            int bg = on ? Ui.accent : R.lerpColor(Ui.flat() ? 0xFFF0F3F6 : 0x14FFFFFF, Ui.flat() ? 0xFFE1E7ED : 0x2CFFFFFF, hv);
            R.rounded(c[0], c[1] + yo, c[2], c[3], Ui.rad(5f), R.alpha(bg, al));
            boolean ns = R.noShadow;
            R.noShadow = ns || on && darkOnAccent();
            R.textCentered(Ui.trim(ms[i].label, c[2] - 6), c[0] + c[2] / 2f, c[1] + yo + 4, R.alpha(on ? onAccent() : Ui.text, al));
            R.noShadow = ns;
        }
    }

    private void drawTip(float o) {
        if (tip == null) return;
        List<String> lines = wrap(fit(tip), 220);
        float w = 0;
        for (String l : lines) w = Math.max(w, R.width(l));
        w += 12;
        float h = lines.size() * 10 + 8;
        float x = Math.min(tipX + 10, vw - w - 4), y = Math.min(tipY + 12, vh - h - 4);
        R.rounded(x, y, w, h, Ui.rad(4f), R.alpha(Ui.flat() ? 0xFAFFFFFF : 0xF2081014, o));
        R.roundedOutline(x, y, w, h, Ui.rad(4f), 1f, R.alpha(Ui.flat() ? Ui.FLAT_BORDER : Ui.accentA(0x70), o));
        for (int i = 0; i < lines.size(); i++) R.text(lines.get(i), x + 6, y + 5 + i * 10, R.alpha(Ui.text, o));
    }

    // ---------------------------------------------------------------- game icons

    private static ItemStack[] icons;

    /** The item each game's tab shows (the ones Hypixel uses for them, roughly). */
    private static ItemStack icon(Game g) {
        if (icons == null) {
            ItemStack[] s = new ItemStack[Game.values().length];
            for (Game x : Game.values()) {
                switch (x) {
                    case BEDWARS: s[x.ordinal()] = new ItemStack(Items.bed); break;
                    case DUELS: s[x.ordinal()] = new ItemStack(Items.fishing_rod); break;
                    case TNT_TAG: s[x.ordinal()] = new ItemStack(Blocks.tnt); break;
                    case BOW_SPLEEF: s[x.ordinal()] = new ItemStack(Items.bow); break;
                    case MURDER: s[x.ordinal()] = new ItemStack(Items.iron_sword); break;
                    default: s[x.ordinal()] = new ItemStack(Items.paper); break;
                }
            }
            icons = s;
        }
        return icons[g.ordinal()];
    }

    private void item(ItemStack s, float x, float y, float size) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0f);
        GlStateManager.scale(size / 16f, size / 16f, 1f);
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableDepth();
        RenderHelper.enableGUIStandardItemLighting();
        try {
            mc.getRenderItem().renderItemAndEffectIntoGUI(s, 0, 0);
        } finally {
            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableDepth();
            GlStateManager.disableRescaleNormal();
            GlStateManager.enableBlend();
            GlStateManager.popMatrix();
        }
    }

    // ---------------------------------------------------------------- input

    private void setGame(Game g) {
        if (g == game) return;
        game = g;
        mode = Game.Mode.OVERALL;
        listOpen = false;
    }

    @Override
    protected void click(int mx, int my, int button) {
        if (button != 0) return;
        if (listOpen) {
            // the open list takes the click; a click anywhere else only closes it
            listOpen = false;
            float[] r = listRect();
            if (r != null && Ui.within(mx, my, r[0], r[1], r[2], r[3])) {
                Game.Mode[] ms = game.modes();
                for (int i = 0; i < ms.length; i++) {
                    float[] c = chipRect(r, i);
                    if (Ui.inside(mx, my, c[0], c[1], c[2], c[3])) {
                        mode = ms[i];
                        return;
                    }
                }
                listOpen = true;   // between the chips: stays open
                return;
            }
            float[] m = modeRect();
            if (m != null) Ui.inside(mx, my, m[0], m[1], m[2], m[3]);   // its own button closes it (with the sound)
            return;
        }
        if (overSkin(mx, my)) {
            turning = true;
            turnX = mx;
            return;
        }
        float[] m = modeRect();
        if (m != null && Ui.inside(mx, my, m[0], m[1], m[2], m[3])) {
            listOpen = true;
            return;
        }
        Game[] gs = Game.values();
        float tw = tabW();
        for (int i = 0; i < gs.length; i++) {
            if (Ui.inside(mx, my, x0() + i * tw, tabY(), tw, TAB_H)) {
                setGame(gs[i]);
                return;
            }
        }
        float[][] p = periodSegs();
        History.Period[] ps = History.Period.values();
        for (int i = 0; i < ps.length; i++) {
            if (Ui.inside(mx, my, p[i][0], p[i][1], p[i][2], p[i][3])) {
                period = ps[i];
                return;
            }
        }
    }

    @Override
    protected void drag(int mx, int my) {
        if (!turning) return;
        yawTarget -= (mx - turnX) * 1.6f;
        turnX = mx;
    }

    @Override
    protected void release(int mx, int my) {
        turning = false;
    }

    @Override
    protected void key(char c, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (listOpen) {
                listOpen = false;
                return;
            }
            mc.displayGuiScreen(null);
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            String s = search.trim();
            if (s.matches("[A-Za-z0-9_]{1,16}")) {
                UiSound.hit();
                search = "";
                listOpen = false;
                load(s);
            }
            return;
        }
        if (keyCode == Keyboard.KEY_BACK) {
            if (!search.isEmpty()) search = search.substring(0, search.length() - 1);
            return;
        }
        if (keyCode == Keyboard.KEY_LEFT || keyCode == Keyboard.KEY_RIGHT) {
            Game[] gs = Game.values();
            setGame(gs[(game.ordinal() + (keyCode == Keyboard.KEY_RIGHT ? 1 : gs.length - 1)) % gs.length]);
            return;
        }
        if (keyCode == Keyboard.KEY_UP || keyCode == Keyboard.KEY_DOWN) {
            History.Period[] ps = History.Period.values();
            period = ps[(period.ordinal() + (keyCode == Keyboard.KEY_DOWN ? 1 : ps.length - 1)) % ps.length];
            return;
        }
        if (keyCode == Keyboard.KEY_TAB) {
            Game.Mode[] ms = game.modes();
            int i = 0;
            while (i < ms.length && ms[i] != mode) i++;
            mode = ms[(i + 1) % ms.length];
            return;
        }
        if (search.length() < 16 && ChatAllowedCharacters.isAllowedCharacter(c) && (Character.isLetterOrDigit(c) || c == '_')) {
            search += c;
        }
    }

    // ---------------------------------------------------------------- developer check

    /** DevTest: shows this data (null = keep what is shown) with these choices, without asking anyone. */
    public void devShow(PlayerData d, Game g, Game.Mode m, History.Period p, boolean list) {
        if (d != null) {
            request++;   // an answer still on its way is ignored
            data = d;
            name = d.name;
            id = d.id;
            loading = false;
            error = null;
        }
        game = g;
        mode = m == null ? Game.Mode.OVERALL : m;
        period = p;
        listOpen = list;
    }

    public PlayerData devData() { return data; }
}
