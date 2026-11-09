package dev.fzkc.fzhud.gui;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiOptionSlider;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.opengl.GL11;

import java.util.WeakHashMap;

/**
 * The game's own screens (multiplayer, singleplayer, options, the Esc menu...) in FzClient's style: buttons,
 * sliders and text boxes follow the UI page (glass / classic / dashboard, accent, roundness), the dirt background
 * becomes the title screen's panorama, in a world the game behind is veiled (and blurred with glass). The mixins
 * on GuiButton, Gui, GuiScreen, GuiSlot and GuiTextField call in here. "バニラの表示にする" on the UI page turns it off.
 * FzClient's own screens, inventories and the chat are never touched.
 */
public final class VanillaUi {
    private VanillaUi() {}

    public static boolean on() {
        return !Modules.uiVanillaScreens.get();
    }

    public static boolean styled(GuiScreen s) {
        return s != null && on() && !(s instanceof FzScreen) && !(s instanceof GuiContainer) && !(s instanceof GuiChat);
    }

    private static boolean styledNow() {
        return styled(Minecraft.getMinecraft().currentScreen);
    }

    // ------------------------------------------------------------------ buttons

    private static final WeakHashMap<GuiButton, float[]> hover = new WeakHashMap<>();
    /** Set while a slider draws its knob (inside mouseDragged), see {@link #knob}. */
    private static boolean knobOn;
    private static int knobX0;

    private static final java.util.HashMap<Class<?>, Boolean> SLIDERS = new java.util.HashMap<>();

    /** Sliders of the game and of other mods (by class name), looked up once per class. */
    public static boolean slider(GuiButton b) {
        if (b instanceof GuiOptionSlider) return true;
        Boolean s = SLIDERS.get(b.getClass());
        if (s == null) SLIDERS.put(b.getClass(), s = b.getClass().getSimpleName().contains("Slider"));
        return s;
    }

    /** Before the label: the button's body. False = draw it the vanilla way. */
    public static boolean buttonBody(GuiButton b, int x, int y, int w, int h, boolean enabled, boolean hovered) {
        if (!styledNow()) return false;
        Ui.refreshRaw();
        float[] a = hover.get(b);
        long now = System.nanoTime();
        if (a == null) hover.put(b, a = new float[]{0f, Float.intBitsToFloat((int) (now / 1_000_000L))});
        // the same speed at any FPS (the step used to be per frame: instant at 500 FPS, slow at 30)
        int ms = (int) (now / 1_000_000L), dt = Math.max(0, Math.min(100, ms - Float.floatToRawIntBits(a[1])));
        a[1] = Float.intBitsToFloat(ms);
        a[0] += ((hovered && enabled ? 1f : 0f) - a[0]) * (1f - (float) Math.exp(-dt / 45.0));
        float alpha = enabled ? 1f : 0.55f;
        if (slider(b)) {
            R.rounded(x, y, w, h, Ui.rad(4f), R.alpha(0xB0060A0C, alpha));
            R.roundedOutline(x, y, w, h, Ui.rad(4f), 1f, R.alpha(R.lerpColor(0x40FFFFFF, Ui.accent, a[0]), alpha));
        } else {
            Ui.button(x, y, w, h, "", a[0], alpha);
        }
        GlStateManager.enableTexture2D();
        GlStateManager.color(1f, 1f, 1f, 1f);
        return true;
    }

    public static void label(GuiButton b, String s, int x, int y, int w, int h, boolean enabled) {
        int col = !slider(b) && Ui.flat() ? Ui.buttonText() : Ui.text;
        if (!enabled) col = R.lerpColor(col, 0xFF808080, 0.5f);
        R.textCentered(s, x + w / 2f, y + (h - 8) / 2f, col);
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    public static void knobStart(GuiButton b, int buttonX) {
        // only sliders: another mod's button may draw something of its own in there
        knobOn = slider(b);
        knobX0 = buttonX;
    }

    public static void knobEnd() {
        knobOn = false;
    }

    /** Gui.drawTexturedModalRect while a slider draws its knob (two 4-px halves): ours instead. */
    public static boolean knob(int x, int y, int u, int w, int h) {
        if (!knobOn) return false;
        if (u == 0) {
            if (x + 4 - knobX0 > 2) R.rounded(knobX0 + 1, y + 1, x + 4 - knobX0 - 1, h - 2, Ui.rad(3f), Ui.accentA(0x70));
            R.rounded(x, y, 8, h, Ui.rad(3f), 0xF2FFFFFF);
            R.roundedOutline(x, y, 8, h, Ui.rad(3f), 1f, Ui.accent);
            GlStateManager.enableTexture2D();
            GlStateManager.color(1f, 1f, 1f, 1f);
        }
        return true;
    }

    // ------------------------------------------------------------------ backgrounds

    private static long worldBgAt;

    /** The game behind a menu in a world: veiled (and blurred, see Glass.tick). */
    public static boolean worldBackground(GuiScreen s) {
        if (!styled(s)) return false;
        worldBgAt = System.currentTimeMillis();
        Ui.refreshRaw();
        Ui.backdrop(s.width, s.height, 1f);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1f, 1f, 1f, 1f);
        return true;
    }

