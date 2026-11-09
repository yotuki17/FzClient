package dev.fzkc.fzhud.gui;

import dev.fzkc.fzhud.FzHud;
import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.config.Module;
import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.Icons;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Sidebar with categories + searchable grid of feature cards. */
public class ModsScreen extends FzScreen {
    /** Label of the extra tab for the client's own settings (see Modules.CLIENT_SETTINGS). */
    static final String CLIENT_LABEL = "Client Settings";
    private static final String[] CATS;
    /** Index of that tab in CATS (the last one). */
    private static final int CLIENT_CAT;
    static {
        Module.Category[] cs = Module.Category.values();
        CATS = new String[cs.length + 2];
        CATS[0] = "すべて";
        for (int i = 0; i < cs.length; i++) CATS[i + 1] = cs[i].label;
        CLIENT_CAT = cs.length + 1;
        CATS[CLIENT_CAT] = CLIENT_LABEL;
    }

    /** Category name shown for a module (client settings have their own). */
    static String categoryLabel(Module m) {
        return Modules.isClientSetting(m) ? CLIENT_LABEL : m.category.label;
    }

    private static String catIcon(int i) {
        return i == CLIENT_CAT ? "menu_options" : Icons.category(i);
    }
    private static int cat = 0;
    /** Dashboard style: showing the home page (tiles, rings, recent changes) instead of a category's cards. */
    private static boolean home = true;
    private static final float SW = 130;
    /** Card height / gap from the UI page. */
    private static float CARD_H() { return dev.fzkc.fzhud.Modules.uiCardHeight.get(); }
    private static float GAP() { return dev.fzkc.fzhud.Modules.uiCardGap.get(); }
    private static final float SEARCH_H = 20;
    private static final int MAX_QUERY = 24;

    private final GuiScreen parent;
    private final Anim open = new Anim(0f, 12f);
    private final Anim scroll = new Anim(0f, 16f);
    private float scrollTarget;
    private String query = "";
    /** Caret position in the search text (0..query.length()). */
    private int cursor;
    private final Map<Object, Anim> anims = new HashMap<Object, Anim>();

