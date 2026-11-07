package dev.fzkc.fzhud.gui;

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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /pv: a player's stats in a window. Games and periods are tabs along the top; typing a name and pressing
 * Enter looks up someone else without closing the window.
 */
public class StatsScreen extends FzScreen {
    private final Anim open = new Anim(0f, 12f);
    private final Map<Object, Anim> anims = new HashMap<>();

    private Game game;
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
    private History.Period viewPeriod;
    private PlayerData viewData;

    private String search = "";

    public StatsScreen(String name, Game game, History.Period period) {
        this.game = game;
        this.period = period;
        load(name);
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
        Tags.get(uuid);   // start the tag lookups at the same time
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
        if (view == null || viewData != data || viewGame != game || viewPeriod != period) {
            view = StatsCommand.view(data, game, period);
            viewData = data;
            viewGame = game;
            viewPeriod = period;
        }
        return view;
    }

    // ---------------------------------------------------------------- layout

    private float W() { return Ui.menuW(vw, 560); }
    private float H() { return Ui.menuH(vh, 320); }
    private float px() { return (vw - W()) / 2f; }
    private float py() { return (vh - H()) / 2f; }
    private float x0() { return px() + 16; }
    private float cw() { return W() - 32; }
    private float searchW() { return Math.max(130, R.width(Lang.t("名前を入力して Enter")) + 16); }
    private float searchX() { return px() + W() - 16 - searchW(); }

    private Anim anim(Object key) {
        Anim a = anims.get(key);
        if (a == null) {
            a = new Anim(0, 14f);
            anims.put(key, a);
        }
        return a;
    }

    /** x, width of each tab in a row of labels. */
    private float[][] tabs(String[] labels) {
        float[][] r = new float[labels.length][2];
        float x = x0();
        for (int i = 0; i < labels.length; i++) {
            float w = R.width(labels[i]) + 18;
            r[i][0] = x;
            r[i][1] = w;
            x += w + 4;
        }
        return r;
    }

    private String[] gameLabels() {
        Game[] gs = Game.values();
        String[] out = new String[gs.length];
        for (int i = 0; i < gs.length; i++) out[i] = gs[i].label;
        return out;
    }

    private String[] periodLabels() {
        History.Period[] ps = History.Period.values();
        String[] out = new String[ps.length];
        for (int i = 0; i < ps.length; i++) out[i] = ps[i].label;
        return out;
    }

    private float gameY() { return py() + 50; }
    private float periodY() { return py() + 72; }
    private float bodyY() { return py() + 100; }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void render(int mx, int my, float pt) {
        float o = open.update(1f);
        Ui.backdrop(vw, vh, o);
        Ui.panel(px(), py(), W(), H(), o);

        drawHeader(o);
        Ui.textBox(searchX(), py() + 14, searchW(), 18, search, Lang.t("名前を入力して Enter"), true, search.length(), o);
        drawTabs("g", gameLabels(), game.ordinal(), gameY(), mx, my, o);
        drawTabs("p", periodLabels(), period.ordinal(), periodY(), mx, my, o);
        R.rect(x0(), py() + 94, cw(), 1, R.alpha(Ui.accentA(0x40), o));
        drawBody(o);

        Decoration.corner(vw, vh, o);
    }

    private void drawHeader(float o) {
        float x = x0(), y = py() + 12;
        if (data != null) {
            R.textScaled(data.display(), x, y, 1.4f, R.alpha(Ui.text, o));
            StringBuilder sub = new StringBuilder("§8" + data.source);
            if (id != null) {
                for (Tags.Tag t : Tags.get(id)) sub.append("  §7").append(t.source).append(": ").append(t.text);
            }
            R.text(sub.toString(), x, y + 17, R.alpha(Ui.text, o));
        } else {
            R.textScaled("§f" + name, x, y, 1.4f, R.alpha(Ui.text, o));
        }
    }

    private void drawTabs(String key, String[] labels, int sel, float y, int mx, int my, float o) {
        float[][] t = tabs(labels);
        for (int i = 0; i < labels.length; i++) {
            boolean on = i == sel;
            float hv = anim(key + i).update(Ui.inside(mx, my, t[i][0], y, t[i][1], 18) ? 1 : 0);
            R.rounded(t[i][0], y, t[i][1], 18, 4f, R.alpha(on ? Ui.accentA(0x40) : (hv > 0.01f ? 0x22FFFFFF : 0x10FFFFFF), o));
            if (on) R.rect(t[i][0] + 4, y + 17, t[i][1] - 8, 1, R.alpha(Ui.accent, o));
            R.textCentered(labels[i], t[i][0] + t[i][1] / 2f, y + 5, R.alpha(on ? Ui.text : Ui.textA(0xB0), o));
        }
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
            R.text(v.message, x, y + 4, R.alpha(Ui.text, o));
            return;
        }
        // each row of the chat output becomes a row of cards (up to three per row)
        float gap = 6, cardH = 30, avail = py() + H() - 22 - y;
        float rowH = Math.min(36, Math.max(cardH + 2, avail / Math.max(1, v.rows.size())));
        cardH = Math.min(cardH, rowH - 4);
        float cardW = (cw() - gap * 2) / 3f;
        for (String[] row : v.rows) {
            for (int i = 0, c = 0; i + 1 < row.length; i += 2, c++) {
                float cx = x + c * (cardW + gap);
                R.rounded(cx, y, cardW, cardH, 4f, R.alpha(0x14FFFFFF, o));
                R.text(Lang.t(row[i]), cx + 8, y + 5, R.alpha(Ui.MUTED, o));
                String val = row[i + 1];
                if (!val.startsWith("§")) val = "§f" + val;
                R.textScaled(val, cx + 8, y + 15, cardH >= 28 ? 1.15f : 1f, R.alpha(Ui.text, o));
            }
            y += rowH;
        }
        String foot = v.note;
        String hp = StatsApi.hypixelProblem();
        if (hp != null) foot = (foot == null ? "" : foot + "  ") + Lang.t(hp);
        if (foot != null) R.text("§8" + foot, x, py() + H() - 16, R.alpha(Ui.text, o));
    }

    // ---------------------------------------------------------------- input

    @Override
    protected void click(int mx, int my, int button) {
        if (button != 0) return;
        float[][] g = tabs(gameLabels());
        for (int i = 0; i < g.length; i++) {
            if (Ui.inside(mx, my, g[i][0], gameY(), g[i][1], 18) && i != game.ordinal()) {
                UiSound.hit();
                game = Game.values()[i];
            }
        }
        float[][] p = tabs(periodLabels());
        for (int i = 0; i < p.length; i++) {
            if (Ui.inside(mx, my, p[i][0], periodY(), p[i][1], 18) && i != period.ordinal()) {
                UiSound.hit();
                period = History.Period.values()[i];
            }
        }
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
            // switch games with the arrow keys
            Game[] gs = Game.values();
            game = gs[(game.ordinal() + (keyCode == Keyboard.KEY_RIGHT ? 1 : gs.length - 1)) % gs.length];
            return;
        }
        if (keyCode == Keyboard.KEY_UP || keyCode == Keyboard.KEY_DOWN) {
            History.Period[] ps = History.Period.values();
            period = ps[(period.ordinal() + (keyCode == Keyboard.KEY_DOWN ? 1 : ps.length - 1)) % ps.length];
            return;
        }
        if (search.length() < 16 && ChatAllowedCharacters.isAllowedCharacter(c) && (Character.isLetterOrDigit(c) || c == '_')) {
            search += c;
        }
    }
}
