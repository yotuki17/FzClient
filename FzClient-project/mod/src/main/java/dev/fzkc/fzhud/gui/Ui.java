package dev.fzkc.fzhud.gui;

import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

/** Shared widgets for the FzClient menus (coordinates in the current screen's virtual pixels). */
public final class Ui {
    private Ui() {}

    /** Set by FzScreen: GUI-pixels per virtual pixel (needed for scissor). */
    public static float k = 1f;

    // palette
    public static final int BG = 0xF00C1215;
    public static final int SIDEBAR = 0xF0091013;
    public static final int CARD = 0xFF141D21;
    public static final int CARD_HOVER = 0xFF1B282D;
    /** Divider lines and grey labels; light versions while the dashboard style is on (see refresh()). */
    public static int LINE = 0x30FFFFFF;
    public static int MUTED = 0xFF8FA5AA;

    // dashboard (light, flat) palette
    public static final int FLAT_BG = 0xFFEEF1F5, FLAT_SIDE = 0xFFE2E7ED, FLAT_CARD = 0xFFFFFFFF,
            FLAT_BORDER = 0xFFC9D2DB, FLAT_TEXT = 0xFF1B252B, FLAT_MUTED = 0xFF55656F;

    /** True with the "ダッシュボード" style: light panels, flat coloured widgets, dark text. */
    public static boolean flat() {
        return dev.fzkc.fzhud.Modules.uiStyle.get() == 2;
    }

    // ------------------------------------------------------------------ look & layout from the "UI" page

    /** Menu accent colour (ARGB, opaque); refreshed every frame by {@link #refresh()}. */
    public static int accent = 0xFF5CE1E6;
    /** Main text colour of the menus. */
    public static int text = 0xFFFFFFFF;

    /** Reads the UI page once per frame (FzScreen calls it before drawing). Chroma colours flow. */
    public static void refresh() {
        accent = 0xFF000000 | (R.solid(dev.fzkc.fzhud.Modules.uiAccent.get()) & 0xFFFFFF);
        text = 0xFF000000 | (R.solid(dev.fzkc.fzhud.Modules.uiTextColor.get()) & 0xFFFFFF);
        boolean f = flat();
        if (f) {
            // light panels: a light text colour (white, the default, or any pale one) is unreadable, and a pale accent
            // (the cyan default) is too faint for titles and links: those are made dark enough here
            if (luma(text) > 0.55f) text = FLAT_TEXT;
            for (int i = 0; i < 6 && luma(accent) > 0.5f; i++) accent = R.lerpColor(accent, 0xFF000000, 0.18f) | 0xFF000000;
        }
        MUTED = f ? FLAT_MUTED : 0xFF8FA5AA;
        LINE = f ? FLAT_BORDER : 0x30FFFFFF;
    }

    /**
     * The text and accent exactly as set, without the darkening for light panels: for screens that draw on the
     * game picture (the title screen) even while the dashboard style is on.
     */
    public static void refreshRaw() {
        accent = 0xFF000000 | (R.solid(dev.fzkc.fzhud.Modules.uiAccent.get()) & 0xFFFFFF);
        text = 0xFF000000 | (R.solid(dev.fzkc.fzhud.Modules.uiTextColor.get()) & 0xFFFFFF);
        if (luma(text) < 0.45f) text = 0xFFFFFFFF;
    }

    /** Text for a light dashboard button (its own colour when that is dark enough). */
    public static int buttonText() {
        return flat() && luma(text) > 0.55f ? FLAT_TEXT : text;
    }

    /** Perceived brightness 0..1 of an RGB colour. */
    public static float luma(int rgb) {
        return (0.299f * (rgb >> 16 & 255) + 0.587f * (rgb >> 8 & 255) + 0.114f * (rgb & 255)) / 255f;
    }

    /** Pie / ring piece from fraction {@code from} to {@code to} (0 = top, clockwise), for the dashboard. */
    public static void ring(float cx, float cy, float rOuter, float rInner, float from, float to, int argb) {
        if (to <= from) return;
        int seg = Math.max(2, (int) Math.ceil((to - from) * 72));
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.disableCull();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        color(argb);
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i <= seg; i++) {
            double a = (from + (to - from) * i / seg) * Math.PI * 2 - Math.PI / 2;
            float c = (float) Math.cos(a), sn = (float) Math.sin(a);
            GL11.glVertex2f(cx + c * rOuter, cy + sn * rOuter);
            GL11.glVertex2f(cx + c * rInner, cy + sn * rInner);
        }
        GL11.glEnd();
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.resetColor();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /** The accent with its own alpha (0..255), e.g. for a faint highlight. */
    public static int accentA(int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (accent & 0xFFFFFF);
    }

