package dev.fzkc.fzhud.gui;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.shader.Shader;
import net.minecraft.client.shader.ShaderGroup;
import net.minecraft.client.shader.ShaderUniform;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.lwjgl.opengl.GL11;

import java.util.List;

/**
 * "Liquid glass" look for the FzClient menus.
 * <ul>
 *   <li>Backdrop: while a menu is open in a world, the game picture behind it is blurred with Minecraft's own
 *       blur post-shader (the same one the 1.8.9 shader system ships), strength set in the module.</li>
 *   <li>Surface: a dark base (keeps white text readable over a bright world), a white glass body that is brighter
 *       at the top, a soft sheen on the upper half and a rim that catches light from the top-left (and a little
 *       from the bottom-right), like a lens edge.</li>
 * </ul>
 * Everything is drawn with plain GL vertices (no textures), in the current screen's coordinates.
 */
public final class Glass {
    private Glass() {}

    // ------------------------------------------------------------------ backdrop blur

    private static boolean loaded;
    private static boolean failed;
    private static boolean wantOff;
    private static ShaderGroup ours;
    private static float appliedRadius = -1f;
    /** The blur was started for one of the game's own screens (removed when no styled screen is open). */
    private static boolean byVanilla;

    /** True while the glass style is selected in the module list. */
    public static boolean on() {
        return Modules.uiStyle.get() == 0;
    }

    /** True while the backdrop is really blurred. */
    public static boolean blurActive() {
        return loaded && on() && Modules.glassBlur.get();
    }

    /** A FzClient menu opened. */
    public static void enter() {
        wantOff = false;
        startBlur();
    }

    /** A FzClient menu closed (the blur is removed on the next tick unless another FzClient menu took over). */
    public static void leave() {
        wantOff = true;
    }

