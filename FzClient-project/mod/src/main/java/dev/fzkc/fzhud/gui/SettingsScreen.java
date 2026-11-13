package dev.fzkc.fzhud.gui;

import dev.fzkc.fzhud.config.BoolSetting;
import dev.fzkc.fzhud.config.ChoiceSetting;
import dev.fzkc.fzhud.config.ColorSetting;
import dev.fzkc.fzhud.config.HeaderSetting;
import dev.fzkc.fzhud.config.Module;
import dev.fzkc.fzhud.config.ModuleHeaderSetting;
import dev.fzkc.fzhud.config.Setting;
import dev.fzkc.fzhud.config.SliderSetting;
import dev.fzkc.fzhud.config.TextSetting;
import dev.fzkc.fzhud.hud.HudElement;
import dev.fzkc.fzhud.render.Anim;
import dev.fzkc.fzhud.render.Icons;
import dev.fzkc.fzhud.util.Lang;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.input.Keyboard;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** One module's settings, with a module list on the left for quick switching. */
public class SettingsScreen extends FzScreen {
    private static final float SW = 140, ROW = 28;
    private static final int[] PRESETS = {0x5CE1E6, 0xFFFFFF, 0xFF5555, 0x22C55E, 0xFFD84A, 0xFF6AD5,
            0xA78BFA, 0xFF9F43, 0x3B82F6, 0x111111, 0x9CA3AF};

    private Module module;
    private final GuiScreen parent;
    private final Anim open = new Anim(0f, 12f);
    private final Anim scroll = new Anim(0f, 16f), listScroll = new Anim(0f, 16f);
    private float scrollTarget, listScrollTarget;
    private final Map<Object, Anim> anims = new HashMap<Object, Anim>();
    /** Switch knob animations, one per on/off setting (kept apart from the row hover animations). */
    private final Map<Setting, Anim> switchAnims = new HashMap<Setting, Anim>();

    // interaction state
    private SliderSetting draggingSlider;
    private ColorSetting draggingSV, draggingHue;
    /** The small windows over the settings: all options of a choice as tiles, or the colour picker. */
    private ChoiceSetting choiceOpen;
    private ColorSetting colorOpen;
    private final Anim pop = new Anim(0f, 18f);
    private float choiceScroll;
    private Setting editing;          // TextSetting or ColorSetting (hex, inside the colour window)
    private String editBuffer = "";
    /** Caret position inside the text being edited (0..length). */
    private int cursor;
    private final Map<ColorSetting, Float> hues = new HashMap<ColorSetting, Float>();

    public SettingsScreen(Module module, GuiScreen parent) {
        this.module = module;
        this.parent = parent;
    }

    @Override
    public void initGui() {
        super.initGui();
        Keyboard.enableRepeatEvents(true);
    }

    /** Width of the preview on the right (0 when the panel is too narrow for it). */
    private static final float PV = 150;
    /**
     * The virtual screen is only about 640 wide at 1080p, so the panel may use almost all of it to make room for
     * the preview (the controls in each row get narrower to fit).
     */
    private float W() { return Math.min(vw - 16, (640 + PV) * dev.fzkc.fzhud.Modules.uiSize.get() / 100f); }
    private float pvW() { return (top() ? W() - 28 : W() - SW - 28) >= 430 ? PV : 0; }
    /** Width of a slider and of a text box in a row (narrower when the preview takes space). */
    private float sliderW() { return Math.min(140, cw() * .38f); }
    private float textW() { return Math.min(210, cw() * .56f); }
    /** Right edge of the header (the whole width, over the preview too). */
    private float hRight() { return x0() + cw() + (pvW() > 0 ? pvW() + 14 : 0) - 10; }
    private float H() { return Ui.menuH(vh, 340); }
    private float px() { return Ui.menuX(vw, W()); }
    private float py() { return (vh - H()) / 2f; }
    /** Left edge of the module list (left or right side of the panel, see the UI page). */
    private float sx() { return Ui.sidebarRight() ? px() + W() - SW : px(); }
    /** Categories along the top (UI page): no module list here, the back button moves into the header. */
    private boolean top() { return Ui.sidebarTop(); }
    private float backW() { return R.width(Lang.t("‹ MODS一覧")) + 18; }
    private float[] backRect() {
        return top() ? new float[]{x0(), py() + 13, backW(), 18} : new float[]{sx() + 10, py() + 10, SW - 20, 18};
    }
    /** Left edge of the header title (after the back button in the top layout). */
    private float hx() { return top() ? x0() + backW() + 10 : x0(); }
    private float x0() { return top() || Ui.sidebarRight() ? px() + 14 : px() + SW + 14; }
    private float cw() { return (top() ? W() - 28 : W() - SW - 28) - (pvW() > 0 ? pvW() + 14 : 0); }
    private float y0() { return py() + 62 + (pages().isEmpty() ? 0 : PAGE_BAR); }
    private float ch() { return H() - 70 - (pages().isEmpty() ? 0 : PAGE_BAR); }

    // ---------------------------------------------------------------- pages (modules with many settings)

    private static final float PAGE_BAR = 24;
    private int page;

    /** The page tabs of this module (empty for a module without pages). */
    private java.util.List<dev.fzkc.fzhud.config.PageSetting> pages() {
        java.util.List<dev.fzkc.fzhud.config.PageSetting> out = new java.util.ArrayList<>();
        for (Setting s : module.settings) if (s instanceof dev.fzkc.fzhud.config.PageSetting) out.add((dev.fzkc.fzhud.config.PageSetting) s);
        return out;
    }

    /** The settings shown right now: all of them, or only the current page's. */
    private java.util.List<Setting> visible() {
        if (pages().isEmpty()) return module.settings;
        java.util.List<Setting> out = new java.util.ArrayList<>();
        int p = -1;
        for (Setting s : module.settings) {
            if (s instanceof dev.fzkc.fzhud.config.PageSetting) {
                p++;
                continue;
            }
            if (p == page) out.add(s);
        }
        return out;
    }

    /** x, width of each page tab. */
    private float[][] pageTabs() {
        java.util.List<dev.fzkc.fzhud.config.PageSetting> ps = pages();
        float[][] r = new float[ps.size()][2];
        float x = x0();
        for (int i = 0; i < ps.size(); i++) {
            float w = R.width(Lang.t(ps.get(i).label)) + 18;
            r[i][0] = x;
            r[i][1] = w;
            x += w + 4;
        }
        return r;
    }

