package dev.fzkc.fzhud.gui;

import dev.fzkc.fzhud.Modules;
import dev.fzkc.fzhud.render.R;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainerCreative;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

/**
 * The bird and the FzClient logo in the corner of the menus. Every screen draws it through here, so the UI page
 * ("ロゴ" section) controls all of them at once: where it shows, the text and bird colours and the size.
 * <ul>
 *   <li>{@link #corner}: FzClient's own menus (already drawing in virtual pixels).</li>
 *   <li>{@link #draw}: Minecraft's own screens (pause menu, inventory, creative), from FzHud's DrawScreenEvent.Post.
 *       Add a screen class to applies() to show it on more of them.</li>
 *   <li>{@link #over}: any other GuiScreen in normal GUI coordinates (e.g. the HUD editor).</li>
 * </ul>
 */
public final class Decoration {
    private Decoration() {}

    private static final ResourceLocation BIRD = new ResourceLocation("fzclient", "textures/bird.png");          // 255x153
    private static final ResourceLocation SPLASH = new ResourceLocation("fzclient", "textures/splash_text.png");   // 288x94

    private static GuiScreen last;
    private static long openedAt;

    /** Minecraft's own screens that get the logo (only when "すべての画面" is chosen). */
    public static boolean applies(GuiScreen gui) {
        return gui instanceof GuiIngameMenu || gui instanceof GuiInventory || gui instanceof GuiContainerCreative;
    }

    private static boolean inFzMenus() { return Modules.uiLogoPlace.get() <= 1; }

    private static boolean everywhere() { return Modules.uiLogoPlace.get() == 0; }

    /** FzClient menu: draws in the current (virtual) coordinates of a screen vw x vh. */
    public static void corner(float vw, float vh, float alpha) {
        if (!inFzMenus()) return;
        paint(vw, vh, alpha);
    }

    /** Minecraft's own screens. */
    public static void draw(GuiScreen gui) {
        if (gui == null || !applies(gui) || !everywhere()) return;
        if (gui != last) {   // fade in when the screen opens
            last = gui;
            openedAt = System.currentTimeMillis();
        }
        float o = Math.min(1f, (System.currentTimeMillis() - openedAt) / 250f);
        scaled(gui, o);
    }

    /** Another FzClient screen that draws in normal GUI coordinates. */
    public static void over(GuiScreen gui, float alpha) {
        if (!inFzMenus()) return;
        scaled(gui, alpha);
    }

    /** Draws in "virtual pixels" (same size as in the FzClient menus, whatever the GUI scale setting). */
    private static void scaled(GuiScreen gui, float o) {
        Minecraft mc = Minecraft.getMinecraft();
        int sf = new ScaledResolution(mc).getScaleFactor();
        int phys = Math.max(1, (int) Math.floor(Math.min(mc.displayWidth / 640f, mc.displayHeight / 360f)));
        float k = phys / (float) sf;

        // the inventory leaves lighting / depth on after drawing; remember the state and put it back
        boolean light = GL11.glIsEnabled(GL11.GL_LIGHTING);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        GlStateManager.disableLighting();
        GlStateManager.disableDepth();
        GlStateManager.enableTexture2D();
        GlStateManager.pushMatrix();
        GlStateManager.scale(k, k, 1f);
        paint(gui.width / k, gui.height / k, o);
        GlStateManager.popMatrix();
        GlStateManager.color(1f, 1f, 1f, 1f);
        if (!blend) GlStateManager.disableBlend();
        if (depth) GlStateManager.enableDepth();
        if (light) GlStateManager.enableLighting();
    }

    /** Bird + text, bottom-right (bottom-left while the menus sit on the right). */
    private static void paint(float vw, float vh, float alpha) {
        float s = Modules.uiLogoSize.get() / 100f;
        float birdW = 40 * s, birdH = birdW * 153f / 255f;
        float logoW = 90 * s, logoH = logoW * 94f / 288f;
        // the text starts over the bird's tail; together 125 px wide at 100 %
        float x = Ui.menuOnRight() ? 10 : vw - 130 * s;
        float y = vh - 35 * s;
        Minecraft mc = Minecraft.getMinecraft();
        image(mc, BIRD, x, y, birdW, birdH, Modules.uiLogoBirdColor.get(), alpha);
        image(mc, SPLASH, x + 35 * s, y, logoW, logoH, Modules.uiLogoColor.get(), alpha);
    }

    private static void image(Minecraft mc, ResourceLocation tex, float x, float y, float w, float h, int argb, float alpha) {
        int c = R.solid(argb);
        mc.getTextureManager().bindTexture(tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(((c >> 16) & 0xFF) / 255f, ((c >> 8) & 0xFF) / 255f, (c & 0xFF) / 255f, alpha);
        GlStateManager.pushMatrix();
        GlStateManager.translate(x, y, 0);
        GlStateManager.scale(w / 256f, h / 256f, 1f);
        Gui.drawModalRectWithCustomSizedTexture(0, 0, 0, 0, 256, 256, 256, 256);
        GlStateManager.popMatrix();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }
}