    /** Call once per client tick. */
    public static void tick() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.entityRenderer == null) return;
        boolean vanilla = VanillaUi.blurs(mc.currentScreen);
        boolean menuOpen = mc.currentScreen instanceof FzScreen || vanilla;
        if (loaded) {
            if (mc.entityRenderer.getShaderGroup() != ours) {   // something else replaced it; don't touch theirs
                loaded = false;
                ours = null;
            } else if (((wantOff || byVanilla) && !menuOpen) || !on() || !Modules.glassBlur.get()) {
                stopBlur();
            } else {
                applyRadius();
            }
        } else if (menuOpen && (!wantOff || vanilla)) {
            byVanilla = vanilla;
            startBlur();   // the blur switch was turned on while the menu is open
        }
    }

    private static void startBlur() {
        if (loaded || failed || !on() || !Modules.glassBlur.get()) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.theWorld == null || mc.entityRenderer == null || !OpenGlHelper.shadersSupported) return;
        if (mc.entityRenderer.isShaderActive()) return;   // spectator shader / F4 shader is running
        try {
            mc.entityRenderer.loadShader(new ResourceLocation("shaders/post/blur.json"));
            ours = mc.entityRenderer.getShaderGroup();
            loaded = ours != null;
            appliedRadius = -1f;
            if (loaded) applyRadius();
            else failed = true;
        } catch (Throwable e) {
            failed = true;
            System.err.println("[FzClient] Menu blur unavailable: " + e);
        }
    }

    private static void stopBlur() {
        Minecraft mc = Minecraft.getMinecraft();
        try {
            if (ours != null && mc.entityRenderer.getShaderGroup() == ours) mc.entityRenderer.stopUseShader();
        } catch (Throwable ignored) { }
        loaded = false;
        ours = null;
        byVanilla = false;
    }

    /** The blur shader reads "Radius"; set it from the module so the slider works live. */
    @SuppressWarnings("unchecked")
    private static void applyRadius() {
        float radius = Modules.glassBlurAmount.get();
        if (radius == appliedRadius || ours == null) return;
        try {
            List<Shader> list = ReflectionHelper.getPrivateValue(ShaderGroup.class, ours, "listShaders", "field_148031_d");
            for (Shader s : list) {
                ShaderUniform u = s.getShaderManager().getShaderUniform("Radius");
                if (u != null) u.set(radius);
            }
            appliedRadius = radius;
        } catch (Throwable e) {
            appliedRadius = radius;   // keep the shader's own radius; don't retry every tick
        }
    }

    /** Dark veil behind a menu: lighter when the picture behind is already blurred. */
    public static void backdrop(float w, float h, float o) {
        int a = Ui.backdropAlpha(blurActive() ? 0x50 : 0x92);
        R.rect(0, 0, w, h, R.alpha(a << 24, o));
    }

    // ------------------------------------------------------------------ surfaces

    /**
     * A glass surface.
     * @param alpha  overall opacity (the menu's fade-in)
     * @param lift   0..1 extra brightness (hover)
     * @param accent 0..1 cyan tint (on / selected / primary)
     * @param rim    strength of the light-catching edge (1 = full)
     */
    public static void surface(float x, float y, float w, float h, float r, float alpha, float lift, float accent, float rim) {
        if (w <= 0 || h <= 0 || alpha <= 0.003f) return;
        r = Math.min(r, Math.min(w, h) / 2f);
        float tint = Modules.glassTint.get() / 100f;

        // dark base: keeps white text readable over a bright world
        float op = Ui.opacity();
        int baseA = Math.min(255, (int) ((blurActive() ? 0x6A : 0xB4) * alpha * op));
        R.rounded(x, y, w, h, r, (baseA << 24) | Ui.panelRgb());

        // glass body: white, brighter toward the top
        float t0 = 0.11f + 0.12f * tint + 0.10f * lift;
        float t1 = 0.035f + 0.05f * tint + 0.05f * lift;
        float t2 = 0.075f + 0.08f * tint + 0.06f * lift;
        float ga = alpha * (0.35f + 0.65f * op);
        fill(x, y, w, h, r, white(t0 * ga), white(t1 * ga), white(t2 * ga));

        if (accent > 0.01f) {
            fill(x, y, w, h, r, cyan(0.50f * accent * alpha), cyan(0.32f * accent * alpha), cyan(0.42f * accent * alpha));
        }

        // sheen on the upper half
        float sh = Math.min(h * 0.5f, 40f);
        if (sh > 4f && w > 6f) {
            fill(x + 1.5f, y + 1f, w - 3f, sh, Math.max(1f, r - 1.5f),
                    white(0.20f * (0.5f + tint) * alpha), white(0.05f * alpha), 0);
        }

        int rimRgb = R.lerpColor(0xFFFFFFFF, R.lerpColor(Ui.accent, 0xFFFFFFFF, 0.3f), Math.min(1f, accent)) & 0xFFFFFF;
        rim(x, y, w, h, r, 1f, alpha, rim, rimRgb);
    }

    /** Soft drop shadow under a panel. */
    public static void shadow(float x, float y, float w, float h, float r, float alpha) {
        for (int i = 3; i >= 1; i--) {
            int a = Math.min(255, (int) (0x16 * alpha));
            R.rounded(x - i, y + i * 1.6f - 0.5f, w + 2 * i, h + 2 * i, r + i, a << 24);
        }
    }

    // ------------------------------------------------------------------ GL primitives

    private static int white(float a) {
        return (Math.max(0, Math.min(255, (int) (a * 255f))) << 24) | 0xFFFFFF;
    }

    private static int cyan(float a) {
        return (Math.max(0, Math.min(255, (int) (a * 255f))) << 24) | (Ui.accent & 0xFFFFFF);
    }

    private static void begin() {
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.shadeModel(GL11.GL_SMOOTH);
    }

    private static void end() {
        GlStateManager.shadeModel(GL11.GL_FLAT);
        GlStateManager.enableTexture2D();
        GlStateManager.enableAlpha();
        GlStateManager.resetColor();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    private static void color(int argb) {
        GL11.glColor4f(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f, (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
    }

    private static int arcN(float r) {
        return Math.max(3, Math.min(10, Math.round(r / 1.3f)));
    }

    /** Rounded rectangle with a top / middle / bottom colour gradient (ARGB). */
    public static void fill(float x, float y, float w, float h, float r, int top, int mid, int bot) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0f, Math.min(r, Math.min(w, h) / 2f));
        int n = r < 0.5f ? 1 : arcN(r);
        int mids = (int) Math.max(0, Math.floor((h - 2 * r) / 12f));
        int total = (n + 1) + mids + (n + 1);
        begin();
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        for (int i = 0; i < total; i++) {
            float yy, inset;
            if (i <= n) {
                double p = Math.PI / 2 * i / n;
                yy = y + r * (1f - (float) Math.cos(p));
                inset = r * (1f - (float) Math.sin(p));
            } else if (i < n + 1 + mids) {
                int j = i - n;
                yy = y + r + (h - 2 * r) * j / (float) (mids + 1);
                inset = 0f;
            } else {
                int j = total - 1 - i;
                double p = Math.PI / 2 * j / n;
                yy = y + h - r * (1f - (float) Math.cos(p));
                inset = r * (1f - (float) Math.sin(p));
            }
            float f = (yy - y) / h;
            color(f < 0.5f ? R.lerpColor(top, mid, f * 2f) : R.lerpColor(mid, bot, (f - 0.5f) * 2f));
            GL11.glVertex2f(x + inset, yy);
            GL11.glVertex2f(x + w - inset, yy);
        }
        GL11.glEnd();
        end();
    }

    // light comes from the top-left; the bottom-right edge catches a weaker reflection
    private static final float LX = -0.55f, LY = -0.835f;

    /** The edge of a glass piece: bright where it faces the light, faint elsewhere, with a soft 1px fade outside. */
    public static void rim(float x, float y, float w, float h, float r, float thick, float alpha, float strength, int rgb) {
        if (w <= 0 || h <= 0 || strength <= 0f) return;
        r = Math.max(0.01f, Math.min(r, Math.min(w, h) / 2f));
        int n = arcN(r);
        ring(x, y, w, h, r, n, 0f, -thick, alpha * strength, rgb, 1f, 1f);
        ring(x, y, w, h, r, n, 1f, 0f, alpha * strength, rgb, 0f, 0.45f);
    }

    private static void ring(float x, float y, float w, float h, float r, int n, float offOut, float offIn,
                             float strength, int rgb, float aOut, float aIn) {
        begin();
        GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
        int per = n + 1;
        for (int idx = 0; idx <= 4 * per; idx++) {
            int c = (idx / per) % 4, i = idx % per;
            float cx = (c == 0 || c == 3) ? x + r : x + w - r;   // TL, TR, BR, BL
            float cy = (c < 2) ? y + r : y + h - r;
            double ang = Math.PI + c * Math.PI / 2 + (Math.PI / 2) * i / n;
            float nx = (float) Math.cos(ang), ny = (float) Math.sin(ang);
            float d = nx * LX + ny * LY;
            float s = 0.30f + 0.70f * (float) Math.pow(Math.max(0f, d), 1.6)
                    + 0.28f * (float) Math.pow(Math.max(0f, -d), 2.0);
            float a = Math.min(1f, s * strength);
            float px = cx + r * nx, py = cy + r * ny;
            color(((int) (a * aOut * 255f) << 24) | rgb);
            GL11.glVertex2f(px + nx * offOut, py + ny * offOut);
            color(((int) (a * aIn * 255f) << 24) | rgb);
            GL11.glVertex2f(px + nx * offIn, py + ny * offIn);
        }
        GL11.glEnd();
        end();
    }
}