    private void drawPages(int mx, int my, float o) {
        java.util.List<dev.fzkc.fzhud.config.PageSetting> ps = pages();
        if (ps.isEmpty()) return;
        if (page >= ps.size()) page = 0;
        float[][] tabs = pageTabs();
        float y = py() + 56;
        for (int i = 0; i < ps.size(); i++) {
            boolean sel = i == page;
            float hv = anim("page" + i, 0).update(Ui.inside(mx, my, tabs[i][0], y, tabs[i][1], 18) ? 1 : 0);
            R.rounded(tabs[i][0], y, tabs[i][1], 18, 4f, R.alpha(sel ? Ui.accentA(0x40) : (hv > 0.01f ? 0x22FFFFFF : 0x10FFFFFF), o));
            if (sel) R.rect(tabs[i][0] + 4, y + 17, tabs[i][1] - 8, 1, R.alpha(Ui.accent, o));
            R.textCentered(Lang.t(ps.get(i).label), tabs[i][0] + tabs[i][1] / 2f, y + 5, R.alpha(sel ? Ui.text : Ui.textA(0xB0), o));
        }
    }
    private float right() { return x0() + cw() - 10; }

    private Anim switchAnim(Setting s, float init) {
        Anim a = switchAnims.get(s);
        if (a == null) {
            a = new Anim(init, 14f);
            switchAnims.put(s, a);
        }
        return a;
    }

    private Anim anim(Object key, float init) {
        Anim a = anims.get(key);
        if (a == null) {
            a = new Anim(init, 14f);
            anims.put(key, a);
        }
        return a;
    }

    private static List<Module> all() {
        return ModsScreen.all();
    }

    private float rowH(Setting s) {
        if (s instanceof ModuleHeaderSetting) return 38;
        if (s instanceof HeaderSetting) return 16;
        return ROW;
    }

    // ---------------------------------------------------------------- choices: side by side, or a window of tiles

    private static final float SEG_GAP = 2;

    /** Width of each option when the options sit side by side in the row. */
    private float[] segWidths(ChoiceSetting c) {
        float[] w = new float[c.options.length];
        for (int i = 0; i < w.length; i++) w[i] = Math.max(30, R.width(c.options[i]) + 14);
        return w;
    }

    private float segTotal(ChoiceSetting c) {
        float t = 0;
        for (float w : segWidths(c)) t += w + SEG_GAP;
        return t - SEG_GAP + 4;
    }

    /** A few short options fit in the row itself; longer lists open a window with all of them. */
    private boolean inline(ChoiceSetting c) {
        if (c.options.length > 4) return false;
        float room = cw() - 20 - Math.min(R.width(Lang.t(c.label)), cw() * 0.42f) - 12;
        return segTotal(c) <= Math.min(room, 250);
    }

    /** Width of the closed control (current value and a small arrow); a long value gets cut, not the label. */
    private float dropW(ChoiceSetting c) {
        return Math.max(100, Math.min(R.width(c.display()) + 34 + (picture(c) != null ? 16 : 0), cw() * 0.5f));
    }

    /** Small down arrow, 5 wide and 3 tall, centred on x. */
    private static void chevron(float x, float y, int argb) {
        for (int i = 0; i < 3; i++) R.rect(x - 2 + i, y + i, 5 - i * 2, 1, argb);
    }

    private void openChoice(ChoiceSetting c) {
        UiSound.hit();
        choiceOpen = c;
        colorOpen = null;
        choiceScroll = 0;
        pop.snap(0f);
        for (int i = 0; i < c.options.length; i++) anim("tile" + i, 0).snap(0f);
    }

    private void openColor(ColorSetting c) {
        UiSound.hit();
        colorOpen = c;
        choiceOpen = null;
        hues.remove(c);
        pop.snap(0f);
        anim("done", 0).snap(0f);
    }

    private void closePopup() {
        commitEdit();
        choiceOpen = null;
        colorOpen = null;
        draggingSV = null;
        draggingHue = null;
    }

    private boolean popupOpen() {
        return choiceOpen != null || colorOpen != null;
    }

    /** Dev check: the settings of a module by id (null when there is none). */
    public static SettingsScreen devOpen(String id, GuiScreen parent) {
        for (Module m : all()) if (m.id.equals(id)) return new SettingsScreen(m, parent);
        return null;
    }

    /** Dev check: opens the window of a setting by key (null closes it). */
    public void devPopup(String key) {
        closePopup();
        for (Setting s : module.settings) {
            if (!s.key.equals(key)) continue;
            if (s instanceof ChoiceSetting) openChoice((ChoiceSetting) s);
            else if (s instanceof ColorSetting) openColor((ColorSetting) s);
        }
        pop.snap(1f);
    }

    private static final float TILE_H = 22, TILE_GAP = 6, POP_HEAD = 30, POP_PAD = 12;

    /** The crosshair whose type this is (its options are drawn as pictures), or null. */
    private dev.fzkc.fzhud.hud.Crosshair picture(ChoiceSetting c) {
        for (Module m : all()) {
            if (m instanceof dev.fzkc.fzhud.hud.Crosshair && ((dev.fzkc.fzhud.hud.Crosshair) m).typeSetting() == c) return (dev.fzkc.fzhud.hud.Crosshair) m;
        }
        return null;
    }

    private float tileH(ChoiceSetting c) {
        return picture(c) != null ? 54 : TILE_H;
    }

    /** Width of one tile of the choice window (all the same, so they line up in a grid). */
    private float tileW(ChoiceSetting c) {
        float w = picture(c) != null ? 64 : 70;
        for (String o : c.options) w = Math.max(w, R.width(o) + 22);
        return Math.min(w, Math.min(200, popMaxW() - POP_PAD * 2));
    }

    /** The widest a window may be: inside the panel and the screen. */
    private float popMaxW() {
        return Math.max(120, Math.min(Math.min(W() - 20, vw - 16), 460));
    }

    private int tileCols(ChoiceSetting c) {
        float maxW = popMaxW() - POP_PAD * 2;
        int cols = (int) ((maxW + TILE_GAP) / (tileW(c) + TILE_GAP));
        return Math.max(1, Math.min(Math.min(cols, 5), c.options.length));
    }

    private float tileRowsH(ChoiceSetting c) {
        int rows = (c.options.length + tileCols(c) - 1) / tileCols(c);
        return rows * (tileH(c) + TILE_GAP) - TILE_GAP;
    }

    private float gridW(ChoiceSetting c) {
        return tileCols(c) * (tileW(c) + TILE_GAP) - TILE_GAP;
    }

    /** Keeps a window on the screen even when the panel is small. */
    private float[] place(float w, float h) {
        float x = px() + (W() - w) / 2f, y = py() + (H() - h) / 2f;
        x = Math.max(4, Math.min(x, vw - w - 4));
        y = Math.max(4, Math.min(y, vh - h - 4));
        return new float[]{x, y};
    }