    /** The text colour with an alpha (0..255) for dimmer labels. */
    public static int textA(int a) {
        return (Math.max(0, Math.min(255, a)) << 24) | (text & 0xFFFFFF);
    }

    /** Corner radius scaled by the "角の丸み" slider. */
    public static float rad(float r) {
        return r * dev.fzkc.fzhud.Modules.uiRadius.get() / 100f;
    }

    /** 0..1 from the "パネルの濃さ" slider. */
    public static float opacity() {
        return dev.fzkc.fzhud.Modules.uiPanelOpacity.get() / 100f;
    }

    /** Panel colour (RGB only). */
    public static int panelRgb() {
        return R.solid(dev.fzkc.fzhud.Modules.uiPanelColor.get()) & 0xFFFFFF;
    }

    /** A colour from the panel colour: lighter by {@code lift} (-1..1, negative = darker) and with alpha {@code a} (0..255). */
    public static int panelShade(float lift, int a) {
        int base = 0xFF000000 | panelRgb();
        int rgb = (lift >= 0 ? R.lerpColor(base, 0xFFFFFFFF, Math.min(1f, lift))
                : R.lerpColor(base, 0xFF000000, Math.min(1f, -lift))) & 0xFFFFFF;
        return (Math.max(0, Math.min(255, (int) (a * opacity()))) << 24) | rgb;
    }

    /** Menu width / height: the screen's own maximum, scaled by "メニューの大きさ" (never past the window). */
    public static float menuW(int vw, float max) {
        return Math.min(vw - 40, max * dev.fzkc.fzhud.Modules.uiSize.get() / 100f);
    }

    public static float menuH(int vh, float max) {
        // only grows: the sidebars need their full height for the buttons at the bottom
        return Math.min(vh - 30, max * Math.max(1f, dev.fzkc.fzhud.Modules.uiSize.get() / 100f));
    }

    /** Left edge of a menu of width w: centred, left or right ("メニューの位置"). */
    public static float menuX(int vw, float w) {
        switch (dev.fzkc.fzhud.Modules.uiPosition.get()) {
            case 1: return 20f;
            case 2: return vw - w - 20f;
            default: return (vw - w) / 2f;
        }
    }

    /** True when the menu sits on the right (the corner logo then moves to the left). */
    public static boolean menuOnRight() {
        return dev.fzkc.fzhud.Modules.uiPosition.get() == 2;
    }

    /** True when the two-column menus put their sidebar on the right. */
    public static boolean sidebarRight() {
        return dev.fzkc.fzhud.Modules.uiSidebarSide.get() == 1;
    }

    /** True when the categories are a row of tabs along the top instead of a sidebar. */
    public static boolean sidebarTop() {
        return dev.fzkc.fzhud.Modules.uiSidebarSide.get() == 2;
    }

    /** Mouse over a control. While a click is being handled, a true result also means "play the touch sound". */
    public static boolean inside(int mx, int my, float x, float y, float w, float h) {
        boolean in = within(mx, my, x, y, w, h);
        if (in && UiSound.clicking) UiSound.hit();
        return in;
    }

