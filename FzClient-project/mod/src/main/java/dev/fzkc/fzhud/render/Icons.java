package dev.fzkc.fzhud.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.Map;

/**
 * Menu icons (Lucide, ISC license) packed into one small texture: textures/icons.png, 48px cells, 16 per row (768x288).
 * One texture bind per icon, tinted with any colour (chroma works too).
 */
public final class Icons {
    private Icons() {}

    private static final ResourceLocation ATLAS = new ResourceLocation("fzclient", "textures/icons.png");
    private static final int CELL = 48, COLS = 16, W = 768, H = 288;
    private static final String[] KEYS = {
            "cat_all",
            "cat_hud",
            "cat_pvp",
            "cat_visual",
            "cat_chat",
            "cat_server",
            "cat_utility",
            "animations",
            "armor",
            "autogg",
            "bgfps",
            "blockoutline",
            "borderless",
            "branding",
            "chathistory",
            "chattweaks",
            "clock",
            "combo",
            "compass",
            "coords",
            "cps",
            "crosshair",
            "deathcoords",
            "discord",
            "fps",
            "fullbright",
            "guiscale",
            "hotbarscroll",
            "ime",
            "itemcounter",
            "itemphysics",
            "keystrokes",
            "lowhp",
            "memory",
            "motionblur",
            "nametags",
            "noloading",
            "overlays",
            "particles",
            "ping",
            "potions",
            "reach",
            "reconnect",
            "saturation",
            "scoreboard",
            "screenshot",
            "server",
            "smoothchat",
            "spotify",
            "status",
            "tab",
            "theme",
            "timechanger",
            "tnttimer",
            "togglesprint",
            "tps",
            "translator",
            "zoom",
            "chat",
            "menu_single",
            "menu_multi",
            "menu_hypixel",
            "menu_fz",
            "menu_options",
            "menu_mods",
            "menu_quit",
            "menu_account",
            "fallback",
            "hitbox",
            "hitdelayfix",
            "rawinput",
            "glassui",
            "targethud",
            "performance",
            "optimize",
            "fixes",
            "discordvc",
            "cosmetics",
            "skinlayers",
            "partydetect",
            "playerstats",
            "droplock",
            "launcherkeys",
            "language",
            "menusound",
            "clientsettings"
    };
    private static final Map<String, Integer> INDEX = new HashMap<String, Integer>();

    static {
        for (int i = 0; i < KEYS.length; i++) INDEX.put(KEYS[i], i);
    }

    private static boolean filtered;

    /** Draws the icon for a module id (or "cat_xxx"); unknown ids get a plain circle. */
    public static void draw(String key, float x, float y, float size, int argb) {
        Integer i = INDEX.get(key);
        if (i == null) i = INDEX.get("fallback");
        if (((argb >>> 24) & 0xFF) == 0) return;
        int c = R.solid(argb);

        Minecraft.getMinecraft().getTextureManager().bindTexture(ATLAS);
        if (!filtered) {
            // smooth scaling for the small sizes the menu uses (set once; it is stored with the texture)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            filtered = true;
        }
        GlStateManager.enableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.disableAlpha();
        GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(((c >> 16) & 0xFF) / 255f, ((c >> 8) & 0xFF) / 255f, (c & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);

        float u0 = (i % COLS) * CELL / (float) W, v0 = (i / COLS) * CELL / (float) H;
        float u1 = u0 + CELL / (float) W, v1 = v0 + CELL / (float) H;
        Tessellator t = Tessellator.getInstance();
        WorldRenderer r = t.getWorldRenderer();
        r.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_TEX);
        r.pos(x, y + size, 0).tex(u0, v1).endVertex();
        r.pos(x + size, y + size, 0).tex(u1, v1).endVertex();
        r.pos(x + size, y, 0).tex(u1, v0).endVertex();
        r.pos(x, y, 0).tex(u0, v0).endVertex();
        t.draw();
        GlStateManager.enableAlpha();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    public static String category(int menuIndex) {
        switch (menuIndex) {
            case 1: return "cat_hud";
            case 2: return "cat_pvp";
            case 3: return "cat_visual";
            case 4: return "cat_server";
            case 5: return "cat_utility";
            default: return "cat_all";
        }
    }
}