    /** x, y, w, h of the choice window, the height of its visible tile area and the left edge of the tiles. */
    private float[] choiceRect(ChoiceSetting c) {
        float w = Math.min(popMaxW(), Math.max(gridW(c) + POP_PAD * 2, R.width(Lang.t(c.label)) + 60));
        float gridH = Math.max(tileH(c), Math.min(tileRowsH(c), Math.min(H(), vh - 8) - 90));
        float h = POP_HEAD + gridH + POP_PAD;
        float[] at = place(w, h);
        return new float[]{at[0], at[1], w, h, gridH, at[0] + (w - gridW(c)) / 2f};
    }

    private void clampChoiceScroll() {
        if (choiceOpen == null) return;
        choiceScroll = Math.max(0, Math.min(Math.max(0, tileRowsH(choiceOpen) - choiceRect(choiceOpen)[4]), choiceScroll));
    }

    // ---------------------------------------------------------------- the colour window

    private static final float SQ_W = 160, SQ_H = 104, HUE_W = 12, SIDE_W = 104;

    private float[] colorRect() {
        float w = POP_PAD + SQ_W + 8 + HUE_W + 12 + SIDE_W + POP_PAD;
        float h = POP_HEAD + SQ_H + 10 + 16 + 12 + 18 + POP_PAD;
        float[] at = place(w, h);
        return new float[]{at[0], at[1], w, h};
    }

    private float hue(ColorSetting c) {
        Float h = hues.get(c);
        if (h == null) {
            h = c.hsb()[0];
            hues.put(c, h);
        }
        return h;
    }

    // ---------------------------------------------------------------- text editing helpers

    /** The text currently being edited (a text setting's value, or the hex buffer of a colour). */
    private String editText() {
        if (editing instanceof TextSetting) {
            String v = ((TextSetting) editing).value;
            return v == null ? "" : v;
        }
        return editBuffer;
    }

    private void setEditText(String s) {
        if (editing instanceof TextSetting) ((TextSetting) editing).value = s;
        else editBuffer = s;
    }

    private int editMax() {
        return editing instanceof TextSetting ? ((TextSetting) editing).maxLength : 9;
    }

    private boolean editAllows(char ch) {
        if (editing instanceof TextSetting) return ChatAllowedCharacters.isAllowedCharacter(ch);
        return ch == '#' || Character.digit(ch, 16) >= 0 || Character.isLetter(ch);
    }

    private int clampCursor(String t) {
        return Math.max(0, Math.min(cursor, t.length()));
    }