    /** Same test without the sound (for areas that are not controls themselves, e.g. a list's background). */
    public static boolean within(int mx, int my, float x, float y, float w, float h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public static void button(float x, float y, float w, float h, String label, float hover, float alpha) {
        if (flat()) {
            R.rounded(x, y, w, h, rad(4f), R.alpha(R.lerpColor(0xFFFFFFFF, 0xFFE8EEF4, hover), alpha));
            R.roundedOutline(x, y, w, h, rad(4f), 1f, R.alpha(R.lerpColor(FLAT_BORDER, accent, hover), alpha));
            R.textCentered(label, x + w / 2f, y + (h - 8) / 2f, R.alpha(buttonText(), alpha));
            return;
        }
        if (Glass.on()) {
            Glass.surface(x, y, w, h, rad(Math.min(h / 2f, 9f)), alpha, hover, hover * 0.22f, 1f);
            R.textCentered(label, x + w / 2f, y + (h - 8) / 2f, R.alpha(Ui.text, alpha));
            return;
        }
        R.rounded(x, y, w, h, rad(4f), R.alpha(R.lerpColor(panelShade(0.06f, 255), panelShade(0.14f, 255), hover), alpha));
        R.roundedOutline(x, y, w, h, rad(4f), 1f, R.alpha(R.lerpColor(0x60FFFFFF, accent, hover), alpha));
        R.textCentered(label, x + w / 2f, y + (h - 8) / 2f, R.alpha(Ui.text, alpha));
    }

    public static void primaryButton(float x, float y, float w, float h, String label, float hover, float alpha) {
        if (flat()) {
            R.rounded(x, y, w, h, rad(4f), R.alpha(R.lerpColor(accent, R.lerpColor(accent, 0xFF000000, 0.15f), hover), alpha));
            R.textCentered(label, x + w / 2f, y + (h - 8) / 2f, R.alpha(0xFFFFFFFF, alpha));
            return;
        }
        if (Glass.on()) {
            Glass.surface(x, y, w, h, rad(Math.min(h / 2f, 9f)), alpha, 0.5f + hover * 0.5f, 0.85f + hover * 0.15f, 1f);
            R.textCentered(label, x + w / 2f, y + (h - 8) / 2f, R.alpha(Ui.text, alpha));
            return;
        }
        R.rounded(x, y, w, h, rad(4f), R.alpha(R.lerpColor(R.lerpColor(accent, 0xFF000000, 0.2f), accent, hover), alpha));
        R.textCentered(label, x + w / 2f, y + (h - 8) / 2f, R.alpha(0xFF071012, alpha));
    }

    /** Pill switch 24x12. t = 0..1 animated state. */
    public static void toggle(float x, float y, float t, float alpha) {
        if (flat()) {
            R.rounded(x, y, 24, 12, rad(6f), R.alpha(R.lerpColor(0xFFAEB9C3, accent, t), alpha));
            R.rounded(x + 2 + t * 12, y + 2, 8, 8, rad(4f), R.alpha(0xFFFFFFFF, alpha));
            return;
        }
        if (Glass.on()) {
            Glass.surface(x, y, 24, 12, rad(6f), alpha, 0f, t * 0.9f, 0.7f);
            float kx = x + 2 + t * 12;
            R.rounded(kx, y + 2, 8, 8, rad(4f), R.alpha(0xF2FFFFFF, alpha));
            Glass.rim(kx, y + 2, 8, 8, 4f, 0.8f, alpha, 0.6f, 0xFFFFFF);
            return;
        }
        R.rounded(x, y, 24, 12, rad(6f), R.alpha(R.lerpColor(0xFF3A4A4F, accent, t), alpha));
        R.rounded(x + 2 + t * 12, y + 2, 8, 8, rad(4f), R.alpha(0xFFFFFFFF, alpha));
    }

    public static void slider(float x, float y, float w, float frac, float hover, float alpha) {
        if (flat()) {
            float f = Math.max(0f, Math.min(1f, frac));
            R.rounded(x, y + 4, w, 4, 2f, R.alpha(0xFFD3DAE0, alpha));
            R.rounded(x, y + 4, Math.max(4, w * f), 4, 2f, R.alpha(accent, alpha));
            float r = 4.5f + hover;
            R.roundedOutline(x + w * f - r, y + 6 - r, r * 2, r * 2, r, 1.5f, R.alpha(accent, alpha));
            R.rounded(x + w * f - r + 1, y + 6 - r + 1, r * 2 - 2, r * 2 - 2, r - 1, R.alpha(0xFFFFFFFF, alpha));
            return;
        }
        frac = Math.max(0f, Math.min(1f, frac));
        if (Glass.on()) {
            R.rounded(x, y + 4, w, 4, rad(2f), R.alpha(0x99060B0D, alpha));
            R.rounded(x, y + 4, Math.max(4, w * frac), 4, rad(2f), R.alpha(accent, alpha));
            float kr = 4.5f + hover;
            float kx = x + w * frac - kr;
            R.rounded(kx, y + 6 - kr, kr * 2, kr * 2, kr, R.alpha(0xF2FFFFFF, alpha));
            Glass.rim(kx, y + 6 - kr, kr * 2, kr * 2, kr, 1f, alpha, 0.7f, 0xFFFFFF);
            return;
        }
        R.rounded(x, y + 4, w, 4, rad(2f), R.alpha(0xFF2C3A3F, alpha));
        R.rounded(x, y + 4, Math.max(4, w * frac), 4, rad(2f), R.alpha(accent, alpha));
        float r = 4.5f + hover;
        R.rounded(x + w * frac - r, y + 6 - r, r * 2, r * 2, r, R.alpha(0xFFFFFFFF, alpha));
    }

    /** Frame of the text boxes: a dark inset in glass mode. */
    private static void boxFrame(float x, float y, float w, float h, boolean focused, float alpha) {
        if (flat()) {
            R.rounded(x, y, w, h, rad(3f), R.alpha(0xFFFFFFFF, alpha));
            R.roundedOutline(x, y, w, h, rad(3f), 1f, R.alpha(focused ? accent : FLAT_BORDER, alpha));
            return;
        }
        if (Glass.on()) {
            R.rounded(x, y, w, h, rad(4f), R.alpha(0xCC04080A, alpha));
            if (focused) R.roundedOutline(x, y, w, h, rad(4f), 1f, R.alpha(accent, alpha));
            else Glass.rim(x, y, w, h, 4f, 1f, alpha, 0.38f, 0xFFFFFF);
            return;
        }
        R.rounded(x, y, w, h, rad(3f), R.alpha(0xFF070C0E, alpha));
        R.roundedOutline(x, y, w, h, rad(3f), 1f, R.alpha(focused ? accent : 0x50FFFFFF, alpha));
    }

    /** Text box whose "_" caret always sits at the end of the text (used by the settings screens). */
    public static void textBox(float x, float y, float w, float h, String text, String placeholder, boolean focused, float alpha) {
        boxFrame(x, y, w, h, focused, alpha);
        float ty = y + (h - 8) / 2f;
        if ((text == null || text.isEmpty()) && !focused) {
            R.text(placeholder, x + 5, ty, R.alpha(flat() ? MUTED : 0x70FFFFFF, alpha));
            return;
        }
        String vis = text == null ? "" : text;
        while (R.width(vis) > w - 14 && !vis.isEmpty()) vis = vis.substring(1);
        boolean blink = focused && (System.currentTimeMillis() / 500) % 2 == 0;
        R.text(vis + (blink ? "_" : ""), x + 5, ty, R.alpha(Ui.text, alpha));
    }

    /**
     * Same look as the box above, but the caret is a thin vertical bar drawn at {@code cursor}
     * (0..text.length()), so it can sit between characters. It also shows when the text is empty.
     */
    public static void textBox(float x, float y, float w, float h, String text, String placeholder,
                               boolean focused, int cursor, float alpha) {
        boxFrame(x, y, w, h, focused, alpha);
        float ty = y + (h - 8) / 2f;
        String t = text == null ? "" : text;
        if (t.isEmpty() && !focused) {
            R.text(placeholder, x + 5, ty, R.alpha(flat() ? MUTED : 0x70FFFFFF, alpha));
            return;
        }
        cursor = Math.max(0, Math.min(cursor, t.length()));
        int start = viewStart(t, cursor, w);
        int end = t.length();
        while (end > cursor && R.width(t.substring(start, end)) > w - 14) end--;
        if (t.isEmpty()) {
            // empty and focused: hint text stays visible next to the caret
            R.text(placeholder, x + 8, ty, R.alpha(0x50FFFFFF, alpha));
        } else {
            R.text(t.substring(start, end), x + 5, ty, R.alpha(Ui.text, alpha));
        }
        if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
            float cx = x + 5 + R.width(t.substring(start, cursor));
            R.rect(cx, ty - 1, 1, 10, R.alpha(accent, alpha));
        }
    }