    /** True while a styled game screen is open over a world (the blur stays on). */
    public static boolean blurs(GuiScreen s) {
        return styled(s) && Minecraft.getMinecraft().theWorld != null && System.currentTimeMillis() - worldBgAt < 500;
    }

    /** The dirt background outside a world: the title screen's panorama, shaded. */
    public static boolean dirtBackground(GuiScreen s) {
        if (!styled(s)) return false;
        panorama(s.width, s.height);
        return true;
    }

    private static void panorama(int w, int h) {
        Minecraft mc = Minecraft.getMinecraft();
        float t = (System.nanoTime() / 1_000_000L % 10_000_000L) / 1000f;
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        org.lwjgl.util.glu.Project.gluPerspective(85f, mc.displayWidth / (float) Math.max(1, mc.displayHeight), 0.05f, 10f);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.loadIdentity();
        GlStateManager.rotate(180f, 1f, 0f, 0f);
        GlStateManager.rotate(8f, 1f, 0f, 0f);
        GlStateManager.rotate(t * 1.8f, 0f, 1f, 0f);
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GlStateManager.disableCull();
        GlStateManager.disableAlpha();
        GlStateManager.disableBlend();
        GlStateManager.disableLighting();
        GlStateManager.disableFog();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1f, 1f, 1f, 1f);
        for (int k = 0; k < 6; k++) {
            mc.getTextureManager().bindTexture(FzMainMenu.PANO[k]);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            float[] f = FzMainMenu.FACES[k];
            GL11.glBegin(GL11.GL_QUADS);
            for (int v = 0; v < 4; v++) {
                GL11.glTexCoord2f(FzMainMenu.UV[v * 2], FzMainMenu.UV[v * 2 + 1]);
                GL11.glVertex3f(f[v * 3], f[v * 3 + 1], f[v * 3 + 2]);
            }
            GL11.glEnd();
        }
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.depthMask(true);
        GlStateManager.enableDepth();
        GlStateManager.enableAlpha();
        GlStateManager.enableBlend();
        R.rect(0, 0, w, h, 0x70000000);
        Ui.gradient(0, h * 0.5f, w, h * 0.5f + 1, 0x00000000, 0x00000000, 0xA0000000, 0xA0000000);
        GlStateManager.enableTexture2D();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    // ------------------------------------------------------------------ lists (GuiSlot)

    /** Set once the list scissor really runs, so the header/footer cover is only dropped when entries are clipped. */
    private static boolean listClipWorks;

    public static boolean listBackground(int left, int top, int right, int bottom) {
        if (!styledNow()) return false;
        Ui.refreshRaw();
        int c = Ui.luma(Ui.panelRgb()) < 0.5f ? Ui.panelShade(0f, 0xA8) : 0xA0000000;
        R.rect(left, top, right - left, bottom - top, c);
        R.rect(left, top, right - left, 1, Ui.accentA(0x50));
        R.rect(left, bottom - 1, right - left, 1, Ui.accentA(0x50));
        GlStateManager.enableTexture2D();
        GlStateManager.color(1f, 1f, 1f, 1f);
        return true;
    }

    public static boolean skipListCover() {
        return listClipWorks && styledNow();
    }

    public static void clipList(int left, int top, int right, int bottom) {
        if (!styledNow()) return;
        listClipWorks = true;
        Minecraft mc = Minecraft.getMinecraft();
        int f = Ui.scaleFactor();
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(left * f, mc.displayHeight - bottom * f, Math.max(0, (right - left) * f), Math.max(0, (bottom - top) * f));
    }

    public static void unclipList() {
        if (styledNow()) GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    // ------------------------------------------------------------------ text boxes

    /** GuiTextField's two frame rects (grey border, black inside): one rounded box instead. */
    public static boolean textBox(int l, int t, int r, int b, int color, boolean focused) {
        if (!styledNow()) return false;
        if (color == -6250336) {
            Ui.refreshRaw();
            R.rounded(l, t, r - l, b - t, Ui.rad(3f), 0xE0080C0E);
            R.roundedOutline(l, t, r - l, b - t, Ui.rad(3f), 1f, focused ? Ui.accent : 0x50FFFFFF);
            GlStateManager.enableTexture2D();
            GlStateManager.color(1f, 1f, 1f, 1f);
            return true;
        }
        return color == -16777216;
    }
}