    /** Inserts text at the caret, so you can type between characters. */
    private void insert(String text) {
        if (text == null || editing == null) return;
        String t = editText();
        int c = clampCursor(t);
        int max = editMax();
        for (char ch : text.toCharArray()) {
            if (t.length() >= max) break;
            if (!editAllows(ch)) continue;
            t = t.substring(0, c) + ch + t.substring(c);
            c++;
        }
        setEditText(t);
        cursor = c;
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void render(int mx, int my, float pt) {
        float o = open.update(1f);
        Ui.backdrop(vw, vh, o);
        Ui.panel(px(), py(), W(), H(), o);
        // with a window open, nothing behind it reacts to the mouse
        boolean popup = popupOpen();
        int bx = popup ? -10000 : mx, by = popup ? -10000 : my;
        drawSidebar(bx, by, o);
        drawHeader(bx, by, o);
        drawPages(bx, by, o);
        drawRows(bx, by, o);
        if (pvW() > 0) ModulePreview.draw(module, x0() + cw() + 14, py() + 58, pvW(), H() - 66, o);
        if (popup) {
            float p = pop.update(1f);
            R.rounded(px(), py(), W(), H(), Ui.rad(7f), R.alpha(0x70000000, o * p));
            if (choiceOpen != null) drawChoicePopup(choiceOpen, mx, my, o * p);
            else drawColorPopup(colorOpen, mx, my, o * p);
        }

        Decoration.corner(vw, vh, o);
    }

    /** The all-in-one Client Settings page (several modules on one page). */
    private boolean combined() {
        return module == dev.fzkc.fzhud.Modules.clientPage();
    }

    /** Reset on the combined page resets every client setting, so it asks for a second press. */
    private long resetArmedAt;

    private boolean resetArmed() {
        return System.currentTimeMillis() - resetArmedAt < 3000L;
    }

    private void drawSidebar(int mx, int my, float o) {
        float[] br = backRect();
        if (top()) {
            Ui.button(br[0], br[1], br[2], br[3], "‹ MODS一覧", anim("back", 0).update(Ui.inside(mx, my, br[0], br[1], br[2], br[3]) ? 1 : 0), o);
            return;
        }
        Ui.sidebar(sx(), py(), SW, H(), o);
        Ui.button(br[0], br[1], br[2], br[3], "‹ MODS一覧", anim("back", 0).update(Ui.inside(mx, my, br[0], br[1], br[2], br[3]) ? 1 : 0), o);

        List<Module> list = all();
        float top = py() + 36, h = H() - 44;
        float maxScroll = Math.max(0, list.size() * 18 - h);
        listScrollTarget = Math.max(0, Math.min(maxScroll, listScrollTarget));
        float sc = listScroll.update(listScrollTarget);
        Ui.scissor(sx(), top, SW, h);
        for (int i = 0; i < list.size(); i++) {
            Module m = list.get(i);
            float y = top + i * 18 - sc;
            if (y + 18 < top || y > top + h) continue;
            boolean sel = m == module;
            boolean over = Ui.inside(mx, my, sx() + 6, y, SW - 12, 17) && my >= top && my < top + h;
            if (sel || over) R.rounded(sx() + 6, y, SW - 12, 17, 3f, R.alpha(sel ? Ui.accentA(0x35) : 0x18FFFFFF, o));
            Icons.draw(m.id, sx() + 11, y + 3, 11, R.alpha(m.enabled ? Ui.accent : 0xFF5A666A, o));
            R.text(Ui.trim(m.name, SW - 38), sx() + 26, y + 5, R.alpha(sel ? Ui.text : Ui.textA(0xB0), o));
        }
        Ui.endScissor();
    }

    // header buttons are laid out from the text widths, so the "有効/無効" label never touches them
    /** Toggle + gap + label + gap before the buttons (0 when the module has no on/off switch). */
    private float toggleAreaW() {
        if (!module.toggleable) return 0f;
        return 24 + 4 + Math.max(R.width(Lang.t("有効")), R.width(Lang.t("無効"))) + 12;
    }

    private float resetW() { return Math.max(50f, R.width(Lang.t("リセット")) + 18f); }

    private float resetX() { return hRight() - toggleAreaW() - resetW(); }

    private float moveW() {
        return Math.max(56f, Math.max(R.width(Lang.t("位置を動かす")), R.width(Lang.t("ワールド内で"))) + 18f);
    }

    private float moveX() { return resetX() - 8 - moveW(); }

    private void drawHeader(int mx, int my, float o) {
        Icons.draw(combined() ? "menu_options" : module.id, hx(), py() + 11, 20, R.alpha(module.enabled ? Ui.accent : 0xFF9DB4B8, o));
        R.textScaled(module.name, hx() + 26, py() + 14, 1.6f, R.alpha(Ui.text, o));
        R.text(combined() ? Lang.t(module.description)
                : Lang.t(ModsScreen.categoryLabel(module)) + "  ·  " + Lang.t(module.description), hx(), py() + 34, R.alpha(Ui.MUTED, o));

        if (module.toggleable) {
            Ui.toggle(hRight() - 24, py() + 16, anim("enabled", module.enabled ? 1 : 0).update(module.enabled ? 1 : 0), o);
            String state = Lang.t(module.enabled ? "有効" : "無効");
            R.text(module.enabled ? "有効" : "無効", hRight() - 24 - 4 - R.width(state), py() + 18,
                    R.alpha(module.enabled ? Ui.accent : Ui.MUTED, o));
        }
        Ui.button(resetX(), py() + 13, resetW(), 18, combined() && resetArmed() ? "本当?" : "リセット", anim("reset", 0).update(Ui.inside(mx, my, resetX(), py() + 13, resetW(), 18) ? 1 : 0), o);
        if (module instanceof HudElement && ((HudElement) module).movable()) {
            boolean inWorld = mc.theWorld != null;
            Ui.button(moveX(), py() + 13, moveW(), 18, inWorld ? "位置を動かす" : "ワールド内で",
                    inWorld ? anim("move", 0).update(Ui.inside(mx, my, moveX(), py() + 13, moveW(), 18) ? 1 : 0) : 0f, inWorld ? o : o * 0.5f);
        }
        R.rect(x0(), py() + 50, hRight() + 10 - x0(), 1, R.alpha(Ui.LINE, o));
    }

    private void drawRows(int mx, int my, float o) {
        float total = 0;
        for (Setting s : visible()) total += rowH(s) + 4;
        float maxScroll = Math.max(0, total - ch());
        scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
        float sc = scroll.update(scrollTarget);

        Ui.scissor(x0() - 2, y0() - 4, cw() + 4, ch() + 4);
        float y = y0() - sc;
        for (Setting s : visible()) {
            float h = rowH(s);
            if (y + h >= y0() - 4 && y <= y0() + ch()) drawRow(s, y, h, mx, my, o);
            y += h + 4;
        }
        if (visible().isEmpty()) {
            R.textCentered("この機能に設定はありません", x0() + cw() / 2f, y0() + 30, R.alpha(Ui.MUTED, o));
        }
        Ui.endScissor();
        if (maxScroll > 0) {
            float barH = Math.max(20, ch() * ch() / total);
            float barY = y0() + (ch() - barH) * (sc / maxScroll);
            R.rounded(x0() + cw() + 7, barY, 3, barH, 1.5f, R.alpha(0x70FFFFFF, o));
        }
    }

    private void drawRow(Setting s, float y, float h, int mx, int my, float o) {
        if (s instanceof ModuleHeaderSetting) {
            // a module inside the combined page: icon, name, description and its own switch
            Module m = ((ModuleHeaderSetting) s).module;
            float top = y + 8;
            Icons.draw(m.id, x0() + 2, top + 2, 14, R.alpha(m.enabled ? Ui.accent : 0xFF9DB4B8, o));
            R.textScaled(m.name, x0() + 22, top + 2, 1.25f, R.alpha(Ui.text, o));
            float nx = x0() + 22 + R.width(m.name) * 1.25f + 8;
            R.text(Ui.trim(m.description, Math.max(20, right() - 40 - nx)), nx, top + 4, R.alpha(Ui.MUTED, o));
            if (m.toggleable) {
                Ui.toggle(right() - 24, top + 2, switchAnim(s, m.enabled ? 1 : 0).update(m.enabled ? 1 : 0), o);
            }
            R.rect(x0(), y + h - 3, cw(), 1, R.alpha(Ui.accentA(0x40), o));
            return;
        }
        if (s instanceof HeaderSetting) {
            // section title: accent text with a thin line after it
            R.text(s.label, x0() + 2, y + 6, R.alpha(Ui.accent, o));
            float lx = x0() + 10 + R.width(s.label);
            if (lx < x0() + cw()) R.rect(lx, y + 10, x0() + cw() - lx, 1, R.alpha(Ui.accentA(0x30), o));
            return;
        }
        boolean over = Ui.inside(mx, my, x0(), y, cw(), ROW) && my >= y0() && my < y0() + ch();
        float hv = anim(s, 0).update(over || s == draggingSlider ? 1 : 0);
        Ui.card(x0(), y, cw(), h, hv, -1f, o);
        float rx = right();
        R.text(Ui.trim(dev.fzkc.fzhud.util.Lang.t(s.label), Math.max(40, rx - controlW(s) - 12 - (x0() + 10))), x0() + 10, y + 10, R.alpha(Ui.text, o));

        if (s instanceof BoolSetting) {
            BoolSetting b = (BoolSetting) s;
            Ui.toggle(rx - 24, y + 8, switchAnim(s, b.get() ? 1 : 0).update(b.get() ? 1 : 0), o);
        } else if (s instanceof SliderSetting) {
            SliderSetting sl = (SliderSetting) s;
            float sw = sliderW();
            Ui.slider(rx - sw, y + 8, sw, (float) sl.fraction(), hv, o);
            String v = sl.display();
            float pw = Math.max(40, R.width(v) + 12);
            R.rounded(rx - sw - 10 - pw, y + 6, pw, 16, 3f, R.alpha(0xFF0A1114, o));
            R.textCentered(v, rx - sw - 10 - pw / 2f, y + 10, R.alpha(Ui.accent, o));
        } else if (s instanceof ChoiceSetting) {
            ChoiceSetting c = (ChoiceSetting) s;
            if (inline(c)) {
                // every option side by side: click the one you want
                float[] ws = segWidths(c);
                float x = rx - segTotal(c);
                R.rounded(x, y + 4, segTotal(c), 20, Ui.rad(5f), R.alpha(Ui.flat() ? 0x14000000 : 0x40000000, o));
                x += 2;
                for (int i = 0; i < ws.length; i++) {
                    boolean sel = i == c.index;
                    boolean on = over && Ui.inside(mx, my, x, y + 6, ws[i], 16);
                    float t = anim(c.options[i] + "#" + c.key, 0).update(on ? 1 : 0);
                    if (sel) R.rounded(x, y + 6, ws[i], 16, Ui.rad(4f), R.alpha(Ui.accentA(0x55), o));
                    else if (t > 0.01f) R.rounded(x, y + 6, ws[i], 16, Ui.rad(4f), R.alpha((int) (0x22 * t) << 24 | 0xFFFFFF, o));
                    if (sel) R.roundedOutline(x, y + 6, ws[i], 16, Ui.rad(4f), 1f, R.alpha(Ui.accent, o));
                    R.textCentered(c.options[i], x + ws[i] / 2f, y + 10, R.alpha(sel ? Ui.text : Ui.textA(0xA0), o));
                    x += ws[i] + SEG_GAP;
                }
            } else {
                // the current option; a click shows all of them in a window
                float bw = dropW(c);
                boolean on = over && mx >= rx - bw;
                Ui.button(rx - bw, y + 5, bw, 18, "", Math.max(hv * 0.5f, on ? 1f : 0f) * (choiceOpen == c ? 1f : 0.8f), o);
                dev.fzkc.fzhud.hud.Crosshair pic = picture(c);
                float tx = rx - bw + 9;
                if (pic != null) {
                    // the chosen crosshair next to its name
                    pic.drawType(c.index, rx - bw + 15, y + 14, 0.65f, o);
                    tx += 16;
                }
                R.text(Ui.trim(c.display(), bw - 30 - (tx - (rx - bw + 9))), tx, y + 10, R.alpha(Ui.text, o));
                chevron(rx - 12, y + 12, R.alpha(choiceOpen == c ? Ui.accent : Ui.textA(0xB0), o));
            }
        } else if (s instanceof TextSetting) {
            TextSetting ts = (TextSetting) s;
            boolean focused = editing == ts;
            String text = ts.get();
            // keys are not shown on screen (streams, screenshots) unless you click into the box
            if (ts.secret && !focused && !text.isEmpty()) text = dev.fzkc.fzhud.util.Lang.t("●●●●●●●● 設定済み");
            // not editing: show the end of a long text (as before); editing: follow the caret
            int caret = focused ? clampCursor(text) : text.length();
            boolean asTyped = ts == dev.fzkc.fzhud.Modules.nameHiderExtra;   // the names to hide, not hidden here
            if (asTyped) dev.fzkc.fzhud.feature.NameHider.raw(true);
            try {
                Ui.textBox(rx - textW(), y + 5, textW(), 18, text,
                        "クリックして入力（右クリックで消去）", focused, caret, o);
            } finally {
                if (asTyped) dev.fzkc.fzhud.feature.NameHider.raw(false);
            }
        } else if (s instanceof ColorSetting) {
            drawColorRow((ColorSetting) s, y, rx, mx, my, o);
        } else if (s instanceof dev.fzkc.fzhud.config.PixelGridSetting) {
            // a small picture of the hand-made crosshair and the button that opens the editor
            dev.fzkc.fzhud.config.PixelGridSetting pg = (dev.fzkc.fzhud.config.PixelGridSetting) s;
            float bw = gridBtnW();
            boolean on = over && Ui.within(mx, my, rx - bw, y + 5, bw, 18);
            Ui.button(rx - bw, y + 5, bw, 18, "作る・編集", Math.max(hv * 0.5f, on ? 1f : 0f), o);
            float cell = 18f / pg.size, tx = rx - bw - 8 - 18, ty = y + 5;
            R.rect(tx - 1, ty - 1, 20, 20, R.alpha(0xFF0A1012, o));
            for (int gy = 0; gy < pg.size; gy++) {
                for (int gx = 0; gx < pg.size; gx++) {
                    if (pg.get(gx, gy)) R.rect(tx + gx * cell, ty + gy * cell, cell, cell, R.alpha(0xFFFFFFFF, o));
                }
            }
        }
    }

    /** How wide the control on the right of a row is (the label stops before it). */
    private float controlW(Setting s) {
        if (s instanceof BoolSetting) return 26;
        if (s instanceof SliderSetting) return sliderW() + 10 + Math.max(40, R.width(((SliderSetting) s).display()) + 12);
        if (s instanceof ChoiceSetting) {
            ChoiceSetting c = (ChoiceSetting) s;
            return inline(c) ? segTotal(c) : dropW(c);
        }
        if (s instanceof TextSetting) return textW();
        if (s instanceof ColorSetting) return colorCtlW((ColorSetting) s);
        if (s instanceof dev.fzkc.fzhud.config.PixelGridSetting) return gridBtnW() + 8 + 18;
        return 0;
    }

    private float gridBtnW() {
        return Math.max(80, R.width("作る・編集") + 20);
    }

    private float colorCtlW(ColorSetting c) {
        return Math.max(R.width(c.hex()), R.width("クロマ")) + 8 + 28 + 8;
    }

    /** The row only shows the colour; a click opens the colour window (picker, HEX, chroma, presets). */
    private void drawColorRow(ColorSetting c, float y, float rx, int mx, int my, float o) {
        float sx = rx - 26;
        boolean on = colorOpen == c || (Ui.inside(mx, my, rx - colorCtlW(c), y + 4, colorCtlW(c), 20) && my >= y0() && my < y0() + ch());
        R.rounded(sx - 1, y + 4, 28, 20, 4f, R.alpha(0xFF0A1114, o));
        R.rounded(sx + 1, y + 6, 24, 16, 3f, R.alpha(c.get(), o));
        R.roundedOutline(sx + 1, y + 6, 24, 16, 3f, 1f, R.alpha(on ? Ui.accent : 0x80FFFFFF, o));
        String txt = c.chroma ? "クロマ" : c.hex();
        R.text(txt, sx - 8 - R.width(txt), y + 10, c.chroma ? R.alpha(R.CHROMA, o) : R.alpha(on ? Ui.text : Ui.textA(0xB0), o));
    }

    /** The window with every option of a choice as a tile. */
    private void drawChoicePopup(ChoiceSetting c, int mx, int my, float o) {
        float[] r = choiceRect(c);
        float wx = r[0], wy = r[1], ww = r[2], wh = r[3], gridH = r[4];
        popBase(wx, wy, ww, wh, o);
        Ui.panel(wx, wy, ww, wh, o);
        R.text(Ui.trim(Lang.t(c.label), ww - 50), wx + POP_PAD, wy + 11, R.alpha(Ui.text, o));
        drawClose(wx + ww - 22, wy + 7, mx, my, o);

        float maxScroll = Math.max(0, tileRowsH(c) - gridH);
        clampChoiceScroll();
        int cols = tileCols(c);
        float tw = tileW(c), th = tileH(c), gx = r[5], gy = wy + POP_HEAD;
        dev.fzkc.fzhud.hud.Crosshair pic = picture(c);
        Ui.scissor(wx, gy - 2, ww, gridH + 4);
        for (int i = 0; i < c.options.length; i++) {
            float tx = gx + (i % cols) * (tw + TILE_GAP), ty = gy + (i / cols) * (th + TILE_GAP) - choiceScroll;
            if (ty + th < gy - 2 || ty > gy + gridH + 2) continue;
            boolean sel = i == c.index;
            boolean on = Ui.within(mx, my, tx, ty, tw, th) && my >= gy && my < gy + gridH;
            float t = anim("tile" + i, 0).update(on ? 1 : 0);
            String label = Ui.trim(c.options[i], tw - 8);
            if (sel) {
                R.rounded(tx, ty, tw, th, Ui.rad(5f), R.alpha(Ui.accentA(0x55), o));
                R.roundedOutline(tx, ty, tw, th, Ui.rad(5f), 1.2f, R.alpha(Ui.accent, o));
            } else {
                Ui.button(tx, ty, tw, th, "", t, o);
            }
            if (pic != null) {
                // the crosshair itself, in your colour and size, with its name under it
                pic.drawType(i, tx + tw / 2f, ty + 21, 1.5f, o);
                R.textCentered(label, tx + tw / 2f, ty + th - 12, R.alpha(sel ? Ui.text : Ui.textA(0xB0), o));
            } else {
                R.textCentered(label, tx + tw / 2f, ty + (th - 8) / 2f, R.alpha(Ui.text, o));
            }
        }
        Ui.endScissor();
        if (maxScroll > 0) {
            float barH = Math.max(16, gridH * gridH / tileRowsH(c));
            R.rounded(wx + ww - 6, gy + (gridH - barH) * (choiceScroll / maxScroll), 3, barH, 1.5f, R.alpha(0x70FFFFFF, o));
        }
    }

    /** Solid ground under a window, so the rows behind do not show through it. */
    private static void popBase(float x, float y, float w, float h, float o) {
        int c = Ui.flat() ? Ui.FLAT_BG : Glass.on() ? 0xFF10181B : Ui.panelShade(0f, 0xFF);
        R.rounded(x, y, w, h, Ui.rad(Glass.on() ? 12f : 7f), R.alpha(0xFF000000 | c, o));
    }

    /** A small × in the corner of a window. */
    private void drawClose(float x, float y, int mx, int my, float o) {
        boolean on = Ui.inside(mx, my, x, y, 16, 16);
        if (on) R.rounded(x, y, 16, 16, Ui.rad(4f), R.alpha(0x30FFFFFF, o));
        R.textCentered("×", x + 8, y + 4, R.alpha(on ? Ui.text : Ui.textA(0xA0), o));
    }

    private void drawColorPopup(ColorSetting c, int mx, int my, float o) {
        float[] r = colorRect();
        float wx = r[0], wy = r[1], ww = r[2];
        popBase(wx, wy, ww, r[3], o);
        Ui.panel(wx, wy, ww, r[3], o);
        R.text(Ui.trim(Lang.t(c.label), ww - 50), wx + POP_PAD, wy + 11, R.alpha(Ui.text, o));
        drawClose(wx + ww - 22, wy + 7, mx, my, o);

        float bx = wx + POP_PAD, by = wy + POP_HEAD;
        float[] hsb = c.hsb();
        float hue = hue(c);
        int pure = java.awt.Color.HSBtoRGB(hue, 1f, 1f) | 0xFF000000;
        // saturation / brightness square
        Ui.gradient(bx, by, SQ_W, SQ_H, R.alpha(0xFFFFFFFF, o), R.alpha(pure, o), R.alpha(0xFF000000, o), R.alpha(0xFF000000, o));
        Ui.gradient(bx, by, SQ_W, SQ_H, 0x00000000, 0x00000000, R.alpha(0xFF000000, o), R.alpha(0xFF000000, o));
        R.roundedOutline(bx, by, SQ_W, SQ_H, 2f, 1f, R.alpha(0x40FFFFFF, o));
        if (!c.chroma) {
            float kx = bx + hsb[1] * SQ_W, ky = by + (1 - hsb[2]) * SQ_H;
            R.roundedOutline(kx - 3, ky - 3, 6, 6, 3f, 1.5f, R.alpha(0xFFFFFFFF, o));
        }
        // hue bar
        float hx = bx + SQ_W + 8;
        for (int i = 0; i < 6; i++) {
            int a = java.awt.Color.HSBtoRGB(i / 6f, 1f, 1f) | 0xFF000000;
            int b = java.awt.Color.HSBtoRGB((i + 1) / 6f, 1f, 1f) | 0xFF000000;
            Ui.gradient(hx, by + i * SQ_H / 6f, HUE_W, SQ_H / 6f, R.alpha(a, o), R.alpha(a, o), R.alpha(b, o), R.alpha(b, o));
        }
        R.rect(hx - 2, by + hue * SQ_H - 1, HUE_W + 4, 2, R.alpha(0xFFFFFFFF, o));

        // right side: the colour, HEX box, chroma
        float gx = hx + HUE_W + 12;
        R.rounded(gx - 1, by - 1, SIDE_W + 2, 30, 4f, R.alpha(Ui.flat() ? Ui.FLAT_BORDER : 0xFF0A1114, o));
        R.rounded(gx + 1, by + 1, SIDE_W - 2, 26, 3f, R.alpha(c.get(), o));
        R.text("HEX", gx, by + 37, R.alpha(Ui.MUTED, o));
        boolean focused = editing == c;
        String txt = focused ? editBuffer : (c.chroma ? "" : c.hex());
        Ui.textBox(gx, by + 47, SIDE_W, 18, txt, "#RRGGBB", focused, focused ? clampCursor(txt) : txt.length(), o);
        boolean chOver = Ui.inside(mx, my, gx, by + 72, SIDE_W, 18);
        R.rounded(gx, by + 72, SIDE_W, 18, 9f, R.alpha(c.chroma ? 0xFF1C2D33 : (Ui.flat() ? (chOver ? 0xFFE6ECF2 : 0xFFF1F4F7) : (chOver ? 0xFF1A2428 : 0xFF111A1D)), o));
        R.roundedOutline(gx, by + 72, SIDE_W, 18, 9f, 1f, R.alpha(c.chroma ? Ui.accent : (Ui.flat() ? Ui.FLAT_BORDER : 0x50FFFFFF), o));
        R.textCentered("クロマ", gx + SIDE_W / 2f, by + 77, c.chroma ? R.alpha(R.CHROMA, o) : R.alpha(Ui.MUTED, o));

        // presets under the square
        float ry = by + SQ_H + 10;
        for (int i = 0; i < PRESETS.length; i++) {
            float ppx = bx + i * 20;
            boolean sel = !c.chroma && c.rgb == PRESETS[i];
            R.rounded(ppx, ry, 16, 16, 4f, R.alpha(0xFF000000 | PRESETS[i], o));
            R.roundedOutline(ppx, ry, 16, 16, 4f, 1f, R.alpha(sel ? Ui.accent : 0x60FFFFFF, o));
        }
        // done
        float[] d = doneRect();
        Ui.primaryButton(d[0], d[1], d[2], d[3], "完了", anim("done", 0).update(Ui.inside(mx, my, d[0], d[1], d[2], d[3]) ? 1 : 0), o);
    }

    private float[] doneRect() {
        float[] r = colorRect();
        return new float[]{r[0] + r[2] - POP_PAD - 64, r[1] + r[3] - POP_PAD - 18, 64, 18};
    }

    // ---------------------------------------------------------------- input

    private void commitEdit() {
        if (editing instanceof ColorSetting) {
            ColorSetting c = (ColorSetting) editing;
            if (c.setHex(editBuffer)) hues.remove(c);
        }
        editing = null;
    }

    @Override
    protected void click(int mx, int my, int button) {
        Setting wasEditing = editing;
        commitEdit();
        if (popupOpen()) {
            popupClick(mx, my, button, wasEditing);
            return;
        }

        // sidebar
        float[] br = backRect();
        if (Ui.inside(mx, my, br[0], br[1], br[2], br[3])) {
            UiSound.cancel();
            mc.displayGuiScreen(parent);
            return;
        }
        List<Module> list = all();
        float top = py() + 36, h = H() - 44;
        if (!top() && Ui.within(mx, my, sx(), top, SW, h)) {
            int i = (int) ((my - top + listScroll.value) / 18);
            if (i >= 0 && i < list.size()) {
                UiSound.hit();
                module = list.get(i);
                page = 0;
                scrollTarget = 0;
            }
            return;
        }
        // header
        if (module.toggleable && Ui.inside(mx, my, hRight() - 26, py() + 12, 28, 18)) {
            module.enabled = !module.enabled;
            if (!module.enabled) UiSound.cancel();
            return;
        }
        if (Ui.inside(mx, my, resetX(), py() + 13, resetW(), 18)) {
            if (combined() && !resetArmed()) {
                resetArmedAt = System.currentTimeMillis();
                return;
            }
            resetArmedAt = 0;
            UiSound.cancel();
            module.resetSettings();
            hues.clear();
            return;
        }
        if (module instanceof HudElement && ((HudElement) module).movable() && Ui.inside(mx, my, moveX(), py() + 13, moveW(), 18)) {
            if (mc.theWorld != null) mc.displayGuiScreen(new EditScreen(this));
            return;
        }
        if (!pages().isEmpty() && my >= py() + 56 && my < py() + 74) {
            float[][] tabs = pageTabs();
            for (int i = 0; i < tabs.length; i++) {
                if (mx >= tabs[i][0] && mx < tabs[i][0] + tabs[i][1] && i != page) {
                    UiSound.hit();
                    page = i;
                    scrollTarget = 0;
                    editing = null;
                }
            }
            return;
        }
        if (my < y0() || my >= y0() + ch()) return;

        float y = y0() - scroll.value;
        for (Setting s : visible()) {
            float rh = rowH(s);
            if (my >= y && my < y + rh && mx >= x0() && mx < x0() + cw()) {
                clickRow(s, y, mx, my, button, wasEditing);
                return;
            }
            y += rh + 4;
        }
    }

    private void clickRow(Setting s, float y, int mx, int my, int button, Setting wasEditing) {
        if (s instanceof ModuleHeaderSetting) {
            Module m = ((ModuleHeaderSetting) s).module;
            if (button == 0 && m.toggleable && Ui.inside(mx, my, right() - 30, y + 6, 34, 20)) {
                m.enabled = !m.enabled;
                if (!m.enabled) UiSound.cancel();
            }
            return;
        }
        if (s instanceof HeaderSetting) return;
        float rx = right();
        if (s instanceof BoolSetting) {
            if (button == 0) {
                UiSound.hit();
                BoolSetting bs = (BoolSetting) s;
                bs.value = !bs.value;
                if (!bs.value) UiSound.cancel();
            }
        } else if (s instanceof SliderSetting) {
            if (button == 0 && mx >= rx - sliderW() - 10) {
                UiSound.hit();
                draggingSlider = (SliderSetting) s;
                setSlider(mx);
            }
        } else if (s instanceof ChoiceSetting) {
            ChoiceSetting c = (ChoiceSetting) s;
            if (button == 1) {
                // right click still steps back one option
                select(c, (c.index - 1 + c.options.length) % c.options.length);
            } else if (button == 0 && inline(c)) {
                float[] ws = segWidths(c);
                float x = rx - segTotal(c) + 2;
                for (int i = 0; i < ws.length; i++) {
                    if (Ui.within(mx, my, x, y + 6, ws[i], 16)) {
                        if (i != c.index) select(c, i);
                        return;
                    }
                    x += ws[i] + SEG_GAP;
                }
            } else if (button == 0 && mx >= rx - dropW(c)) {
                openChoice(c);
            }
        } else if (s instanceof TextSetting) {
            TextSetting ts = (TextSetting) s;
            if (button == 1) {
                ts.value = "";
            } else if (mx >= rx - textW()) {
                UiSound.hit();
                editing = s;
                String t = ts.get();
                // already editing this box: keep the current view; otherwise it was showing the end of the text
                int basis = wasEditing == s ? clampCursor(t) : t.length();
                cursor = Ui.cursorFromX(t, rx - textW(), textW(), basis, mx);
            }
        } else if (s instanceof ColorSetting) {
            if (button == 0 && mx >= rx - colorCtlW((ColorSetting) s)) openColor((ColorSetting) s);
        } else if (s instanceof dev.fzkc.fzhud.config.PixelGridSetting) {
            if (button == 0 && mx >= rx - gridBtnW() - 8 - 18) {
                dev.fzkc.fzhud.config.PixelGridSetting pg = (dev.fzkc.fzhud.config.PixelGridSetting) s;
                for (Module m : all()) {
                    if (m instanceof dev.fzkc.fzhud.hud.Crosshair && ((dev.fzkc.fzhud.hud.Crosshair) m).customGrid() == pg) {
                        UiSound.hit();
                        ((dev.fzkc.fzhud.hud.Crosshair) m).useCustom();
                        mc.displayGuiScreen(new CrosshairEditor(pg, (dev.fzkc.fzhud.hud.Crosshair) m, this));
                    }
                }
            }
        }
    }

    private void select(ChoiceSetting c, int i) {
        UiSound.hit();
        c.index = i;
        if (c == dev.fzkc.fzhud.Modules.uiTheme) Themes.apply(c.index);
    }

    /** A click while a window is open: it goes to the window, and a click outside it closes it. */
    private void popupClick(int mx, int my, int button, Setting wasEditing) {
        if (choiceOpen != null) {
            ChoiceSetting c = choiceOpen;
            float[] r = choiceRect(c);
            if (!Ui.within(mx, my, r[0], r[1], r[2], r[3]) || Ui.within(mx, my, r[0] + r[2] - 22, r[1] + 7, 16, 16)) {
                closePopup();
                return;
            }
            clampChoiceScroll();
            float gx = r[5], gy = r[1] + POP_HEAD, tw = tileW(c), th = tileH(c);
            if (button != 0 || my < gy || my >= gy + r[4]) return;
            int cols = tileCols(c);
            for (int i = 0; i < c.options.length; i++) {
                float tx = gx + (i % cols) * (tw + TILE_GAP), ty = gy + (i / cols) * (th + TILE_GAP) - choiceScroll;
                if (Ui.within(mx, my, tx, ty, tw, th)) {
                    select(c, i);
                    closePopup();
                    return;
                }
            }
            return;
        }
        ColorSetting c = colorOpen;
        float[] r = colorRect();
        float[] d = doneRect();
        if (!Ui.within(mx, my, r[0], r[1], r[2], r[3]) || Ui.within(mx, my, r[0] + r[2] - 22, r[1] + 7, 16, 16)
                || Ui.within(mx, my, d[0], d[1], d[2], d[3])) {
            if (Ui.within(mx, my, d[0], d[1], d[2], d[3])) UiSound.hit();
            closePopup();
            return;
        }
        if (button != 0) return;
        float bx = r[0] + POP_PAD, by = r[1] + POP_HEAD, hx = bx + SQ_W + 8, gx = hx + HUE_W + 12;
        if (Ui.within(mx, my, bx, by, SQ_W, SQ_H)) {
            draggingSV = c;
            setSV(mx, my);
        } else if (Ui.within(mx, my, hx - 3, by, HUE_W + 6, SQ_H)) {
            draggingHue = c;
            setHue(my);
        } else if (Ui.within(mx, my, gx, by + 47, SIDE_W, 18)) {
            UiSound.hit();
            editing = c;
            if (wasEditing != c) editBuffer = c.chroma ? "" : c.hex();
            // already typing here: keep the text and just move the caret
            cursor = Ui.cursorFromX(editBuffer, gx, SIDE_W, wasEditing == c ? clampCursor(editBuffer) : editBuffer.length(), mx);
        } else if (Ui.within(mx, my, gx, by + 72, SIDE_W, 18)) {
            UiSound.hit();
            c.chroma = !c.chroma;
        } else {
            float ry = by + SQ_H + 10;
            for (int i = 0; i < PRESETS.length; i++) {
                if (Ui.within(mx, my, bx + i * 20, ry, 16, 16)) {
                    UiSound.hit();
                    c.rgb = PRESETS[i];
                    c.chroma = false;
                    hues.remove(c);
                }
            }
        }
    }

    private void setSlider(int mx) {
        float rx = right(), sw = sliderW();
        double f = Math.max(0, Math.min(1, (mx - (rx - sw)) / sw));
        draggingSlider.set(draggingSlider.min + f * (draggingSlider.max - draggingSlider.min));
    }

    private void setSV(int mx, int my) {
        float[] r = colorRect();
        float bx = r[0] + POP_PAD, by = r[1] + POP_HEAD;
        float s = Math.max(0, Math.min(1, (mx - bx) / SQ_W));
        float b = 1 - Math.max(0, Math.min(1, (my - by) / SQ_H));
        draggingSV.setHsb(hue(draggingSV), s, b);
    }

    private void setHue(int my) {
        float by = colorRect()[1] + POP_HEAD;
        float h = Math.max(0, Math.min(0.999f, (my - by) / SQ_H));
        hues.put(draggingHue, h);
        float[] hsb = draggingHue.hsb();
        float sat = draggingHue.chroma || hsb[1] < 0.05f ? 1f : hsb[1];
        float bri = draggingHue.chroma || hsb[2] < 0.05f ? 1f : hsb[2];
        draggingHue.setHsb(h, sat, bri);
    }

    @Override
    protected void drag(int mx, int my) {
        if (draggingSlider != null) setSlider(mx);
        if (draggingSV != null) setSV(mx, my);
        if (draggingHue != null) setHue(my);
    }

    @Override
    protected void release(int mx, int my) {
        draggingSlider = null;
        draggingSV = null;
        draggingHue = null;
    }

    @Override
    protected void scroll(int mx, int my, int amount) {
        if (choiceOpen != null) {
            choiceScroll += amount * 28;
            clampChoiceScroll();
        }
        else if (colorOpen != null) return;
        else if (!top() && mx >= sx() && mx < sx() + SW) listScrollTarget += amount * 36;
        else scrollTarget += amount * 30;
    }

    @Override
    protected void key(char c, int keyCode) {
        if (editing != null) {
            String t = editText();
            cursor = clampCursor(t);
            if (keyCode == Keyboard.KEY_ESCAPE) {
                editing = null; // cancel
            } else if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
                commitEdit();
            } else if (keyCode == Keyboard.KEY_LEFT) {
                cursor = Math.max(0, cursor - 1);
            } else if (keyCode == Keyboard.KEY_RIGHT) {
                cursor = Math.min(t.length(), cursor + 1);
            } else if (keyCode == Keyboard.KEY_HOME) {
                cursor = 0;
            } else if (keyCode == Keyboard.KEY_END) {
                cursor = t.length();
            } else if (keyCode == Keyboard.KEY_DELETE) {
                if (cursor < t.length()) setEditText(t.substring(0, cursor) + t.substring(cursor + 1));
            } else if (keyCode == Keyboard.KEY_BACK) {
                if (cursor > 0) {
                    setEditText(t.substring(0, cursor - 1) + t.substring(cursor));
                    cursor--;
                }
            } else if (GuiScreen.isCtrlKeyDown() && keyCode == Keyboard.KEY_V) {
                insert(GuiScreen.getClipboardString());
            } else if (ChatAllowedCharacters.isAllowedCharacter(c)) {
                insert(String.valueOf(c));
            }
            return;
        }
        if (popupOpen()) {
            if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) closePopup();
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) mc.displayGuiScreen(parent);
    }

    @Override
    public void onGuiClosed() {
        commitEdit();
        super.onGuiClosed();
    }
}