    public ModsScreen(GuiScreen parent) {
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);
        if (cat == CLIENT_CAT) cat = 0;   // Client Settings is its own page now
    }

    private float W() { return Ui.menuW(vw, 640); }
    private float H() { return Ui.menuH(vh, 340); }
    private float px() { return Ui.menuX(vw, W()); }
    private float py() { return (vh - H()) / 2f; }
    /** Left edge of the sidebar (left or right side of the panel, see the UI page). */
    private float sx() { return Ui.sidebarRight() ? px() + W() - SW : px(); }
    private boolean top() { return Ui.sidebarTop() || Ui.flat(); }
    private boolean showHome() { return Ui.flat() && home && query.trim().isEmpty(); }
    private float x0() { return top() || Ui.sidebarRight() ? px() + 14 : px() + SW + 14; }
    private float cw() { return top() ? W() - 28 : W() - SW - 28; }
    /** Top layout: row 1 = logo + search, row 2 = category tabs + buttons, then the cards. */
    private float y0() { return top() ? py() + 74 : py() + 44; }
    private float ch() { return top() ? H() - 82 : H() - 52; }
    private int cols() {
        int c = dev.fzkc.fzhud.Modules.uiColumns.get();   // 0 = auto, else 1..4
        if (c > 0) return Math.max(1, Math.min(c, (int) Math.max(1, cw() / 90f)));
        return cw() >= 440 ? 3 : 2;
    }
    private float cardW() { return (cw() - (cols() - 1) * GAP()) / cols(); }

    private Anim anim(Object key, float init) {
        Anim a = anims.get(key);
        if (a == null) {
            a = new Anim(init, 14f);
            anims.put(key, a);
        }
        return a;
    }

    private static List<Module> allCache;
    private static int allCacheSource = -1;

    /** Every module, grouped by category. The set never changes after startup, so it is built once. */
    static List<Module> all() {
        int source = FzHud.elements.size() + Modules.FEATURES.size();
        if (allCache == null || allCacheSource != source) {
            List<Module> all = new ArrayList<Module>();
            all.addAll(FzHud.elements);
            for (Module m : Modules.FEATURES) if (!Modules.hiddenInMenu(m)) all.add(m);   // Hitreg: always on, nothing to show
            sortByCategory(all);
            allCache = all;
            allCacheSource = source;
        }
        return allCache;
    }

    /** Same category next to each other (stable: keeps the order inside a category). */
    static void sortByCategory(List<Module> list) {
        java.util.Collections.sort(list, new java.util.Comparator<Module>() {
            @Override public int compare(Module a, Module b) { return a.category.ordinal() - b.category.ordinal(); }
        });
    }

    /**
     * Client settings only show up in their own tab ("すべて" leaves them out, except while searching, so a search
     * still finds e.g. Language).
     */
    private static boolean inCat(Module m, int c, boolean searching) {
        boolean client = Modules.isClientSetting(m);
        if (c == CLIENT_CAT) return client;
        if (c == 0) return !client || searching;
        return !client && m.category.ordinal() == c - 1;
    }

    private List<Module> visibleCache;
    private String visibleKey;

    /** Cards for the current category / search, rebuilt only when those change (not every frame). */
    private List<Module> visible() {
        String key = cat + "|" + query + "|" + dev.fzkc.fzhud.util.Lang.english();
        if (visibleCache != null && key.equals(visibleKey)) return visibleCache;
        visibleKey = key;
        String q = query.trim().toLowerCase();
        List<Module> out = new ArrayList<Module>();
        visibleCache = out;
        for (Module m : all()) {
            if (!inCat(m, cat, !q.isEmpty())) continue;
            if (!q.isEmpty() && !m.name.toLowerCase().contains(q) && !m.description.toLowerCase().contains(q)
                    && !dev.fzkc.fzhud.util.Lang.t(m.description).toLowerCase().contains(q)) continue;
            out.add(m);
        }
        return out;
    }

    private static int[] counts;

    private static int count(int c) {
        if (counts == null) {
            counts = new int[CATS.length];
            for (Module m : all()) for (int i = 0; i < CATS.length; i++) if (inCat(m, i, false)) counts[i]++;
        }
        return counts[c];
    }

    private float catY(int i) { return py() + 56 + i * 25; }

    private float editBtnY() { return py() + H() - 62; }

    private float closeBtnY() { return py() + H() - 34; }

    private float presetBtnY() { return py() + H() - 88; }

    // ---- where things are: {x, y, w, h}, for the sidebar and for the top-tabs layout

    private static final float TAB_Y = 40, TAB_H = 22;

    /** How much each tab shows so the row fits: 0 = icon + name + count, 1 = name + count, 2 = name only. */
    private int tabDetail() {
        for (int d = 0; d < 2; d++) {
            float need = 0;
            for (int i = 0; i < CLIENT_CAT; i++) need += tabW(i, d) + 4;
            if (need + actionsW() + 12 <= cw()) return d;
        }
        return 2;
    }

    private float tabW(int i, int detail) {
        float w = R.width(dev.fzkc.fzhud.util.Lang.t(CATS[i])) + 14;
        if (detail == 0) w += 16;
        if (detail <= 1) w += 8 + R.width(String.valueOf(count(i)));
        return w;
    }

    private float[] catRect(int i) {
        if (!top()) return new float[]{sx() + 8, catY(i), SW - 16, 23};
        int d = tabDetail();
        float x = x0();
        for (int j = 0; j < i; j++) x += tabW(j, d) + 4;
        return new float[]{x, py() + TAB_Y, tabW(i, d), TAB_H};
    }

    private String editLabel() {
        return mc.theWorld != null ? "HUD編集" : top() ? "HUD編集" : "HUD編集はワールド内で";
    }

    private float actionW(String label) { return R.width(dev.fzkc.fzhud.util.Lang.t(label)) + 18; }

    private float actionsW() {
        return actionW("プリセット・共有コード") + actionW("HUD編集") + actionW("閉じる") + 12;
    }

    /** 0 = presets, 1 = edit HUD, 2 = close */
    private float[] actionRect(int k) {
        if (!top()) {
            if (k == 0) return new float[]{sx() + 12, presetBtnY(), SW - 24, 20};
            if (k == 1) return new float[]{sx() + 12, editBtnY(), SW - 24, 22};
            return new float[]{sx() + 12, closeBtnY(), SW - 24, 20};
        }
        float wClose = actionW("閉じる"), wEdit = actionW("HUD編集"), wPreset = actionW("プリセット・共有コード");
        float right = x0() + cw(), y = py() + TAB_Y + 1;
        if (k == 2) return new float[]{right - wClose, y, wClose, 20};
        if (k == 1) return new float[]{right - wClose - 6 - wEdit, y, wEdit, 20};
        return new float[]{right - wClose - 6 - wEdit - 6 - wPreset, y, wPreset, 20};
    }

    private boolean in(int mx, int my, float[] r) { return Ui.inside(mx, my, r[0], r[1], r[2], r[3]); }

    /** The search box gets narrower in a small menu so the top bar still fits. */
    private float searchW() { return Math.max(90, Math.min(170, cw() - 300)); }
    private float searchX() { return x0() + cw() - searchW(); }
    private float langX() { return searchX() - 6 - Ui.langW(); }
    /** Slim "Client Settings" button left of the language switch. */
    private float clientW() { return R.width(CLIENT_LABEL) + 18; }
    private float clientX() { return langX() - 6 - clientW(); }
    /** Same row and height as the language switch and the search box. */
    private float clientY() { return py() + 12; }

    @Override
    protected void render(int mx, int my, float pt) {
        float o = open.update(1f);
        Ui.backdrop(vw, vh, o);
        Ui.panel(px(), py(), W(), H(), o);
        if (showHome()) {
            renderHome(mx, my, o);
            Decoration.corner(vw, vh, o);
            return;
        }

        // ---- categories: sidebar, or a row of tabs along the top
        if (!top()) {
            Ui.sidebar(sx(), py(), SW, H(), o);
            Ui.title(sx() + 10, py() + 4, 100, R.alpha(Ui.accent, o));
            R.text("Mods & Settings", sx() + 15, py() + 41, R.alpha(Ui.MUTED, o));
        } else {
            if (Ui.flat()) {
                float[] hr = homeRect();
                Ui.button(hr[0], hr[1], hr[2], hr[3], "‹ ホーム", anim("home", 0).update(in(mx, my, hr) ? 1 : 0), o);
            } else {
                Ui.title(x0() - 2, py() + 6, 66, R.alpha(Ui.accent, o));
            }
        }
        int detail = top() ? tabDetail() : 0;
        boolean icons = detail == 0;
        // categories only: Client Settings is the slim button in the top bar
        for (int i = 0; i < CLIENT_CAT; i++) {
            float[] r = catRect(i);
            boolean sel = i == cat;
            float h = anim("cat" + i, 0).update(sel ? 1 : in(mx, my, r) ? 0.4f : 0);
            R.rounded(r[0], r[1], r[2], r[3], Ui.rad(4f), R.alpha(R.lerpColor(0x00000000, Ui.accentA(0x30), h), o));
            if (sel) {
                if (top()) R.rounded(r[0] + 6, r[1] + r[3] - 3, r[2] - 12, 2, 1f, R.alpha(Ui.accent, o));
                else R.rounded(r[0], r[1] + 5, 3, 14, 1.5f, R.alpha(Ui.accent, o));
            }
            float tx = r[0] + (top() ? 7 : 9);
            if (icons) {
                Icons.draw(catIcon(i), tx, r[1] + (r[3] - 12) / 2f, 12, R.alpha(sel ? Ui.accent : 0xFF9DB4B8, o));
                tx += top() ? 16 : 17;
            }
            R.text(CATS[i], tx, r[1] + (r[3] - 8) / 2f, R.alpha(sel ? Ui.text : Ui.textA(0xC0), o));
            if (detail <= 1) {
                String cs = String.valueOf(count(i));
                R.text(cs, r[0] + r[2] - (top() ? 7 : 8) - R.width(cs), r[1] + (r[3] - 8) / 2f, R.alpha(Ui.MUTED, o));
            }
        }
        float[] pr = actionRect(0), er = actionRect(1), cr = actionRect(2);
        Ui.button(pr[0], pr[1], pr[2], pr[3], "プリセット・共有コード", anim("preset", 0).update(in(mx, my, pr) ? 1 : 0), o);
        Ui.primaryButton(er[0], er[1], er[2], er[3], editLabel(), anim("edit", 0).update(in(mx, my, er) ? 1 : 0), mc.theWorld != null || !top() ? o : o * 0.5f);
        Ui.button(cr[0], cr[1], cr[2], cr[3], "閉じる", anim("close", 0).update(in(mx, my, cr) ? 1 : 0), o);

        // ---- top bar
        if (!top()) {
            Icons.draw(catIcon(cat), x0(), py() + 12, 18, R.alpha(Ui.accent, o));
            // the title shrinks a little when a long name would run into the buttons
            String title = CATS[cat];
            float room = clientX() - 8 - (x0() + 24);
            float ts = Math.max(0.9f, Math.min(1.5f, room / Math.max(1f, R.width(title))));
            R.textScaled(title, x0() + 24, py() + 15 + (1.5f - ts) * 4f, ts, R.alpha(Ui.text, o));
        }
        float ch = anim("client", 0).update(Ui.inside(mx, my, clientX(), clientY(), clientW(), 20) ? 1 : 0);
        Ui.button(clientX(), clientY(), clientW(), 20, CLIENT_LABEL, ch, o);
        Ui.langButton(langX(), py() + 12, anim("lang", 0).update(Ui.inside(mx, my, langX(), py() + 12, Ui.langW(), 20) ? 1 : 0), o);
        // the search box always takes input, so it is always "focused" (caret shown even when empty)
        cursor = Math.max(0, Math.min(cursor, query.length()));
        Ui.textBox(searchX(), py() + 12, searchW(), SEARCH_H, query, "⌕ 検索", true, cursor, o);
        R.rect(x0(), top() ? py() + 68 : py() + 38, cw(), 1, R.alpha(Ui.LINE, o));

        // ---- grid
        List<Module> list = visible();
        int rows = (list.size() + cols() - 1) / cols();
        float content = rows * (CARD_H() + GAP());
        float maxScroll = Math.max(0, content - ch());
        scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
        float sc = scroll.update(scrollTarget);

        Ui.scissor(x0() - 2, y0(), cw() + 4, ch());
        for (int i = 0; i < list.size(); i++) {
            Module m = list.get(i);
            float x = x0() + (i % cols()) * (cardW() + GAP());
            float y = y0() + 4 + (i / cols()) * (CARD_H() + GAP()) - sc;
            if (y + CARD_H() < y0() || y > y0() + ch()) continue;
            boolean over = Ui.inside(mx, my, x, y, cardW(), CARD_H()) && my >= y0() && my < y0() + ch();
            float h = anim(m, 0).update(over ? 1 : 0);
            float t = anim("t" + m.id, m.enabled ? 1 : 0).update(m.enabled ? 1 : 0);

            Ui.card(x, y, cardW(), CARD_H(), h, t, o);
            if (t > 0.01f) R.rounded(x + 1, y + 8, 2.5f, CARD_H() - 16, 1.2f, R.alpha(Ui.accent, o * t));

            Icons.draw(m.id, x + 10, y + 7, 13, R.alpha(R.lerpColor(0xFF9DB4B8, Ui.accent, t), o));
            R.textScaled(m.name, x + 28, y + 9, 1.15f, R.alpha(Ui.text, o));
            R.text(Ui.trim(m.description, cardW() - 20), x + 10, y + 24, R.alpha(Ui.MUTED, o));
            if (m.toggleable) {
                Ui.toggle(x + 10, y + CARD_H() - 20, t, o);
                R.text(m.enabled ? "ON" : "OFF", x + 40, y + CARD_H() - 18, R.alpha(m.enabled ? Ui.accent : Ui.MUTED, o));
            }
            if (!m.settings.isEmpty()) {
                float bx = x + cardW() - 54, by = y + CARD_H() - 22;
                Ui.button(bx, by, 46, 16, "設定 ›",
                        anim("g" + m.id, 0).update(Ui.inside(mx, my, bx, by, 46, 16) ? 1 : 0), o);
            }
        }
        if (list.isEmpty()) {
            R.textCentered("見つかりません", x0() + cw() / 2f, y0() + 40, R.alpha(Ui.MUTED, o));
        }
        Ui.endScissor();

        if (maxScroll > 0) {
            float barH = Math.max(20, ch() * ch() / content);
            float barY = y0() + (ch() - barH) * (sc / maxScroll);
            R.rounded(x0() + cw() + 7, barY, 3, barH, 1.5f, R.alpha(Ui.flat() ? 0x50000000 : 0x70FFFFFF, o));
        }
        Decoration.corner(vw, vh, o);
    }

    // ================================================================== dashboard home

    /** Tile colours: すべて, HUD, PvP, Visual, Server, Utility. */
    private static final int[] TILE = {0xFF4CAF50, 0xFFA23CBF, 0xFF2D9CEB, 0xFFEF5350, 0xFFE73370, 0xFF4C5BC0};
    private static final float TILE_H = 56;
    private final Anim recentScroll = new Anim(0f, 16f);
    private float recentScrollTarget;

    private float[] homeRect() { return new float[]{x0(), py() + 12, R.width(dev.fzkc.fzhud.util.Lang.t("‹ ホーム")) + 18, 20}; }
    private float rightW() { return Math.max(150, Math.min(200, cw() * 0.3f)); }
    private float mainW() { return cw() - rightW() - 12; }
    private float tileW() { return (mainW() - 16) / 3f; }

    private float[] tileRect(int i) {
        int c = i % 3, r = i / 3;
        return new float[]{x0() + c * (tileW() + 8), py() + 46 + r * (TILE_H + 8), tileW(), TILE_H};
    }

    private float[] recentRect() {
        return new float[]{x0() + mainW() + 12, py() + 46, rightW(), H() - 56};
    }

    /** Home header buttons, left of "Client Settings": 0 = presets, 1 = edit HUD, 2 = close. */
    private float[] homeAction(int k) {
        float wClose = actionW("閉じる"), wEdit = actionW("HUD編集"), wPreset = actionW("プリセット");
        float right = clientX() - 6, y = py() + 12;
        if (k == 2) return new float[]{right - wClose, y, wClose, 20};
        if (k == 1) return new float[]{right - wClose - 6 - wEdit, y, wEdit, 20};
        return new float[]{right - wClose - 6 - wEdit - 6 - wPreset, y, wPreset, 20};
    }

    /** ON / all of the switchable features in a category. */
    private int[] onCount(int c) {
        int on = 0, n = 0;
        for (Module m : all()) {
            if (!inCat(m, c, false) || !m.toggleable) continue;
            n++;
            if (m.enabled) on++;
        }
        return new int[]{on, n};
    }

    private void renderHome(int mx, int my, float o) {
        // header: logo, buttons, client settings, language, search
        Ui.title(x0() - 2, py() + 6, 66, R.alpha(Ui.accent, o));
        float[] pr = homeAction(0), er = homeAction(1), cr = homeAction(2);
        Ui.button(pr[0], pr[1], pr[2], pr[3], "プリセット", anim("preset", 0).update(in(mx, my, pr) ? 1 : 0), o);
        Ui.primaryButton(er[0], er[1], er[2], er[3], "HUD編集", anim("edit", 0).update(in(mx, my, er) ? 1 : 0), mc.theWorld != null ? o : o * 0.5f);
        Ui.button(cr[0], cr[1], cr[2], cr[3], "閉じる", anim("close", 0).update(in(mx, my, cr) ? 1 : 0), o);
        float ch = anim("client", 0).update(Ui.inside(mx, my, clientX(), clientY(), clientW(), 20) ? 1 : 0);
        Ui.button(clientX(), clientY(), clientW(), 20, CLIENT_LABEL, ch, o);
        Ui.langButton(langX(), py() + 12, anim("lang", 0).update(Ui.inside(mx, my, langX(), py() + 12, Ui.langW(), 20) ? 1 : 0), o);
        cursor = Math.max(0, Math.min(cursor, query.length()));
        Ui.textBox(searchX(), py() + 12, searchW(), SEARCH_H, query, "⌕ 検索", true, cursor, o);
        R.rect(x0(), py() + 38, cw(), 1, R.alpha(Ui.LINE, o));

        // tiles: name, how many features, a big faint icon, and a darker "show" strip
        for (int i = 0; i < CLIENT_CAT && i < TILE.length; i++) {
            float[] r = tileRect(i);
            float h = anim("tile" + i, 0).update(in(mx, my, r) ? 1 : 0);
            int c = R.lerpColor(TILE[i], R.lerpColor(TILE[i], 0xFFFFFFFF, 0.12f), h);
            float strip = 12;
            R.rounded(r[0], r[1], r[2], r[3], Ui.rad(3f), R.alpha(c, o));
            R.rect(r[0], r[1] + r[3] - strip, r[2], strip - Math.min(Ui.rad(3f), 3f), R.alpha(R.lerpColor(c, 0xFF000000, 0.14f), o));
            R.rounded(r[0], r[1] + r[3] - strip, r[2], strip, Ui.rad(3f), R.alpha(R.lerpColor(c, 0xFF000000, 0.14f), o));
            Icons.draw(catIcon(i), r[0] + r[2] - 42, r[1] + 5, 34, R.alpha(0x40FFFFFF, o));
            boolean ns = R.noShadow;
            R.noShadow = false;   // white on colour reads better with a shadow
            R.textScaled(CATS[i], r[0] + 7, r[1] + 6, 1.25f, R.alpha(0xFFFFFFFF, o));
            String n = String.valueOf(count(i));
            R.textScaled(n, r[0] + r[2] - 7 - R.width(n) * 2f, r[1] + r[3] - strip - 18, 2f, R.alpha(0xFFFFFFFF, o));
            R.noShadow = ns;
            R.textCentered("表示", r[0] + r[2] / 2f, r[1] + r[3] - strip + 2, R.alpha(0xFFFFFFFF, o));
        }

        // rings: how many of each category's features are switched on
        float top = py() + 46 + 2 * (TILE_H + 8) + 6, bottom = py() + H() - 10;
        int n = Math.min(CLIENT_CAT, TILE.length) - 1;
        float slot = mainW() / n;
        float rad = Math.max(10, Math.min(slot / 2f - 8, (bottom - top - 16) / 2f));
        for (int k = 0; k < n; k++) {
            int i = k + 1;
            float cx = x0() + slot * k + slot / 2f;
            int[] oc = onCount(i);
            float frac = oc[1] == 0 ? 0 : oc[0] / (float) oc[1];
            float f = anim("ring" + i, 0).update(frac);
            R.textScaledCentered(CATS[i], cx, top, 1.1f, R.alpha(TILE[i], o));
            float cy = top + 14 + rad;
            Ui.ring(cx, cy, rad, rad * 0.72f, 0f, 1f, R.alpha(0xFFD5DADF, o));
            Ui.ring(cx, cy, rad, rad * 0.72f, 0f, f, R.alpha(TILE[i], o));
            String pct = Math.round(frac * 100) + "%";
            R.textScaledCentered(pct, cx, cy - 6, 1.4f, R.alpha(0xFFB4BEC5, o));
            R.textCentered(oc[0] + " / " + oc[1], cx, cy + rad + 3, R.alpha(Ui.MUTED, o));
        }

        // recent changes
        float[] rr = recentRect();
        Ui.card(rr[0], rr[1], rr[2], rr[3], 0f, -1f, o);
        R.textCentered("最近の変更", rr[0] + rr[2] / 2f, rr[1] + 6, R.alpha(Ui.text, o));
        List<RecentActions.Entry> list = RecentActions.list();
        float rowH = 20, listY = rr[1] + 20, listH = rr[3] - 24;
        float maxScroll = Math.max(0, list.size() * rowH - listH);
        recentScrollTarget = Math.max(0, Math.min(maxScroll, recentScrollTarget));
        float sc = recentScroll.update(recentScrollTarget);
        Ui.scissor(rr[0], listY, rr[2], listH);
        if (list.isEmpty()) R.textCentered("まだ変更はありません", rr[0] + rr[2] / 2f, listY + 10, R.alpha(Ui.MUTED, o));
        for (int i = 0; i < list.size(); i++) {
            float y = listY + i * rowH - sc;
            if (y + rowH < listY || y > listY + listH) continue;
            RecentActions.Entry e = list.get(i);
            boolean over = Ui.within(mx, my, rr[0] + 2, y, rr[2] - 4, rowH) && my >= listY && my < listY + listH;
            if (over && e.module != null) R.rounded(rr[0] + 3, y, rr[2] - 6, rowH, 3f, R.alpha(0x14000000, o));
            int dot = e.kind == RecentActions.ON ? 0xFF43A047 : e.kind == RecentActions.OFF ? 0xFFE53935
                    : e.kind == RecentActions.MANY ? 0xFF7A8992 : 0xFFF0B429;
            R.rounded(rr[0] + 8, y + 4, 5, 5, 2.5f, R.alpha(dot, o));
            Module m = RecentActions.module(e.module);
            String name = e.kind == RecentActions.MANY ? dev.fzkc.fzhud.util.Lang.t("まとめて変更")
                    : m != null ? m.name : String.valueOf(e.module);
            String detail = e.kind == RecentActions.ON ? "ON" : e.kind == RecentActions.OFF ? "OFF"
                    : e.kind == RecentActions.MANY ? dev.fzkc.fzhud.util.Lang.f("{0} 件", e.detail) : dev.fzkc.fzhud.util.Lang.t(e.detail);
            float tx = rr[0] + 17, room = rr[2] - 24;
            String nm = Ui.trim(name, room);
            R.text(nm, tx, y + 2, R.alpha(0xFF2F80C9, o));
            float dx = tx + R.width(nm) + 4;
            if (dx < rr[0] + rr[2] - 10) R.text(Ui.trim(detail, rr[0] + rr[2] - 8 - dx), dx, y + 2, R.alpha(Ui.text, o));
            String ago = RecentActions.ago(e.time);
            R.text(ago, rr[0] + rr[2] - 8 - R.width(ago), y + 11, R.alpha(Ui.MUTED, o));
        }
        Ui.endScissor();
    }

    private void clickHome(int mx, int my) {
        for (int i = 0; i < CLIENT_CAT && i < TILE.length; i++) {
            if (in(mx, my, tileRect(i))) {
                cat = i;
                home = false;
                scrollTarget = 0;
                return;
            }
        }
        if (in(mx, my, homeAction(0))) {
            mc.displayGuiScreen(new PresetScreen(this));
            return;
        }
        if (in(mx, my, homeAction(1))) {
            if (mc.theWorld != null) mc.displayGuiScreen(new EditScreen(this));
            return;
        }
        if (in(mx, my, homeAction(2))) {
            UiSound.cancel();
            mc.displayGuiScreen(parent);
            return;
        }
        if (Ui.inside(mx, my, clientX(), clientY(), clientW(), 20)) {
            mc.displayGuiScreen(new SettingsScreen(Modules.clientPage(), this));
            return;
        }
        if (Ui.inside(mx, my, langX(), py() + 12, Ui.langW(), 20)) {
            dev.fzkc.fzhud.util.Lang.toggle();
            return;
        }
        if (Ui.inside(mx, my, searchX(), py() + 12, searchW(), SEARCH_H)) {
            cursor = Ui.cursorFromX(query, searchX(), searchW(), cursor, mx);
            return;
        }
        // a recent change: open that feature's settings
        float[] rr = recentRect();
        float rowH = 20, listY = rr[1] + 20, listH = rr[3] - 24;
        if (!Ui.within(mx, my, rr[0], listY, rr[2], listH)) return;
        int idx = (int) ((my - listY + recentScroll.value) / rowH);
        List<RecentActions.Entry> list = RecentActions.list();
        if (idx < 0 || idx >= list.size()) return;
        Module m = RecentActions.module(list.get(idx).module);
        if (m != null) {
            UiSound.hit();
            if (m.settings.isEmpty()) {
                cat = 0;
                home = false;
            } else {
                mc.displayGuiScreen(new SettingsScreen(m, this));
            }
        }
    }

    @Override
    protected void click(int mx, int my, int button) {
        if (button != 0) return;
        if (showHome()) {
            clickHome(mx, my);
            return;
        }
        if (Ui.flat() && in(mx, my, homeRect())) {
            home = true;
            UiSound.cancel();
            return;
        }
        for (int i = 0; i < CLIENT_CAT; i++) {
            if (in(mx, my, catRect(i))) {
                cat = i;
                scrollTarget = 0;
                return;
            }
        }
        if (Ui.inside(mx, my, clientX(), clientY(), clientW(), 20)) {
            // every client setting on one page
            mc.displayGuiScreen(new SettingsScreen(Modules.clientPage(), this));
            return;
        }
        if (Ui.inside(mx, my, langX(), py() + 12, Ui.langW(), 20)) {
            dev.fzkc.fzhud.util.Lang.toggle();
            return;
        }
        // click in the search box: put the caret at the clicked spot
        if (Ui.inside(mx, my, searchX(), py() + 12, searchW(), SEARCH_H)) {
            cursor = Ui.cursorFromX(query, searchX(), searchW(), cursor, mx);
            return;
        }
        if (in(mx, my, actionRect(0))) {
            mc.displayGuiScreen(new PresetScreen(this));
            return;
        }
        if (in(mx, my, actionRect(1))) {
            if (mc.theWorld != null) mc.displayGuiScreen(new EditScreen(this));
            return;
        }
        if (in(mx, my, actionRect(2))) {
            UiSound.cancel();
            mc.displayGuiScreen(parent);
            return;
        }
        if (my < y0() || my >= y0() + ch()) return;
        List<Module> list = visible();
        for (int i = 0; i < list.size(); i++) {
            Module m = list.get(i);
            float x = x0() + (i % cols()) * (cardW() + GAP());
            float y = y0() + 4 + (i / cols()) * (CARD_H() + GAP()) - scroll.value;
            if (!Ui.inside(mx, my, x, y, cardW(), CARD_H())) continue;
            if (!m.settings.isEmpty() && (!m.toggleable || Ui.inside(mx, my, x + cardW() - 54, y + CARD_H() - 22, 46, 16))) {
                mc.displayGuiScreen(new SettingsScreen(m, this));
            } else if (m.toggleable) {
                m.enabled = !m.enabled;
                if (!m.enabled) UiSound.cancel();
            }
            return;
        }
    }

    @Override
    protected void scroll(int mx, int my, int amount) {
        if (showHome()) {
            recentScrollTarget += amount * 20;
            return;
        }
        scrollTarget += amount * 34;
    }

    @Override
    protected void key(char c, int keyCode) {
        cursor = Math.max(0, Math.min(cursor, query.length()));
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (!query.isEmpty()) {
                query = "";
                cursor = 0;
            } else {
                mc.displayGuiScreen(parent);
            }
            return;
        }
        if (keyCode == Keyboard.KEY_LEFT) {
            cursor = Math.max(0, cursor - 1);
            return;
        }
        if (keyCode == Keyboard.KEY_RIGHT) {
            cursor = Math.min(query.length(), cursor + 1);
            return;
        }
        if (keyCode == Keyboard.KEY_HOME) {
            cursor = 0;
            return;
        }
        if (keyCode == Keyboard.KEY_END) {
            cursor = query.length();
            return;
        }
        if (keyCode == Keyboard.KEY_DELETE) {
            if (cursor < query.length()) {
                query = query.substring(0, cursor) + query.substring(cursor + 1);
                scrollTarget = 0;
            }
            return;
        }
        if (keyCode == Keyboard.KEY_BACK) {
            if (cursor > 0) {
                query = query.substring(0, cursor - 1) + query.substring(cursor);
                cursor--;
            }
            scrollTarget = 0;
            return;
        }
        if (GuiScreen.isCtrlKeyDown() && keyCode == Keyboard.KEY_V) {
            insert(GuiScreen.getClipboardString());
            return;
        }
        if (ChatAllowedCharacters.isAllowedCharacter(c)) insert(String.valueOf(c));
    }

    /** Inserts text at the caret (so you can type between characters). */
    private void insert(String s) {
        if (showHome()) cat = 0;   // a search from the home page looks through everything
        if (s == null) return;
        for (char ch : s.toCharArray()) {
            if (query.length() >= MAX_QUERY) break;
            if (!ChatAllowedCharacters.isAllowedCharacter(ch)) continue;
            query = query.substring(0, cursor) + ch + query.substring(cursor);
            cursor++;
        }
        scrollTarget = 0;
    }
}