    /** Clicked x position -> index in the text (for placing the caret with the mouse). */
    public static int cursorFromX(String text, float boxX, float boxW, int cursor, float mx) {
        String t = text == null ? "" : text;
        cursor = Math.max(0, Math.min(cursor, t.length()));
        int start = viewStart(t, cursor, boxW);
        float rel = mx - (boxX + 5);
        int best = start;
        float bestDist = Float.MAX_VALUE;
        for (int i = start; i <= t.length(); i++) {
            float d = Math.abs(R.width(t.substring(start, i)) - rel);
            if (d < bestDist) {
                bestDist = d;
                best = i;
            }
        }
        return best;
    }

    /** First visible character, chosen so that the caret stays inside the box. */
    private static int viewStart(String t, int cursor, float w) {
        int start = 0;
        while (start < cursor && R.width(t.substring(start, cursor)) > w - 14) start++;
        return start;
    }

    public static void panel(float x, float y, float w, float h, float alpha) {
        if (flat()) {
            R.rounded(x - 1, y + 1, w + 2, h + 2, rad(7f), R.alpha(0x40000000, alpha));
            R.rounded(x, y, w, h, rad(6f), R.alpha(FLAT_BG, alpha));
            return;
        }
        if (Glass.on()) {
            Glass.shadow(x, y, w, h, rad(12f), alpha);
            Glass.surface(x, y, w, h, rad(12f), alpha, 0f, 0f, 1f);
            return;
        }
        R.rounded(x - 1, y - 1, w + 2, h + 2, rad(8f), R.alpha(0x60000000, alpha));
        R.rounded(x, y, w, h, rad(7f), R.alpha(panelShade(0f, 0xF0), alpha));
        R.roundedOutline(x, y, w, h, rad(7f), 1.2f, R.alpha(accentA(0x50), alpha));
    }

