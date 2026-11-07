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
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /pv: a player's stats in a window. On the left the player's skin (drag to turn it), name, tags and a search
 * box; on the right the game, mode (Solo, Doubles, UHC, ...) and period, and the numbers as coloured cards.
 */
public class StatsScreen extends FzScreen {
    private static final float SB = 160;

    private final Anim open = new Anim(0f, 12f);
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

    private String search = "";

    private float yaw = 25f, yawTarget = 25f;
    private boolean turning;
    private int turnX;
    private final long openedAt = System.currentTimeMillis();

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
        if (view == null || viewData != data || viewGame != game || viewMode != mode || viewPeriod != period) {
            view = StatsCommand.view(data, game, mode, period);
            viewData = data;
            viewGame = game;
            viewMode = mode;
            viewPeriod = period;
        }
        return view;
    }

    // ---------------------------------------------------------------- layout

    private float W() { return Ui.menuW(vw, 620); }
    private float H() { return Ui.menuH(vh, 350); }
    private float px() { return (vw - W()) / 2f; }
    private float py() { return (vh - H()) / 2f; }
    private float x0() { return px() + SB + 14; }
    private float cw() { return W() - SB - 28; }

    private Anim anim(Object key) {
        Anim a = anims.get(key);
        if (a == null) {
            a = new Anim(0, 14f);
            anims.put(key, a);
        }
        return a;
    }

    /** x, y, width of each pill in a row of labels starting at (x0, y), wrapping inside the content width. */
    private float[][] pills(String[] labels, float y, float h, float pad) {
        float[][] r = new float[labels.length][3];
        float x = x0();
        for (int i = 0; i < labels.length; i++) {
            float w = R.width(labels[i]) + pad;
            if (x + w > x0() + cw() && x > x0()) {
                x = x0();
                y += h + 4;
            }
            r[i][0] = x;
            r[i][1] = y;
            r[i][2] = w;
            x += w + 4;
        }
        return r;
    }

    private static final float GAME_H = 18, MODE_H = 15, PERIOD_H = 16;

    private float[][] gamePills() {
        Game[] gs = Game.values();
        String[] l = new String[gs.length];
        for (int i = 0; i < gs.length; i++) l[i] = gs[i].label;
        return pills(l, py() + 12, GAME_H, 18);
    }

    private float modesY() {
        float[][] g = gamePills();
        return g[g.length - 1][1] + GAME_H + 6;
    }

    private float[][] modePills() {
        Game.Mode[] ms = game.modes();
        if (ms.length <= 1) return new float[0][];
        String[] l = new String[ms.length];
        for (int i = 0; i < ms.length; i++) l[i] = ms[i].label;
        return pills(l, modesY(), MODE_H, 12);
    }

    private float periodY() {
        float[][] m = modePills();
        return m.length == 0 ? modesY() : m[m.length - 1][1] + MODE_H + 6;
    }

    /** The period switch: one bar split into four. */
    private float[][] periodSegs() {
        History.Period[] ps = History.Period.values();
        float[][] r = new float[ps.length][3];
        float x = x0(), y = periodY();
        for (int i = 0; i < ps.length; i++) {
            float w = R.width(ps[i].label) + 16;
            r[i][0] = x;
            r[i][1] = y;
            r[i][2] = w;
            x += w;
        }
        return r;
    }

    private float bodyY() { return periodY() + PERIOD_H + 12; }

    // skin area in the sidebar
    private float skinX() { return px() + SB / 2f; }
    private float skinFoot() { return py() + 168; }
    private boolean overSkin(int mx, int my) { return Ui.inside(mx, my, px() + 10, py() + 10, SB - 20, 165); }

    private float searchY() { return py() + H() - 30; }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void render(int mx, int my, float pt) {
        float o = open.update(1f);
        Ui.backdrop(vw, vh, o);
        Ui.panel(px(), py(), W(), H(), o);
        Ui.sidebar(px(), py(), SB, H(), o);

        drawProfile(mx, my, o);
        drawGames(mx, my, o);
        drawModes(mx, my, o);
        drawPeriods(mx, my, o);
        R.rect(x0(), bodyY() - 6, cw(), 1, R.alpha(Ui.accentA(0x30), o));
        drawBody(o);

        Decoration.corner(vw, vh, o);
    }

    private void drawProfile(int mx, int my, float o) {
        // skin
        yaw += (yawTarget - yaw) * 0.25f;
        float age = (System.currentTimeMillis() - openedAt) / 50f;
        if (id != null) {
            SkinCache.Skin sk = SkinCache.get(id, name);
            R.rounded(px() + 10, py() + 10, SB - 20, 165, 6f, R.alpha(0x0CFFFFFF, o));
            // soft floor shadow
            R.rounded(skinX() - 26, skinFoot() - 3, 52, 6, 3f, R.alpha(0x40000000, o));
            if (o > 0.5f) PlayerPreview.drawSkin(sk.skin, sk.slim, skinX(), skinFoot(), 66f, yaw, sk.cape, age);
            if (overSkin(mx, my) && !turning) R.textCentered("§8" + Lang.t("ドラッグで回転"), skinX(), py() + 14, R.alpha(Ui.text, o));
        } else {
            R.rounded(px() + 10, py() + 10, SB - 20, 165, 6f, R.alpha(0x0CFFFFFF, o));
        }

        // name, fitted to the sidebar
        String shown = data != null ? data.display() : "§f" + name;
        float w = R.width(shown);
        float sc = Math.min(1.3f, (SB - 20) / Math.max(1f, w));
        R.textScaledCentered(shown, skinX(), py() + 184, sc, R.alpha(Ui.text, o));

        float y = py() + 184 + 9 * sc + 6;
        if (data != null) {
            long mins = (System.currentTimeMillis() - data.fetchedAt) / 60_000L;
            String age2 = mins < 1 ? Lang.t("たった今") : Lang.f("{0} 分前", mins);
            R.textCentered("§8" + data.source + " · " + age2, skinX(), y, R.alpha(Ui.text, o));
            y += 14;
        }
        // tags as small chips
        if (id != null) {
            List<Tags.Tag> tags = Tags.get(id);
            float cx = px() + 10;
            for (Tags.Tag t : tags) {
                String txt = t.text;
                float tw = Math.min(SB - 20, R.width(txt) + 10);
                if (cx + tw > px() + SB - 10 && cx > px() + 10) {
                    cx = px() + 10;
                    y += 15;
                }
                R.rounded(cx, y, tw, 13, 3f, R.alpha(0x20FF5555, o));
                R.text(Ui.trim(txt, tw - 8), cx + 5, y + 3, R.alpha(Ui.text, o));
                cx += tw + 4;
            }
            if (tags.isEmpty() && Tags.loading(id)) R.textCentered("§8" + Lang.t("タグを確認中…"), skinX(), y, R.alpha(Ui.text, o));
            if (!tags.isEmpty()) y += 17;
            // why a lookup failed (a wrong key, a limit), so a missing tag is not a mystery
            for (String p : Tags.problems(id)) {
                String line = "§c" + Lang.t(p);
                float ps = Math.min(1f, (SB - 20) / Math.max(1f, R.width(line)));
                R.textScaledCentered(line, skinX(), y, ps, R.alpha(Ui.text, o));
                y += 11;
            }
        }

        // search
        Ui.textBox(px() + 10, searchY(), SB - 20, 18, search, Lang.t("名前を入力して Enter"), true, search.length(), o);
    }

    private void drawPill(String key, float[] r, float h, String label, boolean on, int mx, int my, float o) {
        float hv = anim(key).update(Ui.inside(mx, my, r[0], r[1], r[2], h) ? 1 : 0);
        R.rounded(r[0], r[1], r[2], h, h / 2f, R.alpha(on ? Ui.accentA(0x50) : R.lerpColor(0x10FFFFFF, 0x26FFFFFF, hv), o));
        if (on) R.roundedOutline(r[0], r[1], r[2], h, h / 2f, 1f, R.alpha(Ui.accentA(0xA0), o));
        R.textCentered(label, r[0] + r[2] / 2f, r[1] + (h - 8) / 2f, R.alpha(on ? Ui.text : Ui.textA(0xB0), o));
    }

    private void drawGames(int mx, int my, float o) {
        float[][] g = gamePills();
        Game[] gs = Game.values();
        for (int i = 0; i < gs.length; i++) drawPill("g" + i, g[i], GAME_H, gs[i].label, gs[i] == game, mx, my, o);
    }

    private void drawModes(int mx, int my, float o) {
        float[][] m = modePills();
        Game.Mode[] ms = game.modes();
        for (int i = 0; i < m.length; i++) drawPill("m" + game.key + i, m[i], MODE_H, ms[i].label, ms[i] == mode, mx, my, o);
    }

    private void drawPeriods(int mx, int my, float o) {
        float[][] p = periodSegs();
        History.Period[] ps = History.Period.values();
        float total = p[p.length - 1][0] + p[p.length - 1][2] - p[0][0];
        R.rounded(p[0][0], p[0][1], total, PERIOD_H, 4f, R.alpha(0x14FFFFFF, o));
        boolean dim = !mode.overall();
        for (int i = 0; i < ps.length; i++) {
            boolean on = ps[i] == period;
            float hv = anim("p" + i).update(Ui.inside(mx, my, p[i][0], p[i][1], p[i][2], PERIOD_H) ? 1 : 0);
            if (on) R.rounded(p[i][0] + 1, p[i][1] + 1, p[i][2] - 2, PERIOD_H - 2, 3f, R.alpha(Ui.accentA(dim ? 0x28 : 0x55), o));
            else if (hv > 0.01f) R.rounded(p[i][0] + 1, p[i][1] + 1, p[i][2] - 2, PERIOD_H - 2, 3f, R.alpha(R.alpha(0x18FFFFFF, hv), o));
            R.textCentered(ps[i].label, p[i][0] + p[i][2] / 2f, p[i][1] + 4, R.alpha(on ? Ui.text : Ui.textA(dim ? 0x60 : 0xB0), o));
        }
        // what is shown, on the right of the period switch
        String what = "§7" + game.label + (mode.overall() ? "" : " §8· §7" + mode.label);
        R.text(what, x0() + cw() - R.width(what), p[0][1] + 4, R.alpha(Ui.text, o));
    }

    /** RGB of a Minecraft colour code, for the card's accent bar. */
    private static int rgbOf(String value) {
        int[] rgb = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
                0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
        if (value != null && value.length() > 1 && value.charAt(0) == '§') {
            int i = "0123456789abcdef".indexOf(Character.toLowerCase(value.charAt(1)));
            if (i >= 0) return rgb[i];
        }
        return 0xFFFFFF;
    }

    private void drawBody(float o) {
        float x = x0(), y = bodyY();
        if (loading && data == null) {
            R.text(Lang.f("{0} の統計を取得中…", name), x, y + 4, R.alpha(Ui.MUTED, o));
            return;
        }
        if (error != null) {
            R.text("§c" + error, x, y + 4, R.alpha(Ui.text, o));
            return;
        }
        StatsCommand.View v = view();
        if (v == null) return;
        if (v.message != null) {
            R.rounded(x, y, cw(), 30, 5f, R.alpha(0x10FFFFFF, o));
            float ms = Math.min(1f, (cw() - 20) / Math.max(1f, R.width(v.message)));
            R.textScaled(v.message, x + 10, y + 15 - 4 * ms, ms, R.alpha(Ui.text, o));
            return;
        }
        // single stats (the star, the streak) go together in a top row of bigger cards; the rest keep their rows
        List<String[]> rows = new ArrayList<>();
        List<String> heroes = new ArrayList<>();
        for (String[] row : v.rows) {
            if (row.length == 2) {
                heroes.add(row[0]);
                heroes.add(row[1]);
            } else rows.add(row);
        }
        int count = rows.size() + (heroes.isEmpty() ? 0 : 1);
        float gap = 6, avail = py() + H() - 18 - y;
        float rowH = Math.min(42, Math.max(28, avail / Math.max(1, count)));
        float cardH = rowH - 5;
        if (!heroes.isEmpty()) {
            drawCards(heroes.toArray(new String[0]), x, y, cardH, gap, true, o);
            y += rowH;
        }
        for (String[] row : rows) {
            drawCards(row, x, y, cardH, gap, false, o);
            y += rowH;
        }
        String foot = v.note;
        String hp = StatsApi.hypixelProblem();
        if (hp != null) foot = (foot == null ? "" : foot + "  ") + Lang.t(hp);
        if (foot != null) R.text("§8" + foot, x, py() + H() - 14, R.alpha(Ui.text, o));
    }

    private void drawCards(String[] row, float x, float y, float h, float gap, boolean hero, float o) {
        int n = row.length / 2;
        int cols = hero ? Math.max(n, 2) : 3;
        float w = (cw() - gap * (cols - 1)) / cols;
        for (int i = 0; i < n; i++) {
            float cx = x + i * (w + gap);
            String label = Lang.t(row[i * 2]), val = row[i * 2 + 1];
            if (!val.startsWith("§")) val = "§f" + val;
            int rgb = rgbOf(val);
            R.rounded(cx, y, w, h, 5f, R.alpha(hero ? 0x1CFFFFFF : 0x12FFFFFF, o));
            R.rect(cx, y + 5, 2, h - 10, R.alpha(0xFF000000 | rgb, o));
            R.text(label, cx + 9, y + 5, R.alpha(Ui.MUTED, o));
            float vs = h >= 34 ? (hero ? 1.6f : 1.4f) : 1.1f;
            R.textScaled(val, cx + 9, y + h - 6 - 8 * vs, vs, R.alpha(Ui.text, o));
        }
    }

    // ---------------------------------------------------------------- input

    @Override
    protected void click(int mx, int my, int button) {
        if (button != 0) return;
        if (overSkin(mx, my)) {
            turning = true;
            turnX = mx;
            return;
        }
        float[][] g = gamePills();
        Game[] gs = Game.values();
        for (int i = 0; i < gs.length; i++) {
            if (Ui.inside(mx, my, g[i][0], g[i][1], g[i][2], GAME_H) && gs[i] != game) {
                UiSound.hit();
                game = gs[i];
                mode = Game.Mode.OVERALL;
                return;
            }
        }
        float[][] m = modePills();
        Game.Mode[] ms = game.modes();
        for (int i = 0; i < m.length; i++) {
            if (Ui.inside(mx, my, m[i][0], m[i][1], m[i][2], MODE_H) && ms[i] != mode) {
                UiSound.hit();
                mode = ms[i];
                return;
            }
        }
        float[][] p = periodSegs();
        History.Period[] ps = History.Period.values();
        for (int i = 0; i < ps.length; i++) {
            if (Ui.inside(mx, my, p[i][0], p[i][1], p[i][2], PERIOD_H) && ps[i] != period) {
                UiSound.hit();
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
            mc.displayGuiScreen(null);
            return;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            String s = search.trim();
            if (s.matches("[A-Za-z0-9_]{1,16}")) {
                UiSound.hit();
                search = "";
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
            game = gs[(game.ordinal() + (keyCode == Keyboard.KEY_RIGHT ? 1 : gs.length - 1)) % gs.length];
            mode = Game.Mode.OVERALL;
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
}