    /** Dark veil behind a whole menu (lighter when the picture behind is blurred). */
    public static void backdrop(float w, float h, float alpha) {
        if (flat()) {
            R.rect(0, 0, w, h, R.alpha(backdropAlpha(0x90) << 24, alpha));
            return;
        }
        if (Glass.on()) Glass.backdrop(w, h, alpha);
        else R.rect(0, 0, w, h, R.alpha(backdropAlpha(0xA0) << 24, alpha));
    }

    /** Veil strength from "後ろの画面の暗さ" (60% = the original look). */
    public static int backdropAlpha(int original) {
        return Math.max(0, Math.min(255, Math.round(original * dev.fzkc.fzhud.Modules.uiBackdrop.get() / 60f)));
    }

    /** Left column of the two-column menus. */
    public static void sidebar(float x, float y, float w, float h, float alpha) {
        if (flat()) {
            R.rounded(x, y, w, h, rad(6f), R.alpha(FLAT_SIDE, alpha));
            return;
        }
        if (Glass.on()) R.rounded(x, y, w, h, rad(12f), R.alpha((int) (0x58 * opacity()) << 24, alpha));
        else R.rounded(x, y, w, h, rad(7f), R.alpha(panelShade(-0.25f, 0xF0), alpha));
    }

    /**
     * A row / card. accentT = 0..1 how "on" it is (also tints the edge); pass a negative value for a plain row
     * (no accent, and no outline in the classic look).
     */
    public static void card(float x, float y, float w, float h, float hover, float accentT, float alpha) {
        if (flat()) {
            R.rounded(x, y + 1, w, h, rad(4f), R.alpha(0x14000000, alpha));   // a soft shadow under each card
            R.rounded(x, y, w, h, rad(4f), R.alpha(R.lerpColor(FLAT_CARD, 0xFFF6F9FC, hover), alpha));
            R.roundedOutline(x, y, w, h, rad(4f), 1f, R.alpha(R.lerpColor(FLAT_BORDER, accent, hover), alpha));
            return;
        }
        if (Glass.on()) {
            float at = Math.max(0f, accentT);
            Glass.surface(x, y, w, h, rad(Math.min(8f, h / 2f)), alpha, hover * 0.8f, Math.max(hover * 0.12f, at * 0.12f), 0.5f);
            return;
        }
        if (accentT < 0f) {
            R.rounded(x, y, w, h, rad(4f), R.alpha(R.lerpColor(panelShade(0.04f, 0xFF), panelShade(0.09f, 0xFF), hover), alpha));
            return;
        }
        R.rounded(x, y, w, h, rad(5f), R.alpha(R.lerpColor(panelShade(0.04f, 0xFF), panelShade(0.09f, 0xFF), hover), alpha));
        R.roundedOutline(x, y, w, h, rad(5f), 1f, R.alpha(R.lerpColor(0x28FFFFFF, accentA(0x90), Math.max(hover, accentT * 0.6f)), alpha));
    }

    /** The two big buttons of the main FzClient menu. */
    public static void bigCard(float x, float y, float w, float h, float hover, float alpha) {
        if (flat()) {
            R.rounded(x, y, w, h, rad(6f), R.alpha(R.lerpColor(FLAT_CARD, 0xFFF4F8FB, hover), alpha));
            R.roundedOutline(x, y, w, h, rad(6f), 1.2f, R.alpha(R.lerpColor(FLAT_BORDER, accent, hover), alpha));
            return;
        }
        if (Glass.on()) {
            Glass.surface(x, y, w, h, rad(14f), alpha, hover, hover * 0.30f, 1f);
            return;
        }
        R.rounded(x, y, w, h, rad(6f), R.alpha(R.lerpColor(panelShade(0.04f, 0xF0), panelShade(0.10f, 0xF0), hover), alpha));
        R.roundedOutline(x, y, w, h, rad(6f), 1.2f, R.alpha(R.lerpColor(accentA(0x70), accent, hover), alpha));
    }

    /** Four-corner gradient quad (colours: top-left, top-right, bottom-left, bottom-right). */
    public static void gradient(float x, float y, float w, float h, int tl, int tr, int bl, int br) {
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
        GL11.glBegin(GL11.GL_QUADS);
        color(tl); GL11.glVertex2f(x, y);
        color(bl); GL11.glVertex2f(x, y + h);
        color(br); GL11.glVertex2f(x + w, y + h);
        color(tr); GL11.glVertex2f(x + w, y);
        GL11.glEnd();
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.resetColor(); // the colour was set directly; make GlStateManager re-send the next one
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private static void color(int argb) {
        GL11.glColor4f(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
    }

    private static final net.minecraft.util.ResourceLocation TITLE =
            new net.minecraft.util.ResourceLocation("fzclient", "textures/title.png"); // 512x191, white script

    /** The script "FzClient" title (same as the title screen), tinted. x,y = top-left. */
    public static void title(float x, float y, float w, int argb) {
        int c = R.solid(argb);
        float h = w * 191f / 512f;
        net.minecraft.client.Minecraft.getMinecraft().getTextureManager().bindTexture(TITLE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(((c >> 16) & 0xFF) / 255f, ((c >> 8) & 0xFF) / 255f, (c & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0);
        GlStateManager.scale(w / 256f, h / 256f, 1f);
        net.minecraft.client.gui.Gui.drawModalRectWithCustomSizedTexture(0, 0, 0, 0, 256, 256, 256, 256);
        GlStateManager.popMatrix();
        GlStateManager.enableAlpha();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /** Width of the language switch: fits the longer of its two labels (same width in both languages). */
    public static float langW() {
        return Math.max(52f, Math.max(R.width("English"), R.width("日本語")) + 18f);
    }

    /** Small "日本語 / English" switch. Returns nothing; call langClick() on click. */
    public static void langButton(float x, float y, float hover, float alpha) {
        button(x, y, langW(), 20, dev.fzkc.fzhud.util.Lang.english() ? "English" : "日本語", hover, alpha);
    }

    /** A trimmed text and what it was trimmed for. */
    private static final class Trimmed {
        float maxW;
        boolean english;
        String out;
    }

    /** Trimming measures the text again for every character removed, so results are remembered per text. */
    private static final java.util.HashMap<String, Trimmed> TRIMS = new java.util.HashMap<>();
    private static int trimsWidthGen = Integer.MIN_VALUE;

    public static String trim(String s, float maxW) {
        if (s == null) return trimNow(null, maxW);
        boolean english = dev.fzkc.fzhud.util.Lang.english();
        R.measure(""); // lets the width memory refresh itself first if it is due
        if (trimsWidthGen != R.widthGen || TRIMS.size() > 1024) {
            TRIMS.clear();
            trimsWidthGen = R.widthGen;
        }
        Trimmed t = TRIMS.get(s);
        if (t == null) {
            t = new Trimmed();
            TRIMS.put(s, t);
        } else if (t.out != null && t.maxW == maxW && t.english == english) {
            return t.out;
        }
        t.maxW = maxW;
        t.english = english;
        t.out = trimNow(s, maxW);
        return t.out;
    }

    private static String trimNow(String s, float maxW) {
        s = dev.fzkc.fzhud.util.Lang.t(s);
        if (R.width(s) <= maxW) return s;
        while (!s.isEmpty() && R.width(s + "…") > maxW) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    public static void scissor(float x, float y, float w, float h) {
        Minecraft mc = Minecraft.getMinecraft();
        float f = new ScaledResolution(mc).getScaleFactor() * k;
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor((int) (x * f), (int) (mc.displayHeight - (y + h) * f), (int) Math.max(0, w * f), (int) Math.max(0, h * f));
    }

    public static void endScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }
}
